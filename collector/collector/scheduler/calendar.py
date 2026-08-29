import datetime as dt


class TradingCalendar:
    def __init__(self, dates: set[dt.date]):
        self.dates = dates

    def is_trading_day(self, d: dt.date) -> bool:
        return d in self.dates
