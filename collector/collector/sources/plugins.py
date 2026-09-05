import datetime as dt
import re
from concurrent.futures import ThreadPoolExecutor

import akshare as ak
import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.ratelimit import RateLimiter

# StockFinancialSource 逐股拉取 fina_indicator 前的最小调用间隔（秒）。
# fina_indicator 上游限 200 次/分钟，取 0.35s（≈171 次/分钟）留安全余量，避免逐股并发触发限流。
FINANCIAL_MIN_INTERVAL = 0.35

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
    ("1Y", "中国国债收益率1年"),
    ("3Y", "中国国债收益率3年"),
    ("5Y", "中国国债收益率5年"),
    ("10Y", "中国国债收益率10年"),
    ("30Y", "中国国债收益率30年"),
]


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

    def fetch(self, params):
        df = ak.bond_zh_us_rate()
        start, end = self._range_bounds(params)
        # 有显式区间时以区间为准，不再以 DB max(trading_day) 做增量下界；
        # 无区间（日常增量调度）才回退到 watermark 行为。
        since = None if start is not None or end is not None else self._max_trading_day()
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
                if col in df.columns and pd.notna(r[col]):
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
    """全 A 个股估值日快照：tushare daily_basic 按交易日批量 + stock_basic 过滤 ST/退市/北交所。"""

    supports_range = False

    def __init__(self, source_id, pro_factory):
        self.source_id = source_id
        self.pro_factory = pro_factory

    def _valid_universe(self, pro):
        basic = pro.stock_basic(list_status="L", fields="ts_code,name")
        basic = basic[basic["ts_code"].str.endswith((".SH", ".SZ"))]  # 剔除北交所/老三板
        return basic[~basic["name"].str.contains("ST|退", na=False)]

    def fetch(self, params):
        pro = self.pro_factory()
        day = _date_param(params, "date")
        universe = self._valid_universe(pro)
        daily = pro.daily_basic(trade_date=day)
        df = daily.merge(universe, on="ts_code", how="inner")
        df = df.copy()
        df["stock_code"] = df["ts_code"].str.split(".").str[0]
        df["stock_name"] = df["name"]
        df["dividend_yield"] = df["dv_ttm"]
        df["total_mv"] = df["total_mv"] * 10000  # 万元 → 元
        df["circ_mv"] = df["circ_mv"] * 10000
        return df[
            ["stock_code", "stock_name", "pe_ttm", "pb", "dividend_yield", "total_mv", "circ_mv", "turnover_rate"]
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
    """全 A 个股财务指标季数据：tushare fina_indicator 按报告期批量，回填近 3 年（12 季）。"""

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
