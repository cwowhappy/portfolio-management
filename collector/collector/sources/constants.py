"""运营级常量集中地（P1-6）：码表 / 上游接口 / 限速。

此前散落在 plugins.py 各处，改任何一处都要翻代码；现收拢于此，plugins 统一引用。
定位：**运营可调**的值（哪些指数/行业、期限表、上游端点、礼貌限速）；
与实现强耦合的业务规则（分类关键词、统计窗口、SQL、合并口径）仍留在 plugins 原地。
值锚定由 tests/test_constants.py 承担，行为回归由 test_plugins 等既有套件承担。
"""

import datetime as dt

# ---------------------------------------------------------------- 码表

# 指数估值（index_valuation 任务）：5 指数
INDEX_CODES = {"000016": "上证50", "000300": "沪深300", "000905": "中证500", "399006": "创业板指", "000688": "科创50"}

# 基准指数收盘（index_close 任务）：3 基准；tushare 后缀映射
INDEX_CLOSE_CODES = {"000300": "沪深300", "000905": "中证500", "930950": "中证偏股基金指数"}
INDEX_CLOSE_TS = {"000300": "000300.SH", "000905": "000905.SH", "930950": "930950.CSI"}

# 申万 2021 一级 31 行业；Task 0 四方对齐核验零差异（调研报告 §四）
INDUSTRY_INDEX_CODES = {
    "801010": "农林牧渔",
    "801030": "基础化工",
    "801040": "钢铁",
    "801050": "有色金属",
    "801080": "电子",
    "801110": "家用电器",
    "801120": "食品饮料",
    "801130": "纺织服饰",
    "801140": "轻工制造",
    "801150": "医药生物",
    "801160": "公用事业",
    "801170": "交通运输",
    "801180": "房地产",
    "801200": "商贸零售",
    "801210": "社会服务",
    "801230": "综合",
    "801710": "建筑材料",
    "801720": "建筑装饰",
    "801730": "电力设备",
    "801740": "国防军工",
    "801750": "计算机",
    "801760": "传媒",
    "801770": "通信",
    "801780": "银行",
    "801790": "非银金融",
    "801880": "汽车",
    "801890": "机械设备",
    "801950": "煤炭",
    "801960": "石油石化",
    "801970": "环保",
    "801980": "美容护理",
}

# 债券/黄金回测代理（MS-13）：中证全债指数、华安黄金ETF
BOND_INDEX_TS_CODE = "H11001.CSI"
BOND_INDEX_CODE = "H11001"
BOND_INDEX_NAME = "中证全债"
GOLD_ETF_TS_CODE = "518880.SH"
GOLD_ETF_CODE = "518880"
GOLD_ETF_NAME = "华安黄金ETF"

# 国债收益率曲线：期限表与回补窗口
TERMS = [
    ("1Y", "1年"),
    ("3Y", "3年"),
    ("5Y", "5年"),
    ("10Y", "10年"),
    ("30Y", "30年"),
]
# bond_zh_us_rate 已下架 1Y/3Y 列（2026-09 实测仅剩 2Y/5Y/10Y/30Y），改用中债信息网
# bond_china_yield（全期限、按区间查询）。其单次区间上限约 1 年，按 180 天切块留余量。
CURVE_CHUNK_DAYS = 180
CURVE_EARLIEST = dt.date(2006, 1, 1)  # 中债国债收益率曲线自 2006-03 起，空表全量回填起点

# ---------------------------------------------------------------- 上游接口（非官方端点，易变）

# 东财·天天基金移动端 Detail 端点（etf_basic enrich 逐只）
ETF_DETAIL_URL = (
    "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNDetailInformation"
    "?FCODE={code}&deviceid=Wap&plat=Wap&product=EFund&version=6.2.8"
)
ETF_DETAIL_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
    "Referer": "https://fund.eastmoney.com/",
}
ETF_DETAIL_TIMEOUT = 15  # 单只请求超时（秒）

# 新闻源（MS-20 P1 Task 6）：URL/参数/游标语义由探测报告实测钉住
# （09-调研报告/2026-09-29-MS20-数据源探测报告.md §1 东财 / §2 新浪），漂移时以报告复核。
NEWS_HTTP_TIMEOUT = 10  # 单页请求超时（秒）
NEWS_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"  # 东财无 UA 要求；新浪带浏览器 UA 更稳
# 东财 7×24 快讯主源：sortEnd 游标首页传空、其后传上页 data.sortEnd（恒等于末条 realSort，向更旧翻）
NEWS_EASTMONEY_URL = "https://np-listapi.eastmoney.com/comm/web/getFastNewsList"
NEWS_EASTMONEY_QUERY = "client=web&biz=web_724&fastColumn=102&sortEnd={cursor}&pageSize={page_size}&req_trace=1"
# 接口无 URL 字段，详情页按此模板拼接（实测 200；仅回溯元数据，失败不阻塞——§1.3/§1.5）
NEWS_EASTMONEY_DETAIL_URL = "https://finance.eastmoney.com/a/{code}.html"
# 新浪 zhibo 财经直播降级源：page 页码从 1 递增翻更旧（实测边界衔接零重叠——§2.4）
NEWS_SINA_URL = "https://zhibo.sina.com.cn/api/zhibo/feed"
NEWS_SINA_QUERY = "callback=&page={page}&page_size={page_size}&zhibo_id=152&tag_id=0&dire=f&dpc=1"
NEWS_SINA_REFERER = "https://finance.sina.com.cn/7x24/"  # 带 Referer 更稳（§2.1，未验证强制项）
NEWS_PAGE_SIZE = 20
# 单轮翻页上限：*/10 增量通常首页即命中已存 id 截断；上限兜底夜间 2 小时档与断档补拉（§1.4）
NEWS_MAX_PAGES = 5
NEWS_PAGE_INTERVAL = 0.3  # 翻页/重试的礼貌间隔（秒）
NEWS_RETRY_ATTEMPTS = 3  # 请求级失败（网络/非 200/JSON 解析）重试次数，连续失败即 SourceError 走降级

# 公告源（MS-21 P2 Task 1）：巨潮 hisAnnouncement 主源 + 东财公告流降级源。
# URL/参数/栏目映射由探测报告实测钉住（§3 巨潮 + §4 东财公告流——响应样本与字段映射为
# 2026-09-29 P2 补测成节），漂移时以报告复核。
ANN_HTTP_TIMEOUT = 10  # 单页请求超时（秒）
ANN_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
ANN_PAGE_INTERVAL = 0.3  # 相邻请求（跨栏目流/翻页/重试）礼貌间隔：主源每轮 30+ 请求
ANN_RETRY_ATTEMPTS = 3  # 请求级失败重试次数，连续失败即 SourceError 走 selector 降级
# 巨潮 hisAnnouncement：POST form-encoded；pageSize 服务端封顶 30（传 100 实测静默回落 30，P2 补测）
CNINFO_URL = "https://www.cninfo.com.cn/new/hisAnnouncement/query"
# orgId 权威查询口（§3.1：orgId 不可推导，错/缺静默 0 条）；keyBoardList[].plate = sse/szse/bj（P2 补测）
CNINFO_TOPSEARCH_URL = "https://www.cninfo.com.cn/new/information/topSearch/detailOfQuery"
CNINFO_STATIC_URL = "https://static.cninfo.com.cn/"  # PDF 拼接前缀（§3.3 实测：+ adjunctUrl）
CNINFO_PAGE_SIZE = 30
# 单流分页上限：须覆盖 3 日窗口最热栏目（年报季 ndbg 深市 ~900 条/3 日 ≈ 30 页）；
# 40 页 = 1200 条留冷启动余量——增量截断依赖已存集合，冷启动空库时 30 页会触顶静默丢尾（T1 审查 minor③）
ANN_CNINFO_MAX_PAGES = 40
# topSearch.plate → (column, plate) 请求参数（§3.1：column/plate 必须与市场一致，配错静默 0 条）
CNINFO_MARKETS = {"szse": ("szse", "sz"), "sse": ("sse", "sh"), "bj": ("bj", "bj")}
# 东财公告流：GET；page_size 上限 100（P2 补测）；ann_type=A 覆盖沪深北三市（920982 实测 1280 条）
EASTMONEY_ANN_URL = "https://np-anotice-stock.eastmoney.com/api/security/ann"
# 首附件 PDF 直链模板（P2 补测：HEAD 200 + application/pdf，免 content API 二次请求；仅取 _1 首附件）
EASTMONEY_ANN_PDF_URL = "https://pdf.dfcfw.com/pdf/H2_{art_code}_1.pdf"
ANN_EASTMONEY_PAGE_SIZE = 100
ANN_EASTMONEY_MAX_PAGES = 50  # 全市场流无日期参数，3 日窗口 ≈ 4500 条 ≈ 45 页（截断后常态 1~2 页）
ANN_LOOKBACK_DAYS = 3  # 默认窗口回看天数：覆盖周五晚→周一早的周末缺口（任务 MON-FRI 双跑）
ANN_EXISTING_IDS_LIMIT = 2000  # 增量截断的已存集合行数上限（覆盖不足只多翻页不丢数据，UPSERT 兜底）
ANN_STOCKS_ENV = "INTELLIGENCE_ANN_STOCKS"  # 关注集兜底 env 变量名（逗号分隔代码串）

# 「巨潮栏目 → AnnouncementType 十类」直判映射（探测报告 §3.4 矩阵 + 决策 #24）：
# key=AnnouncementType，value=巨潮 category 参数值集合（_szsh 后缀族三市场通用，sse/bj 实测生效）。
# 仅 6 类有专属栏目可直判；增持/减持/回购/关联交易 4 类无专属栏目（宽栏目 gqbd/rcjy 混含且量大），
# 控制器裁定不做全市场扫描——仅在关注集标的全量采集后由 LLM 标签判定（P2 Task 5）。
ANNOUNCEMENT_MAJOR_COLUMNS: dict[str, set[str]] = {
    "PERIODIC_REPORT": {"category_ndbg_szsh", "category_bndbg_szsh", "category_yjdbg_szsh", "category_sjdbg_szsh"},
    "EARNINGS_FORECAST": {"category_yjygjxz_szsh"},  # 栏目混含业绩快报（§3.4），标题细分留给抽取侧
    "EARNINGS_FLASH": {"category_yjygjxz_szsh"},
    "PLACEMENT": {"category_zf_szsh", "category_pg_szsh"},
    "EQUITY_INCENTIVE": {"category_gqjl_szsh"},
    "DELISTING_RISK": {"category_tbclts_szsh", "category_tszlq_szsh"},
}

# 宏观指标源（MS-22 P3 Task 2）：五先行指标 + M2 备源。URL/解析坑由探测报告实测钉住
# （09-调研报告/2026-10-02-MS22-宏观取数页适配探测.md §1~§3），漂移时以报告复核：
# CPI/PPI 详情双副本表格取第一个、列序固定 环比|同比|累计（第三列表头文字随期别变）；
# PMI 表 0 末行（13 个月升序最新在末）；LPR 期别从标题取（URL slug=建页时间戳≠发布日）；
# 社融/M2 xlsx 全年 12 个月预置空行（取末个非空 cell）。yoy 恒 None 为报告裁定。
MACRO_HTTP_TIMEOUT = 15  # 单请求超时（秒）：统计局偶发 4.7s 慢响应（§1.1），宽于新闻/公告源
MACRO_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
MACRO_PAGE_INTERVAL = 0.3  # 相邻请求（列表/详情/翻页/重试）礼貌间隔（秒）
MACRO_RETRY_ATTEMPTS = 3  # 请求级失败重试次数，连续失败即 SourceError 走 selector 降级
MACRO_STATS_MAX_PAGES = 4  # zxfb 列表翻页上限（每页 ~20 条 ≈ 3~4 周发布量；增量常态首页命中）
MACRO_EXISTING_PERIODS_LIMIT = 100  # 增量截断的已存期别集合上限（月频≈8 年 / LPR 日频≈3 个月）
MACRO_LIST_URL_STATS = "https://www.stats.gov.cn/sj/zxfb/"  # CPI/PPI/PMI 同源列表（§1.1）
# LPR 月度公告列表（§2.1：20 条直出，slug=建页时间戳 ≠ 发布日，期别从标题取）
MACRO_LIST_URL_LPR = "https://www.pbc.gov.cn/zhengcehuobisi/125207/125213/125440/3876551/index.html"
# 社融主源（§3.1：页面仅挂最新一期 Flow/Stock 更新块，xlsx 文件名=上传时间戳）
MACRO_LIST_URL_SOCFIN = "https://www.pbc.gov.cn/diaochatongjisi/116219/116319/2026ntjsj/shrzgm/index.html"
# M2 备源（§3.2：与社融同日同批上传；跨年 backfill 属 URL 级扩展，§3.3 备案）
MACRO_LIST_URL_M2 = "https://www.pbc.gov.cn/diaochatongjisi/116219/116319/2026ntjsj/hbtjgl/index.html"

# 四部委政策源（MS-22 P3 Task 2）：pboc/mof/stats 服务端渲染两跳 + csrc JSON 单跳（§5）。
POLICY_HTTP_TIMEOUT = 15
POLICY_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)"
POLICY_PAGE_INTERVAL = 0.3
POLICY_RETRY_ATTEMPTS = 3
POLICY_TRS_MAX_PAGES = 5  # 三站静态翻页上限（增量常态首页即命中已存截断；日更低频源）
POLICY_CSRC_MAX_PAGES = 10  # csrc JSON 翻页默认上限（冷启动 200 条≈数月；更深回补构造源时调大）
POLICY_CSRC_PAGE_SIZE = 20  # §5.2 实测服务端遵循 _pageSize（传 20 回 20）；终止判据用 len(results) 勿用 rows 回显
# csrc JSON 列表接口（channelid=证监会要闻；旧 HTML 列表页为 2021-12 冻结旧档勿用）
POLICY_CSRC_LIST_URL = "https://www.csrc.gov.cn/searchList/a1a078ee0bc54721ab6b148884c784a8"
# pboc 沟通交流›新闻（§5.1：混排领导活动即黑名单过滤对象；翻页 11040-{N}.html）
POLICY_LIST_URL_PBOC = "https://www.pbc.gov.cn/goutongjiaoliu/113456/113469/index.html"
# mof 政务信息›政策发布（§5.3；根路径为 JS 壳勿用，/zhengwufabu 旧路径 404）
POLICY_LIST_URL_MOF = "https://www.mof.gov.cn/zhengwuxinxi/zhengcefabu/"
POLICY_LIST_URL_STATS = "https://www.stats.gov.cn/xw/tjxw/tzgg/"  # 统计新闻›通知公告（§5.4 控制器裁定取 tzgg）
POLICY_EXISTING_IDS_LIMIT = 500  # 增量截断的已存 external_id 集合上限（覆盖不足只多翻页，UPSERT 兜底）
POLICY_CONTENT_MAX_CHARS = 8000  # 正文截断上限（§5.6：四站实测 650~5000 字，超长办法/细则截断）
POLICY_TRUNCATION_SUFFIX = "…[截断]"  # 截断尾注（LLM 感知用）；列表层信息（标题/日期/URL）永不截断
POLICY_MIN_CONTENT_CHARS = 200  # 正文低于此长度视为抓取失败（容器缺失兜底后的丢弃阈值）

# 政策标题黑名单两栏（§5.5 实测采样提炼，Task 1 fix round 钉死——**以本常量为准**，勿照报告
# 表格裸词回抄）：通用栏四站生效、专属栏仅对应 source 生效，标题级子串匹配，命中即丢弃 +
# last_warnings 计数；「出席」等词仅拦标题不拦正文（防误杀政策解读文）；漏网由 LLM 兜底
# （isPolicy=false → LOW confidence 落库，T5 契约）——黑名单是成本闸门不是正确性闸门。
POLICY_TITLE_BLACKLIST_COMMON = (
    "会见",
    "出席",
    "调研",
    "走访",
    "转发",  # 领导活动/转载
    "任党委书记",
    "任免",
    "人事",
    "招聘",
    "拟聘用",
    "公开招聘",  # 人事任命/招聘
    "接受纪律审查",
    "严重违纪",
    "被开除",  # 纪检
)
POLICY_TITLE_BLACKLIST_BY_SOURCE = {
    # tzgg 栏目行政事务噪声（⚠ 信息披露在 csrc 是真政策词，全局禁用）
    "stats": ("立项公示", "立项公告", "信息披露", "薪酬", "工资总额", "成绩查询", "考务", "网站与政务新媒体检查"),
    # pboc/csrc/mof 实测未见需专属词（领导活动已被通用栏覆盖）
}

# ---------------------------------------------------------------- 限速（对上游的礼貌间隔，秒）

FINANCIAL_MIN_INTERVAL = 0.35  # fina_indicator/income（tushare 200 次/分 → 0.35s，18-19 实测钉死）
STOCK_DAILY_MIN_INTERVAL = 0.35  # daily_basic 客户端限速（≈171 次/分钟）
ETF_ENRICH_INTERVAL = 0.3  # 逐只保守限速：~1685 只实测 ~16 分钟（周更可接受，报告 §2.2）
ETF_DAILY_INTERVAL = 0.3  # fund_daily 逐日回填调用间隔（探测 §三：0.35s 连发零限频，保守 0.3s）
INDEX_DAILY_INTERVAL = 0.3  # index_daily 逐指数调用间隔（同上）

# ---------------------------------------------------------------- 回溯窗口

RECENT_OPEN_LOOKBACK_DAYS = 15  # 无参日常增量回溯最近开市日的自然日窗口（覆盖最长假期）

# ---------------------------------------------------------------- backfill 分片例外

# 这些任务的 fetch 是「全历史拉取+客户端裁剪」语义（如 industry_index_close 经
# akshare index_hist_sw），月片分片会导致每片重复拉全量——回补时整区间一片执行。
BACKFILL_WHOLE_RANGE_TASKS = frozenset({"industry_index_close"})
