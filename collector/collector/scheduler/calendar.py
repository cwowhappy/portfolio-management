import datetime as dt


class TradingCalendar:
    """交易日历。

    传 conn_factory 时按需查库（长期运行的调度进程用，避免内存日历过期）；
    否则退回内存集合（CLI 一次性加载、单元测试）。
    """

    def __init__(self, dates: set[dt.date] | None = None, conn_factory=None):
        self.dates = dates or set()
        self.conn_factory = conn_factory

    def is_trading_day(self, d: dt.date) -> bool:
        if self.conn_factory is not None:
            with self.conn_factory() as conn, conn.cursor() as cur:
                cur.execute("SELECT 1 FROM trading_calendar WHERE trade_date=%s", (d,))
                return cur.fetchone() is not None
        return d in self.dates
