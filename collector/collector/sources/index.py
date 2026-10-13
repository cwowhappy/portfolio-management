"""指数域数据源：指数估值（index_dailybasic + 成分股息率估算）、基准/债券指数收盘
（index_close_history）、指数成分权重（index_constituent）、国债收益率曲线
（akshare bond_china_yield → treasury_yield_curve）。
"""

import datetime as dt
import logging

import akshare as ak
import pandas as pd

from collector.sources.base import Source, SourceError
from collector.sources.common import _date_param, _open_days_desc, normalize_date
from collector.sources.constants import (
    BOND_INDEX_CODE,
    BOND_INDEX_NAME,
    BOND_INDEX_TS_CODE,
    CURVE_CHUNK_DAYS,
    CURVE_EARLIEST,
    INDEX_CLOSE_CODES,
    INDEX_CLOSE_TS,
    INDEX_CODES,
    TERMS,
)

logger = logging.getLogger(__name__)


def _index_ts_code(code):
    # 指数代码后缀约定：000/399 等指数段 0 开头 → .SH（上证段），其余 → .SZ（深证段）。
    # 注意与个股 ts_code 规则（6/9→SH、0/3→SZ）不同，勿混用；全部调用点传的均为指数码表。
    return code + (".SH" if code.startswith("0") else ".SZ")


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
        # P0-4：源是共享单例，观测状态每次 fetch 重置，避免跨运行累积
        self.last_warnings = None
        fallback_attr = getattr(self.dividend_fetch, "fallbacks", None)
        if fallback_attr is not None:
            fallback_attr.clear()
        for code, name in self.index_codes.items():
            df = pro.index_dailybasic(ts_code=_index_ts_code(code), start_date=start, end_date=end)
            df = df.rename(columns={"trade_date": "trading_day"})
            df["index_code"], df["index_name"] = code, name
            df["dividend_yield"] = None
            if self.dividend_fetch is not None:
                value = self.dividend_fetch(code, start, end)
                if isinstance(value, dict):
                    value = value.get(code)
                df["dividend_yield"] = value
            frames.append(df[["trading_day", "index_code", "index_name", "pe", "pb", "dividend_yield"]])
        if fallback_attr:
            self.last_warnings = [f"dividend_yield 回退 NULL（估算不可得）：{', '.join(fallback_attr)}"]
            logger.warning("%s: %s", self.source_id, self.last_warnings[0])
        return pd.concat(frames, ignore_index=True)


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
            df = pro.index_daily(ts_code=INDEX_CLOSE_TS[code], start_date=start, end_date=end)
            df = df.rename(columns={"trade_date": "trading_day"})
            df["index_code"], df["index_name"] = code, name
            frames.append(df[["trading_day", "index_code", "index_name", "close"]])
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
        df = pro.index_daily(ts_code=BOND_INDEX_TS_CODE, start_date=start, end_date=end)
        df = df.rename(columns={"trade_date": "trading_day"})
        df["index_code"], df["index_name"] = BOND_INDEX_CODE, BOND_INDEX_NAME
        return df[["trading_day", "index_code", "index_name", "close"]]


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
            df = pro.index_weight(index_code=_index_ts_code(code))
            df = df.rename(columns={"index_code": "index_code", "con_code": "stock_code"})
            df["stock_code"] = df["stock_code"].str.split(".").str[0]
            df["index_code"] = code
            frames.append(df[["index_code", "stock_code", "weight"]])
        return pd.concat(frames, ignore_index=True)


_CURVE_NAME = "中债国债收益率曲线"


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
            chunk_end = min(chunk_start + dt.timedelta(days=CURVE_CHUNK_DAYS - 1), end)
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
        query_start = start or (since + dt.timedelta(days=1) if since else CURVE_EARLIEST)
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


def _index_dividend_yield(pro, index_code, end_ymd):
    """指数股息率近似：index_weight 最新成分权重 × daily_basic dv_ttm 的加权均值（口径 %）。

    index_weight 不带 trade_date（与 IndexConstituentSource 一致）取最新生效权重；
    个股 dv_ttm 在 end（含）最近有数据的开市日取一次。任一步无数据返回 None（调用方回退默认）。
    """
    weights = pro.index_weight(index_code=_index_ts_code(index_code))
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


def make_index_dividend_fetch(pro_factory, default=None):
    """构造 IndexValuationSource 的 dividend_fetch：(index_code, start, end) -> float | None。

    真实拉取 tushare 成分股权重×dv_ttm 估算指数股息率；积分不足/无数据时回退
    default。P0-4 起默认 None（写 NULL，backend ERP 序列 null 过滤自动跳过该日，
    不再以 0.0 污染）；显式传 default 仍支持。回退事件记录在 fetch.fallbacks
    （源侧合入 last_warnings → run.message）。
    """
    fallbacks: list[str] = []

    def fetch(index_code, start, end):
        try:
            value = _index_dividend_yield(pro_factory(), index_code, end or start)
        except Exception:
            value = None
        if value is None:
            fallbacks.append(index_code)
            return default
        return value

    fetch.fallbacks = fallbacks
    return fetch
