import datetime as dt
import re

import akshare as ak
import pandas as pd

from collector.sources.base import Source

INDEX_CODES = {"000016": "上证50", "000300": "沪深300", "000905": "中证500", "399006": "创业板指", "000688": "科创50"}

_DATE_COMPACT = re.compile(r"\d{8}")


def _ts_code(code):
    return code + (".SH" if code.startswith("0") else ".SZ")


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
    """全A快照 + 申万行业映射的 JOIN 源。

    直接读取 weekly `shenwan_mapping` 任务已写入的 shenwan_industry_mapping 表，
    与 akshare 全A快照做 inner join，产出带 industry_code/industry_name 的全A明细，
    供行业加权估值（industry_weighted calc）消费。Converter/Calc 保持纯净。
    """

    supports_range = False

    def __init__(self, source_id, conn_factory):
        self.source_id = source_id
        self.conn_factory = conn_factory  # 返回 psycopg 连接的可调用对象

    def fetch(self, params):
        universe = ak.stock_zh_a_spot_em()  # 原始中文列
        with self.conn_factory() as conn, conn.cursor() as cur:
            cur.execute("SELECT stock_code, industry_code, industry_name FROM shenwan_industry_mapping")
            rows = cur.fetchall()
        mapping = pd.DataFrame(rows, columns=["stock_code", "industry_code", "industry_name"])
        return universe.merge(mapping, left_on="代码", right_on="stock_code", how="inner")


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

    def fetch(self, params):
        since = self._max_trading_day()
        df = ak.bond_zh_us_rate()
        rows = []
        for _, r in df.iterrows():
            day = r["日期"]
            if isinstance(day, pd.Timestamp):
                day = day.date()
            elif not isinstance(day, dt.date):
                day = dt.date.fromisoformat(str(day))
            if since is not None and day <= since:
                continue  # 增量：只拉入库最大交易日之后的行
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
