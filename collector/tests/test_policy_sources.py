"""MS-22 P3 Task 2 四部委政策源（PolicySiteSource ×4：pboc/csrc/mof/stats）。

样本形态取探测报告（09-调研报告/2026-10-02-MS22-宏观取数页适配探测.md）§5 实测裁剪：
- pboc：服务端渲染列表（slug=发布时间戳作 external_id）+ 翻页 11040-{N}.html；详情 #zoom
  容器 + meta createDate 精确发布时刻。
- csrc：/searchList JSON 单跳（manuscriptId 幂等、url 协议相对须补 https:、publishedTime
  epoch ms、列表响应直接带全文）；翻页终止 = len(results)<_pageSize 或空数组，ceil(total/
  pageSize) 兜底——**rows 回显不作判据**（Task 1 fix round 钉死）。
- mof：政务信息›政策发布列表（条目为司局子域绝对链接 http 保留）+ TRS_Editor 正文容器。
- stats：xw/tjxw/tzgg 扁平列表（与 zxfb 同款 t{date}_{id} 链接）+ .txt-content 容器
  （开头内联 <style> 块须剔除）。
契约：输出列 POLICY_COLUMNS；黑名单两栏（COMMON 四站 + BY_SOURCE 仅该站，§5.5 常量为准）
标题级命中即丢弃 + last_warnings 计数；正文 8000 字截断加「…[截断]」尾注；增量 =
首见已存 (source, external_id) 即停。
"""

import datetime as dt
import json
import urllib.error
from unittest.mock import MagicMock

import pytest

import collector.sources.policy as policy
from collector.sources.base import SourceError
from collector.sources.constants import (
    POLICY_CONTENT_MAX_CHARS,
    POLICY_CSRC_PAGE_SIZE,
    POLICY_PAGE_INTERVAL,
)
from collector.sources.policy import POLICY_COLUMNS

_UTC8 = dt.timezone(dt.timedelta(hours=8))


# ---------------------------------------------------------------- 测试基座


def _resp(payload, status=200):
    """urlopen 返回的 HTTPResponse 形状 mock（上下文管理器 + read + status）；str→utf-8、dict→JSON。"""
    if isinstance(payload, (bytes, str)):
        body = payload if isinstance(payload, bytes) else payload.encode("utf-8")
    else:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
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


def _existing_factory(ids):
    conn = _ExistingConn([(i,) for i in ids])
    return lambda: conn


def _no_sleep(_seconds):
    pass


def _src(site, **overrides):
    kwargs = {**policy.POLICY_SOURCE_SPECS[site], "sleep_fn": _no_sleep}
    kwargs.update(overrides)
    return policy.PolicySiteSource(site, **kwargs)


_EMPTY_PAGE = "<html><body></body></html>"  # 静态翻页越界页：零条目即止（增量/冷启动翻页兜底）


# ---------------------------------------------------------------- 样本（探测报告 §5 实测裁剪）


_PBOC_LIST = """
<html><body><ul class="newslist">
<li><a href="/goutongjiaoliu/113456/113469/2026092917153512345678/index.html"
 title="中国人民银行行长潘功胜会见欧盟驻华大使范恺珀">中国人民银行行长潘功胜会见欧盟驻华大使范恺珀</a>
 <span>2026-09-29</span></li>
<li><a href="/goutongjiaoliu/113456/113469/2026092810000000012345/index.html"
 title="中国人民银行关于规范商业汇票承兑业务有关事项的公告">中国人民银行关于规范商业汇票承兑业务有关事项的公告</a>
 <span>2026-09-28</span></li>
</ul>
<div class="page"><a href="11040-2.html">下一页</a></div>
</body></html>
"""

_PBOC_DETAIL = """
<html><head>
<meta name="createDate" content="2026-09-28 09:30:00">
<meta name="keywords" content="商业汇票">
</head><body>
<div id="zoom" class="zoom1"><p>为规范商业汇票承兑业务，中国人民银行、银保监会联合发布公告。</p>
<p>一、银行承兑汇票和财务公司承兑汇票的承兑余额不得超过该机构总资产的10%，保证金余额不得超过存款规模的5%。</p>
<p>二、金融机构应完善商业汇票承兑业务管理机制，强化风险防控，切实服务实体经济，严禁为无真实交易背景的票据办理承兑。</p>
<p>三、人民银行、银保监会将加强监督检查，对违反公告规定的，依法依规严肃处理。（2600 字正文以重复段代）</p>
<p>四、本公告自发布之日起施行，此前规定与本公告不一致的，以本公告为准。</p></div>
</body></html>
"""

# tzgg 实测形态（2026-10-03 实机核验）：每条目三锚点副本（pc/mobile/hidden，§1.1 同款模板），
# <span> 日期跟在末个副本之后（日期回填由 _trs_entries 去重合并承担）。
_STATS_LIST = (
    '<html><body><ul class="tzgg_list">'
    '<li><a class="fl pc_1600" href="./202609/t20260930_1965449.html" title="统计改革发展“十五五”规划印发">规划印发</a>'
    '<a class="fl mhide pc1200" href="./202609/t20260930_1965449.html">规划印发（移动副本）</a>'
    "<span>2026-09-30</span></li>"
    '<li><a class="fl pc_1600" href="./202609/t20260925_1965400.html" title="统计专业技术资格考试成绩查询">成绩查询</a>'
    '<a class="fl mhide pc1200" href="./202609/t20260925_1965400.html">成绩查询（移动副本）</a>'
    "<span>2026-09-25</span></li>"
    '<li><a class="fl pc_1600" href="./202609/t20260920_1965380.html" title="任免一批司厅级干部">任免</a>'
    "<span>2026-09-20</span></li>"
    "</ul></body></html>"
)

# 实测正文 1000~3000 字（§5.6）；样本拉长过 POLICY_MIN_CONTENT_CHARS 阈值，期望文本按段落列表推导
_STATS_PARAS = [
    "国家统计局印发《统计改革发展“十五五”规划》。",
    "规划明确了未来五年统计现代化改革的总体要求与主要任务。",
] * 5

_STATS_DETAIL = (
    "<html><body>\n"
    '<div class="txt-content"><style>.trs_import_c4tly5{font-size:16px;color:#333}</style>\n'
    + "".join(f"<p>{p}</p>\n" for p in _STATS_PARAS)
    + "</div>\n</body></html>"
)

_MOF_LIST = """
<html><body><ul class="zc_list">
<li><a href="http://jjs.mof.gov.cn/zhengcefabu/202609/t20260915_1234567.htm"
 title="财政部关于延长部分税收优惠政策执行期限的公告">财政部关于延长部分税收优惠政策执行期限的公告</a>
 <span>2026-09-15</span></li>
<li><a href="http://gks.mof.gov.cn/zhengcefabu/202608/t20260801_7654321.htm" title="无日期条目">无日期条目</a></li>
</ul></body></html>
"""

_MOF_DETAIL = """
<html><body>
<style>.TRS_Editor{font-size:16px}</style>
<div class="TRS_Editor"><p>为支持小微企业发展，现将有关税收政策公告如下。</p>
<p>一、对小型微利企业年应纳税所得额不超过300万元的部分，减按25%计入应纳税所得额，按20%的税率缴纳企业所得税。</p>
<p>二、本公告执行期限延长至2027年12月31日。对已按规定享受优惠政策但尚未到期的小型微利企业，可继续享受至优惠期限结束。</p>
<p>三、纳税人享受上述优惠事项采取“自行判别、申报享受、相关资料留存备查”的办理方式，相关资料由纳税人留存备查。（正文以重复段代）</p>
<p>四、本公告自发布之日起施行。此前发生的相关事项，已按原有规定处理的不再调整。</p></div>
</body></html>
"""


def _csrc_item(manuscript_id, title, published, content="<p>正文内容。</p>", url=None):
    return {
        "title": title,
        "url": url or f"//www.csrc.gov.cn/csrc/c100028/c{manuscript_id}/content.shtml",
        "publishedTime": str(int(published.timestamp() * 1000)),
        "publishedTimeStr": published.strftime("%Y-%m-%d %H:%M:%S"),
        "manuscriptId": str(manuscript_id),
        "content": content,
        "channelName": "证监会要闻",
    }


def _csrc_page(results, total=3197, page=1):
    return {"data": {"results": results, "total": total, "page": page, "rows": POLICY_CSRC_PAGE_SIZE}}


# ---------------------------------------------------------------- pboc：两跳 + 黑名单


def test_pboc_blacklist_drop_and_two_hop_detail(mocker):
    """pboc：通用栏「会见」命中即丢弃并计数；保留条目两跳抓 #zoom 正文、meta createDate 精确时刻。"""
    m = mocker.patch.object(policy, "urlopen", side_effect=[_resp(_PBOC_LIST), _resp(_PBOC_DETAIL), _resp(_EMPTY_PAGE)])
    src = _src("pboc")
    df = src.fetch({})
    assert list(df.columns) == POLICY_COLUMNS
    assert len(df) == 1  # 会见条目被黑名单丢弃
    assert src.last_warnings and "黑名单" in src.last_warnings[0] and "会见" in src.last_warnings[0]
    row = df.iloc[0]
    assert row["source"] == "pboc"
    assert row["external_id"] == "2026092810000000012345"  # URL slug（发布时间戳）
    assert row["title"] == "中国人民银行关于规范商业汇票承兑业务有关事项的公告"
    assert row["url"] == "https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/2026092810000000012345/index.html"
    assert row["published_at"] == dt.datetime(2026, 9, 28, 9, 30, 0, tzinfo=_UTC8)  # meta createDate 胜列表日
    assert row["content_text"].startswith("为规范商业汇票承兑业务")
    assert "一、银行承兑汇票" in row["content_text"] and "\n" in row["content_text"]  # 段落保留
    assert m.call_count == 3  # 列表 + 详情 + 空翻页兜底


def test_pboc_list_date_fallback_when_meta_missing(mocker):
    """详情无 meta createDate → 回落列表 <span> 日期（当日 00:00，北京时间）。"""
    detail = '<html><body><div id="zoom"><p>' + "政策正文。" * 80 + "</p></div></body></html>"
    mocker.patch.object(policy, "urlopen", side_effect=[_resp(_PBOC_LIST), _resp(detail), _resp(_EMPTY_PAGE)])
    src = _src("pboc")
    df = src.fetch({})
    assert df.iloc[0]["published_at"] == dt.datetime(2026, 9, 28, 0, 0, 0, tzinfo=_UTC8)


def test_pboc_blacklist_second_page_words(mocker):
    """翻页续页后通用栏词同样生效（出席/调研——第 2 页采样同族词，§5.1）；保留条目不受影响。"""
    page2 = (
        "<html><body><ul>"
        '<li><a href="/goutongjiaoliu/113456/113469/2026092815000000099999/index.html"'
        ' title="副行长出席国际清算银行行长例会">副行长出席国际清算银行行长例会</a><span>2026-09-28</span></li>'
        '<li><a href="/goutongjiaoliu/113456/113469/2026092715000000088888/index.html"'
        ' title="人民银行开展县域金融服务调研">人民银行开展县域金融服务调研</a><span>2026-09-27</span></li>'
        "</ul></body></html>"
    )
    m = mocker.patch.object(policy, "urlopen", side_effect=[_resp(_PBOC_LIST), _resp(_PBOC_DETAIL), _resp(page2)])
    src = _src("pboc", max_pages=2)
    df = src.fetch({})
    assert list(df["title"]) == ["中国人民银行关于规范商业汇票承兑业务有关事项的公告"]
    assert m.call_count == 3
    assert src.last_warnings and "标题黑名单丢弃 3 条" in src.last_warnings[0]


# ---------------------------------------------------------------- 黑名单作用域（两栏）


def test_blacklist_by_source_scope(mocker):
    """stats 专属词（成绩查询）在 stats 丢弃、在 csrc 不丢（§5.5：信息披露/考试类仅 stats 作用域）。"""
    mocker.patch.object(policy, "urlopen", side_effect=[_resp(_STATS_LIST), _resp(_STATS_DETAIL), _resp(_EMPTY_PAGE)])
    stats_src = _src("stats")
    stats_df = stats_src.fetch({})
    titles = set(stats_df["title"])
    assert titles == {"统计改革发展“十五五”规划印发"}  # 成绩查询（stats 专属）+ 任免（通用）皆丢
    assert stats_src.last_warnings and "黑名单" in stats_src.last_warnings[0]

    # csrc 侧：同一「成绩查询」标题不被丢弃（专属栏仅 stats 生效）；「信息披露管理办法」真政策不拦
    kept = [
        _csrc_item(7661514, "统计专业技术资格考试成绩查询提示", dt.datetime(2026, 9, 24, 10, 0, tzinfo=_UTC8)),
        _csrc_item(7661515, "证监会修改《上市公司信息披露管理办法》", dt.datetime(2026, 9, 23, 10, 0, tzinfo=_UTC8)),
    ]
    mocker.patch.object(policy, "urlopen", return_value=_resp(_csrc_page(kept, total=2)))
    csrc_df = _src("csrc").fetch({})
    assert len(csrc_df) == 2  # 两栏作用域：专属词不跨站、csrc 无专属词


# ---------------------------------------------------------------- csrc：JSON 单跳 + 翻页三态


def test_csrc_json_single_hop_field_mapping(mocker):
    """csrc 单跳：manuscriptId 幂等、协议相对 url 补 https:、epoch ms 北京时间、全文截断加尾注。"""
    long_content = "<p>超长公告正文。</p>" + "字" * (POLICY_CONTENT_MAX_CHARS + 100)
    items = [
        _csrc_item(
            7661513,
            "中国证监会发布《期货公司监督管理办法》及配套实施公告",
            dt.datetime(2026, 9, 28, 15, 15, 26, tzinfo=_UTC8),
            content=long_content,
        )
    ]
    m = mocker.patch.object(policy, "urlopen", return_value=_resp(_csrc_page(items, total=1)))
    src = _src("csrc")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["source"] == "csrc" and row["external_id"] == "7661513"
    assert row["url"] == "https://www.csrc.gov.cn/csrc/c100028/c7661513/content.shtml"
    assert row["published_at"] == dt.datetime(2026, 9, 28, 15, 15, 26, tzinfo=_UTC8)
    assert len(row["content_text"]) == POLICY_CONTENT_MAX_CHARS + len("…[截断]")
    assert row["content_text"].endswith("…[截断]")
    assert "_pageSize=20" in m.call_args_list[0].args[0].full_url and "page=1" in m.call_args_list[0].args[0].full_url


def test_csrc_pagination_stops_on_short_page(mocker):
    """末页短页（len(results) < pageSize）→ 终止翻页（首条命中即典型路径）。"""
    items = [_csrc_item(1000 + i, f"标题{i}", dt.datetime(2026, 9, 20, 10, 0, tzinfo=_UTC8)) for i in range(5)]
    m = mocker.patch.object(policy, "urlopen", return_value=_resp(_csrc_page(items, total=5)))
    df = _src("csrc").fetch({})
    assert len(df) == 5 and m.call_count == 1


def test_csrc_pagination_stops_on_empty_array(mocker):
    """越界页 HTTP 200 + results=[]（键不缺失）→ 终止（rows 回显 20 不作判据）。"""
    page1 = [
        _csrc_item(2000 + i, f"标题{i}", dt.datetime(2026, 9, 20, 10, 0, tzinfo=_UTC8))
        for i in range(POLICY_CSRC_PAGE_SIZE)
    ]
    m = mocker.patch.object(
        policy, "urlopen", side_effect=[_resp(_csrc_page(page1, total=3197)), _resp(_csrc_page([], total=3197, page=2))]
    )
    df = _src("csrc").fetch({})
    assert len(df) == POLICY_CSRC_PAGE_SIZE and m.call_count == 2
    assert "page=2" in m.call_args_list[1].args[0].full_url


def test_csrc_pagination_ceil_total_bound(mocker):
    """total/pageSize ceil 兜底：服务端恒回满页时页数不越界（total=40、pageSize=20 → 2 页）。"""
    page1 = [
        _csrc_item(3000 + i, f"标题{i}", dt.datetime(2026, 9, 20, 10, 0, tzinfo=_UTC8))
        for i in range(POLICY_CSRC_PAGE_SIZE)
    ]
    page2 = [
        _csrc_item(4000 + i, f"旧标题{i}", dt.datetime(2026, 9, 10, 10, 0, tzinfo=_UTC8))
        for i in range(POLICY_CSRC_PAGE_SIZE)
    ]
    m = mocker.patch.object(
        policy,
        "urlopen",
        side_effect=[_resp(_csrc_page(page1, total=40)), _resp(_csrc_page(page2, total=40, page=2))],
    )
    df = _src("csrc", max_pages=99).fetch({})
    assert len(df) == 40 and m.call_count == 2


def test_csrc_structural_drift_source_error(mocker):
    """data.results 缺失/非列表（上游改版）→ SourceError 漂移即告警。"""
    mocker.patch.object(policy, "urlopen", return_value=_resp({"data": {"total": 10, "rows": 20}}))
    with pytest.raises(SourceError, match="结构漂移"):
        _src("csrc").fetch({})


def test_csrc_fallback_to_published_time_str(mocker):
    """publishedTime 缺失/不可解析 → publishedTimeStr 字符串形态双保险（§5.2）。"""
    item = _csrc_item(8888, "标题", dt.datetime(2026, 9, 26, 8, 0, 0, tzinfo=_UTC8))
    item["publishedTime"] = "not-a-number"
    mocker.patch.object(policy, "urlopen", return_value=_resp(_csrc_page([item], total=1)))
    df = _src("csrc").fetch({})
    assert df.iloc[0]["published_at"] == dt.datetime(2026, 9, 26, 8, 0, 0, tzinfo=_UTC8)


# ---------------------------------------------------------------- mof / stats：trs 两跳


def test_mof_cross_subdomain_absolute_url_and_trs_editor(mocker):
    """mof：司局子域绝对链接 http 协议原样入库；external_id 取 t{date}_{id} 数字段；TRS_Editor 容器。"""
    m = mocker.patch.object(policy, "urlopen", side_effect=[_resp(_MOF_LIST), _resp(_MOF_DETAIL), _resp(_EMPTY_PAGE)])
    src = _src("mof")
    df = src.fetch({})
    assert len(df) == 1  # 无日期条目（published_at NOT NULL）丢弃 + 告警
    assert src.last_warnings and "日期" in src.last_warnings[0]
    row = df.iloc[0]
    assert row["source"] == "mof"
    assert row["external_id"] == "20260915_1234567"
    assert row["url"] == "http://jjs.mof.gov.cn/zhengcefabu/202609/t20260915_1234567.htm"
    assert row["published_at"] == dt.datetime(2026, 9, 15, 0, 0, 0, tzinfo=_UTC8)
    assert row["content_text"].startswith("为支持小微企业发展")
    assert "font-size" not in row["content_text"]  # 内嵌 CSS 噪声剔除
    assert m.call_count == 3  # 列表 + 详情 + 空翻页兜底


def test_stats_txt_content_strips_inline_style(mocker):
    """stats：.txt-content 容器开头内联 <style> 块剔除（§5.4 实测坑）。"""
    mocker.patch.object(policy, "urlopen", side_effect=[_resp(_STATS_LIST), _resp(_STATS_DETAIL), _resp(_EMPTY_PAGE)])
    src = _src("stats")
    df = src.fetch({})
    row = df.iloc[0]
    assert row["external_id"] == "20260930_1965449"
    assert row["published_at"] == dt.datetime(2026, 9, 30, 0, 0, 0, tzinfo=_UTC8)  # 末副本 span 日期回填首副本
    assert row["url"] == "https://www.stats.gov.cn/xw/tjxw/tzgg/202609/t20260930_1965449.html"
    assert row["content_text"] == "\n".join(_STATS_PARAS)  # 段落保留、style 剔除
    assert "trs_import" not in row["content_text"] and "{" not in row["content_text"]


def test_trs_detail_container_missing_falls_back_to_body(mocker):
    """容器缺失（页面模板漂移）→ 全 body 文本兜底（单条软降级，不失败整轮）。"""
    long_body = "<html><body><p>" + "政策通知正文。" * 60 + "</p></body></html>"
    mocker.patch.object(policy, "urlopen", side_effect=[_resp(_STATS_LIST), _resp(long_body), _resp(_EMPTY_PAGE)])
    src = _src("stats")
    df = src.fetch({})
    assert len(df) == 1
    assert df.iloc[0]["content_text"].startswith("政策通知正文")


def test_trs_detail_too_short_dropped_with_warning(mocker):
    """容器缺失且兜底文本过短（< POLICY_MIN_CONTENT_CHARS，疑似抓到壳页）→ 丢弃 + last_warnings。"""
    short_body = "<html><body><p>短。</p></body></html>"
    mocker.patch.object(policy, "urlopen", side_effect=[_resp(_STATS_LIST), _resp(short_body), _resp(_EMPTY_PAGE)])
    src = _src("stats")
    df = src.fetch({})
    assert df.empty
    assert src.last_warnings and "正文" in src.last_warnings[0]


def test_trs_incremental_truncates_at_existing_id(mocker):
    """增量：首见已存 external_id 即停（其后的更旧条目一并不抓详情）。"""
    m = mocker.patch.object(
        policy,
        "urlopen",
        side_effect=[_resp(_STATS_LIST), _resp(_STATS_DETAIL)],
    )
    src = _src("stats", conn_factory=_existing_factory(["20260930_1965449"]))
    df = src.fetch({})
    assert df.empty and list(df.columns) == POLICY_COLUMNS
    assert m.call_count == 1  # 首条即已存：列表 1 次、零详情请求


def test_trs_backfill_intent_pages_further(mocker):
    """显式 start/end（backfill 意图）：跳过已存截断继续翻页（trs 静态 index_{N}.htm）。"""
    page2 = _STATS_LIST.replace("./202609/", "./202608/").replace("202609", "202608")
    m = mocker.patch.object(
        policy,
        "urlopen",
        side_effect=[_resp(_STATS_LIST), _resp(_STATS_DETAIL), _resp(page2), _resp(_STATS_DETAIL)],
    )
    src = _src("stats", conn_factory=_existing_factory(["20260930_1965449"]), max_pages=2)
    df = src.fetch({"start": "2026-08-01", "end": "2026-09-30"})
    assert "20260930_1965449" in set(df["external_id"])  # 已存不截断
    assert m.call_args_list[2].args[0].full_url.endswith("/tzgg/index_1.html")


def test_trs_list_drift_source_error(mocker):
    """列表页非空但零条目命中链接模式（改版）→ SourceError 而非空帧静默。"""
    nav_only = '<html><body><a href="/xw/tjxw/" title="统计新闻">统计新闻</a><a href="/">首页</a></body></html>'
    mocker.patch.object(policy, "urlopen", return_value=_resp(nav_only))
    with pytest.raises(SourceError, match="结构漂移"):
        _src("stats").fetch({})


# ---------------------------------------------------------------- 通用


def test_existing_ids_query_scopes_source(mocker):
    """已存集合按本源 source 过滤（业务键 (source, external_id)，四站 id 空间互不相交）。"""
    conn = _ExistingConn([("20260930_1965449",)])
    src = _src("stats", conn_factory=lambda: conn)
    src._existing_ids()
    assert conn._cursor.params == ("stats", policy.POLICY_EXISTING_IDS_LIMIT)


def test_request_failure_retries_then_source_error(mocker):
    """请求级异常重试 POLICY_RETRY_ATTEMPTS 次后 SourceError（selector 降级），不自吞。"""
    m = mocker.patch.object(policy, "urlopen", side_effect=urllib.error.URLError("boom"))
    sleeps = []
    src = _src("stats", sleep_fn=sleeps.append)
    with pytest.raises(SourceError, match="连续 3 次"):
        src.fetch({})
    assert m.call_count == 3
    assert sleeps == [POLICY_PAGE_INTERVAL, POLICY_PAGE_INTERVAL]


def test_source_specs_cover_four_sites():
    specs = policy.POLICY_SOURCE_SPECS
    assert set(specs) == {"pboc", "csrc", "mof", "stats"}
    assert specs["csrc"]["url"].startswith("https://www.csrc.gov.cn/searchList/")
    assert specs["mof"]["url"] == "https://www.mof.gov.cn/zhengwuxinxi/zhengcefabu/"
    assert specs["stats"]["url"].endswith("/xw/tjxw/tzgg/")
