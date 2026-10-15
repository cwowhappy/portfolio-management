"""个股域数据源：估值日快照（tushare daily_basic 主源 + akshare spot 备源）与财务季数据
（fina_indicator + income 营收并入）。

停牌语义契约（C1，2026-10-12 审查）：daily_basic 只返回当日**有交易**的股票，
writer upsert 只写存在行——stock_valuation_daily 中 (trading_day, stock_code)
**缺席即停牌**，表中无显式 suspended 标志。连续停牌期间该股无新行，PE/PB 分位等
下游消费方不得把最近有行日的旧估值当作当日新数据。
"""

import datetime as dt
import logging
from concurrent.futures import ThreadPoolExecutor

import akshare as ak
import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.common import _daily_basic_spot, _date_param, normalize_date
from collector.sources.constants import FINANCIAL_MIN_INTERVAL, STOCK_DAILY_MIN_INTERVAL
from collector.sources.ratelimit import RateLimiter

logger = logging.getLogger(__name__)

# StockFinancialSource 逐股拉取 fina_indicator 前的最小调用间隔（秒）。
# fina_indicator 上游限 200 次/分钟，取 0.35s（≈171 次/分钟）留安全余量，避免逐股并发触发限流。

# income（利润表）并入营收的实测口径（09-调研报告/2026-09-17-income接口校准.md）：
# 缺省查询只回 report_type='1'（合并报表），客户端仍显式过滤防上游缺省行为变化；
# revenue 单位为元（600519/601398 2024 年报 1e11 量级实测），换算系数 1.0；
# ann_date/update_flag 必取：同 end_date 多行的真实来源是 update_flag 0/1 并存，去重与溯源必需。
INCOME_MERGED_REPORT_TYPE = "1"
INCOME_REVENUE_SCALE = 1.0
INCOME_FIELDS = "ts_code,end_date,report_type,revenue,ann_date,update_flag"


def _valid_universe_ts_codes(pro):
    """全 A 有效个股 ts_code 集合：仅沪深正常交易股，剔除 ST/退市/北交所（value-screening P1 口径）。"""
    basic = pro.stock_basic(list_status="L", fields="ts_code,name")
    if basic is None or basic.empty:
        return set()
    basic = basic[basic["ts_code"].str.endswith((".SH", ".SZ"))]
    basic = basic[~basic["name"].str.contains("ST|退", na=False)]
    return set(basic["ts_code"])


class StockValuationDailySource(Source):
    """全 A 个股估值日快照：tushare daily_basic 按交易日批量 + stock_basic 过滤 ST/退市/北交所。

    停牌语义（模块 docstring 契约）：daily_basic 只写有交易股票，停牌股当日无行。
    含收盘价 close 列（MS-07）；supports_range=True 时按交易日历逐日拉取，供历史回填。"""

    supports_range = True

    def __init__(self, source_id, pro_factory, limiter=None):
        self.source_id = source_id
        self.pro_factory = pro_factory
        self.limiter = limiter if limiter is not None else RateLimiter(min_interval=STOCK_DAILY_MIN_INTERVAL)

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


class StockValuationDailyBackupSource(Source):
    """全 A 个股估值日快照的 akshare 备源（P1-9，tushare daily_basic 失败时降级）。

    口径妥协（已确认接受，仅降级日生效；tushare 恢复后 upsert 覆盖回 TTM）：
    - dividend_yield=NULL：spot_em 无 dv_ttm（筛选域股息率过滤对 NULL 天然排除）；
    - pe_ttm 用「市盈率-动态」近似：动态口径（预测年化 EPS）非 TTM，分位消费方注意；
    - total_mv/circ_mv 为元口径直通（tushare 万元 → 主源 ×10000，此处原生元）。
    supports_range=False：spot 是实时快照，只救当日增量；历史 date 直接拒绝（防错日期写入）。
    过滤与主源同构：剔 ST/退市（名称规则）/北交所（代码段规则——spot 无后缀，用前缀白名单）。
    """

    supports_range = False

    _COLUMNS = [
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

    # 沪主板 60 / 深主板 00 / 中小板 002(00 前缀覆盖) / 创业板 30 / 科创板 68——北交所 43/83/87/920 段自然排除
    _MAIN_BOARD_PREFIXES = ("60", "00", "30", "68")

    def __init__(self, source_id, spot_fetch=None, is_open_day=None):
        self.source_id = source_id
        self.spot_fetch = spot_fetch or (lambda: ak.stock_zh_a_spot_em())
        # 节假日空帧保护（MS-28 P2-C5）：开市判定（YYYYMMDD → bool），None 保持原行为不查日历
        self.is_open_day = is_open_day

    def fetch(self, params):
        target_day = params.get("date")
        if target_day and normalize_date(target_day, "date") != dt.date.today().strftime("%Y%m%d"):
            raise SourceError(f"{self.source_id}: 备源为实时快照，仅支持当日增量（date={target_day} 不支持）")
        day = dt.date.today().strftime("%Y%m%d")
        # 节假日空帧保护（照 EtfCloseSource 语义）：非开市日返回空帧——0 行、不写非交易日行
        if self.is_open_day and not self.is_open_day(day):
            return pd.DataFrame(columns=self._COLUMNS)
        df = self.spot_fetch()
        df = df[df["代码"].astype(str).str.startswith(self._MAIN_BOARD_PREFIXES)]
        df = df[~df["名称"].astype(str).str.contains("ST|退", na=False)]
        out = pd.DataFrame(
            {
                "trading_day": day,
                "stock_code": df["代码"].astype(str),
                "stock_name": df["名称"].astype(str),
                "pe_ttm": pd.to_numeric(df["市盈率-动态"], errors="coerce"),
                "pb": pd.to_numeric(df["市净率"], errors="coerce"),
                "dividend_yield": None,
                "total_mv": pd.to_numeric(df["总市值"], errors="coerce"),
                "circ_mv": pd.to_numeric(df["流通市值"], errors="coerce"),
                "turnover_rate": pd.to_numeric(df["换手率"], errors="coerce"),
                "close": pd.to_numeric(df["最新价"], errors="coerce"),
            }
        )
        # 量级防御：A股市值消费方按「元」口径（V7 契约），个股中位数应在 1e8~1e12；
        # 上游若改单位（如亿元）会差 1e8 倍，拒绝而非污染落库（宁可不写不可错写）
        mv = out["total_mv"].dropna()
        median_mv = float(mv.median()) if not mv.empty else None
        if median_mv is not None and not 1e8 <= median_mv <= 1e12:
            raise SourceError(
                f"{self.source_id}: 总市值中位数 {median_mv:.3g} 超出元口径量级（1e8~1e12），疑似上游单位变更"
            )
        return out[self._COLUMNS]


def make_open_day_check(pro_factory, source=None):
    """构造单日开市判定（YYYYMMDD → bool）：trade_cal 查询，供 spot 快照类备源做
    节假日空帧保护（MS-28 P2-C5，registry 装配处注入 StockValuationDailyBackupSource）。

    日历访问沿 AllASpotBackupSource/_open_days_desc 的 trade_cal 方式；日历不可得
    （tushare 故障/空响应）视为开市照常快照——备源本为 tushare 故障兜底，不能因
    trade_cal 失败自废，退回注入前行为。
    source：可选源实例句柄（C5，2026-10-15 审查 P2-5）——降级放行除 logger 外 append 到
    source.last_warnings，经 executor 合入 run.message 落 DB（飞书链可达）；executor 的
    reset_observability 在 fetch 前重置，追加发生在干净状态上。
    """

    def is_open_day(day_ymd):
        try:
            cal = pro_factory().trade_cal(exchange="SSE", start_date=day_ymd, end_date=day_ymd)
        except Exception as e:
            # C5（2026-10-12 审查）：日历查询失败仍视为开市（备源不能自废），但必须留痕——
            # 此时恰是 tushare 故障场景，静默放行会在节假日写入当日快照行
            logger.warning("trade_cal 查询失败（%s），%s 视为开市继续快照", e, day_ymd)
            if source is not None:
                # 挂源实例 last_warnings（照全仓统一挂法）→ executor run.message 通道
                source.last_warnings = [
                    *(source.last_warnings or []),
                    f"trade_cal 查询失败（{e}），{day_ymd} 视为开市继续快照（降级放行）",
                ]
            return True
        if cal is None or cal.empty:
            return True
        return bool((cal["is_open"] == 1).any())

    return is_open_day


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
