"""ETF 域数据源：ETF 目录/费率/规模（etf_basic，周更）、ETF 与跟踪指数日线
（index_close_history）、跟踪误差近 1 年重算（DB→DB 回写 etf_basic）、
黄金 ETF 收盘（MS-13 回测黄金类代理）。
"""

import datetime as dt
import json
import logging
import math
import re
import time
import urllib.request

import akshare as ak
import pandas as pd

from collector.config import env_float
from collector.sources.base import Source
from collector.sources.common import _date_param, _open_days_desc, normalize_date
from collector.sources.constants import (
    ETF_DAILY_INTERVAL,
    ETF_DETAIL_HEADERS,
    ETF_DETAIL_TIMEOUT,
    ETF_DETAIL_URL,
    ETF_ENRICH_INTERVAL,
    GOLD_ETF_CODE,
    GOLD_ETF_NAME,
    GOLD_ETF_TS_CODE,
    INDEX_DAILY_INTERVAL,
    RECENT_OPEN_LOOKBACK_DAYS,
)

logger = logging.getLogger(__name__)

# ------------------------------------------- MS-14 P3 Task 13 ETF 目录/费率/规模 etf_basic
#
# 源口径（09-调研报告/2026-09-26-ETF数据源探测.md §七，字段样例均实测）：
# - 枚举：akshare fund_etf_category_sina("ETF基金")——场内 ETF 专列（1685 只），天然全为场内
#   标的，无需 ISEXCHG 过滤（§七勘误后口径）；代码带 sh/sz 前缀，6 位化后即 fund_code。
# - enrich：天天基金移动端 FundMNDetailInformation 逐只（Datas 单基金 JSON，UA+Referer 即可
#   无需 cookie）——SHORTNAME→fund_name、MGREXP+TRUSTEXP→fee_rate（合计年化%）、
#   ENDNAV→scale（元→亿元 ÷1e8）、INDEXCODE/INDEXNAME→tracking_index_*、FTYPE→category。
#   单只失败/超时该行字段置 null 不阻断整批（整体失败由 validator/retry 兜底）。

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

    def __init__(self, source_id, catalog_fetch=None, detail_fetch=None, sleep_fn=time.sleep, interval=None):
        self.source_id = source_id
        self.catalog_fetch = catalog_fetch or _default_etf_catalog
        self.detail_fetch = detail_fetch or _default_etf_detail
        self.sleep_fn = sleep_fn
        # P1-8：间隔默认取集中常量，env COLLECTOR_ETF_DETAIL_INTERVAL 可覆盖（如降速应对上游限流）
        if interval is None:
            interval = env_float("COLLECTOR_ETF_DETAIL_INTERVAL", ETF_ENRICH_INTERVAL)
        self.interval = interval

    def fetch(self, params):
        self.last_warnings = None  # 单例源：观测状态每次 fetch 重置
        self._enrich_failures = []
        catalog = self.catalog_fetch()
        codes = catalog["代码"].astype(str).str.extract(_ETF_FUND_CODE, expand=False)
        catalog_names = catalog["名称"].astype(str)
        rows = []
        for code, fallback_name in zip(codes, catalog_names, strict=True):
            try:
                detail = self.detail_fetch(code) or {}
                if not detail:
                    self._enrich_failures.append(str(code))
            except Exception:  # 单只失败/超时 → 该行 enrich 字段 null，不阻断整批
                detail = {}
                self._enrich_failures.append(str(code))
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
            self.sleep_fn(self.interval)
        # P1-8：失败计数上报（异常与空返回都算 enrich 失败——均导致该行字段 null），
        # 样例码截 5 个防 1685 只全量刷屏；进 run.message 由 executor 的 last_warnings 通道承担
        if self._enrich_failures:
            sample = ", ".join(self._enrich_failures[:5])
            self.last_warnings = [f"enrich 失败 {len(self._enrich_failures)}/{len(codes)}，样例：{sample}"]
            logger.warning("%s: %s", self.source_id, self.last_warnings[0])
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


def _target_days(pro, params):
    """目标交易日序列：显式 start/end（backfill）→ 区间开市日；date → 单日；无参 → 最近一个开市日。

    无参回溯最近开市日而非直取今天：交易日 cron 下两者一致；周末/节假日手动补跑可自愈
    最近交易日的缺口（upsert 幂等，重复行无害）。开市日统一走 common._open_days_desc
    （C6，2026-10-15 审查 P3-7：trade_cal 回溯口径收敛到 common，消除本模块的语义重叠）。
    """
    if "start" in params or "end" in params:
        start = normalize_date(params.get("start") or params.get("end"), "start")
        end = normalize_date(params.get("end") or params.get("start"), "end")
        # lookback 取区间自然日跨度：窗口恰为 [start, end]，升序后即区间开市日
        span = (dt.datetime.strptime(end, "%Y%m%d") - dt.datetime.strptime(start, "%Y%m%d")).days
        return sorted(_open_days_desc(pro, end, lookback=span))
    if params.get("date"):
        return [normalize_date(params["date"], "date")]
    today = dt.date.today().strftime("%Y%m%d")
    return _open_days_desc(pro, today, lookback=RECENT_OPEN_LOOKBACK_DAYS)[:1]


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
# 僵尸写收口（H2，2026-10-15 审查）：fetch 内 UPDATE 与被放弃线程不兼容——本任务 YAML 显式
# timeout_seconds=86400 让 fetch 超时包裹永不触发放弃（纯本地 DB 操作无需超时保护）。
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
                self.last_affected_rows = 0  # P0-5：参与集为空也如实上报 0
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
        # P0-5：UPDATE 在 fetch 内完成、fetch 恒返空帧（writer 0 行），实际影响行数挂属性上报
        self.last_affected_rows = len(updates)
        computed = sum(1 for value, _ in updates if value is not None)
        logger.info(
            "%s: 跟踪误差重算完成，参与 %d 只（非 null %d 只，样本<120 或指数缺行置 null %d 只）",
            self.source_id,
            len(updates),
            computed,
            len(updates) - computed,
        )
        return pd.DataFrame(columns=self._EMPTY_COLUMNS)


class GoldEtfCloseSource(Source):
    """华安黄金ETF收盘（MS-13 回测黄金类代理，真实可投含费损）：tushare fund_daily 518880.SH。"""

    supports_range = True

    def __init__(self, source_id, pro_factory):
        self.source_id = source_id
        self.pro_factory = pro_factory

    def fetch(self, params):
        pro = self.pro_factory()
        start, end = _date_param(params, "start"), _date_param(params, "end")
        df = pro.fund_daily(ts_code=GOLD_ETF_TS_CODE, start_date=start, end_date=end)
        df = df.rename(columns={"trade_date": "trading_day"})
        df["index_code"], df["index_name"] = GOLD_ETF_CODE, GOLD_ETF_NAME
        return df[["trading_day", "index_code", "index_name", "close"]]
