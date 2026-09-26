import datetime as dt
import json
import logging
import math
import re
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

import akshare as ak
import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.ratelimit import RateLimiter

logger = logging.getLogger(__name__)

# StockFinancialSource 逐股拉取 fina_indicator 前的最小调用间隔（秒）。
# fina_indicator 上游限 200 次/分钟，取 0.35s（≈171 次/分钟）留安全余量，避免逐股并发触发限流。
FINANCIAL_MIN_INTERVAL = 0.35

# income（利润表）并入营收的实测口径（09-调研报告/2026-09-17-income接口校准.md）：
# 缺省查询只回 report_type='1'（合并报表），客户端仍显式过滤防上游缺省行为变化；
# revenue 单位为元（600519/601398 2024 年报 1e11 量级实测），换算系数 1.0；
# ann_date/update_flag 必取：同 end_date 多行的真实来源是 update_flag 0/1 并存，去重与溯源必需。
INCOME_MERGED_REPORT_TYPE = "1"
INCOME_REVENUE_SCALE = 1.0
INCOME_FIELDS = "ts_code,end_date,report_type,revenue,ann_date,update_flag"

INDEX_CODES = {"000016": "上证50", "000300": "沪深300", "000905": "中证500", "399006": "创业板指", "000688": "科创50"}

_DATE_COMPACT = re.compile(r"\d{8}")


def _ts_code(code):
    return code + (".SH" if code.startswith("0") else ".SZ")


def _valid_universe_ts_codes(pro):
    """全 A 有效个股 ts_code 集合：仅沪深正常交易股，剔除 ST/退市/北交所（value-screening P1 口径）。"""
    basic = pro.stock_basic(list_status="L", fields="ts_code,name")
    if basic is None or basic.empty:
        return set()
    basic = basic[basic["ts_code"].str.endswith((".SH", ".SZ"))]
    basic = basic[~basic["name"].str.contains("ST|退", na=False)]
    return set(basic["ts_code"])


def normalize_date(value, name="date"):
    """日期统一归一化为 YYYYMMDD；兼容 date 对象、YYYY-MM-DD 与已规范的 YYYYMMDD 直通。"""
    if isinstance(value, dt.date):
        return value.strftime("%Y%m%d")
    s = str(value).strip()
    if _DATE_COMPACT.fullmatch(s):
        try:
            dt.date(int(s[:4]), int(s[4:6]), int(s[6:]))  # 校验是真实日期
        except ValueError as e:
            raise ValueError(f"非法日期参数 {name}={value!r}，期望 YYYY-MM-DD 或 YYYYMMDD") from e
        return s
    try:
        return dt.date.fromisoformat(s).strftime("%Y%m%d")
    except ValueError as e:
        raise ValueError(f"非法日期参数 {name}={value!r}，期望 YYYY-MM-DD 或 YYYYMMDD") from e


def _date_param(params, key):
    """取 date/start/end 日期参数；start/end 缺失时回退到 date 或今天，统一 YYYYMMDD。"""
    val = params.get(key) or params.get("date")
    if val:
        return normalize_date(val, key)
    return dt.date.today().strftime("%Y%m%d")


class ShenwanMappingSource(Source):
    supports_range = False

    def __init__(self, source_id, pro_factory):
        self.source_id = source_id
        self.pro_factory = pro_factory

    def fetch(self, params):
        pro = self.pro_factory()
        industries = pro.index_classify(level="L1", src="SW2021")
        frames = []
        for code in industries["index_code"]:
            members = pro.index_member_all(l1_code=code)
            frames.append(members[["ts_code", "name", "l1_code", "l1_name"]])
        result = pd.concat(frames, ignore_index=True)
        result = result.rename(columns={"ts_code": "code", "name": "stock_name", "l1_name": "industry_name"})
        result["code"] = result["code"].str.split(".").str[0]
        result["industry_code"] = result["l1_code"].str.split(".").str[0]
        return result[["code", "stock_name", "industry_code", "industry_name"]]


class IndexValuationSource(Source):
    supports_range = True

    def __init__(self, source_id, pro_factory, dividend_fetch=None, index_codes=None):
        self.source_id = source_id
        self.pro_factory = pro_factory
        self.dividend_fetch = dividend_fetch
        self.index_codes = index_codes or INDEX_CODES

    def fetch(self, params):
        pro = self.pro_factory()
        start, end = _date_param(params, "start"), _date_param(params, "end")
        frames = []
        for code, name in self.index_codes.items():
            df = pro.index_dailybasic(ts_code=_ts_code(code), start_date=start, end_date=end)
            df = df.rename(columns={"trade_date": "trading_day"})
            df["index_code"], df["index_name"] = code, name
            df["dividend_yield"] = None
            if self.dividend_fetch is not None:
                value = self.dividend_fetch(code, start, end)
                if isinstance(value, dict):
                    value = value.get(code)
                df["dividend_yield"] = value
            frames.append(df[["trading_day", "index_code", "index_name", "pe", "pb", "dividend_yield"]])
        return pd.concat(frames, ignore_index=True)


INDEX_CLOSE_CODES = {"000300": "沪深300", "000905": "中证500", "930950": "中证偏股基金指数"}

_INDEX_CLOSE_TS = {"000300": "000300.SH", "000905": "000905.SH", "930950": "930950.CSI"}


class IndexCloseSource(Source):
    """基准指数收盘价历史：tushare index_daily 区间拉取（supports_range=True，供 backfill 复用）。"""

    supports_range = True

    def __init__(self, source_id, pro_factory):
        self.source_id = source_id
        self.pro_factory = pro_factory

    def fetch(self, params):
        pro = self.pro_factory()
        start, end = _date_param(params, "start"), _date_param(params, "end")
        frames = []
        for code, name in INDEX_CLOSE_CODES.items():
            df = pro.index_daily(ts_code=_INDEX_CLOSE_TS[code], start_date=start, end_date=end)
            df = df.rename(columns={"trade_date": "trading_day"})
            df["index_code"], df["index_name"] = code, name
            frames.append(df[["trading_day", "index_code", "index_name", "close"]])
        return pd.concat(frames, ignore_index=True)


INDUSTRY_INDEX_CODES = {
    "801010": "农林牧渔",
    "801030": "基础化工",
    "801040": "钢铁",
    "801050": "有色金属",
    "801080": "电子",
    "801110": "家用电器",
    "801120": "食品饮料",
    "801130": "纺织服饰",
    "801140": "轻工制造",
    "801150": "医药生物",
    "801160": "公用事业",
    "801170": "交通运输",
    "801180": "房地产",
    "801200": "商贸零售",
    "801210": "社会服务",
    "801230": "综合",
    "801710": "建筑材料",
    "801720": "建筑装饰",
    "801730": "电力设备",
    "801740": "国防军工",
    "801750": "计算机",
    "801760": "传媒",
    "801770": "通信",
    "801780": "银行",
    "801790": "非银金融",
    "801880": "汽车",
    "801890": "机械设备",
    "801950": "煤炭",
    "801960": "石油石化",
    "801970": "环保",
    "801980": "美容护理",
}  # 申万 2021 一级 31 行业；Task 0 四方对齐核验零差异（调研报告 §四）


def _default_sw_fetch(code):
    """akshare 申万官网源（Task 0 裁决：sw_daily 积分权限不可得）；返回全历史，列 日期/收盘。"""
    import akshare as ak

    return ak.index_hist_sw(symbol=code, period="day")


class IndustryIndexCloseSource(Source):
    """申万一级行业指数收盘（MS-13 归因基准）：akshare index_hist_sw（申万 2021 口径原生同构）。

    index_hist_sw 无日期参数、一次返回全历史——fetch 内按 params 窗口客户端裁剪（supports_range=True 供 backfill）。
    index_code 去 .SI 后缀落库（源即无后缀）——与 shenwan_industry_mapping.industry_code 同构（Join 零转换）。
    """

    supports_range = True

    def __init__(self, source_id, sw_fetch=None, sleep_fn=time.sleep):
        self.source_id = source_id
        self.sw_fetch = sw_fetch or _default_sw_fetch
        self.sleep_fn = sleep_fn

    def fetch(self, params):
        start, end = _date_param(params, "start"), _date_param(params, "end")
        frames = []
        for code, name in INDUSTRY_INDEX_CODES.items():
            df = self.sw_fetch(code)[["日期", "收盘"]]
            df = df.rename(columns={"日期": "trading_day", "收盘": "close"})
            df["trading_day"] = pd.to_datetime(df["trading_day"]).dt.strftime("%Y%m%d")
            df = df[(df["trading_day"] >= start) & (df["trading_day"] <= end)]
            df["index_code"], df["index_name"] = code, name
            frames.append(df[["trading_day", "index_code", "index_name", "close"]])
            self.sleep_fn(0.3)  # 申万官网源保守限速（探测建议 0.3–0.5s）
        return pd.concat(frames, ignore_index=True)


class BondIndexCloseSource(Source):
    """中证全债指数收盘（MS-13 回测债券类代理）：tushare index_daily H11001.CSI。

    该接口对债券指数仅 close 口径（open/high/low/amount 为 None，Task 0 探测实证）——本用途只需 close。
    """

    supports_range = True

    def __init__(self, source_id, pro_factory):
        self.source_id = source_id
        self.pro_factory = pro_factory

    def fetch(self, params):
        pro = self.pro_factory()
        start, end = _date_param(params, "start"), _date_param(params, "end")
        df = pro.index_daily(ts_code="H11001.CSI", start_date=start, end_date=end)
        df = df.rename(columns={"trade_date": "trading_day"})
        df["index_code"], df["index_name"] = "H11001", "中证全债"
        return df[["trading_day", "index_code", "index_name", "close"]]


class GoldEtfCloseSource(Source):
    """华安黄金ETF收盘（MS-13 回测黄金类代理，真实可投含费损）：tushare fund_daily 518880.SH。"""

    supports_range = True

    def __init__(self, source_id, pro_factory):
        self.source_id = source_id
        self.pro_factory = pro_factory

    def fetch(self, params):
        pro = self.pro_factory()
        start, end = _date_param(params, "start"), _date_param(params, "end")
        df = pro.fund_daily(ts_code="518880.SH", start_date=start, end_date=end)
        df = df.rename(columns={"trade_date": "trading_day"})
        df["index_code"], df["index_name"] = "518880", "华安黄金ETF"
        return df[["trading_day", "index_code", "index_name", "close"]]


# ------------------------------------------- MS-14 P3 Task 13 ETF 目录/费率/规模 etf_basic
#
# 源口径（09-调研报告/2026-09-26-ETF数据源探测.md §七，字段样例均实测）：
# - 枚举：akshare fund_etf_category_sina("ETF基金")——场内 ETF 专列（1685 只），天然全为场内
#   标的，无需 ISEXCHG 过滤（§七勘误后口径）；代码带 sh/sz 前缀，6 位化后即 fund_code。
# - enrich：天天基金移动端 FundMNDetailInformation 逐只（Datas 单基金 JSON，UA+Referer 即可
#   无需 cookie）——SHORTNAME→fund_name、MGREXP+TRUSTEXP→fee_rate（合计年化%）、
#   ENDNAV→scale（元→亿元 ÷1e8）、INDEXCODE/INDEXNAME→tracking_index_*、FTYPE→category。
#   单只失败/超时该行字段置 null 不阻断整批（整体失败由 validator/retry 兜底）。
ETF_DETAIL_URL = (
    "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNDetailInformation"
    "?FCODE={code}&deviceid=Wap&plat=Wap&product=EFund&version=6.2.8"
)
ETF_DETAIL_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
    "Referer": "https://fund.eastmoney.com/",
}
ETF_DETAIL_TIMEOUT = 15  # 单只请求超时（秒）
ETF_ENRICH_INTERVAL = 0.3  # 逐只保守限速：~1685 只实测 ~16 分钟（含网络 RTT；周更可接受，报告 §2.2）

# 非证券指数的跟踪标的码 → tracking_index_code/name 置 null（不参与 Task 15 误差计算）：
# SGE 贵金属现货（AU9999 黄金/AG 白银）；商品期货价格指数（DCESMFI 等）按 INDEXNAME 含
# 期货/现货识别。海外证券指数码（NDX100/N225/SPAHLVCP）保留展示，误差计算侧自然降级 null。
ETF_SPOT_INDEX_PREFIXES = ("AU", "AG")

# category 关键词规则（常量化钉住，作用于 FTYPE|INDEXNAME|基金名 联合文本，按序先命中先出）。
# FTYPE 实测五类：指数型-股票/指数型-其他/指数型-固收/指数型-海外股票/货币型-普通货币。
# 已知不精确处（关键词法的固有取舍，1685 只中个位数）：黄金产业股票指数含「黄金」误入商品；
# 一带一路/央企等主题未列举归宽基。
ETF_QDII_KEYWORDS = (
    "QDII",
    "海外",
    "纳斯达克",
    "纳指",
    "中概",
    "日经",
    "恒生",
    "港股",
    "标普500",
    "道琼斯",
    "德国",
    "法国",
    "印度",
    "越南",
    "沙特",
    "亚太",
    "亚洲",
)
ETF_BOND_KEYWORDS = ("债", "固收", "存单", "短融")
ETF_COMMODITY_KEYWORDS = ("商品", "黄金", "白银", "货币", "期货", "豆粕", "原油")
ETF_INDUSTRY_KEYWORDS = (
    "行业",
    "主题",
    "半导体",
    "芯片",
    "医药",
    "医疗",
    "生物",
    "创新药",
    "中药",
    "消费",
    "食品",
    "饮料",
    "白酒",
    "酒",
    "新能源",
    "光伏",
    "电池",
    "军工",
    "国防",
    "证券",
    "券商",
    "银行",
    "保险",
    "地产",
    "房地产",
    "建筑",
    "建材",
    "钢铁",
    "煤炭",
    "化工",
    "石油",
    "石化",
    "计算机",
    "传媒",
    "游戏",
    "通信",
    "电子",
    "汽车",
    "家电",
    "农业",
    "养殖",
    "环保",
    "电力",
    "交通",
    "物流",
    "旅游",
    "教育",
    "科技",
    "人工智能",
    "机器人",
    "软件",
    "信息",
    "互联网",
    "航空",
    "港口",
    "有色",
    "金属",
    "机械",
    "设备",
    "养老",
)

_ETF_FUND_CODE = re.compile(r"(\d{6})")

ETF_BASIC_COLUMNS = [
    "fund_code",
    "fund_name",
    "fee_rate",
    "scale",
    "tracking_index_code",
    "tracking_index_name",
    "category",
]


def _default_etf_catalog():
    """新浪场内 ETF 目录（1 次调用全集枚举）。"""
    return ak.fund_etf_category_sina(symbol="ETF基金")


def _default_etf_detail(code):
    """天天基金移动端 Detail 端点：返回 Datas 单基金 dict（ErrCode!=0 或异常由调用方容错）。"""
    req = urllib.request.Request(ETF_DETAIL_URL.format(code=code), headers=ETF_DETAIL_HEADERS)
    with urllib.request.urlopen(req, timeout=ETF_DETAIL_TIMEOUT) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("ErrCode") != 0:
        return {}
    data = payload.get("Datas")
    return data if isinstance(data, dict) else {}


def _etf_rate(value):
    """费率字段（实测形如 "0.15%" / "--"）→ 百分数 float；不可解析返回 None。"""
    if value is None:
        return None
    s = str(value).strip().rstrip("%").strip()
    if not s or s == "--":
        return None
    try:
        return float(s)
    except ValueError:
        return None


def _etf_fee_rate(detail):
    """管理费+托管费合计（年化%）：两值均解析成功才输出，任一缺失/不可解析 → None
    （宁缺毋低估——部分合计会让费率上限筛选漏杀）。"""
    mgmt, trust = _etf_rate(detail.get("MGREXP")), _etf_rate(detail.get("TRUSTEXP"))
    if mgmt is None or trust is None:
        return None
    return round(mgmt + trust, 4)


def _etf_scale(detail):
    """ENDNAV（元，字符串，实测 510300="94872183996.4"）→ 亿元；缺失/不可解析 → None。"""
    raw = detail.get("ENDNAV")
    if raw is None:
        return None
    s = str(raw).strip()
    if not s or s == "--":
        return None
    try:
        return round(float(s) / 1e8, 4)
    except ValueError:
        return None


def _etf_tracking_index_code(detail):
    """证券指数码才保留：空/`--`（货币）、AU*/AG* 现货前缀、INDEXNAME 含期货/现货
    （大商所豆粕期货价格指数等）→ None，且 tracking_index_name 一并置 null。"""
    code = str(detail.get("INDEXCODE") or "").strip()
    name = str(detail.get("INDEXNAME") or "").strip()
    if not code or code == "--":
        return None
    if code.upper().startswith(ETF_SPOT_INDEX_PREFIXES):
        return None
    if "期货" in name or "现货" in name:
        return None
    return code


def _etf_category(ftype, index_name, fund_name=""):
    """FTYPE + INDEXNAME + 基金名（enrich 失败时的兜底信号）→ 宽基/行业/商品/债券/QDII/其他。
    关键词规则常量化（ETF_*_KEYWORDS），优先级 QDII → 债券 → 商品 → 行业 → 宽基，
    无任何分类信号 → 其他。"""
    text = "|".join(str(x or "") for x in (ftype, index_name, fund_name))
    if any(k in text for k in ETF_QDII_KEYWORDS):
        return "QDII"
    if any(k in text for k in ETF_BOND_KEYWORDS):
        return "债券"
    if any(k in text for k in ETF_COMMODITY_KEYWORDS):
        return "商品"
    if any(k in text for k in ETF_INDUSTRY_KEYWORDS):
        return "行业"
    if not text.strip("|"):
        return "其他"
    return "宽基"


class EtfBasicSource(Source):
    """ETF 目录/费率/规模（MS-14 P3 Task 13，周更）：新浪目录枚举 + 移动端 Detail 逐只 enrich。

    supports_range=False：目录是「当前全集快照」语义，无历史区间可回填（backfill 拒绝）。
    """

    supports_range = False

    def __init__(self, source_id, catalog_fetch=None, detail_fetch=None, sleep_fn=time.sleep):
        self.source_id = source_id
        self.catalog_fetch = catalog_fetch or _default_etf_catalog
        self.detail_fetch = detail_fetch or _default_etf_detail
        self.sleep_fn = sleep_fn

    def fetch(self, params):
        catalog = self.catalog_fetch()
        codes = catalog["代码"].astype(str).str.extract(_ETF_FUND_CODE, expand=False)
        catalog_names = catalog["名称"].astype(str)
        rows = []
        for code, fallback_name in zip(codes, catalog_names, strict=True):
            try:
                detail = self.detail_fetch(code) or {}
            except Exception:  # 单只失败/超时 → 该行 enrich 字段 null，不阻断整批
                detail = {}
            fund_name = str(detail.get("SHORTNAME") or "").strip() or fallback_name
            if not fund_name:  # 目录与 enrich 均无名：无展示价值的行直接跳过
                continue
            index_code = _etf_tracking_index_code(detail)
            rows.append(
                {
                    "fund_code": code,
                    "fund_name": fund_name,
                    "fee_rate": _etf_fee_rate(detail),
                    "scale": _etf_scale(detail),
                    "tracking_index_code": index_code,
                    "tracking_index_name": str(detail.get("INDEXNAME") or "").strip() if index_code else None,
                    "category": _etf_category(detail.get("FTYPE"), detail.get("INDEXNAME"), fund_name),
                }
            )
            self.sleep_fn(ETF_ENRICH_INTERVAL)
        return pd.DataFrame(rows, columns=ETF_BASIC_COLUMNS)


# ------------------------------------------- MS-14 P3 Task 14 ETF/跟踪指数日线 etf_close / tracking_index_close
#
# 源口径（09-调研报告/2026-09-26-ETF数据源探测.md §三/§七，实测钉住）：
# - etf_close：fund_daily(trade_date=YYYYMMDD) 全市场单次模式（实测 2134 行/0.1s，含 LOF/封基
#   与 .OF 脏行）——与 etf_basic.fund_code 半连接过滤（勿用前缀白名单，§七建议：深市新段
#   158xxx 与脏行自然解决）；fund_daily ts_code 带 .SH/.SZ 后缀，剥后缀后 join。
#   日常增量取最近一个开市日（单次调用）；回填（supports_range）按交易日历逐日循环
#   trade_date 模式（250 日 × 0.3s ≈ 2 分钟，远优于单码 1685 次循环）。
# - tracking_index_close：读 etf_basic DISTINCT tracking_index_code（非 null）去重循环
#   index_daily(ts_code=..., start_date, end_date)——后缀按 §三码族规则候选逐一探测
#   （000 段 .SH 拉空须回退 .CSI；INDEXTEXCH 对中证系为 '--' 不能照搬拼后缀），命中缓存
#   实例级复用；海外/港股/极新国证码（NDX100/N225/CES100/980034…）探测不可得 → 跳过并
#   计数（日志留痕，df 不含该码），Task 15 误差计算侧自然降级 null（报告 §六）。
ETF_DAILY_INTERVAL = 0.3  # fund_daily 逐日回填调用间隔（探测 §三：0.35s 连发零限频，保守 0.3s）
INDEX_DAILY_INTERVAL = 0.3  # index_daily 逐指数调用间隔（同上）
_RECENT_OPEN_LOOKBACK_DAYS = 15  # 无参日常增量回溯最近开市日的自然日窗口（覆盖最长假期）


def _open_days(pro, start_ymd, end_ymd):
    """trade_cal 开市日升序（YYYYMMDD 字符串）。"""
    cal = pro.trade_cal(exchange="SSE", start_date=start_ymd, end_date=end_ymd, is_open="1")
    if cal is None or cal.empty:
        return []
    return sorted(pd.to_datetime(cal["cal_date"]).dt.strftime("%Y%m%d").tolist())


def _target_days(pro, params):
    """目标交易日序列：显式 start/end（backfill）→ 区间开市日；date → 单日；无参 → 最近一个开市日。

    无参回溯最近开市日而非直取今天：交易日 cron 下两者一致；周末/节假日手动补跑可自愈
    最近交易日的缺口（upsert 幂等，重复行无害）。
    """
    if "start" in params or "end" in params:
        start = normalize_date(params.get("start") or params.get("end"), "start")
        end = normalize_date(params.get("end") or params.get("start"), "end")
        return _open_days(pro, start, end)
    if params.get("date"):
        return [normalize_date(params["date"], "date")]
    today = dt.date.today().strftime("%Y%m%d")
    lookback = (dt.date.today() - dt.timedelta(days=_RECENT_OPEN_LOOKBACK_DAYS)).strftime("%Y%m%d")
    return _open_days(pro, lookback, today)[-1:]


def _index_ts_candidates(code):
    """跟踪指数码 → index_daily ts_code 候选序列（探测报告 §三后缀规则，按序逐一探测）。

    中证系 93xxxx/H 码族 → .CSI；国证 970 → .SZ；沪系 000/950 → .SH（次选 .CSI——中证系
    与上证共用 000 段，先 .SH 拉空再试 .CSI）；深系 399 → .SZ；未归族码（980/987 国证
    极新码、CBA 估值码、海外字母码等）三候选兜底探测，全空即跳过。
    """
    if code.startswith(("93", "H")):
        return (code + ".CSI",)
    if code.startswith(("000", "950")):
        return (code + ".SH", code + ".CSI")
    if code.startswith(("399", "970")):
        return (code + ".SZ",)
    return (code + ".SZ", code + ".CSI", code + ".SH")


class EtfCloseSource(Source):
    """全市场 ETF 收盘（MS-14 P3 Task 14，写 index_close_history）：fund_daily trade_date
    全市场单次 + etf_basic.fund_code 半连接过滤；index_name 取 etf_basic.fund_name。

    supports_range=True：backfill 按交易日历逐日循环 trade_date 模式。
    etf_basic 为空（目录任务未跑）时半连接产出 0 行，由 min_rows hard 校验兜底失败。
    """

    supports_range = True

    def __init__(self, source_id, conn_factory, pro_factory, sleep_fn=time.sleep):
        self.source_id = source_id
        self.conn_factory = conn_factory
        self.pro_factory = pro_factory
        self.sleep_fn = sleep_fn

    def _fund_whitelist(self):
        """etf_basic 白名单：fund_code → fund_name（半连接过滤 + index_name 来源）。"""
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute("SELECT fund_code, fund_name FROM etf_basic")
            rows = cur.fetchall()
        return pd.DataFrame(rows, columns=["fund_code", "fund_name"])

    def _filter_etf_rows(self, day_frame, whitelist):
        """ts_code 剥 .SH/.SZ/.OF 后缀后与白名单 inner join：LOF/封基/场外脏行自然剔除。"""
        out = day_frame.rename(columns={"trade_date": "trading_day"})
        out["index_code"] = out["ts_code"].str.split(".").str[0]
        out = out.merge(whitelist, left_on="index_code", right_on="fund_code", how="inner")
        out["index_name"] = out["fund_name"]
        return out[["trading_day", "index_code", "index_name", "close"]]

    def fetch(self, params):
        pro = self.pro_factory()
        whitelist = self._fund_whitelist()
        frames = []
        for i, day in enumerate(_target_days(pro, params)):
            if i:
                self.sleep_fn(ETF_DAILY_INTERVAL)
            df = pro.fund_daily(trade_date=day)
            if df is None or df.empty:
                continue  # 回填窗口内上游暂无该日数据：跳过不阻断（当日增量缺数由 min_rows hard 兜底）
            frames.append(self._filter_etf_rows(df, whitelist))
        if not frames:
            return pd.DataFrame(columns=["trading_day", "index_code", "index_name", "close"])
        return pd.concat(frames, ignore_index=True)


class TrackingIndexCloseSource(Source):
    """ETF 跟踪指数收盘（MS-14 P3 Task 14，写 index_close_history）：etf_basic DISTINCT
    tracking_index_code 去重循环 index_daily；指数行 index_name 取 etf_basic.tracking_index_name。

    ts_code 后缀候选逐一探测（000 段 .SH 空回退 .CSI），命中缓存实例级复用（调度进程长驻，
    后续运行免重复探测）；探测不可得码跳过并计数。supports_range=True：backfill 以
    start/end 区间逐指数单次拉取（index_daily 原生支持区间）。
    """

    supports_range = True

    _CLOSE_COLUMNS = ["trading_day", "index_code", "index_name", "close"]

    def __init__(self, source_id, conn_factory, pro_factory, sleep_fn=time.sleep):
        self.source_id = source_id
        self.conn_factory = conn_factory
        self.pro_factory = pro_factory
        self.sleep_fn = sleep_fn
        self._suffix_cache = {}  # code → 命中的 ts_code（实例级，跨运行复用；不做负缓存，新码可复探）

    def _tracking_indexes(self):
        """etf_basic 去重跟踪指数码 → 名称（同码多名取 MAX，稳态下名称一致）。"""
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT tracking_index_code, MAX(tracking_index_name) FROM etf_basic "
                "WHERE tracking_index_code IS NOT NULL AND tracking_index_code <> '' "
                "GROUP BY tracking_index_code"
            )
            return cur.fetchall()

    def _fetch_one(self, code, call):
        """缓存/候选探测 index_daily；返回命中的原始帧，全候选空返回 None（不可得）。"""
        candidates = (self._suffix_cache[code],) if code in self._suffix_cache else _index_ts_candidates(code)
        for ts_code in candidates:
            df = call(ts_code)
            if df is not None and not df.empty:
                self._suffix_cache[code] = ts_code
                return df
        return None

    def fetch(self, params):
        pro = self.pro_factory()
        start, end = _date_param(params, "start"), _date_param(params, "end")
        if not (params.get("start") or params.get("end") or params.get("date")):
            days = _target_days(pro, {})  # 无参日常增量：最近开市日（周末补跑自愈）
            if not days:
                return pd.DataFrame(columns=self._CLOSE_COLUMNS)
            start = end = days[-1]
        frames, skipped = [], []
        state = {"first": True}

        def call(ts_code, _start=start, _end=end):
            if not state["first"]:
                self.sleep_fn(INDEX_DAILY_INTERVAL)
            state["first"] = False
            return pro.index_daily(ts_code=ts_code, start_date=_start, end_date=_end)

        for code, name in sorted(self._tracking_indexes()):
            df = self._fetch_one(code, call)
            if df is None:
                skipped.append(code)
                continue
            df = df.rename(columns={"trade_date": "trading_day"})
            df["index_code"], df["index_name"] = code, name
            frames.append(df[["trading_day", "index_code", "index_name", "close"]])
        if skipped:
            logger.warning(
                "%s: %d 个跟踪指数 index_daily 探测不可得已跳过（海外/港股/极新码，Task 15 误差计算将降级 null）：%s",
                self.source_id,
                len(skipped),
                ",".join(skipped),
            )
        if not frames:
            return pd.DataFrame(columns=self._CLOSE_COLUMNS)
        return pd.concat(frames, ignore_index=True)


# ------------------------------------------- MS-14 P3 Task 15 ETF 跟踪误差近1年重算 etf_tracking_error
#
# 口径（设计规格 §3.1 决策 #3，权威）：窗口=近 1 年（250 交易日，join 后最近 251 个共同收盘 →
# 250 个日收益差）；std(ETF日收益 − 指数日收益, ddof=1) × √252；日收益=收盘价环比（市场价口径，
# 与 index_close_history 同源）；有效共同交易日样本 <120 → null。对齐：两序列按 trading_day
# inner join 后各自 pct_change（等价于「共同交易日上的各自收益」，指数缺失日的 ETF 收益自然跳过，
# 不插值）。
# 写路径：共享 etf_basic upsert 不触碰 tracking_error_1y（writer.py 注记），本源在 fetch 内
# 自行 UPDATE 该列（读+回写同连接同事务）；参与集 = tracking_index_code 非 null 的基金逐行回写
# （值或 null——全量重算幂等自愈），tracking_index_code null（货币/商品现货）与指数码无日线行
# （NDX100/N225 等 82 码探测不可得）自然降级 null。fetch 恒返回空帧走 writer 0 行写路径。
ETF_TRACKING_WINDOW_ROWS = 251  # join 后保留的最近共同交易日数（251 收盘 → 250 收益差）
ETF_TRACKING_MIN_SAMPLES = 120  # 有效共同交易日下限（<120 → null，设计规格 §3.1）
ETF_ANNUALIZATION = math.sqrt(252)  # 日频 std → 年化

ETF_TRACKING_PAIRS_SQL = (
    "SELECT fund_code, tracking_index_code FROM etf_basic "
    "WHERE tracking_index_code IS NOT NULL AND tracking_index_code <> ''"
)
# 400 自然日读窗：250 交易日 ≈ 365 自然日 + 节假日余量；窗口精确裁剪（tail 251）在 pandas 侧。
ETF_TRACKING_CLOSES_SQL = (
    "SELECT trading_day, index_code, close FROM index_close_history "
    "WHERE index_code = ANY(%s) AND trading_day >= CURRENT_DATE - 400"
)
ETF_TRACKING_UPDATE_SQL = "UPDATE etf_basic SET tracking_error_1y = %s WHERE fund_code = %s"


class EtfTrackingErrorSource(Source):
    """ETF 跟踪误差近1年重算（MS-14 P3 Task 15，DB→DB 日更）：读 index_close_history 两序列
    （ETF 日线 index_code=6位基金码 + 跟踪指数日线）对齐自算，回写 etf_basic.tracking_error_1y。

    supports_range=False：常规调度即全量重算（每日幂等），不走 backfill CLI。
    """

    supports_range = False

    _EMPTY_COLUMNS = ["fund_code", "tracking_error_1y"]

    def __init__(self, source_id, conn_factory):
        self.source_id = source_id
        self.conn_factory = conn_factory

    @staticmethod
    def _tracking_error(joined):
        """join 对齐后的两收盘序列 → 年化跟踪误差；样本不足返回 None。

        joined 需已按 trading_day 升序：tail(251) 取窗口，pct_change(fill_method=None)
        在共同交易日上环比（不插值，指数缺失日的 ETF 收益自然跳过）。
        """
        window = joined.tail(ETF_TRACKING_WINDOW_ROWS)
        if len(window) < ETF_TRACKING_MIN_SAMPLES:
            return None
        diff = window["close_etf"].pct_change(fill_method=None) - window["close_idx"].pct_change(fill_method=None)
        return round(float(diff.std(ddof=1)) * ETF_ANNUALIZATION, 6)

    def fetch(self, params):
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(ETF_TRACKING_PAIRS_SQL)
            pairs = cur.fetchall()
            if not pairs:
                return pd.DataFrame(columns=self._EMPTY_COLUMNS)
            codes = sorted({fund for fund, _ in pairs} | {index for _, index in pairs})
            cur.execute(ETF_TRACKING_CLOSES_SQL, (codes,))
            rows = cur.fetchall()
            closes = pd.DataFrame(rows, columns=["trading_day", "index_code", "close"])
            # NUMERIC 列经 psycopg 还原为 decimal.Decimal（对象 dtype，pct_change/std 无法做
            # float 算术）；统一转 float64，测试侧已是 float 时为幂等 no-op。
            closes["close"] = closes["close"].astype(float)
            series = {
                code: g[["trading_day", "close"]].sort_values("trading_day") for code, g in closes.groupby("index_code")
            }
            updates = []
            for fund_code, index_code in pairs:
                etf, index = series.get(fund_code), series.get(index_code)
                if etf is None or index is None:
                    updates.append((None, fund_code))  # 指数日线无行（海外码等）→ null
                    continue
                joined = etf.merge(index, on="trading_day", how="inner", suffixes=("_etf", "_idx"))
                updates.append((self._tracking_error(joined), fund_code))
            cur.executemany(ETF_TRACKING_UPDATE_SQL, updates)
        computed = sum(1 for value, _ in updates if value is not None)
        logger.info(
            "%s: 跟踪误差重算完成，参与 %d 只（非 null %d 只，样本<120 或指数缺行置 null %d 只）",
            self.source_id,
            len(updates),
            computed,
            len(updates) - computed,
        )
        return pd.DataFrame(columns=self._EMPTY_COLUMNS)


class IndustryUniverseSource(Source):
    """全A估值快照 + 申万行业映射的 JOIN 源。

    直接读取 weekly `shenwan_mapping` 任务已写入的 shenwan_industry_mapping 表，
    与 tushare daily_basic 全A估值快照做 inner join，产出带 industry_code/industry_name 的全A明细，
    供行业加权估值（industry_weighted calc）消费。Converter/Calc 保持纯净。
    """

    supports_range = False

    def __init__(self, source_id, conn_factory, pro_factory):
        self.source_id = source_id
        self.conn_factory = conn_factory  # 返回 psycopg 连接的可调用对象
        self.pro_factory = pro_factory  # 返回 tushare pro_api 的可调用对象

    def fetch(self, params):
        pro = self.pro_factory()
        target = normalize_date(params.get("date") or dt.date.today(), "date")
        universe = _daily_basic_spot(pro, target, self.source_id)  # tushare 全A估值（替代 akshare spot）
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute("SELECT stock_code, industry_code, industry_name FROM shenwan_industry_mapping")
            rows = cur.fetchall()
        mapping = pd.DataFrame(rows, columns=["stock_code", "industry_code", "industry_name"])
        merged = universe.merge(mapping, left_on="代码", right_on="stock_code", how="inner")
        # 追加：读 stock_financial 最新 roe 与 stock_valuation_daily 最新 dividend_yield 并入
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT DISTINCT ON (stock_code) stock_code, roe "
                "FROM stock_financial ORDER BY stock_code, report_date DESC"
            )
            fin = pd.DataFrame(cur.fetchall(), columns=["stock_code", "roe"])
            cur.execute(
                "SELECT stock_code, dividend_yield FROM stock_valuation_daily "
                "WHERE trading_day = (SELECT max(trading_day) FROM stock_valuation_daily)"
            )
            val = pd.DataFrame(cur.fetchall(), columns=["stock_code", "dividend_yield"])
        merged = merged.merge(fin, left_on="stock_code", right_on="stock_code", how="left")
        merged = merged.merge(val, left_on="stock_code", right_on="stock_code", how="left")
        return merged


TERMS = [
    ("1Y", "1年"),
    ("3Y", "3年"),
    ("5Y", "5年"),
    ("10Y", "10年"),
    ("30Y", "30年"),
]

# bond_zh_us_rate 已下架 1Y/3Y 列（2026-09 实测仅剩 2Y/5Y/10Y/30Y），改用中债信息网
# bond_china_yield（全期限、按区间查询）。其单次区间上限约 1 年，按 180 天切块留余量。
_CURVE_NAME = "中债国债收益率曲线"
_CURVE_CHUNK_DAYS = 180
_CURVE_EARLIEST = dt.date(2006, 1, 1)  # 中债国债收益率曲线自 2006-03 起，空表全量回填起点


class TreasuryCurveSource(Source):
    supports_range = True

    def __init__(self, source_id, conn_factory=None):
        self.source_id = source_id
        self.conn_factory = conn_factory  # 增量拉取用：查询已入库的最大交易日

    def _max_trading_day(self):
        if self.conn_factory is None:
            return None
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute("SELECT max(trading_day) FROM treasury_yield_curve")
            return cur.fetchone()[0]

    def _range_bounds(self, params):
        """显式 start/end 区间（backfill）→ (start_date, end_date)；无区间 → (None, None)。

        区间以 params 为准并优先于 DB watermark：backfill 需按调用方指定区间回填，
        而不是被 DB 已入库最大交易日静默改写语义。start/end 可只给一端。
        """
        start_s = params.get("start") and normalize_date(params["start"], "start")
        end_s = params.get("end") and normalize_date(params["end"], "end")
        if not start_s and not end_s:
            return None, None
        start = dt.datetime.strptime(start_s, "%Y%m%d").date() if start_s else None
        end = dt.datetime.strptime(end_s, "%Y%m%d").date() if end_s else None
        return start, end

    def _fetch_treasury_curve(self, start, end):
        """bond_china_yield 按 180 天切块拉取，过滤出国债曲线；期限列缺失时显式报错（不静默跳过）。"""
        frames = []
        chunk_start = start
        while chunk_start <= end:
            chunk_end = min(chunk_start + dt.timedelta(days=_CURVE_CHUNK_DAYS - 1), end)
            df = ak.bond_china_yield(start_date=chunk_start.strftime("%Y%m%d"), end_date=chunk_end.strftime("%Y%m%d"))
            if df is not None and not df.empty:
                frames.append(df)
            chunk_start = chunk_end + dt.timedelta(days=1)
        if not frames:
            return pd.DataFrame(columns=["日期", *[col for _, col in TERMS]])
        df = pd.concat(frames, ignore_index=True)
        df = df[df["曲线名称"] == _CURVE_NAME]
        missing = [col for _, col in TERMS if col not in df.columns]
        if missing:
            raise SourceError(f"{self.source_id}: bond_china_yield 缺少期限列 {missing}，上游结构可能已变更")
        return df

    def fetch(self, params):
        start, end = self._range_bounds(params)
        # 有显式区间时以区间为准，不再以 DB max(trading_day) 做增量下界；
        # 无区间（日常增量调度）才回退到 watermark 行为。
        since = None if start is not None or end is not None else self._max_trading_day()
        query_start = start or (since + dt.timedelta(days=1) if since else _CURVE_EARLIEST)
        query_end = end or dt.date.today()
        if query_start > query_end:
            return pd.DataFrame(columns=["trading_day", "term", "yield"])
        df = self._fetch_treasury_curve(query_start, query_end)
        rows = []
        for _, r in df.iterrows():
            day = r["日期"]
            if isinstance(day, pd.Timestamp):
                day = day.date()
            elif not isinstance(day, dt.date):
                day = dt.date.fromisoformat(str(day))
            if since is not None and day <= since:
                continue  # 增量：只拉入库最大交易日之后的行
            if start is not None and day < start:
                continue
            if end is not None and day > end:
                continue
            for term, col in TERMS:
                if pd.notna(r[col]):
                    rows.append({"trading_day": day, "term": term, "yield": float(r[col])})
        return pd.DataFrame(rows, columns=["trading_day", "term", "yield"])


class IndexConstituentSource(Source):
    supports_range = False

    def __init__(self, source_id, pro_factory, index_codes=None):
        self.source_id = source_id
        self.pro_factory = pro_factory
        self.index_codes = index_codes or INDEX_CODES

    def fetch(self, params):
        pro = self.pro_factory()
        frames = []
        for code in self.index_codes:
            df = pro.index_weight(index_code=_ts_code(code))
            df = df.rename(columns={"index_code": "index_code", "con_code": "stock_code"})
            df["stock_code"] = df["stock_code"].str.split(".").str[0]
            df["index_code"] = code
            frames.append(df[["index_code", "stock_code", "weight"]])
        return pd.concat(frames, ignore_index=True)


class StockValuationDailySource(Source):
    """全 A 个股估值日快照：tushare daily_basic 按交易日批量 + stock_basic 过滤 ST/退市/北交所。
    含收盘价 close 列（MS-07）；supports_range=True 时按交易日历逐日拉取，供历史回填。"""

    STOCK_DAILY_MIN_INTERVAL = 0.35  # daily_basic 客户端限速（≈171 次/分钟），对照 FINANCIAL_MIN_INTERVAL

    supports_range = True

    def __init__(self, source_id, pro_factory, limiter=None):
        self.source_id = source_id
        self.pro_factory = pro_factory
        self.limiter = limiter if limiter is not None else RateLimiter(min_interval=self.STOCK_DAILY_MIN_INTERVAL)

    def _valid_universe(self, pro):
        basic = pro.stock_basic(list_status="L", fields="ts_code,name")
        basic = basic[basic["ts_code"].str.endswith((".SH", ".SZ"))]  # 剔除北交所/老三板
        return basic[~basic["name"].str.contains("ST|退", na=False)]

    def _fetch_day(self, pro, universe, day):
        daily = pro.daily_basic(trade_date=day)
        df = daily.merge(universe, on="ts_code", how="inner").copy()
        df["stock_code"] = df["ts_code"].str.split(".").str[0]
        df["stock_name"] = df["name"]
        df["dividend_yield"] = df["dv_ttm"]
        df["total_mv"] = df["total_mv"] * 10000
        df["circ_mv"] = df["circ_mv"] * 10000
        return df

    def _open_days(self, pro, start, end):
        cal = pro.trade_cal(exchange="SSE", start_date=start, end_date=end, is_open="1")
        return sorted(cal["cal_date"].tolist())

    def fetch(self, params):
        pro = self.pro_factory()
        start, end = _date_param(params, "start"), _date_param(params, "end")
        universe = self._valid_universe(pro)
        if "start" in params or "end" in params or "date" not in params:
            days = self._open_days(pro, start, end)
        else:
            days = [start]  # 仅 date 参数：单日增量（start 已回退为 date）
        frames = []
        for day in days:
            self.limiter.wait()
            df = self._fetch_day(pro, universe, day)
            df.insert(0, "trading_day", day)
            frames.append(df)
        out = pd.concat(frames, ignore_index=True)
        return out[
            [
                "trading_day",
                "stock_code",
                "stock_name",
                "pe_ttm",
                "pb",
                "dividend_yield",
                "total_mv",
                "circ_mv",
                "turnover_rate",
                "close",
            ]
        ]


def _last_n_periods(n):
    """最近 n 个季报期末日（YYYYMMDD 升序）：从最近已结束季度起逐季倒推。"""
    today = dt.date.today()
    periods = []
    # 当前季度（0 起）减 1 = 最近已结束季度，作为回填起点
    start_quarter = (today.month - 1) // 3 - 1
    for offset in range(n - 1, -1, -1):
        quarter_index = start_quarter - offset
        y = today.year
        m = quarter_index * 3 + 1
        while m <= 0:  # 跨多个年度时循环归一化月份与年份
            m += 12
            y -= 1
        end_month = m + 2  # 该季度最后一个月（m∈{1,4,7,10} → end_month∈{3,6,9,12}）
        period_end = dt.date(y, 12, 31) if end_month == 12 else dt.date(y, end_month + 1, 1) - dt.timedelta(days=1)
        periods.append(period_end.strftime("%Y%m%d"))
    return periods


class StockFinancialSource(Source):
    """全 A 个股财务指标季数据：tushare fina_indicator 按报告期批量，回填近 3 年（12 季）；
    income 合并口径营收按 end_date 左并入（MS-09），无匹配期营收留 NaN。"""

    supports_range = False

    def __init__(self, source_id, pro_factory, periods=12, limiter=None):
        self.source_id = source_id
        self.pro_factory = pro_factory
        self.periods = periods
        # FR-11 客户端限速：每个报告期请求前 wait() 一次，全局错开调用起始时刻。
        self.limiter = limiter if limiter is not None else RateLimiter(min_interval=FINANCIAL_MIN_INTERVAL)

    def fetch(self, params):
        pro = self.pro_factory()
        # 最近 N 个季报期末日（升序）的最早一个作截断：逐股拉取后按 end_date 过滤，保留近 N 季。
        cutoff = min(_last_n_periods(self.periods))
        # 一次取全 A 有效池（剔除 ST/退市/北交所），逐股请求，与全 A 口径一致。
        valid = sorted(_valid_universe_ts_codes(pro))

        def _fetch_stock(ts_code):
            self.limiter.wait()
            # fina_indicator 是逐股接口（必填 ts_code），缺省返回该股全部报告期，再按截断过滤近 N 季。
            df = pro.fina_indicator(ts_code=ts_code)
            if df is None or df.empty:
                return None
            df = df[df["end_date"] >= cutoff]
            if df.empty:
                return None
            df = df[
                [
                    "ts_code",
                    "end_date",
                    "roe",
                    "roa",
                    "grossprofit_margin",
                    "debt_to_assets",
                    "current_ratio",
                    "or_yoy",
                    "netprofit_yoy",
                ]
            ]
            # income 并入营收（MS-09）：同为逐股接口。start_date/end_date 是公告日窗口不可靠
            # （调研实测），禁止传参限窗，报告期窗口由客户端按 end_date >= cutoff 截断（fina 同款）。
            self.limiter.wait()
            income_df = pro.income(ts_code=ts_code, fields=INCOME_FIELDS)
            if income_df is not None and not income_df.empty:
                income_df = income_df[income_df["report_type"] == INCOME_MERGED_REPORT_TYPE]
                income_df = income_df[income_df["end_date"] >= cutoff]
                # 同 end_date 多行的真实来源是 update_flag 0/1 并存：按 (update_flag, ann_date)
                # 降序稳定排序后每组取首行 = update_flag 最大者、并列时 ann_date 更晚者（调研报告定稿规则）；
                # 两键完全并列时按稳定语义保持原始出现顺序、先出现的行胜（issue #39 显式化：
                # 默认 quicksort 非稳定，完全并列的胜者会随输入行序漂移，故必须 kind="stable"）。
                income_df = income_df.sort_values(["update_flag", "ann_date"], ascending=False, kind="stable")
                income_df = income_df.drop_duplicates(subset=["end_date"], keep="first")
                income_df = income_df.assign(revenue=income_df["revenue"] * INCOME_REVENUE_SCALE)
                df = df.merge(income_df[["end_date", "revenue"]], on="end_date", how="left")
            if "revenue" not in df.columns:
                df["revenue"] = float("nan")  # income 无数据/无匹配：营收留空，不阻断其余指标
            return df[
                [
                    "ts_code",
                    "end_date",
                    "roe",
                    "roa",
                    "grossprofit_margin",
                    "debt_to_assets",
                    "current_ratio",
                    "or_yoy",
                    "netprofit_yoy",
                    "revenue",
                ]
            ]

        # 全 A 个股并行拉取：executor.map 保持输入顺序，结果拼接顺序与串行版一致
        with ThreadPoolExecutor(max_workers=4) as executor:
            frames = [frame for frame in executor.map(_fetch_stock, valid) if frame is not None]

        if not frames:
            return pd.DataFrame(
                columns=[
                    "report_date",
                    "stock_code",
                    "roe",
                    "roa",
                    "gross_margin",
                    "debt_to_assets",
                    "current_ratio",
                    "revenue_yoy",
                    "netprofit_yoy",
                    "revenue",
                ]
            )
        result = pd.concat(frames, ignore_index=True)
        result["stock_code"] = result["ts_code"].str.split(".").str[0]
        result = result.rename(
            columns={
                "end_date": "report_date",
                "grossprofit_margin": "gross_margin",
                "or_yoy": "revenue_yoy",
            }
        )
        return result[
            [
                "report_date",
                "stock_code",
                "roe",
                "roa",
                "gross_margin",
                "debt_to_assets",
                "current_ratio",
                "revenue_yoy",
                "netprofit_yoy",
                "revenue",
            ]
        ]


class AllASpotBackupSource(Source):
    """全A估值快照的 tushare 备源（akshare stock_zh_a_spot_em 失败时降级）。

    用 daily_basic 按交易日取全市场估值，与 stock_basic 内接股票名，剔除 ST/退市/北交所；
    输出列与 akshare spot 同构（代码/名称/市盈率-动态/市净率/总市值），使
    field_mapping_all_a 与 snapshot calc 无需区分来源。目标日无数据时沿交易日历前溯。
    """

    supports_range = False

    def __init__(self, source_id, pro_factory):
        self.source_id = source_id
        self.pro_factory = pro_factory

    def fetch(self, params):
        pro = self.pro_factory()
        target = normalize_date(params.get("date") or dt.date.today(), "date")
        return _daily_basic_spot(pro, target, self.source_id)


def _open_days_desc(pro, end_ymd, lookback=45):
    """end（含）往前 lookback 自然日内的开市日（trade_cal），按降序。

    lookback 取 45 自然日：index_weight 仅在调仓日发布，需能回溯到最近一个调仓日。
    """
    end = dt.datetime.strptime(end_ymd, "%Y%m%d").date()
    start = end - dt.timedelta(days=lookback)
    cal = pro.trade_cal(exchange="SSE", start_date=start.strftime("%Y%m%d"), end_date=end_ymd)
    if cal is None or cal.empty:
        return []
    cal = cal[cal["is_open"] == 1]
    return sorted((str(c) for c in cal["cal_date"]), reverse=True)


def _daily_basic_spot(pro, day_ymd, source_id):
    """tushare daily_basic 全市场估值 → akshare 同构中文列（代码/名称/市盈率-动态/市净率/总市值）。

    目标日无 daily_basic 时沿最近开市日回溯；输出列与 akshare stock_zh_a_spot_em 同构，
    供 all_a_valuation 备源与 industry_universe 复用，field_mapping/snapshot/industry calc 无需区分来源。
    """
    df = pro.daily_basic(trade_date=day_ymd)
    if df is None or df.empty:
        day = next((d for d in _open_days_desc(pro, day_ymd, lookback=15) if d < day_ymd), None)
        if day is None:
            raise SourceError(f"{source_id}: 目标日 {day_ymd} 无 daily_basic 数据")
        df = pro.daily_basic(trade_date=day)
    if df is None or df.empty:
        raise SourceError(f"{source_id}: daily_basic 无数据")
    basic = pro.stock_basic(list_status="L", fields="ts_code,name")
    df = df.merge(basic, on="ts_code", how="inner")
    df = df[df["ts_code"].str.endswith((".SH", ".SZ"))]
    df = df[~df["name"].str.contains("ST|退", na=False)]
    df = df.copy()
    df["代码"] = df["ts_code"].str.split(".").str[0]
    df["名称"] = df["name"]
    df["市盈率-动态"] = df["pe_ttm"]
    df["市净率"] = df["pb"]
    df["总市值"] = df["total_mv"] * 10000  # tushare 万元 → 元（与 akshare 总市值口径一致）
    return df[["代码", "名称", "市盈率-动态", "市净率", "总市值"]]


def _index_dividend_yield(pro, index_code, end_ymd):
    """指数股息率近似：index_weight 最新成分权重 × daily_basic dv_ttm 的加权均值（口径 %）。

    index_weight 不带 trade_date（与 IndexConstituentSource 一致）取最新生效权重；
    个股 dv_ttm 在 end（含）最近有数据的开市日取一次。任一步无数据返回 None（调用方回退默认）。
    """
    weights = pro.index_weight(index_code=_ts_code(index_code))
    if weights is None or weights.empty or "con_code" not in weights.columns or "weight" not in weights.columns:
        return None
    for day in _open_days_desc(pro, end_ymd):
        basic = pro.daily_basic(trade_date=day, fields="ts_code,dv_ttm")
        if basic is None or basic.empty:
            continue
        merged = weights.merge(basic, left_on="con_code", right_on="ts_code", how="inner")
        merged = merged.dropna(subset=["weight", "dv_ttm"])
        merged = merged[merged["dv_ttm"] > 0]
        if merged.empty:
            continue
        return round(float((merged["weight"] * merged["dv_ttm"]).sum() / merged["weight"].sum()), 4)
    return None


def make_index_dividend_fetch(pro_factory, default=0.0):
    """构造 IndexValuationSource 的 dividend_fetch：(index_code, start, end) -> float。

    真实拉取 tushare 成分股权重×dv_ttm 估算指数股息率；积分不足/无数据时回退
    default（默认 0.0），保证 dividend_yield 不为 None 且不崩。
    """

    def fetch(index_code, start, end):
        try:
            value = _index_dividend_yield(pro_factory(), index_code, end or start)
        except Exception:
            value = None
        return default if value is None else value

    return fetch


class IndustryValuationBackfillSource(Source):
    """行业估值历史重算：从库内 stock_valuation_daily × 申万映射重算缺失日期的行业加权 PE/PB。

    仅产出早于 industry_valuation 现存最早快照日（且早于当日）的日期——与每日增量任务
    日期不相交，writer upsert 的 SET 不会触碰既有行的 roe/股息率；稳态（无缺失）返回空帧。
    口径与 IndustryValuationCalc 逐值一致（记录级过滤 pe>0 且市值非空；pe/pb 同以 Σ(total_mv)
    为分母，pb 缺失行只跳过分子——calc 的 pb 分母是无条件 Σcap，见 snapshot.py:45-46,62，
    非 roe/股息率的条件分母），由 tests/test_industry_valuation_backfill.py 双实现一致性测试锚定。
    supports_range=False：常规调度即自愈，不走 backfill CLI（backfill.py D3 会拒绝）。
    """

    supports_range = False

    _SQL = """
        SELECT d.trading_day, m.industry_code, MAX(m.industry_name) AS industry_name,
               SUM(d.pe_ttm * d.total_mv) / NULLIF(SUM(d.total_mv), 0) AS pe,
               SUM(d.pb * d.total_mv) / NULLIF(SUM(d.total_mv), 0) AS pb
        FROM stock_valuation_daily d
        JOIN shenwan_industry_mapping m ON m.stock_code = d.stock_code
        WHERE d.pe_ttm > 0 AND d.total_mv IS NOT NULL
          AND d.trading_day < CURRENT_DATE
          AND d.trading_day < COALESCE((SELECT MIN(trading_day) FROM industry_valuation), '2999-12-31')
        GROUP BY d.trading_day, m.industry_code
        ORDER BY d.trading_day
    """

    def __init__(self, source_id, conn_factory):
        self.source_id = source_id
        self.conn_factory = conn_factory

    def fetch(self, params):
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute(self._SQL)
            rows = cur.fetchall()
        out = pd.DataFrame(rows, columns=["trading_day", "industry_code", "industry_name", "pe", "pb"])
        # psycopg 把 DATE 列还原为 date 对象；统一转 ISO 字符串使 field_mapping_industry_history
        # （type:str）直通——输出契约（trading_day 为 'YYYY-MM-DD' 字符串）由集成测试锚定。
        out["trading_day"] = out["trading_day"].astype(str)
        return out
