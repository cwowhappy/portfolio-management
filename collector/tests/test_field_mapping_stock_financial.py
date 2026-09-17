import pandas as pd

from collector.scheduler.jobs import _field_columns


def test_field_mapping_stock_financial_passes_revenue():
    conv = _field_columns()["field_mapping_stock_financial"]
    records = conv.convert(
        pd.DataFrame([{"report_date": "20260630", "stock_code": "600519", "revenue": 8.0e9}])
    )
    assert records[0]["revenue"] == 8.0e9
