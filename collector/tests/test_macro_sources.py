r"""MS-22 P3 Task 2 宏观指标参数化源（MacroPageSource ×6：cpi/ppi/pmi/lpr/socfin/m2）。

样本形态取探测报告（09-调研报告/2026-10-02-MS22-宏观取数页适配探测.md）实测裁剪：
- §1 统计局 zxfb：列表同条目三副本按 href 去重、静态翻页 index_{N}.html；CPI/PPI 详情
  双副本表格取第一个、列序固定 环比|同比|累计（表头第三列文字随期别变）；PMI 表 0 末行
  （13 个月升序最新在末）+ 正文正则兜底。
- §2 LPR：URL slug 为建页时间戳 ≠ 发布日（实测 4 月公告 slug=20260417…、标题 4 月 20 日），
  期别必须从标题取（DAY 型）；详情一句式正文 1年期LPR为([\d.]+)%。
- §3 社融/M2 xlsx：两跳（列表定位标题行 → 首个 .xlsx 附件）；Flow 表 B 列非空末行
  （未来月预置空行坑）、M2 行×月列（D..O）末个非空列。
契约：输出列 MACRO_COLUMNS；幂等键 (indicator, period)；yoy 恒 None（裁定）；value Decimal。
增量 = 列表期别首见已存即停；显式 start/end（backfill）跳过截断并按期别串序过滤；
漂移即告警——页面非空零命中/详情解析空帧 → SourceError（已存全命中的空产出不算）。
"""

import io
import urllib.error
from decimal import Decimal
from unittest.mock import MagicMock

import pytest

import collector.sources.macro as macro
from collector.sources.base import SourceError
from collector.sources.constants import MACRO_PAGE_INTERVAL
from collector.sources.macro import MACRO_COLUMNS

# ---------------------------------------------------------------- 测试基座


def _resp(payload, status=200):
    """urlopen 返回的 HTTPResponse 形状 mock（上下文管理器 + read + status）；HTML/bytes 原样。"""
    body = payload if isinstance(payload, bytes) else payload.encode("utf-8")
    r = MagicMock()
    r.status = status
    r.read.return_value = body
    r.__enter__.return_value = r
    r.__exit__.return_value = False
    return r


class _ExistingCursor:
    def __init__(self, rows):
        self._rows = rows
        self.sql = None
        self.params = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, sql, *args, **kwargs):
        self.sql = sql
        self.params = args[0] if args else None

    def fetchall(self):
        return self._rows


class _ExistingConn:
    def __init__(self, rows):
        self._cursor = _ExistingCursor(rows)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def cursor(self):
        return self._cursor


def _existing_factory(periods):
    conn = _ExistingConn([(p,) for p in periods])
    return lambda: conn


def _no_sleep(_seconds):
    pass


def _src(source_id, **overrides):
    kwargs = {**macro.MACRO_SOURCE_SPECS[source_id], "sleep_fn": _no_sleep}
    kwargs.update(overrides)
    return macro.MacroPageSource(source_id, **kwargs)


# ---------------------------------------------------------------- 样本（探测报告实测裁剪）


def _zxfb_list(*entries):
    """zxfb 列表页骨架：条目锚点（title 属性）+ 同 href 的移动端副本（三副本坑取二足以验证去重）。"""
    items = "".join(
        f'<li><span class="sys_url sys_zxgk">2026-09-30</span><a href="{href}" title="{title}">{title}</a></li>'
        for href, title in entries
    )
    copies = "".join(f'<div class="mob-list"><a href="{href}">{title}</a></div>' for href, title in entries[:1])
    return f"<html><body><ul class='list'>{items}</ul>{copies}</body></html>"


_CPI_ENTRY = ("./202609/t20260909_1965263.html", "2026年8月份居民消费价格变动情况")
_PPI_ENTRY = ("./202609/t20260909_1965262.html", "2026年8月份工业生产者出厂价格变动情况")
_PMI_ENTRY = ("./202609/t20260930_1965449.html", "2026年9月中国采购经理指数为50.1%")
_OTHER_ENTRY = ("./202609/t20260917_1965400.html", "2026年8月份规模以上工业增加值变动情况")

_CPI_DETAIL = """
<html><body>
<table>
<tr><td>指标</td><td>环比涨跌幅（%）</td><td>同比涨跌幅（%）</td><td>1—8月同比涨跌幅（%）</td></tr>
<tr><td>居民消费价格</td><td>0.4</td><td>0.8</td><td>0.9</td></tr>
<tr><td>　其中：城市</td><td>0.4</td><td>0.8</td><td>0.9</td></tr>
<tr><td>　　　　农村</td><td>0.4</td><td>0.7</td><td>0.9</td></tr>
</table>
<table><!-- 打印副本，内容相同：取第一个即可（§1.2） -->
<tr><td>指标</td><td>环比涨跌幅（%）</td><td>同比涨跌幅（%）</td><td>1—8月同比涨跌幅（%）</td></tr>
<tr><td>居民消费价格</td><td>0.4</td><td>0.8</td><td>0.9</td></tr>
</table>
</body></html>
"""

_PPI_DETAIL = """
<html><body><table>
<tr><td>指标</td><td>环比涨跌幅（%）</td><td>同比涨跌幅（%）</td><td>1—8月同比涨跌幅（%）</td></tr>
<tr><td>一、工业生产者出厂价格</td><td>0.4</td><td>3.8</td><td>2.0</td></tr>
<tr><td>　（一）生产资料</td><td>0.5</td><td>4.6</td><td>2.5</td></tr>
<tr><td>二、工业生产者购进价格</td><td>0.6</td><td>4.0</td><td>2.2</td></tr>
</table></body></html>
"""

_PMI_DETAIL = """
<html><body>
<p>9月份，制造业采购经理指数（PMI）为50.1%，比上月上升0.3个百分点。</p>
<table>
<tr><td colspan="6">表1 中国制造业PMI及构成指数（经季节调整）</td></tr>
<tr><td>单位：%</td></tr>
<tr><td>PMI</td><td>生产</td><td>新订单</td><td>原材料库存</td><td>从业人员</td><td>供应商配送时间</td></tr>
<tr><td>2025年9月</td><td>49.8</td><td>49.1</td><td>47.5</td><td>48.2</td><td>49.6</td></tr>
<tr><td>2026年8月</td><td>49.8</td><td>51.6</td><td>50.4</td><td>48.0</td><td>49.6</td></tr>
<tr><td>2026年9月</td><td>50.1</td><td>52.0</td><td>50.9</td><td>48.1</td><td>49.9</td></tr>
</table>
<table><!-- 非制造业商务活动指数表：PMI 取表 0，不进此表 -->
<tr><td>商务活动</td><td>新订单</td></tr>
<tr><td>2026年9月</td><td>50.0</td></tr>
</table>
</body></html>
"""

# LPR 列表：slug=建页时间戳 ≠ 发布日（§2.1 实测钉死：4 月公告 slug 20260417…、标题 4 月 20 日）
_LPR_LIST = """
<html><body><ul>
<li><a href="/zhengcehuobisi/125207/125213/125440/3876551/2026041714492978015/index.html"
 title="2026年4月20日全国银行间同业拆借中心受权公布贷款市场报价利率">2026年4月20日贷款市场报价利率</a></li>
</ul></body></html>
"""

_LPR_DETAIL = """
<html><body><div class="content">
<p>中国人民银行授权全国银行间同业拆借中心公布，2026年4月20日贷款市场报价利率（LPR）为：
1年期LPR为3.0%，5年期以上LPR为3.5%。以上LPR在下一次发布LPR之前有效。</p>
</div></body></html>
"""

# pbc 调查统计司数据页实测形态（2026-10-03 实机核验）：条目标题在纯文本 div（titp20）、
# htm/xlsx/pdf 为其后独立锚点（xlsx 锚可见文本是「xls」，须按 href 后缀判）。
_SOCFIN_LIST = """
<html><body>
<table class="a2015"><tr>
<td><div class="titp20"> 社会融资规模增量统计表 <br> Aggregate Financing to the Real Economy (Flow) </div></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091417323857622.htm">htm</a></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091418125322850.xlsx">xls</a></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091418182740877.pdf">pdf</a></td>
</tr></table>
<table class="a2015"><tr>
<td><div class="titp20"> 社会融资规模存量统计表 <br> Aggregate Financing to the Real Economy (Stock) </div></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091417323857623.htm">htm</a></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091418125322851.xlsx">xls</a></td>
</tr></table>
</body></html>
"""

_M2_LIST = """
<html><body>
<table class="a2015"><tr>
<td><div class="titp20"> 官方储备资产 <br> Official Reserve Assets </div></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091418100000001.xlsx">xls</a></td>
</tr></table>
<table class="a2015"><tr>
<td><div class="titp20"> 货币供应量 <br> Money Supply </div></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091417313352505.htm">htm</a></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026091418181718462.xlsx">xls</a></td>
</tr></table>
<table class="a2015"><tr>
<td><div class="titp20"> 公布日程预告 <br> Advance Release Calendar </div></td>
<td><a href="/diaochatongjisi/attachDir/2026/09/2026093016043439203.xls">xls</a></td>
</tr></table>
</body></html>
"""


def _flow_xlsx():
    """社融 Flow xlsx（§3.1 逐 cell 结构）：行 9 列头、行 12 起月度行、末 4 行未来月预置 B 列空。"""
    import openpyxl

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(["社会融资规模增量统计表"])
    ws.append(["Aggregate Financing to the Real Economy (Flow)"])
    ws.append(["单位：亿元人民币"])
    ws.append([])
    ws.append(["月份", "社会融资规模增量", "人民币贷款", "外币贷款", "委托贷款", "信托贷款"])
    for month, flow in [(1, 72185), (2, 22357), (3, 59592), (4, 5910), (5, 5962), (6, 42196), (7, 7107), (8, 16577)]:
        ws.append([f"2026.{month:02d}", flow, 49016, 101, -309, -53])
    for month in (9, 10, 11, 12):  # 全年 12 个月行预置，未发布月 B 列为空（实测坑）
        ws.append([f"2026.{month}", None, None, None, None, None])
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def _money_xlsx():
    """货币供应量 xlsx（§3.2 行方向转置）：行 6 月列头 D..O、行 8 = 货币和准货币（M2）余额。"""
    import openpyxl

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.append(["货币供应量 Money Supply"])
    ws.append(["单位：亿元人民币"])
    ws.append([])
    ws.append([None, None, None] + [f"2026.{m:02d}" for m in range(1, 13)])  # D..O 月列头
    ws.append(["项目"])
    m2_balances = [3130000.2, 3180000.5, 3240000.1, 3290000.7, 3330000.3, 3400000.9, 3450000.4, 3568083.6]
    ws.append(["货币和准货币（M2）", None, None] + m2_balances + [None] * 4)  # K 列（2026.08）= 3568083.6
    ws.append(["货币（M1）", None, None] + [1000000.0 + 1000 * m for m in range(1, 9)] + [None] * 4)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


# ---------------------------------------------------------------- stats_table：CPI / PPI


_EMPTY_PAGE = "<html><body></body></html>"  # 翻页越界/无条目页：零条目即止


def test_cpi_headline_fixed_column_order_and_dedupe(mocker):
    """CPI：双副本表格取第一个、headline 行按列序固定取（环比|同比|累计）、三副本按 href 去重。"""
    m = mocker.patch.object(
        macro,
        "urlopen",
        side_effect=[_resp(_zxfb_list(_CPI_ENTRY, _OTHER_ENTRY)), _resp(_CPI_DETAIL), _resp(_EMPTY_PAGE)],
    )
    src = _src("cpi")
    df = src.fetch({})
    assert list(df.columns) == MACRO_COLUMNS
    assert len(df) == 1
    row = df.iloc[0]
    assert row["indicator"] == "CPI" and row["period"] == "2026-08" and row["period_type"] == "MONTH"
    assert row["value"] == Decimal("0.8")  # 列 3 同比 → value（== Decimal("0.80") 亦真）
    assert row["yoy"] is None  # 裁定：释放表无独立同比列，恒 None
    assert row["source_url"] == "https://www.stats.gov.cn/sj/zxfb/202609/t20260909_1965263.html"
    assert "环比" in row["source_note"] and "0.4" in row["source_note"]  # 列 2 环比 → source_note
    assert m.call_count == 3  # 列表 1 次 + 详情 1 次（同 href 副本去重）+ 空第 2 页即止


def test_ppi_headline_strips_sequence_prefix(mocker):
    """PPI：headline 行「一、工业生产者出厂价格」去序号前缀后命中，列 3 同比 3.8 → value。"""
    mocker.patch.object(
        macro, "urlopen", side_effect=[_resp(_zxfb_list(_PPI_ENTRY)), _resp(_PPI_DETAIL), _resp(_EMPTY_PAGE)]
    )
    src = _src("ppi")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["indicator"] == "PPI" and row["period"] == "2026-08"
    assert row["value"] == Decimal("3.8") and row["yoy"] is None


def test_stats_detail_headline_missing_drift(mocker):
    """详情表格在但 headline 行缺失（上游改版）→ 单条跳过；整轮全毒空产出 → 收口聚合漂移 SourceError。"""
    mocker.patch.object(
        macro, "urlopen", side_effect=[_resp(_zxfb_list(_CPI_ENTRY)), _resp(_PPI_DETAIL), _resp(_EMPTY_PAGE)]
    )
    src = _src("cpi")
    with pytest.raises(SourceError, match="全部解析失败"):
        src.fetch({})


# ---------------------------------------------------------------- stats_table：PMI


def test_pmi_table0_last_row_is_latest(mocker):
    """PMI：表 0 末行（13 个月升序，最新月在末行非首行）× 列头定位 PMI 列 → 50.1 / 2026-09。"""
    mocker.patch.object(
        macro,
        "urlopen",
        side_effect=[_resp(_zxfb_list(_PMI_ENTRY, _OTHER_ENTRY)), _resp(_PMI_DETAIL), _resp(_EMPTY_PAGE)],
    )
    src = _src("pmi")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["indicator"] == "PMI" and row["period"] == "2026-09"  # 行标签 2026年9月 规整
    assert row["value"] == Decimal("50.1") and row["yoy"] is None


def test_pmi_body_regex_fallback_when_table_drifted(mocker):
    """PMI 表格漂移（无 PMI 列头）→ 正文「制造业采购经理指数（PMI）为50.1%」二级路径兜底。"""
    drifted = (
        "<html><body><table><tr><td>无关表格</td></tr></table>"
        "<p>9月份，制造业采购经理指数（PMI）为50.1%，比上月上升0.3个百分点。</p></body></html>"
    )
    mocker.patch.object(
        macro, "urlopen", side_effect=[_resp(_zxfb_list(_PMI_ENTRY)), _resp(drifted), _resp(_EMPTY_PAGE)]
    )
    src = _src("pmi")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["value"] == Decimal("50.1") and row["period"] == "2026-09"  # 期别回落标题提取
    assert "兜底" in row["source_note"]


def test_pmi_all_paths_failed_drift(mocker):
    """表格与正文双路径皆空 → 单条跳过；整轮全毒空产出 → 收口聚合漂移 SourceError。"""
    empty = "<html><body><table><tr><td>无关</td></tr></table><p>其他内容</p></body></html>"
    mocker.patch.object(macro, "urlopen", side_effect=[_resp(_zxfb_list(_PMI_ENTRY)), _resp(empty), _resp(_EMPTY_PAGE)])
    src = _src("pmi")
    with pytest.raises(SourceError, match="全部解析失败"):
        src.fetch({})


def test_stats_list_keyword_miss_drift(mocker):
    """列表页非空但翻页内零关键词命中（改版或栏目迁移）→ SourceError 而非空帧静默。"""
    mocker.patch.object(macro, "urlopen", return_value=_resp(_zxfb_list(_OTHER_ENTRY)))
    src = _src("cpi", max_pages=2)
    with pytest.raises(SourceError, match="未命中"):
        src.fetch({})
    assert src.last_warnings is None  # 漂移走异常，不走观测告警


# ---------------------------------------------------------------- pboc_text：LPR


def test_lpr_period_from_title_not_slug(mocker):
    """LPR 期别从标题取（DAY 型 2026-04-20），URL slug=20260417…（建页戳）不作判据（§2.1 坑）。"""
    m = mocker.patch.object(macro, "urlopen", side_effect=[_resp(_LPR_LIST), _resp(_LPR_DETAIL)])
    src = _src("lpr")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["indicator"] == "LPR" and row["period_type"] == "DAY"
    assert row["period"] == "2026-04-20"  # 非 slug 暗示的 2026-04-17
    assert row["value"] == Decimal("3")  # 1年期LPR为3.0%
    assert "5年期以上LPR为3.5%" in row["source_note"]
    assert m.call_args_list[1].args[0].full_url.endswith("3876551/2026041714492978015/index.html")


def test_lpr_detail_value_missing_drift(mocker):
    """详情正文无「1年期LPR为…%」句式（页面改版）→ 单条跳过；整轮全毒空产出 → 收口聚合漂移 SourceError。"""
    mocker.patch.object(
        macro,
        "urlopen",
        side_effect=[_resp(_LPR_LIST), _resp("<html><body><p>本页面正在维护。</p></body></html>")],
    )
    src = _src("lpr")
    with pytest.raises(SourceError, match="全部解析失败"):
        src.fetch({})


def test_lpr_poison_entry_skipped_and_normal_kept(mocker):
    """fix round 1 Important#1：同栏「带日期标题但正文无 LPR 句式」的毒条单条跳过 + 告警，
    正常条目照常产出——不抛异常不丢已采行（毒条滞留列表也只持续告警，不每月失败整任务）。"""
    poison_list = """
    <html><body><ul>
    <li><a href="/zhengcehuobisi/125207/125213/125440/3876551/2026092008384254324/index.html"
     title="2026年9月20日全国银行间同业拆借中心受权公布贷款市场报价利率">2026年9月20日LPR</a></li>
    <li><a href="/zhengcehuobisi/125207/125213/125440/3876551/2026082010000000000001/index.html"
     title="2026年8月20日全国银行间同业拆借中心受权公布贷款市场报价利率">2026年8月20日LPR</a></li>
    </ul></body></html>
    """
    poison_detail = "<html><body><p>中国人民银行就有关事项答记者问。（同栏公告，无 LPR 句式）</p></body></html>"
    m = mocker.patch.object(
        macro, "urlopen", side_effect=[_resp(poison_list), _resp(poison_detail), _resp(_LPR_DETAIL)]
    )
    src = _src("lpr")
    df = src.fetch({})
    assert list(df["period"]) == ["2026-08-20"]  # 毒条（2026-09-20）跳过，正常条目照常产出
    assert src.last_warnings and "解析失败已跳过" in src.last_warnings[0]
    assert m.call_count == 3  # 两条详情都请求（毒条下载后方知不可解析）


# ---------------------------------------------------------------- 翻页越界 / 首页不可达


def test_stats_page2_404_clean_stop_keeps_rows(mocker):
    """fix round 1 Important#2：页码 >1 的 404 = 静态存档越界 → 干净终止（不重试），已采行保留。"""
    m = mocker.patch.object(
        macro,
        "urlopen",
        side_effect=[_resp(_zxfb_list(_CPI_ENTRY)), _resp(_CPI_DETAIL), _resp(b"", status=404)],
    )
    src = _src("cpi")  # max_pages=4：第 2 页 404 即止
    df = src.fetch({})
    assert list(df["period"]) == ["2026-08"]  # 已采行不丢
    assert m.call_count == 3  # 404 单次即停（不进入请求重试）
    assert m.call_args_list[2].args[0].full_url.endswith("/sj/zxfb/index_1.html")


def test_first_page_404_retries_then_source_error(mocker):
    """首页 404 = 源不可达：仍按请求失败重试后 SourceError（不归为干净终止）。"""
    m = mocker.patch.object(macro, "urlopen", return_value=_resp(b"", status=404))
    src = _src("cpi", attempts=2, sleep_fn=_no_sleep)
    with pytest.raises(SourceError, match="404"):
        src.fetch({})
    assert m.call_count == 2


# ---------------------------------------------------------------- pboc_xlsx：socfin / M2


def test_socfin_flow_xlsx_last_nonempty_b_row(mocker):
    """社融：两跳定位 Flow 条目 xlsx（非 Stock 的）；B 列非空末行 → 2026-08 / 16577 亿元。"""
    m = mocker.patch.object(macro, "urlopen", side_effect=[_resp(_SOCFIN_LIST), _resp(_flow_xlsx())])
    src = _src("socfin")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["indicator"] == "AFMI" and row["period"] == "2026-08" and row["period_type"] == "MONTH"
    assert row["value"] == Decimal("16577")  # 未来月（09~12）B 列空行不误取
    assert row["yoy"] is None  # Flow 表无同比列，裁定不计算
    assert "亿元人民币" in row["source_note"]
    assert m.call_args_list[1].args[0].full_url.endswith("attachDir/2026/09/2026091418125322850.xlsx")


def test_m2_xlsx_last_nonempty_month_column(mocker):
    """M2 备源：定位「货币供应量」条目（.xls 日程附件不误取）；M2 行 × 月列（D..O）末个非空 → 2026-08。"""
    m = mocker.patch.object(macro, "urlopen", side_effect=[_resp(_M2_LIST), _resp(_money_xlsx())])
    src = _src("m2")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["indicator"] == "M2" and row["period"] == "2026-08"  # indicator=M2 而非冒充 AFMI（D19）
    assert row["value"] == Decimal("3568083.6")  # 行 × 末个非空月列（2026.09~12 整列空不误取）
    assert row["yoy"] is None  # 该 xlsx 仅余额无增速列，裁定不计算
    assert m.call_args_list[1].args[0].full_url.endswith("attachDir/2026/09/2026091418181718462.xlsx")


def test_xlsx_title_miss_drift(mocker):
    """列表页无标题关键词条目（附件区改版）→ SourceError。"""
    mocker.patch.object(macro, "urlopen", return_value=_resp(_zxfb_list(_OTHER_ENTRY)))
    src = _src("socfin")
    with pytest.raises(SourceError, match="未命中"):
        src.fetch({})


# ---------------------------------------------------------------- 增量 / backfill


def _cpi_entry_for(month, article_id):
    return (f"./202609/t2026090{article_id}_1965{article_id:03d}.html", f"2026年{month}月份居民消费价格变动情况")


def test_incremental_truncates_at_first_stored_period(mocker):
    """增量：列表新→旧，首见已存期别（2026-07）即停——其后更旧期别一并不抓。"""
    entries = _zxfb_list(_cpi_entry_for(8, 1), _cpi_entry_for(7, 2), _cpi_entry_for(6, 3))
    m = mocker.patch.object(macro, "urlopen", side_effect=[_resp(entries), _resp(_CPI_DETAIL)])
    src = _src("cpi", conn_factory=_existing_factory(["2026-07", "2026-06"]))
    df = src.fetch({})
    assert list(df["period"]) == ["2026-08"]
    assert m.call_count == 2  # 列表 + 仅 2026-08 详情


def test_incremental_all_stored_returns_empty_frame(mocker):
    """已存全命中（重复 cron 窗口多跑幂等）→ 0 行空帧带列契约，不算漂移不告警。"""
    mocker.patch.object(macro, "urlopen", side_effect=[_resp(_zxfb_list(_CPI_ENTRY)), _resp(_CPI_DETAIL)])
    src = _src("cpi", conn_factory=_existing_factory(["2026-08"]))
    df = src.fetch({})
    assert df.empty and list(df.columns) == MACRO_COLUMNS
    assert src.last_warnings is None


def test_explicit_range_skips_truncation_and_filters(mocker):
    """显式 start/end（backfill 意图）：跳过已存截断、翻满 max_pages，按期别串序过滤窗口外期次。"""
    entries = _zxfb_list(_cpi_entry_for(8, 1), _cpi_entry_for(7, 2), _cpi_entry_for(6, 3))
    m = mocker.patch.object(
        macro,
        "urlopen",
        side_effect=[_resp(entries), _resp(_CPI_DETAIL), _resp(_CPI_DETAIL), _resp(_CPI_DETAIL), _resp("")],
    )
    src = _src("cpi", conn_factory=_existing_factory(["2026-07"]), max_pages=2)
    df = src.fetch({"start": "2026-06", "end": "2026-07"})
    assert list(df["period"]) == ["2026-07", "2026-06"]  # 2026-08 窗口外跳过；2026-07 已存不截断
    assert m.call_count == 4  # 列表 + 07/06 详情 + 空的第 2 页（翻页继续）


def test_stats_pagination_reaches_second_page(mocker):
    """翻页：第 1 页零命中 → index_1.html（§1.1 静态分页）续翻，命中后正常取数。"""
    page2 = _zxfb_list(_PMI_ENTRY)
    m = mocker.patch.object(
        macro,
        "urlopen",
        side_effect=[_resp(_zxfb_list(_OTHER_ENTRY)), _resp(page2), _resp(_PMI_DETAIL)],
    )
    src = _src("pmi", max_pages=2)
    df = src.fetch({})
    assert list(df["period"]) == ["2026-09"]
    assert m.call_args_list[1].args[0].full_url.endswith("/sj/zxfb/index_1.html")


def test_existing_periods_query_scopes_indicator(mocker):
    """已存集合按本指标过滤（幂等键 indicator+period；LPR 日频与 CPI 月频互不干扰）。"""
    conn = _ExistingConn([("2026-08",)])
    src = _src("cpi", conn_factory=lambda: conn)
    src._existing_periods()
    assert conn._cursor.params == ("CPI", macro.MACRO_EXISTING_PERIODS_LIMIT)


# ---------------------------------------------------------------- 请求级异常


def test_request_failure_retries_then_source_error(mocker):
    """请求级异常重试 MACRO_RETRY_ATTEMPTS 次后 SourceError（selector 换源），不自吞。"""
    m = mocker.patch.object(macro, "urlopen", side_effect=urllib.error.URLError("boom"))
    sleeps = []
    src = _src("cpi", sleep_fn=sleeps.append)
    with pytest.raises(SourceError, match="连续 3 次"):
        src.fetch({})
    assert m.call_count == 3
    assert sleeps == [MACRO_PAGE_INTERVAL, MACRO_PAGE_INTERVAL]


def test_non_200_status_source_error(mocker):
    mocker.patch.object(macro, "urlopen", return_value=_resp("<html/>", status=502))
    src = _src("cpi", attempts=1, sleep_fn=_no_sleep)
    with pytest.raises(SourceError, match="502"):
        src.fetch({})


# ---------------------------------------------------------------- 装配规格


def test_source_specs_cover_six_indicators():
    """6 实例规格：五先行指标 + M2 备源；parser_kind 三形态齐备；URL 锚定探测报告。"""
    specs = macro.MACRO_SOURCE_SPECS
    assert set(specs) == {"cpi", "ppi", "pmi", "lpr", "socfin", "m2"}
    assert {s["indicator"] for s in specs.values()} == {"CPI", "PPI", "PMI", "LPR", "AFMI", "M2"}
    assert {s["parser_kind"] for s in specs.values()} == {"stats_table", "pboc_text", "pboc_xlsx"}
    assert specs["lpr"]["period_type"] == "DAY"  # 探测报告 §2.1 修正：DAY 型
    assert all(s["period_type"] == "MONTH" for k, s in specs.items() if k != "lpr")
    assert specs["cpi"]["url"] == "https://www.stats.gov.cn/sj/zxfb/"
    assert specs["socfin"]["url"].endswith("/2026ntjsj/shrzgm/index.html")
    assert specs["m2"]["url"].endswith("/2026ntjsj/hbtjgl/index.html")


def test_unknown_parser_kind_rejected():
    with pytest.raises(ValueError, match="parser_kind"):
        macro.MacroPageSource(
            "x",
            indicator="CPI",
            period_type="MONTH",
            parser_kind="nope",
            url="https://example.com",
            title_keyword="k",
            sleep_fn=_no_sleep,
        )
