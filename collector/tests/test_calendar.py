import datetime as dt
from unittest.mock import MagicMock

from collector.scheduler.calendar import TradingCalendar


def test_is_trading_day_queries_db_on_demand():
    conn = MagicMock()
    conn.__enter__.return_value = conn
    cur = conn.cursor.return_value.__enter__.return_value
    cur.fetchone.return_value = (1,)
    cal = TradingCalendar(conn_factory=lambda: conn)
    assert cal.is_trading_day(dt.date(2026, 8, 28)) is True
    cur.fetchone.return_value = None
    assert cal.is_trading_day(dt.date(2026, 8, 29)) is False


def test_in_memory_fallback_without_conn_factory():
    cal = TradingCalendar({dt.date(2026, 8, 28)})
    assert cal.is_trading_day(dt.date(2026, 8, 28)) is True
    assert cal.is_trading_day(dt.date(2026, 8, 29)) is False
