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
ANN_CNINFO_MAX_PAGES = 30  # 单流分页上限：须覆盖 3 日窗口最热栏目（年报季 ndbg 深市 ~900 条/3 日）
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
