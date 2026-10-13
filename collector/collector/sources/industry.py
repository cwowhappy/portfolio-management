"""行业域数据源：申万行业映射（tushare index_classify/index_member_all）、申万一级行业
指数收盘（akshare 申万官网）、全A×行业 JOIN 源（行业加权估值输入）、行业估值历史
重算（DB→DB，仅补 industry_valuation 最早快照日之前的缺失日期）。
"""

import datetime as dt
import logging
import time

import pandas as pd

from collector.sources.base import Source
from collector.sources.common import _daily_basic_spot, _date_param, normalize_date
from collector.sources.constants import INDUSTRY_INDEX_CODES

logger = logging.getLogger(__name__)


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
