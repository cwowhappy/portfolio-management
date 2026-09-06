# akshare 与 tushare 接口详析

> **调研核实日期：2026-09-06**。实测环境：Python 3.13（`collector/.venv`）、akshare 1.16.72、tushare 1.4.21、本仓库 `TUSHARE_TOKEN`。
> 方法：官方文档 + **本地逐接口实测**（每个接口记录真实返回字段、行数、耗时、报错）。实测失败均保留报错摘要与根因分析，不伪造数据。
> 定位：`research/01` 是「全部外部来源」的全景速查，本文档是 akshare/tushare 两库的**接口手册**，每张卡片含「本系统映射」（已在用 / 可替代 / 可扩展）。
> 注意：tushare 实测权限结论基于本仓库 token（实测积分档 ≥2000）；akshare 免费接口实测结果受当日风控状态影响，复现前先看各卡片「稳定性」栏。

## 实测总览（2026-09-06）

| 部分 | 接口数 | 成功 | 失败 | 要点 |
|---|---|---|---|---|
| 一、行情数据 | 17 | 11 | 6 | tushare 7/7 全通（含 `moneyflow`）；akshare 东财 push2 快照类大面积断连（风控），历史类正常 |
| 二、基本面与估值 | 25 | 23 | 2 | tushare 12/12 全通；akshare 11/13，legulegu 系 2 个硬故障（日期解析 bug） |
| 三、宏观与债券 | 13 | 10 | 3 | 宏观大部可用；**发现本系统国债 1Y/3Y 实际未入库的 bug**（见下） |
| 四、新闻、公告与研报 | 12 | 6 | 6 | akshare 新闻封装损坏率高；东财 7×24/新浪快讯/东财研报可用；tushare news 需单独权限 |
| 五、其它市场 | 20 | 12 | 8 | 东财快照类全挂、历史类正常；新浪源稳定兜底；tushare 基金/期货/港股实测可用 |

合计实测 87 次调用：成功 62 / 失败 25（含预期内的权限失败）。

## 关键发现（优先关注）

1. **本系统 bug（已于 2026-09-06 修复）：`bond_zh_us_rate` 当前版本没有 1Y/3Y 列**（只有 2Y/5Y/10Y/30Y），而 collector 的 TERMS 映射含 1Y/3Y，因防御性 `if col in df.columns` 判断**静默只采到 5Y/10Y/30Y** —— 库里 1Y/3Y 从未入库。**修复**：`TreasuryCurveSource` 改用 `bond_china_yield`（中债信息网官方曲线，全期限、按区间查询、单次上限约 1 年按 180 天切块），并把期限列缺失改为显式 `SourceError`（不再静默跳过）。详见第三部分 `bond_china_yield` 卡片。
2. **akshare 东财快照类接口当日大面积风控断连**（`stock_zh_a_spot_em`、指数/板块快照、港股快照等，秒级 `RemoteDisconnected`）——印证 collector 全 A 估值备源（tushare `daily_basic`）的必要性；东财**历史明细类**接口（K线、财务、研报、基金净值）不受影响。
3. **tushare 本 token 权限面好于文档预期**：`moneyflow`（文档标 2000 分）与 `hk_basic`/`hk_daily`（文档标单独权限）实测可用；`news`/`major_news`/`yc_cb` 确认无权限（单独权限接口）。
4. **akshare 1.16.72 接口名漂移**：`stock_financial_abstract_new_ths` 不存在（现名 `stock_financial_abstract_ths`）；A 股无 `stock_financial_analysis_indicator_em`（用新浪源 `stock_financial_analysis_indicator`）；`stock_info_global_cls` 仍走已失效的财联社老端点。
5. **legulegu 系不可依赖**：`stock_index_pe_lg`、`stock_a_below_net_asset_statistics` 因上游日期格式变更硬故障；破净统计可改用库里 `daily_basic` 的 `pb<1` 自聚合。
6. **新闻类**：东财 search-api-web 必须带 UA（akshare `stock_news_em` 因此坏，本系统 Java 后端直连不受影响）；财联社要走 `v1/roll/get_roll_list` 本地签名方案（见 research/01）。

---

## 一、行情数据

> 实测环境：Python 3.13（collector/.venv），akshare 1.16.72，tushare 1.4.21。
> 实测日期 2026-09-06（周日，最近交易日 2026-09-04）。每次调用间隔 ≥1.5s，失败接口追加 3 次重试（间隔 5s）。
> 重要背景：实测当日东财 `push2` 系列接口对本出口 IP 触发了风控断连（详见各卡片与文末小结），akshare 东财系接口大面积失败属于**环境级现象**，非接口已下线。

### akshare 接口

#### `stock_zh_a_spot_em`
- **库/定位**：akshare（东方财富 push2 行情中心，全 A 实时快照）
- **签名与关键参数**：`ak.stock_zh_a_spot_em()`，无参数，单次返回沪深京全 A 快照。
  ```python
  import akshare as ak
  df = ak.stock_zh_a_spot_em()   # 全A实时快照，约 5500+ 行
  ```
- **返回字段**：本次未调通。akshare 1.16.72 源码定义的有效列为（23 列）：`序号、代码、名称、最新价、涨跌幅、涨跌额、成交量、成交额、振幅、换手率、市盈率-动态、量比、5分钟涨跌、最高、最低、今开、昨收、总市值、流通市值、涨速、市净率、60日涨跌幅、年初至今涨跌幅`。
- **限制**：无积分概念（免费爬虫接口）；底层 `82.push2.eastmoney.com/api/qt/clist/get` 分页拉取，单次全量 5500+ 行、耗时通常 5–20s；东财对高频/爬虫指纹有风控，会整 IP 断连。
- **底层来源与稳定性**：东方财富行情中心（`quote.eastmoney.com/center/gridlist.html#hs_a_board`）。已知问题：东财反爬升级导致间歇性断连，见 [akshare#6986（2026-01，浏览器可访问但 python 被中断）](https://github.com/akfamily/akshare/issues/6986)、[akshare#6100（限流，换 IP 或等数小时）](https://github.com/akfamily/akshare/issues/6100)。
- **实测记录（2026-09-06）**：**失败**。首轮 0.24s 抛 `aiohttp.ServerDisconnectedError: Server disconnected`；重试 3 次（0.21–0.37s）均同错。curl 带浏览器 UA 直连 82.push2 同样连接被重置（http_code=000），确认为主机级风控断连，非 akshare 代码问题。
- **本系统映射**：**已在用**——collector `tasks/all_a_valuation.yaml` 主源（`akshare_spot_em`），且已配 tushare `daily_basic` 备源（`AllASpotBackupSource`，plugins.py:365）。本次实测失败直接印证了备源链路的必要性。

#### `stock_zh_a_hist`
- **库/定位**：akshare（东方财富 push2his，个股历史 K 线）
- **签名与关键参数**：`ak.stock_zh_a_hist(symbol="000001", period="daily", start_date="20260101", end_date="20260906", adjust="qfq")`。`period` ∈ `daily/weekly/monthly`；`adjust` ∈ `""`（不复权）/`qfq`（前复权）/`hfq`（后复权）。
  ```python
  df = ak.stock_zh_a_hist(symbol="000001", period="daily",
                          start_date="20260101", end_date="20260906", adjust="qfq")
  ```
- **返回字段（实测）**：`日期、股票代码、开盘、收盘、最高、最低、成交量、成交额、振幅、涨跌幅、涨跌额、换手率`（12 列）。daily qfq 实测 164 行；weekly 不复权实测 1262 行。
- **限制**：单次按区间全量返回，无分页；历史起点可覆盖上市首日（实测 600519 周线起点 2001-08-31，即茅台上市首周）；东财限流时会断连（issue #6100）。
- **底层来源与稳定性**：`push2his.eastmoney.com/api/qt/stock/kline/get`。同主机本次 `index_daily_em`/`fund_flow` 失败而本接口成功，说明东财风控是按路径+参数+概率触发，不是整站封禁。
- **实测记录（2026-09-06）**：**成功**。daily qfq（000001，2026-01-01~09-06）：164 行 / 1.43s，首行 2026-01-05 开 11.06 收 11.14 成交额 10.03 亿。weekly 不复权（600519 全历史）：1262 行 / 0.19s。
- **本系统映射**：未在 collector 落库链路使用；可作为「个股 K 线落库」的备选（对应 Agent kline 工具的离线化、回测数据准备）。后端运行期 K 线由 Java 直连东财同族接口。

#### `stock_zh_a_minute`
- **库/定位**：akshare（**新浪财经**，非东财；个股分钟线）
- **签名与关键参数**：`ak.stock_zh_a_minute(symbol="sh600519", period="1", adjust="")`，`period` ∈ `1/5/15/30/60`，symbol 需带 `sh/sz` 前缀。
  ```python
  df = ak.stock_zh_a_minute(symbol="sh600519", period="1", adjust="")
  ```
- **返回字段（实测）**：`day, open, high, low, close, volume`（6 列，值为字符串）。1 分钟线实测 1970 行。
- **限制**：仅返回最近若干交易日（1 分钟线 1970 行 ≈ 8 个交易日），无法取长历史；数值为字符串需自行转类型。
- **底层来源与稳定性**：`quotes.sina.cn` JSONP 接口，与东财风控无关，是东财系失效时的天然兜底。
- **实测记录（2026-09-06）**：**成功**，1970 行 / 3.29s，样本 2026-08-25 13:53 close=1304.960。
- **本系统映射**：未在用。可扩展「盘中分钟级行情」能力（Agent 大盘速览/quote 的分钟粒度增强），且因其新浪源属性可作东财分钟线兜底。

#### `stock_zh_index_spot_em`
- **库/定位**：akshare（东财 push2，指数实时快照）
- **签名与关键参数**：`ak.stock_zh_index_spot_em(symbol="沪深重要指数")`，symbol ∈ `沪深重要指数/上证系列指数/深证系列指数/指数成份/中证系列指数`。
- **返回字段**：本次未调通。源码定义：`序号、代码、名称、最新价、涨跌幅、涨跌额、成交量、成交额、振幅、最高、最低、今开、昨收、量比`（14 列）。
- **限制**：免费接口无积分门槛；受东财 push2 风控影响同 `stock_zh_a_spot_em`。
- **底层来源与稳定性**：`48.push2.eastmoney.com/api/qt/clist/get`（沪深重要指数走内部 `__stock_zh_main_spot_em`）。
- **实测记录（2026-09-06）**：**失败**。0.22s 抛 `requests.ConnectionError: RemoteDisconnected('Remote end closed connection without response')`，重试 3 次均败。
- **本系统映射**：未在用。可扩展「指数实时速览」（Agent 大盘速览工具的数据源之一）；当前 Agent 大盘速览由后端 Java 直连东财实现。

#### `stock_zh_index_daily_em`
- **库/定位**：akshare（东财 push2his，指数日 K）
- **签名与关键参数**：`ak.stock_zh_index_daily_em(symbol="sh000300", start_date="19900101", end_date="20500101")`，symbol 带市场前缀（`sh/sz/csi`）。
- **返回字段**：本次未调通。源码定义：`date, open, close, high, low, volume, amount`（英文列名，注意与 `stock_zh_a_hist` 的中文列不同）。
- **限制**：无分页，全历史一次返回。
- **底层来源与稳定性**：`push2his.eastmoney.com/api/qt/stock/kline/get`——与成功的 `stock_zh_a_hist` 同主机同路径，本次仍失败，再次印证东财风控的概率性/会话性。
- **实测记录（2026-09-06）**：**失败**。0.19s `ConnectionError: RemoteDisconnected`，重试 3 次均败。
- **本系统映射**：未在用。可替代/扩展「指数历史行情落库」（当前 collector 指数维度只有 tushare `index_dailybasic` 估值，无指数行情序列；tushare `index_daily` 亦可填此空白）。

#### `stock_board_industry_name_em` + `stock_board_industry_cons_em`
- **库/定位**：akshare（东财 push2，行业板块列表 + 板块成分股）
- **签名与关键参数**：
  ```python
  names = ak.stock_board_industry_name_em()            # 全部行业板块快照
  cons = ak.stock_board_industry_cons_em(symbol="酿酒行业")  # 板块成分股
  ```
- **返回字段**：本次未调通。源码定义——`name_em`：`排名、板块名称、板块代码、最新价、涨跌幅、涨跌额、总市值、换手率、上涨家数、下跌家数、领涨股票、领涨股票-涨跌幅`；`cons_em`：`序号、代码、名称、最新价、涨跌幅、涨跌额、成交量、成交额、振幅、换手率、市盈率-动态、最高、最低、今开、昨收、市净率`。
- **限制**：免费接口；`cons_em` 的 symbol 必须是 `name_em` 返回的板块名称原文。
- **底层来源与稳定性**：`17.push2.eastmoney.com/api/qt/clist/get`（带东财 ut 参数）。
- **实测记录（2026-09-06）**：**双双失败**。`name_em` 0.27s、`cons_em` 0.70s 抛 `ConnectionError: RemoteDisconnected`，各重试 3 次均败。
- **本系统映射**：未在用（行业维度走 tushare 申万 `index_classify`/`index_member_all`，见 `tasks/shenwan_mapping.yaml`）。可作申万映射的东财口径备源，或扩展「板块行情/板块轮动」能力。

#### `stock_board_concept_name_em`
- **库/定位**：akshare（东财 push2，概念板块列表）
- **签名与关键参数**：`ak.stock_board_concept_name_em()`，无参数。
- **返回字段（实测）**：`排名、板块名称、板块代码、最新价、涨跌额、涨跌幅、总市值、换手率、上涨家数、下跌家数、领涨股票、领涨股票-涨跌幅`（12 列），实测 504 行。
- **限制**：同 push2 风控；但**该接口是少数不带 ut 参数的 push2 调用**，本次在东财系大面积失败中幸存（见文末根因分析）。
- **底层来源与稳定性**：`79.push2.eastmoney.com/api/qt/clist/get`（无 ut）。
- **实测记录（2026-09-06）**：**成功**，504 行 / 0.83s，样本：排名第 1「猪肉概念」BK0882，涨跌幅 +5.42%，领涨股票涨跌幅见列。
- **本系统映射**：未在用。可扩展「概念板块速览/题材追踪」能力（Agent 工具或行情台新卡片）。

#### `stock_individual_fund_flow`
- **库/定位**：akshare（东财 push2his，个股资金流历史）
- **签名与关键参数**：`ak.stock_individual_fund_flow(stock="600519", market="sh")`，market ∈ `sh/sz/bj`。
- **返回字段**：本次未调通。源码定义：`日期、主力净流入-净额、小单净流入-净额、中单净流入-净额、大单净流入-净额、超大单净流入-净额、主力净流入-净占比、小单净流入-净占比、中单净流入-净占比、大单净流入-净占比、超大单净流入-净占比、收盘价、涨跌幅`（13 列有效）。
- **限制**：免费接口；**本次定位到确切根因：akshare 硬编码的 `ut=b2884a393a59ad64002292a3e90d46a5` 参数被东财风控针对性断连**——同一 URL 去掉 ut 直连返回 HTTP 200（13.8KB），带上 ut（无论 UA）立即 RemoteDisconnected。应急方案：自行 requests 调同 URL 并省略 ut 参数即可取数。
- **底层来源与稳定性**：`push2his.eastmoney.com/api/qt/stock/fflow/daykline/get`。
- **实测记录（2026-09-06）**：**失败**。0.11s `ConnectionError: RemoteDisconnected`，重试 3 次均败；对照实验（去 ut 成功/带 ut 失败）确认 ut 参数为触发点。
- **本系统映射**：未在用。可扩展「个股资金流」能力；但同口径数据 tushare `moneyflow` 已实测可用且更稳定，优先走 tushare（需 2000 积分，本账户满足）。

#### `tool_trade_date_hist_sina`
- **库/定位**：akshare（新浪财经，A 股交易日历）
- **签名与关键参数**：`ak.tool_trade_date_hist_sina()`，无参数。
  ```python
  df = ak.tool_trade_date_hist_sina()   # 单列 trade_date
  ```
- **返回字段（实测）**：`trade_date`（date 类型，单列），实测 8797 行。
- **限制**：只含历史与当年已确定的交易日，不保证未来年度；无 is_open 标记（每行即一个交易日）。
- **底层来源与稳定性**：新浪源，稳定，与东财风控无关。
- **实测记录（2026-09-06）**：**成功**，8797 行 / 0.11s，首行 1990-12-19（上交所开市日）。
- **本系统映射**：**已在用**——`collector/scheduler/jobs.py:272` 交易日历任务。tushare `trade_cal`（含 is_open/未来日期）可作备源。

### tushare 接口

#### `daily`
- **库/定位**：tushare pro（个股日线行情，未复权）
- **签名与关键参数**：
  ```python
  import tushare as ts
  pro = ts.pro_api(token)
  df = pro.daily(ts_code="000001.SZ", start_date="20260801", end_date="20260906")
  # 也可按 trade_date 单日全市场
  ```
- **返回字段（实测）**：`ts_code, trade_date, open, high, low, close, pre_close, change, pct_chg, vol, amount`（11 列），区间实测 25 行。
- **限制**：[120 积分起调](https://tushare.pro/document/1?doc_id=108)；单次最大 6000 条；交易日 15:00–17:00 间更新当日数据；未复权（复权需 `pro_bar` 或自行乘因子）。
- **底层来源与稳定性**：tushare 官方整理数据，API 化、稳定性远高于爬虫系。
- **实测记录（2026-09-06）**：**成功**，25 行 / 0.09s，样本 000001.SZ 20260904 收 11.89、pre_close 11.88。
- **本系统映射**：未在 collector 使用（个股估值走 `daily_basic`）。可替代 akshare `stock_zh_a_hist` 做日线落库主源（更稳，但无复权价、受单次 6000 条约束需按日循环）。

#### `daily_basic`
- **库/定位**：tushare pro（每日指标：换手率/量比/PE/PB/市值等）
- **签名与关键参数**：
  ```python
  df = pro.daily_basic(trade_date="20260904")   # 单日全市场
  ```
- **返回字段（实测）**：`ts_code, trade_date, close, turnover_rate, turnover_rate_f, volume_ratio, pe, pe_ttm, pb, ps, ps_ttm, dv_ratio, dv_ttm, total_share, float_share, free_share, total_mv, circ_mv`（18 列），单日全市场实测 5548 行。
- **限制**：[≥2000 积分调取，5000 积分无总量限制](https://tushare.pro/document/2?doc_id=32)；单次最大 6000 条——单日全 A（约 5550）已接近上限，按日调用是正确姿势。
- **底层来源与稳定性**：tushare 官方。
- **实测记录（2026-09-06）**：**成功**，5548 行 / 0.18s，样本 000001.SZ pe_ttm 5.31、pb 见列、turnover_rate 0.42%。
- **本系统映射**：**已在用**——`tasks/stock_valuation_daily.yaml` 主源、`IndustryUniverseSource` 全 A 估值、`AllASpotBackupSource`（akshare spot 失败时的降级备源，plugins.py:365）。

#### `index_daily`
- **库/定位**：tushare pro（指数日线行情）
- **签名与关键参数**：
  ```python
  df = pro.index_daily(ts_code="000300.SH", start_date="20260801", end_date="20260906")
  ```
- **返回字段（实测）**：`ts_code, trade_date, close, open, high, low, pre_close, change, pct_chg, vol, amount`（11 列），区间实测 25 行。
- **限制**：[≥2000 积分，5000 以上频次更高](https://tushare.pro/document/2?doc_id=95)；**不含申万行业指数行情**。
- **底层来源与稳定性**：tushare 官方。
- **实测记录（2026-09-06）**：**成功**，25 行 / 0.04s，样本 000300.SH 20260904 收 4548.05、跌 4.53 点。
- **本系统映射**：未在用。可填补「指数行情序列」空白（当前 collector 只落指数估值 `index_dailybasic`，不落指数 K 线）；也可在东财系失效时支撑 Agent 大盘速览的历史对比。

#### `index_dailybasic`
- **库/定位**：tushare pro（大盘指数每日指标：PE/PB/市值/换手率）
- **签名与关键参数**：
  ```python
  df = pro.index_dailybasic(trade_date="20260904")        # 单日
  df = pro.index_dailybasic(ts_code="000300.SH", start_date=..., end_date=...)  # 单指数区间
  ```
- **返回字段（实测）**：`ts_code, trade_date, total_mv, float_mv, total_share, float_share, free_share, turnover_rate, turnover_rate_f, pe, pe_ttm, pb`（12 列）。**注意：按 trade_date 单日调用实测仅 12 行**（只覆盖主要宽基指数），要全量指数需按 ts_code 循环。
- **限制**：[≥2000 积分](https://tushare.pro/document/2?doc_id=128)；历史自 2004-01 起。
- **底层来源与稳定性**：tushare 社区统计计算。
- **实测记录（2026-09-06）**：**成功**，12 行 / 0.05s，样本 399300.SZ 20260904 total_mv 68.36 万亿、turnover_rate 0.59%。
- **本系统映射**：**已在用**——`tasks/index_valuation.yaml`（指数估值落库，plugins.py:94 按 ts_code 逐指调取）。

#### `stock_basic`
- **库/定位**：tushare pro（股票列表/基础信息）
- **签名与关键参数**：
  ```python
  df = pro.stock_basic(exchange="", list_status="L")   # L上市 D退市 P暂停
  ```
- **返回字段（实测）**：`ts_code, symbol, name, area, industry, cnspell, market, list_date, act_name, act_ent_type`（10 列），L 状态实测 5556 行。
- **限制**：120 积分档基础接口；单次全量无分页压力。
- **底层来源与稳定性**：tushare 官方。
- **实测记录（2026-09-06）**：**成功**，5556 行 / 0.13s，样本 000001.SZ 平安银行、银行、主板、19910403 上市。
- **本系统映射**：**已在用**——`IndustryUniverseSource`/`AllASpotBackupSource` 内接股票名并过滤 ST/退市/北交所（plugins.py）。

#### `trade_cal`
- **库/定位**：tushare pro（交易日历）
- **签名与关键参数**：
  ```python
  df = pro.trade_cal(exchange="SSE", start_date="20260101", end_date="20261231")
  ```
- **返回字段（实测）**：`exchange, cal_date, is_open, pretrade_date`（4 列），2026 全年 365 行（含周末，is_open 标记）。
- **限制**：120 积分档；支持 SSE/SZSE/BSE/CFFEX 等；**含未来日期与 is_open 标记**（优于 akshare sina 日历）。
- **底层来源与稳定性**：tushare 官方。
- **实测记录（2026-09-06）**：**成功**，365 行 / 0.05s，样本 20261231 is_open=1、pretrade_date 20261230。
- **本系统映射**：未在用（日历用 akshare `tool_trade_date_hist_sina`，jobs.py:272）。结构更完整（is_open + pretrade_date + 未来日期），可作日历备源或替换升级。

#### `moneyflow`
- **库/定位**：tushare pro（个股资金流向，小/中/大/特大单）
- **签名与关键参数**：
  ```python
  df = pro.moneyflow(trade_date="20260904")   # 单日全市场
  ```
- **返回字段（实测）**：`ts_code, trade_date, buy_sm_vol, buy_sm_amount, sell_sm_vol, sell_sm_amount, buy_md_vol, buy_md_amount, sell_md_vol, sell_md_amount, buy_lg_vol, buy_lg_amount, sell_lg_vol, sell_lg_amount, buy_elg_vol, buy_elg_amount, sell_elg_vol, sell_elg_amount, net_mf_vol, net_mf_amount`（20 列），单日实测 5548 行。
- **限制**：[文档标注 ≥2000 积分](https://tushare.pro/document/2?doc_id=170)，单次最大 6000 条。**预先研判可能因积分不足失败，实测成功**——说明本账户积分 ≥2000（与 daily_basic/index_dailybasic 可调相互印证）。
- **底层来源与稳定性**：tushare 官方。
- **实测记录（2026-09-06）**：**成功**，5548 行 / 0.19s，样本 000001.SZ 小单买 236169 手/28128.96 万元。
- **本系统映射**：未在用。可扩展「资金流向」能力（对比 akshare `stock_individual_fund_flow`：同口径东财数据，但 tushare 侧已实测可用且不受东财风控影响，优先级更高）。

### 本节根因分析：东财 push2 风控（2026-09-06 实测）

- 失败接口的共性：全部命中东财 `N.push2.eastmoney.com` / `push2his.eastmoney.com` 的 `clist/get`、`fflow/daykline/get` 等路径，表现为 TCP 连接秒断（0.1–0.7s 内 `RemoteDisconnected` / `Server disconnected`），重试无效。
- 对照实验结论：
  - `fflow/daykline`：去掉 akshare 硬编码 `ut` 参数直连返回 200；带上 ut 必断 → **ut 指纹被针对性风控**。
  - `clist/get`：82/17/48/79/7/28/91 各编号主机全部断连，与 ut 无关 → **clist 路径整体风控**；但同路径的 `stock_board_concept_name_em`（79.push2，无 ut）在首轮曾成功 → 风控是动态/概率性的。
  - 同主机不同命运：`push2his` 的 `kline/get`（stock_zh_a_hist）成功而 `fflow/daykline`（带 ut）失败 → 风控按「路径+参数」粒度触发。
- 工程启示：东财系接口必须配重试+备源（本系统 all_a_valuation 已这么做）；应急时可去 ut 参数直连；新浪系（minute/交易日历）与 tushare 不受影响。

---
本节实测统计：共 17 个接口（akshare 10 + tushare 7），成功 11 / 失败 6（akshare 成功 4 失败 6；tushare 成功 7 失败 0）。失败 6 个全部为东财 push2/push2his 风控断连，非接口下线。


---

## 二、基本面与估值

实测环境：Python 3.13（`collector/.venv`），akshare 1.16.72、tushare 1.4.21；实测日期 2026-09-06（最近交易日 2026-09-04）。tushare 各接口仅调用 1 次；调用间隔 ≥1s。

### tushare 财务三表与财务指标

#### `income`
- **库/定位**：tushare / 利润表（单股历史）
- **签名与关键参数**：`pro.income(ts_code, start_date, end_date, period, report_type, comp_type)`；start/end_date 过滤的是**公告日**（ann_date），非报告期；`report_type=1` 合并报表（默认）、`2` 单季合并。示例：
  ```python
  pro.income(ts_code="600519.SH", start_date="20250101", end_date="20251231")
  ```
- **返回字段（实测）**：89 列。关键列：`ts_code, ann_date, f_ann_date, end_date, report_type, comp_type, basic_eps, diluted_eps, total_revenue, revenue, total_cogs, oper_cost, sell_exp, admin_exp, fin_exp, rd_exp, operate_profit, total_profit, income_tax, n_income, n_income_attr_p, minority_gain, ebit, ebitda, continued_net_profit, update_flag`。实测 4 行（600519 在 2025 公告年内的 4 个报告期，含 report_type=1 重复口径需注意去重）。
- **限制**：≥2000 积分；单接口只能按单股取历史，按季度取全市场需 `income_vip`（5000 积分）。文档：[doc_id=33](https://tushare.pro/document/2?doc_id=33)。实测当前 token 正常调取。
- **底层来源与稳定性**：tushare 官方整理（交易所公告），稳定。
- **实测记录（2026-09-06）**：成功，0.12s。首行 600519.SH 2025Q3：basic_eps=51.53，total_revenue=130,903,889,634.88 元，n_income_attr_p=64,626,746,712.18 元。
- **本系统映射**：未在用（后端 Java 直连东财 `RPT_F10_FINANCE_MAINFINADATA` 取单股财务指标）。可扩展：落三大表明细，支撑投研问答中的报表级追问。

#### `balancesheet`
- **库/定位**：tushare / 资产负债表（单股历史）
- **签名与关键参数**：`pro.balancesheet(ts_code, start_date, end_date, period, ...)`，参数语义同 income。示例：
  ```python
  pro.balancesheet(ts_code="600519.SH", start_date="20250101", end_date="20251231")
  ```
- **返回字段（实测）**：141 列。关键列：`total_share, money_cap, accounts_receiv, inventories, total_cur_assets, fix_assets, goodwill, total_assets, st_borr, acct_payable, contract_liab, total_cur_liab, total_liab, minority_int, total_hldr_eqy_exc_min_int, total_liab_hldr_eqy`。
- **限制**：≥2000 积分；全市场按季度用 `balancesheet_vip`（5000 积分）。文档：[doc_id=36](https://tushare.pro/document/2?doc_id=36)。
- **底层来源与稳定性**：同 income。
- **实测记录（2026-09-06）**：成功，0.05s，4 行。首行 600519.SH 2025Q3：total_assets=304,738,184,929.86，money_cap=51,753,057,846.45，total_hldr_eqy_exc_min_int=257,069,964,386.23。
- **本系统映射**：未在用。可扩展：与 income 一起构成三大表落库。

#### `cashflow`
- **库/定位**：tushare / 现金流量表（单股历史）
- **签名与关键参数**：`pro.cashflow(ts_code, start_date, end_date, period, ...)`。示例：
  ```python
  pro.cashflow(ts_code="600519.SH", start_date="20250101", end_date="20251231")
  ```
- **返回字段（实测）**：98 列。关键列：`net_profit, c_fr_sale_sg, c_inf_fr_operate_a, n_cashflow_act（经营净额）, n_cashflow_inv_act, n_cash_flows_fnc_act, free_cashflow, n_incr_cash_cash_equ, c_cash_equ_end_period`。
- **限制**：≥2000 积分；全市场按季度用 `cashflow_vip`（5000 积分）。文档：[doc_id=44](https://tushare.pro/document/2?doc_id=44)。
- **底层来源与稳定性**：同 income。
- **实测记录（2026-09-06）**：成功，0.05s，6 行（同一报告期有多 report_type 版本）。首行 600519.SH 2025Q3：n_cashflow_act=38,196,802,155.27，free_cashflow 该行为 NaN（部分报告期不填）。
- **本系统映射**：未在用。可扩展：三大表落库。

#### `fina_indicator`
- **库/定位**：tushare / 财务指标（每股指标、盈利能力、偿债、营运、成长，单股按季）
- **签名与关键参数**：`pro.fina_indicator(ts_code, start_date, end_date, period)`；start/end 为公告日区间。示例：
  ```python
  pro.fina_indicator(ts_code="600519.SH", start_date="20250101", end_date="20251231")
  ```
- **返回字段（实测）**：110 列。关键列：`eps, dt_eps, bps, ocfps, roe, roe_waa, roe_dt, roa, roic, grossprofit_margin, netprofit_margin, debt_to_assets, current_ratio, quick_ratio, basic_eps_yoy, netprofit_yoy, or_yoy, q_roe, q_sales_yoy, q_op_qoq`。
- **限制**：≥2000 积分；全市场按报告期用 `fina_indicator_vip`（5000 积分）。文档：[doc_id=79](https://tushare.pro/document/2?doc_id=79)。实测当前 token 正常。
- **底层来源与稳定性**：tushare 官方，稳定。
- **实测记录（2026-09-06）**：成功，0.05s，5 行。首行 600519.SH 2025 年报（ann_date=20260417）：eps=65.66，roe=34.462，grossprofit_margin=91.18，netprofit_yoy=-4.53。
- **本系统映射**：**已在用** —— collector 逐股财务季数据采集（4 线程 + 限速），落 `stock_financial` 表。

#### `forecast`
- **库/定位**：tushare / 业绩预告
- **签名与关键参数**：`pro.forecast(ts_code, ann_date, start_date, end_date, period, type)`；type 为预告类型（预增/预减/扭亏/首亏/续亏/续盈/略增/略减）。示例：
  ```python
  pro.forecast(ts_code="600519.SH", start_date="20250101", end_date="20260831")
  ```
- **返回字段（实测）**：13 列：`ts_code, ann_date, end_date, type, p_change_min, p_change_max, net_profit_min, net_profit_max, last_parent_net, first_ann_date, summary, change_reason, update_flag`。net_profit 单位**万元**。
- **限制**：≥2000 积分。文档：[doc_id=45](https://tushare.pro/document/2?doc_id=45)。
- **底层来源与稳定性**：tushare 官方，稳定。
- **实测记录（2026-09-06）**：成功，0.05s，2 行。首行：600519.SH 2024 年报预告（ann_date=20250113），type=略增，p_change=14.67%，net_profit=8,570,000 万元，summary="预计净利润8570000万"。
- **本系统映射**：未在用。可扩展：业绩预告落库，供 Agent 回答"近期业绩预告"类问题。

#### `express`
- **库/定位**：tushare / 业绩快报
- **签名与关键参数**：`pro.express(ts_code, ann_date, start_date, end_date, period)`。示例：
  ```python
  pro.express(ts_code="600519.SH", start_date="20250101", end_date="20260831")
  ```
- **返回字段（实测）**：16 列：`ts_code, ann_date, end_date, revenue, operate_profit, total_profit, n_income, total_assets, total_hldr_eqy_exc_min_int, diluted_eps, diluted_roe, yoy_net_profit, bps, perf_summary, update_flag`。
- **限制**：≥2000 积分。文档：[doc_id=46](https://tushare.pro/document/2?doc_id=46)。
- **底层来源与稳定性**：tushare 官方，稳定。
- **实测记录（2026-09-06）**：调用成功，0.05s，**0 行** —— 贵州茅台在该公告窗口内未发布业绩快报（茅台惯例只发预告/年报），属正常空结果而非接口故障。选样本时应挑常发快报的公司（如银行/券商）。
- **本系统映射**：未在用。可扩展：快报落库（注意大量公司不发快报，覆盖率低于 forecast）。

#### `dividend`
- **库/定位**：tushare / 分红送股（单股全部历史）
- **签名与关键参数**：`pro.dividend(ts_code, ann_date, record_date, ex_date, imp_ann_date)`。示例：
  ```python
  pro.dividend(ts_code="600519.SH")
  ```
- **返回字段（实测）**：14 列：`ts_code, end_date, ann_date, div_proc（预案/实施等）, stk_div, stk_bo_rate, stk_co_rate, cash_div, cash_div_tax（每股税前派现，元）, record_date, ex_date, pay_date, div_listdate, imp_ann_date`。实测 89 行（茅台上市以来全部分红记录）。
- **限制**：≥2000 积分。文档：[doc_id=103](https://tushare.pro/document/2?doc_id=103)。
- **底层来源与稳定性**：tushare 官方，稳定。
- **实测记录（2026-09-06）**：成功，0.05s。首行：2026 中报分红"预案"（ann_date=20260815），现金字段为 0（预案未出金额），record_date/ex_date 为空——预案阶段字段稀疏是常态。
- **本系统映射**：未在用。可扩展：分红历史支撑股息率/分红连续性分析（当前股息率来自 daily_basic 的 dv_ttm）。

### tushare 估值与指数/行业

#### `daily_basic`
- **库/定位**：tushare / 每日指标（全市场估值+换手+股本市值日快照）
- **签名与关键参数**：`pro.daily_basic(ts_code, trade_date, start_date, end_date)`；ts_code 与 trade_date 二选一，按 trade_date 一次取全市场。示例：
  ```python
  pro.daily_basic(trade_date="20260904")
  ```
- **返回字段（实测）**：18 列：`ts_code, trade_date, close, turnover_rate, turnover_rate_f, volume_ratio, pe, pe_ttm, pb, ps, ps_ttm, dv_ratio, dv_ttm, total_share, float_share, free_share, total_mv, circ_mv`。实测 5548 行（20260904 全 A）。
- **限制**：≥2000 积分（5000 积分无总量限制）；单次最大 6000 条；交易日 15:00–17:00 更新。文档：[doc_id=32](https://tushare.pro/document/2?doc_id=32)。
- **底层来源与稳定性**：tushare 官方，稳定。
- **实测记录（2026-09-06）**：成功，0.20s。首行 000001.SZ 20260904：close=11.89，pe=5.41，pe_ttm=5.31，pb=0.49，dv_ttm=5.01%。
- **本系统映射**：**已在用** —— 个股估值日快照（`stock_valuation_daily`）、全 A 估值备源与行业估值（`valuation_snapshot`、`industry_valuation`）。

#### `index_dailybasic`
- **库/定位**：tushare / 大盘指数每日估值（PE/PB/市值/换手）
- **签名与关键参数**：`pro.index_dailybasic(ts_code, trade_date, start_date, end_date)`。示例：
  ```python
  pro.index_dailybasic(ts_code="000300.SH", start_date="20260825", end_date="20260904")
  ```
- **返回字段（实测）**：12 列：`ts_code, trade_date, total_mv, float_mv, total_share, float_share, free_share, turnover_rate, turnover_rate_f, pe, pe_ttm, pb`。实测 9 行。
- **限制**：≥2000 积分。文档：[doc_id=84](https://tushare.pro/document/2?doc_id=84)。
- **底层来源与稳定性**：tushare 官方，稳定。
- **实测记录（2026-09-06）**：成功，0.05s。首行 000300.SH 20260904：pe=14.75，pe_ttm=13.68，pb=1.44，turnover_rate=0.59。
- **本系统映射**：**已在用** —— 5 大指数估值，落 `index_valuation_history`。

#### `index_weight`
- **库/定位**：tushare / 指数成分与权重（月度）
- **签名与关键参数**：`pro.index_weight(index_code, trade_date, start_date, end_date)`；官方建议 start/end 取当月首末日。示例：
  ```python
  pro.index_weight(index_code="000300.SH", start_date="20260801", end_date="20260904")
  ```
- **返回字段（实测）**：4 列：`index_code, con_code, trade_date, weight（%）`。实测 300 行（沪深300 全成分，trade_date=20260831）。
- **限制**：≥2000 积分；月度数据。文档：[doc_id=96](https://tushare.pro/document/2?doc_id=96)。
- **底层来源与稳定性**：指数公司公开数据，稳定。
- **实测记录（2026-09-06）**：成功，0.05s。首行：000300.SH / 300750.SZ（宁德时代）/ 20260831 / weight=3.66。
- **本系统映射**：**已在用** —— 成分权重，落 `index_constituent`。

#### `index_classify` + `index_member_all`
- **库/定位**：tushare / 申万行业分类（SW2014/SW2021）与分级成分
- **签名与关键参数**：`pro.index_classify(level, src, parent_code)`（level=L1/L2/L3，src=SW2014/SW2021）；`pro.index_member_all(l1_code/l2_code/l3_code/ts_code, is_new)`。示例：
  ```python
  pro.index_classify(level="L1", src="SW2021")        # 31 个一级行业
  pro.index_member_all(l1_code="801010.SI")           # 某一级行业的分级成分
  ```
- **返回字段（实测）**：index_classify 7 列（`index_code, industry_name, level, industry_code, is_pub, parent_code, src`），31 行；index_member_all 11 列（`l1_code, l1_name, l2_code, l2_name, l3_code, l3_name, ts_code, name, in_date, out_date, is_new`），126 行（农林牧渔分级成分）。
- **限制**：index_classify/index_member_all 均 ≥2000 积分（权限表 doc_id=108）；文档：[index_classify doc_id=181](https://tushare.pro/document/2?doc_id=181)、[index_member_all doc_id=335](https://tushare.pro/document/2?doc_id=335)。
- **底层来源与稳定性**：申万官方分类的整理，稳定。
- **实测记录（2026-09-06）**：均成功，各 0.05s。index_classify 首行 801010.SI 农林牧渔；index_member_all 首行 600359.SH 新农开发（L1 农林牧渔 / L2 种植业 / L3 其他种植业，in_date=19990429）。
- **本系统映射**：**已在用** —— SW2021 一级行业映射，落 `shenwan_industry_mapping`。

### akshare 财务

#### `stock_financial_abstract`
- **库/定位**：akshare / 新浪财经-财务摘要（宽表：行=指标，列=报告期）
- **签名与关键参数**：`ak.stock_financial_abstract(symbol="600519")`，symbol 为纯 6 位代码。示例：
  ```python
  ak.stock_financial_abstract(symbol="600519")
  ```
- **返回字段（实测）**：80 行 × 112 列：`选项`（指标分组，如"常用指标"）、`指标`（如归母净利润），其余每列一个报告期（`20260630`…`19981231`，茅台可追溯至 1998）。首行：归母净利润 20260630=44,516,880,421.86。
- **限制**：无积分/无限频声明；网页接口，需自控频率。
- **底层来源与稳定性**：新浪财经 F10，稳定性中等（页面改版会带动 akshare 修复）。
- **实测记录（2026-09-06）**：成功，0.54s。宽表 pivot 结构，程序消费需转置。
- **本系统映射**：未在用。可替代 fina_indicator 的轻量备源（免费、无积分门槛），但字段口径与 tushare 不完全一致。

#### `stock_financial_abstract_ths`（任务书所列 `stock_financial_abstract_new_ths` 的现行名）
- **库/定位**：akshare / 同花顺-财务摘要
- **签名与关键参数**：`ak.stock_financial_abstract_ths(symbol="600519", indicator="按报告期")`；indicator ∈ {按报告期, 按年度, 按单季度}。示例：
  ```python
  ak.stock_financial_abstract_ths(symbol="600519", indicator="按报告期")
  ```
- **返回字段（实测）**：103 行 × 25 列：`报告期, 净利润, 净利润同比增长率, 扣非净利润, 营业总收入, 基本每股收益, 每股净资产, 每股经营现金流, 销售净利率, 销售毛利率, 净资产收益率, 资产负债率` 等，纵表结构。**注意数值是带单位的字符串**（"1.47亿"、"23.38%"），缺失值填布尔 `False`，落库前必须清洗。
- **限制**：无门槛；网页接口需自控频率。
- **底层来源与稳定性**：同花顺 iwencai/10jqka，稳定性中等。
- **实测记录（2026-09-06）**：首轮按任务书名称 `stock_financial_abstract_new_ths` 调用报 `AttributeError: module 'akshare' has no attribute 'stock_financial_abstract_new_ths'`（1.16.72 中不存在该名）；改用 `stock_financial_abstract_ths` 后成功，0.47s。
- **本系统映射**：未在用。可扩展为财务摘要备源（纵表比新浪宽表更易落库，但需单位解析）。

#### `stock_financial_analysis_indicator`（任务书所列 `stock_financial_analysis_indicator_em` 的说明）
- **库/定位**：akshare / 新浪财经-财务分析指标（逐年/逐季比率全集）。**注意：akshare 1.16.72 中不存在 `stock_financial_analysis_indicator_em`**（`_em` 后缀只有港股 `stock_financial_hk_analysis_indicator_em` 与美股 `stock_financial_us_analysis_indicator_em`）；A 股对应接口是新浪源的 `stock_financial_analysis_indicator`。
- **签名与关键参数**：`ak.stock_financial_analysis_indicator(symbol="600519", start_year="2024")`。示例：
  ```python
  ak.stock_financial_analysis_indicator(symbol="600519", start_year="2024")
  ```
- **返回字段（实测）**：10 行 × 87 列：`日期, 摊薄每股收益(元), 每股净资产_调整后(元), 每股经营性现金流(元), 净资产收益率(%), 加权净资产收益率(%), 主营业务收入增长率(%), 净利润增长率(%), 流动比率, 速动比率, 资产负债率(%)` 等。首行 2024-03-31：摊薄 EPS=19.81，加权 ROE=10.57%，资产负债率=12.95%。
- **限制**：无门槛；start_year 起逐年返回。
- **底层来源与稳定性**：新浪财经，稳定性中等（akshare 更新日志中该接口被 fix 过多次：1.10.97 / 1.13.98 / 1.15.90，说明源端结构常变）。
- **实测记录（2026-09-06）**：首轮 `_em` 名报 `AttributeError`；改 `stock_financial_analysis_indicator` 成功，1.82s。
- **本系统映射**：未在用。可作为 fina_indicator 备源（比率口径接近，但无 tushare 的 q_* 单季同比字段）。

#### `stock_profit_sheet_by_report_em`（同族：`stock_balance_sheet_by_report_em`、`stock_cash_flow_sheet_by_report_em`）
- **库/定位**：akshare / 东方财富-利润表（按报告期，全科目+同比）。同族三接口分别对应资产负债/利润/现金流量表，签名一致，本次实测利润表，其余两个标注同族未单独实测。
- **签名与关键参数**：`ak.stock_profit_sheet_by_report_em(symbol="SH600519")`；symbol 需带市场前缀（SH/SZ/BJ）。示例：
  ```python
  ak.stock_profit_sheet_by_report_em(symbol="SH600519")
  ```
- **返回字段（实测）**：103 行（茅台 2001 以来全部报告期）× 206 列，全大写东财原生字段：`SECUCODE, SECURITY_CODE, REPORT_DATE, REPORT_TYPE, NOTICE_DATE, TOTAL_OPERATE_INCOME(+_YOY), OPERATE_COST(+_YOY), OPERATE_PROFIT, TOTAL_PROFIT, NETPROFIT, PARENT_NETPROFIT(+_YOY), DEDUCT_PARENT_NETPROFIT, BASIC_EPS` 等，每个科目都带 `_YOY` 同比列。首行 2026 中报：TOTAL_OPERATE_INCOME=92,278,072,083.21（YoY +1.30%），PARENT_NETPROFIT=44,516,880,421.86（YoY -1.95%）。
- **限制**：无门槛；内部分页 21 页，单股全历史耗时约 7s（比分页并行的 tushare 慢）。
- **底层来源与稳定性**：东财 datacenter-web（与本系统后端 Java 直连的 `RPT_F10_*` 同一数据源族），稳定性较好。
- **实测记录（2026-09-06）**：成功，7.12s（103 行，21 页分页）。
- **本系统映射**：未在用。**可替代**后端 Java 直连东财的单股财务指标方案（同源，且自带 YOY）；也可扩展为三大表全历史落库的数据源。

#### `stock_yjbb_em`（同族：`stock_yjkb_em`、`stock_yjyg_em`）
- **库/定位**：akshare / 东方财富-业绩报表（全市场某报告期）。同族 `stock_yjkb_em`（业绩快报）、`stock_yjyg_em`（业绩预告）签名一致，本次实测 yjbb，其余标注同族未单独实测。
- **签名与关键参数**：`ak.stock_yjbb_em(date="20260630")`，date 为报告期（季度末日）。示例：
  ```python
  ak.stock_yjbb_em(date="20260630")
  ```
- **返回字段（实测）**：11446 行 × 16 列：`序号, 股票代码, 股票简称, 每股收益, 营业总收入-营业总收入, 营业总收入-同比增长, 营业总收入-季度环比增长, 净利润-净利润, 净利润-同比增长, 净利润-季度环比增长, 每股净资产, 净资产收益率, 每股经营现金流量, 销售毛利率, 所处行业, 最新公告日期`。
- **限制**：无门槛；一次一个报告期全市场，分页 23 页约 10s。
- **底层来源与稳定性**：东财 datacenter，稳定性较好。
- **实测记录（2026-09-06）**：成功，10.66s。首行 603976 正川股份：EPS=0.08，营收 YoY +17.58%，净利 YoY -12.79%，公告日 2026-09-05。
- **本系统映射**：未在用。**可替代** tushare forecast/express 做全市场业绩预告/快报采集（免费、一次全市场，无 2000 积分门槛）；也可扩展"季报季全市场业绩速览"能力。

#### `stock_fhps_em`
- **库/定位**：akshare / 东方财富-分红配送（全市场某报告期）
- **签名与关键参数**：`ak.stock_fhps_em(date="20251231")`，date 为分红报告期。示例：
  ```python
  ak.stock_fhps_em(date="20251231")
  ```
- **返回字段（实测）**：3653 行 × 18 列：`代码, 名称, 送转股份-送转总比例, 送转股份-送转比例, 送转股份-转股比例, 现金分红-现金分红比例, 现金分红-股息率, 每股收益, 每股净资产, 每股公积金, 每股未分配利润, 净利润同比增长, 总股本, 预案公告日, 股权登记日, 除权除息日, 方案进度, 最新公告日期`。
- **限制**：无门槛；一次一个报告期。
- **底层来源与稳定性**：东财 datacenter，稳定性较好。
- **实测记录（2026-09-06）**：成功，3.65s。首行 002107 沃华医药：现金分红比例 1.46，股息率 2.13%，方案进度"实施分配"。
- **本系统映射**：未在用。**可替代** tushare dividend（按报告期全市场 vs 按单股全历史，互补）；可扩展分红能力。

### akshare 估值与市场宽度

#### `stock_zh_index_value_csindex`
- **库/定位**：akshare / 中证指数官网-指数估值（官方 PE1/PE2/股息率）
- **签名与关键参数**：`ak.stock_zh_index_value_csindex(symbol="000300")`，symbol 为纯数字指数代码。示例：
  ```python
  ak.stock_zh_index_value_csindex(symbol="000300")
  ```
- **返回字段（实测）**：20 行 × 10 列：`日期, 指数代码, 指数中文全称, 指数中文简称, 指数英文全称, 指数英文简称, 市盈率1, 市盈率2, 股息率1, 股息率2`（1=按总股本/2=按计算用股本口径）。**只返回最近约一个月**（20 个交易日），无历史区间参数。首行 2026-09-04 沪深300：PE1=14.87，PE2=17.07，股息率1=2.58。
- **限制**：无门槛；仅近一个月窗口。
- **底层来源与稳定性**：中证指数官网（官方口径），稳定性较好。
- **实测记录（2026-09-06）**：成功，0.29s。
- **本系统映射**：未在用。可扩展：作为 `index_dailybasic`（index_valuation_history）的官方口径校准源；历史序列需每日落库自行累积。

#### `stock_index_pe_lg`
- **库/定位**：akshare / 乐咕乐股-指数市盈率（legulegu 系）
- **签名与关键参数**：`ak.stock_index_pe_lg(symbol="沪深300")`。
- **返回字段（实测）**：未返回（调用失败）。
- **限制**：无门槛。
- **底层来源与稳定性**：legulegu.com，历史上频繁失效；本次确认为**源端数据格式变更导致 akshare 1.16.72 解析失败**：akshare 代码按毫秒时间戳解析（`pd.to_datetime(..., unit="ms")`），而 legulegu 现返回 ISO 日期字符串（`2005-04-29`），直接抛错。
- **实测记录（2026-09-06）**：**失败**，0.45s。报错：`ValueError: non convertible value 2005-04-29 with the unit 'ms', at position 0`。
- **本系统映射**：不可用。指数 PE 历史用已接入的 tushare `index_dailybasic` + 东财方案即可，不建议依赖 legulegu。

#### `stock_a_below_net_asset_statistics`
- **库/定位**：akshare / 乐咕乐股-破净股统计（市场宽度）
- **签名与关键参数**：`ak.stock_a_below_net_asset_statistics(symbol="全部A股")`。
- **返回字段（实测）**：未返回（调用失败）。
- **限制**：无门槛。
- **底层来源与稳定性**：legulegu.com，同上的 `unit="ms"` 解析 bug。
- **实测记录（2026-09-06）**：**失败**，0.15s。报错：`ValueError: non convertible value 2005-01-05 with the unit 'ms', at position 0`。
- **本系统映射**：不可用。破净统计可自行用 `daily_basic` 的 pb<1 聚合实现（数据已在库）。

#### `stock_ebs_lg`
- **库/定位**：akshare / 乐咕乐股-股债利差（沪深300 盈利收益率 - 10Y 国债收益率）
- **签名与关键参数**：`ak.stock_ebs_lg()`，无参数。
- **返回字段（实测）**：5199 行 × 4 列：`日期, 沪深300指数, 股债利差, 股债利差均线`，历史自 2005-04-08 起。首行：2005-04-08，沪深300=1003.45，股债利差=0.022656。
- **限制**：无门槛。
- **底层来源与稳定性**：legulegu.com；同站接口本次有 2 个失效，该接口幸免（其解析路径未走 `unit="ms"`），但整体 legulegu 系稳定性差，使用需加兜底。
- **实测记录（2026-09-06）**：成功，0.35s。
- **本系统映射**：未在用。可扩展为市场估值温度计（择时/仓位参考）。

#### `stock_a_congestion_lg`
- **库/定位**：akshare / 乐咕乐股-A 股拥挤度（成交额占比口径）
- **签名与关键参数**：`ak.stock_a_congestion_lg()`，无参数。
- **返回字段（实测）**：3638 行 × 3 列：`date, close, congestion`，历史自 2011-09-05 起。首行：2011-09-05，close=2478.74，congestion=0.3167。
- **限制**：无门槛。
- **底层来源与稳定性**：legulegu.com，同 ebs_lg 的稳定性警示。
- **实测记录（2026-09-06）**：成功，0.29s。
- **本系统映射**：未在用。可扩展为市场情绪/拥挤度指标。

### akshare 申万行业

#### `sw_index_first_info`
- **库/定位**：akshare / 申万宏源研究-一级行业信息（含当日估值快照）
- **签名与关键参数**：`ak.sw_index_first_info()`，无参数。
- **返回字段（实测）**：31 行 × 7 列：`行业代码, 行业名称, 成份个数, 静态市盈率, TTM(滚动)市盈率, 市净率, 静态股息率`。首行 801010.SI 农林牧渔：成份 104，静态 PE 32.1，TTM PE 33.97，PB 2.04，股息率 2.13。
- **限制**：无门槛；仅当前快照，无历史。
- **底层来源与稳定性**：申万宏源研究所官网（swsresearch.com），官方口径，稳定性较好。
- **实测记录（2026-09-06）**：成功，1.01s。
- **本系统映射**：未在用。可扩展为 `industry_valuation` 的官方口径校准源（当前行业估值由 daily_basic 聚合）。

#### `index_analysis_daily_sw`
- **库/定位**：akshare / 申万宏源研究-指数日度分析（行业 PE/PB/换手/股息率历史）
- **签名与关键参数**：`ak.index_analysis_daily_sw(symbol="一级行业", start_date="20260901", end_date="20260904")`；symbol ∈ {市场表征, 一级行业, 二级行业, 风格指数}。示例：
  ```python
  ak.index_analysis_daily_sw(symbol="一级行业", start_date="20260901", end_date="20260904")
  ```
- **返回字段（实测）**：31 行（单日一级行业全集）× 14 列：`指数代码, 指数名称, 发布日期, 收盘指数, 成交量, 涨跌幅, 换手率, 市盈率, 市净率, 均价, 成交额占比, 流通市值, 平均流通市值, 股息率`。首行 801010 农林牧渔 2026-09-01：PE=85.1，PB=2.53，换手率 7.31%，股息率 1.74。
- **限制**：无门槛；**symbol 必须是上述四个枚举值**，传错（如"全部行业"）时返回空结果并在后续列访问抛 `KeyError: '发布日期'`（报错信息不直观，是本接口的坑）。
- **底层来源与稳定性**：申万宏源研究所官网 API，官方口径，稳定性较好。
- **实测记录（2026-09-06）**：首轮 `symbol="全部行业"` 失败（`KeyError: '发布日期'`）；改 `symbol="一级行业"` 成功，0.34s。
- **本系统映射**：未在用。**可替代/扩展** `industry_valuation`：申万官方行业 PE/PB 日度历史，比 daily_basic 自聚合更权威；与 tushare `index_classify`(SW2021) 的 801xxx 代码天然对齐。

---

### 本节实测统计（2026-09-06）

- **tushare 12/12 成功**（express 返回 0 行属正常空结果：茅台不发业绩快报）。
- **akshare 13 个目标接口：11 成功 / 2 失败**。
  - 首轮 3 个失败为调用侧问题，修正后成功：`stock_financial_abstract_new_ths` → 现名 `stock_financial_abstract_ths`；`stock_financial_analysis_indicator_em` → A 股无 `_em` 版，用新浪源 `stock_financial_analysis_indicator`；`index_analysis_daily_sw` 的 symbol 须用枚举值（"一级行业"等），传错报 `KeyError: '发布日期'`。
  - 2 个实质失败均为 legulegu 系：`stock_index_pe_lg`、`stock_a_below_net_asset_statistics`，源端日期格式变更 + akshare 1.16.72 按 `unit="ms"` 解析 → `ValueError`，当前版本不可用。
- **合计：成功 23 / 失败 2**（共 25 个接口）。


---

## 三、宏观与债券

> 实测环境：akshare 1.16.72 / tushare 1.4.21，实测日期 2026-09-06（周日）。
> 每个接口调用间 sleep ≥1.5s，子进程隔离 + 超时控制；tushare 每接口仅调 1 次。

### `bond_zh_us_rate`（akshare）

- **库/定位**：akshare · 中美国债收益率对比（债券 > 中国债券）。
- **签名与关键参数**：`ak.bond_zh_us_rate()`，无参数，全量返回。

```python
import akshare as ak
df = ak.bond_zh_us_rate()   # 9336 行
```

- **返回字段（实测）**：`日期, 中国国债收益率2年, 中国国债收益率5年, 中国国债收益率10年, 中国国债收益率30年, 中国国债收益率10年-2年, 中国GDP年增率, 美国国债收益率2年/5年/10年/30年, 美国国债收益率10年-2年, 美国GDP年增率`；共 9336 行（1990-12-19 ~ 2026-09-04）。
- **限制**：无参数、每次全量拉取（约 5s）；**当前版本只有 2Y/5Y/10Y/30Y 四档，无 1Y、3Y 列**；`中国GDP年增率`/`美国GDP年增率` 列近年全为 NaN（名存实亡）。
- **底层来源与稳定性**：东方财富数据中心。列结构历史上变过（早年版本有 1 年档），**下游必须做列存在性判断**。
- **实测记录（2026-09-06）**：成功，4.8s；最新日期 2026-09-04（上周五），10Y=1.6804%、30Y=2.1335%、5Y=1.4014%、2Y=1.2430%，新鲜度良好（T+0 收盘后更新）。
- **本系统映射**：**曾用，已下线** —— collector `TreasuryCurveSource` 原采此接口落库 `treasury_yield_curve`。**⚠️ 隐患（已于 2026-09-06 修复）**：collector 的 `TERMS` 映射引用了 `中国国债收益率1年`/`3年` 两列，当前 akshare 版本已无这两列，因 `if col in df.columns` 防御性判断而**静默只采到 5Y/10Y/30Y**（2Y 存在但未映射）。修复方案不是最初设想的 `bond_china_close_return`，而是更优的 `bond_china_yield`（见下一张卡片）。**勿回退到此接口**。

### `bond_china_close_return`（akshare）

- **库/定位**：akshare · 中债国债收盘收益率曲线（完整期限结构）。
- **签名与关键参数**：`ak.bond_china_close_return(symbol="国债", period="1", start_date="20260901", end_date="20260904")`。`period ∈ {0.1, 0.5, 1}`（期限间隔，年）；**start/end 必填且区间不得超过 1 个月**；`symbol` 支持国债/国开债等。

```python
import akshare as ak
df = ak.bond_china_close_return(symbol="国债", period="1",
                                start_date="20260901", end_date="20260904")
```

- **返回字段（实测）**：`日期, 期限, 到期收益率, 即期收益率, 远期收益率`；50 行（期限 0.083 年 ~ 46 年，1 年间隔），覆盖 1Y/3Y/5Y/10Y/30Y 全档。
- **限制**：单次区间 ≤1 个月，历史长区间需循环；调用较慢（实测 20s/次）；无参数默认值直接调用会因默认日期（2023-11-01）无数据而抛 `KeyError: 'newDateValue'`。
- **底层来源与稳定性**：中国货币网（chinamoney.com.cn）中债收益率曲线页，官方性强；接口 2025 年改版过一次（旧签名 `indicator/period` 已失效）。
- **实测记录（2026-09-06）**：成功，20.1s；最新 2026-09-04；1Y 附近 ≈1.24%（期限 1.0 行），30Y=2.31%（即期）。
- **本系统映射**：可作 `treasury_yield_curve` 的备源。代价是慢（20s/次）、需按月循环——实际修复选了更快的 `bond_china_yield`（见下一张卡片），本接口退居备源候选。

### `bond_china_yield`（akshare）—— 本系统国债曲线现用源（2026-09-06 起）

- **库/定位**：akshare · 中债信息网（chinabond）各券种收益率曲线，含完整期限档。
- **签名与关键参数**：`ak.bond_china_yield(start_date="20260901", end_date="20260904")`，按区间查询。

```python
import akshare as ak
df = ak.bond_china_yield(start_date="20260901", end_date="20260904")
df = df[df["曲线名称"] == "中债国债收益率曲线"]   # 必须过滤，否则混入国开债/AAA 等其他曲线
```

- **返回字段（实测）**：`曲线名称, 日期, 3月, 6月, 1年, 3年, 5年, 7年, 10年, 30年`；每日期多行（各券种曲线），**必须按 `曲线名称 == "中债国债收益率曲线"` 过滤**。
- **限制**：单次区间上限约 1 年（实测 362 天正常、368 天返回空），长区间需切块；历史起点 2006-03-01；速度快（实测数日区间 <1s）。
- **底层来源与稳定性**：中国债券信息网官方发布，权威性高于东财二手转发。
- **实测记录（2026-09-06）**：成功。2026-09-01~04 国债曲线 4 天 × 5 期限齐全（1Y≈1.23%、3Y≈1.25%、5Y≈1.40%、10Y≈1.68%、30Y≈2.14%）；1 年区间 247 个交易日；2000 年区间返回空（早于 2006-03 起点）。
- **本系统映射**：**已在用** —— `TreasuryCurveSource`（`collector/collector/sources/plugins.py`）自 2026-09-06 起改用此接口，按 180 天切块拉取，落库 `treasury_yield_curve` 的 1Y/3Y/5Y/10Y/30Y 全档；期限列缺失时显式抛 `SourceError`。

### `macro_china_gdp`（akshare）

- **库/定位**：akshare · 中国 GDP（季度）。
- **签名与关键参数**：`ak.macro_china_gdp()`，无参数。
- **返回字段（实测）**：`季度, 国内生产总值-绝对值, 国内生产总值-同比增长, 第一/二/三产业-绝对值, 第一/二/三产业-同比增长`；82 行，2006Q1 起，倒序。
- **限制**：仅 2006 年起；季度频率，官方发布后更新。
- **底层来源与稳定性**：东方财富数据中心（转引国家统计局）。
- **实测记录（2026-09-06）**：成功，0.6s；最新 `2026年第1-2季度`（累计口径，GDP 同比 4.7%）及 `2026年第1季度`（5.0%）。注意"第1-2季度"是**累计值**而非单季。
- **本系统映射**：可扩展（宏观择时：GDP 增速作基本面慢变量）。

### `macro_china_cpi`（akshare）

- **库/定位**：akshare · 中国 CPI（月度）。
- **签名与关键参数**：`ak.macro_china_cpi()`，无参数。
- **返回字段（实测）**：`月份, 全国-当月, 全国-同比增长, 全国-环比增长, 全国-累计, 城市-×4, 农村-×4`；223 行，2008-01 起，倒序。
- **限制**：2008 年起；月度。
- **底层来源与稳定性**：东方财富（转引国家统计局）。
- **实测记录（2026-09-06）**：成功，0.8s；最新 **2026年07月**（全国同比 +0.5%）——滞后约 1 个月属正常发布节奏（8 月数据 9 月上旬发布）。
- **本系统映射**：可扩展（宏观择时/通胀因子）。

### `macro_china_ppi`（akshare）

- **库/定位**：akshare · 中国 PPI（月度）。
- **签名与关键参数**：`ak.macro_china_ppi()`，无参数。
- **返回字段（实测）**：`月份, 当月, 当月同比增长, 累计`；247 行，2006-01 起，倒序。
- **限制**：月度。
- **底层来源与稳定性**：东方财富（转引国家统计局）。
- **实测记录（2026-09-06）**：成功，0.5s；最新 2026年07月（同比 +3.5%）。
- **本系统映射**：可扩展（PPI 与工业企业利润、周期板块联动）。

### `macro_china_pmi`（akshare）

- **库/定位**：akshare · 中国官方 PMI（月度）。
- **签名与关键参数**：`ak.macro_china_pmi()`，无参数。
- **返回字段（实测）**：`月份, 制造业-指数, 制造业-同比增长, 非制造业-指数, 非制造业-同比增长`；224 行，2008-01 起，倒序。
- **限制**：仅官方 PMI（财新 PMI 需另接口 `macro_china_cxin_pmi` 等）。
- **底层来源与稳定性**：东方财富（转引统计局/物流与采购联合会）。
- **实测记录（2026-09-06）**：成功，0.5s；最新 **2026年08月**（制造业 49.8）——PMI 月末发布，新鲜度最好的宏观指标。
- **本系统映射**：可扩展（PMI 是 ERP/仓位择时最常用的领先指标，月频低噪）。

### `macro_china_shrzgm`（akshare）—— 实测失败

- **库/定位**：akshare · 社会融资规模增量统计（月度）。
- **签名与关键参数**：`ak.macro_china_shrzgm()`，无参数。
- **返回字段（实测）**：未取得（两次尝试均失败）。
- **限制**：—
- **底层来源与稳定性**：东方财富数据中心接口。本次实测 150s 超时一次、重试 150s 后返回非 JSON（`JSONDecodeError: Expecting value: line 1 column 1 (char 0)`），判断为东财侧限流/反爬或接口变更，**稳定性差**。
- **实测记录（2026-09-06）**：失败 ×2（超时 150s → 重试 150.9s 后 JSONDecodeError）。
- **本系统映射**：可扩展但**不建议依赖此接口**；社融替代：tushare `cn_sf`（社融，积分制）或人行官网。若做宏观择时需要社融，建议直接换源。

### `macro_china_supply_of_money`（akshare）

- **库/定位**：akshare · 中国货币供应量 M0/M1/M2（月度）。
- **签名与关键参数**：`ak.macro_china_supply_of_money()`，无参数。
- **返回字段（实测）**：`统计时间, 货币和准货币（广义货币M2）, M2同比增长, 货币(狭义货币M1), M1同比增长, 流通中现金(M0), M0同比增长, 活期存款, 准货币, 定期存款, 储蓄存款, 其他存款` 及各自同比，共 17 列；583 行，1978-01 起（1978–1996 多数列 NaN），倒序。`统计时间` 为 `2026.7` 浮点格式（需自行解析）。
- **限制**：部分细分列（活期/定期/储蓄存款）近年为 NaN。
- **底层来源与稳定性**：东方财富（转引中国人民银行）。
- **实测记录（2026-09-06）**：成功，3.8s；最新 2026.7（M2 同比 7.7%，M1 同比 4.0%）。
- **本系统映射**：可扩展（M1−M2 剪刀差是经典 A 股流动性择时信号）。

### `macro_china_lpr`（akshare）

- **库/定位**：akshare · LPR 报价 + 历史贷款基准利率。
- **签名与关键参数**：`ak.macro_china_lpr()`，无参数。
- **返回字段（实测）**：`TRADE_DATE, LPR1Y, LPR5Y, RATE_1, RATE_2`；1575 行，1991-04-21 起，升序。`LPR1Y/LPR5Y` 仅 2019-08 LPR 改革后有值；早期为 `RATE_1/RATE_2`（基准贷款利率）。
- **限制**：月度（每月 20 日报价）。
- **底层来源与稳定性**：东方财富（转引全国银行间同业拆借中心）。
- **实测记录（2026-09-06）**：成功，1.4s；最新 2026-08-20（LPR1Y=3.0%，LPR5Y=3.5%）。
- **本系统映射**：可扩展（利率环境因子；LPR5Y 与地产链相关）。

### `macro_china_shibor_all`（akshare）

- **库/定位**：akshare · Shibor 全期限报价（日频）。
- **签名与关键参数**：`ak.macro_china_shibor_all()`，无参数。
- **返回字段（实测）**：`日期, O/N-定价, O/N-涨跌幅, 1W/2W/1M/3M/6M/9M/1Y-定价, …-涨跌幅`，共 17 列；2361 行，2015-05-08 起（2015–2017 间记录稀疏），升序。
- **限制**：历史仅到 2015 年；涨跌幅单位是 BP。
- **底层来源与稳定性**：东方财富（转引 Shibor 官网）。
- **实测记录（2026-09-06）**：成功，0.6s；最新 2026-09-04（O/N=1.362%，1Y=1.48%），日频 T+0 新鲜。
- **本系统映射**：可扩展（资金面松紧度，可解释短端利率与 ERP 联动）。

### `macro_china_fx_reserves_yearly`（akshare）

- **库/定位**：akshare · 中国外汇储备（名为 yearly，实为**逐次公布的事件流**）。
- **签名与关键参数**：`ak.macro_china_fx_reserves_yearly()`，无参数。
- **返回字段（实测）**：`商品, 日期, 今值, 预测值, 前值`；132 行，2014-01-15 起，升序。
- **限制**：⚠️ **数据疑似停更**：最新行日期 2025-09-07 且 `今值` 为 NaN（距今约 1 年）；最后一条有效值是 2025-08-07（32920 亿美元）。接口名有误导性，实际是月度公布记录。
- **底层来源与稳定性**：金十数据（非官方直连），稳定性差。
- **实测记录（2026-09-06）**：调用成功（1.8s），但数据滞后约 1 年，视为**不可用**。
- **本系统映射**：不建议接入；外储需求低，如需可换东财其他接口或人行数据。

### `macro_china_urban_unemployment`（akshare）—— 实测失败

- **库/定位**：akshare · 城镇调查失业率（月度）。
- **签名与关键参数**：`ak.macro_china_urban_unemployment()`，无参数。
- **实测记录（2026-09-06）**：失败 ×2（均 `JSONDecodeError: Expecting value: line 1 column 1 (char 0)`，0.5s 快速失败），东财侧接口变更/反爬。
- **本系统映射**：可扩展但需换源；优先级低。

### `yc_cb`（tushare）

- **库/定位**：tushare pro · 中债国债收益率曲线（到期/即期，完整期限）。
- **签名与关键参数**：

```python
import tushare as ts
pro = ts.pro_api(token)
df = pro.yc_cb(ts_code="1001.CB", curve_type="0", trade_date="20260904")
# curve_type: 0-到期, 1-即期; 单次最大 2000 行，可按 trade_date/start_date/end_date 循环
```

- **返回字段（文档）**：`trade_date, ts_code, curve_name, curve_type, curve_term, yield`。
- **限制**：**单独权限接口，不在常规积分体系内**——[官方文档](https://tushare.pro/document/2?doc_id=201)注明"属于单独的权限接口，请在群里联系群主或管理员"。
- **底层来源与稳定性**：中债估值中心数据（官方级质量）。
- **实测记录（2026-09-06）**：失败（0.4s）：`Exception: 抱歉，您没有接口(yc_cb)访问权限`（积分不足/未开通专项权限，原样记录，仅调 1 次）。
- **本系统映射**：**潜在最优备源**——字段与 `treasury_yield_curve(trading_day, term, yield)` 天然同构，curve_term 覆盖全期限（含 1Y/3Y）；但需先解决专项权限，短期不可行，现用源为 `bond_china_yield`（2026-09-06 起）。

### tushare 宏观类接口（文档依据，未实测）

tushare 宏观接口较少且多为积分门槛，依据官方文档列出，供后续接入参考：

| 接口 | 内容 | 权限（文档） |
|---|---|---|
| `cn_gdp` | GDP 季度 | 600 积分，单次最大 10000 行（[doc_id=227](https://tushare.pro/document/2?doc_id=227)） |
| `cn_cpi` | CPI（全国/城市/农村） | 单次最大 5000 行（[doc_id=228](https://tushare.pro/document/2?doc_id=228)） |
| `cn_pmi` | 采购经理人指数 | 5000 积分，单次最大 2000 行（[doc_id=325](https://tushare.pro/document/2?doc_id=325)） |
| `shibor` / `shibor_lpr` / `libor` / `hibor` | 银行间拆借利率 / LPR | 积分制（文档未逐一核分值） |
| `us_tycr` | 美国国债收益率曲线（日频） | 120 积分（[doc_id=219](https://tushare.pro/document/2?doc_id=219)） |
| `cn_schedule` | 中国经济数据发布日历 | 单次最大 3000 条（[doc_id=461](https://tushare.pro/document/2?doc_id=461)） |

宏观数据 akshare 免费且覆盖已够（GDP/CPI/PPI/PMI/M2/LPR/Shibor 全部实测可用），tushare 宏观接口仅在已持高积分账号、追求稳定性 SLA 时考虑。

---

### 实测统计（2026-09-06）

- **akshare 12 个**：成功 10（`bond_zh_us_rate`、`bond_china_close_return`、`macro_china_gdp`、`macro_china_cpi`、`macro_china_ppi`、`macro_china_pmi`、`macro_china_supply_of_money`、`macro_china_lpr`、`macro_china_shibor_all`、`macro_china_fx_reserves_yearly`¹）；失败 2（`macro_china_shrzgm` 超时+JSON 错误、`macro_china_urban_unemployment` JSON 错误）。
- **tushare 1 个**：失败 1（`yc_cb` 无专项权限，报错原样记录）。
- **合计：成功 10 / 失败 3**（共 13 个实测接口；另有 6 个 tushare 宏观接口仅文档列示未实测）。

¹ `macro_china_fx_reserves_yearly` 调用成功但数据停更近 1 年，计为调用成功、业务不可用。

**关键坑汇总**：

1. `bond_zh_us_rate` 当前版本**无 1Y/3Y 列**（只有 2Y/5Y/10Y/30Y），collector `TERMS` 中 1Y/3Y 映射静默落空，只采到 5Y/10Y/30Y —— **已于 2026-09-06 修复**（改用 `bond_china_yield` + 缺列显式报错）。
2. `bond_china_close_return` 必须显式传 `start_date/end_date`（区间 ≤1 个月），默认参数直接调会 `KeyError: 'newDateValue'`。
3. 东财系宏观接口（`shrzgm`、`urban_unemployment`）反爬/变更频繁，社融与失业率需另寻稳定源。
4. `macro_china_fx_reserves_yearly` 名为年度实为事件流且停更近一年。
5. tushare `yc_cb` 是单独权限接口，常规积分不可用。


---

# akshare 与 tushare 接口详析 · 四

## 四、新闻、公告与研报

> 实测日期：2026-09-06；环境：akshare 1.16.72 / tushare 1.4.21（Python 3.13，collector/.venv）。
> 调用间 sleep ≥1.5s，单次超时 90s；tushare 每接口仅调 1 次。失败均为多次复现后的稳定结论。

### `stock_news_em`（akshare）
- **库/定位**：akshare · 东方财富个股新闻（按股票代码搜新闻，官方宣称"最近 100 条"）。
- **签名与关键参数**：`ak.stock_news_em(symbol: str = "300059")`，symbol 为 6 位股票代码。
  ```python
  import akshare as ak
  df = ak.stock_news_em(symbol="300059")
  ```
- **返回字段（实测）**：未取到（接口当前不可用）。按源码应返回 `关键词/新闻标题/新闻内容/发布时间/文章来源/新闻链接`。
- **限制**：单页 pageSize=100，无时间范围参数，只取"最新一页"。
- **底层来源与稳定性**：`http://search-api-web.eastmoney.com/search/jsonp`（`akshare/news/news_stock.py`）。**1.16.72 封装已失效（实测根因见下）**。
- **实测记录（2026-09-06）**：❌ 3 次调用均 0.1s 内 `JSONDecodeError: Expecting value: line 1 column 1 (char 0)`。根因已定位：akshare 封装**不带 User-Agent**，而东财该接口对无 UA 请求返回 HTTP 200 + **空 body**，导致 JSON 解析失败。同一 endpoint 带上 UA 后 http/https 均正常返回（`hitsTotal: 233`，最新一条《23.31亿元资金今日流入非银金融股》2026-09-03 17:03:00）。即：**底层接口活着，akshare 这层壳坏了**；自行 requests 调用时务必带 `User-Agent`。
- **本系统映射**：**已在用（等价能力）**——后端 Java 直连同一 `search-api-web.eastmoney.com/search/jsonp` 支撑 Agent 工具 `get_news`，不经过 akshare，故不受此 bug 影响。Python 侧若需个股新闻，应照后端方式直连并带 UA，不要依赖此封装。

### `stock_info_global_em`（akshare）
- **库/定位**：akshare · 东方财富 7×24 全球财经快讯。
- **签名与关键参数**：`ak.stock_info_global_em()`，无参数。
  ```python
  import akshare as ak
  df = ak.stock_info_global_em()   # 最近 200 条
  ```
- **返回字段（实测）**：`标题 / 摘要 / 发布时间 / 链接`，**200 行**。
- **限制**：固定返回最近 200 条（本次覆盖约 25.5 小时，2026-09-05 11:04 ~ 09-06 12:43），无分页/时间参数。
- **底层来源与稳定性**：`https://np-weblist.eastmoney.com/comm/web/getFastNewsList`（页面 `kuaixun.eastmoney.com/7_24.html`）。与 research/01 结论一致：该接口稳定可用。
- **实测记录（2026-09-06）**：✅ 0.8s，最新一条《劳尔·卡斯特罗出席古巴内务部活动》12:43:48，距实测时刻仅数分钟，时效性好。
- **本系统映射**：**可扩展**——市场快讯流的首选源：免费、无需签名、分钟级时效、带摘要和原文链接。适合落库做"7×24 快讯"页或喂给 Agent 做盘面事件问答。

### `stock_info_global_cls`（akshare）
- **库/定位**：akshare · 财联社电报（盘中快讯，市场公认时效最强的一档）。
- **签名与关键参数**：`ak.stock_info_global_cls(symbol: str = "全部")`；symbol 取 `全部/重点/A股/港股/美股/公司/基金/期货/外汇/债券` 等栏目。
  ```python
  import akshare as ak
  df = ak.stock_info_global_cls(symbol="全部")
  ```
- **返回字段（实测）**：未取到。按文档应为 `标题 / 内容 / 发布日期 / 发布时间`。
- **限制**：akshare 封装内部分页拉取约 20 页，无显式条数上限参数。
- **底层来源与稳定性**：`https://www.cls.cn/nodeapi/telegraphList`（**老接口，需签名**）。research/01 已验证 `cls.cn/v1/roll/get_roll_list` 带本地签名可用；而 akshare 仍走 nodeapi 老路径，本次实测直接挂起——**可认为 akshare 封装已失效**。
- **实测记录（2026-09-06）**：❌ 两次调用分别 90s、40s 超时挂起（连接无响应，非报错）。akshare 此接口不可用。
- **本系统映射**：**可扩展（绕过 akshare）**——财联社快讯价值高（A 股盘中消息最快一档），但应自行实现 `v1/roll/get_roll_list` + 本地签名（research/01 已打通），不要依赖此封装。

### `stock_info_global_sina`（akshare）
- **库/定位**：akshare · 新浪财经 7×24 直播快讯。
- **签名与关键参数**：`ak.stock_info_global_sina()`，无参数。
  ```python
  import akshare as ak
  df = ak.stock_info_global_sina()   # 仅 20 条
  ```
- **返回字段（实测）**：`时间 / 内容`，**仅 20 行**（无标题、无链接）。
- **限制**：固定返回最新 20 条，覆盖窗口很短（本次约 1.4 小时），是六个快讯源里单次信息量最小的。
- **底层来源与稳定性**：`https://zhibo.sina.com.cn/api/zhibo/feed`（页面 `finance.sina.com.cn/7x24`）。老牌接口，多年稳定。
- **实测记录（2026-09-06）**：✅ 0.1s（最快），最新一条《福建莆田市区22个积水点已经完全退水》12:57:50，分钟级时效。
- **本系统映射**：**可替代/兜底**——快讯流的轻量兜底源：结构最简单、响应最快，适合做东财快讯的交叉验证或降级备份；信息量小，不宜做主源。

### `stock_info_global_futu`（akshare）
- **库/定位**：akshare · 富途牛牛 7×24 快讯。
- **签名与关键参数**：`ak.stock_info_global_futu()`，无参数。
  ```python
  import akshare as ak
  df = ak.stock_info_global_futu()   # 最近 50 条
  ```
- **返回字段（实测）**：`标题 / 内容 / 发布时间 / 链接`，**50 行**（注意：部分条目 `标题` 为空字符串，仅 `内容` 有值）。
- **限制**：固定最近 50 条（本次覆盖约 7.5 小时）。
- **底层来源与稳定性**：`https://news.futunn.com/news-site-api/main/get-flash-list`（页面 `news.futunn.com/main/live`）。港美股资讯起家的源，全球宏观/海外事件占比高于东财。
- **实测记录（2026-09-06）**：✅ 0.5s，最新一条《上半年券商净利润合计超1386亿元，自营转型驱动行业高景气》12:40:44，分钟级时效。
- **本系统映射**：**可扩展**——与东财快讯互补：富途的海外/港股/宏观内容更全，可作为快讯流第二主源；消费时注意标题可能为空（用内容截断兜底）。

### `stock_info_global_ths`（akshare）
- **库/定位**：akshare · 同花顺财经实时新闻。
- **签名与关键参数**：`ak.stock_info_global_ths()`，无参数。
  ```python
  import akshare as ak
  df = ak.stock_info_global_ths()   # 仅 20 条
  ```
- **返回字段（实测）**：`标题 / 内容 / 发布时间 / 链接`，**20 行**。
- **限制**：固定最新 20 条（本次覆盖约 3.5 小时）。历史上同花顺反爬严格（hexin-v 签名），该接口时好时坏。
- **底层来源与稳定性**：`https://news.10jqka.com.cn/tapp/news/push/stock`（tapp 移动端推送接口，页面 `news.10jqka.com.cn/realtimenews.html`）。移动端 API 目前**未触发反爬**。
- **实测记录（2026-09-06）**：✅ 意外成功，0.2s，最新一条《河北省首个欧盟NG认证落户深泽经济开发区》12:53:19，分钟级时效。
- **本系统映射**：**可扩展（第三备份源）**——当前可用但历史上不稳，建议只做快讯聚合的补充源并加失败隔离；若未来返回 403/签名校验错误，直接摘除即可。

### `stock_news_main_cx`（akshare）
- **库/定位**：akshare · 财新网财经要闻精选。
- **签名与关键参数**：`ak.stock_news_main_cx()`，无参数。
  ```python
  import akshare as ak
  df = ak.stock_news_main_cx()
  ```
- **返回字段（实测）**：未取到。按文档应为 `tag / summary / interval / pub_time / url`。
- **限制**：单次拉取财新首页要闻，条数由对方页面决定（通常几十条）。
- **底层来源与稳定性**：`https://cxdata.caixin.com/api/dataplus/sjtPc/jxNews`（财新数据通 API）。**已失效**：该 API 现返回 404。
- **实测记录（2026-09-06）**：❌ 3.3s，`akshare.exceptions.APIError: API request failed. Status code: 404`。财新侧已下线或更换该端点，akshare 1.16.72 未跟进修复。
- **本系统映射**：**不可用，放弃**。财新内容偏深度宏观，对本系统（A 股个股投研）非刚需；若未来需要深度报道源，应另找 RSS 或官方渠道，勿等 akshare 修复。

### `news_economic_baidu`（akshare）
- **库/定位**：akshare · 百度股市通财经日历（每日经济数据/事件公布日程：CPI、利率决议等）。
- **签名与关键参数**：`ak.news_economic_baidu(date: str = "20241107")`，date 为 `YYYYMMDD`。
  ```python
  import akshare as ak
  df = ak.news_economic_baidu(date="20260905")
  ```
- **返回字段（实测）**：未取到。按文档应为 `日期 / 时间 / 地区 / 事件 / 公布 / 预期 / 前值 / 重要性`。
- **限制**：按单日查询，需逐日循环。
- **底层来源与稳定性**：`https://finance.pae.baidu.com/api/financecalendar`（页面 `gushitong.baidu.com/calendar`）。**已失效（返回结构变更）**：akshare 按旧 JSON 结构取字段，触发 `TypeError: string indices must be integers`。
- **实测记录（2026-09-06）**：❌ 0.2s，`TypeError: string indices must be integers, not 'str'`——接口有响应但 JSON 结构已变，akshare 解析逻辑失配。
- **本系统映射**：**可扩展（待修/绕行）**——财经日历对"本周有什么数据要公布"类 Agent 问答有价值，但当前封装不可用；需要时自行解析百度 calendar API 新结构，或改用金十/汇通等日历源。

### `stock_zh_a_disclosure_report_cninfo`（akshare）
- **库/定位**：akshare · 巨潮资讯 A 股公告（法定信息披露官方平台，公告的权威源）。
- **签名与关键参数**：
  ```python
  import akshare as ak
  df = ak.stock_zh_a_disclosure_report_cninfo(
      symbol="000001",        # 股票代码
      market="沪深京",         # 沪深京 / 港股 / 三板 / 基金 / 债券
      keyword="",             # 标题关键词
      category="",            # 年报/半年报/重大事项等分类
      start_date="20260801", end_date="20260906",
  )
  ```
- **返回字段（实测）**：`代码 / 简称 / 公告标题 / 公告时间 / 公告链接`，本次（000001，2026-08-01~09-06）**10 行**，最新《关于职工董事任职资格核准的公告》2026-08-22，链接直达 `cninfo.com.cn` 公告详情页。
- **限制**：按代码+时间窗查询；单次时间跨度建议别太大（内部按窗口分页）；**接口不稳定（见实测）**。公告正文需再爬链接页/PDF。
- **底层来源与稳定性**：`http://www.cninfo.com.cn/new/hisAnnouncement/query`（巨潮历史公告查询 POST 接口）。老牌接口，但**偶发返回空 body → JSONDecodeError**，需重试。
- **实测记录（2026-09-06）**：⚠️ 首测 ❌（20.8s，`JSONDecodeError`，分页进度 0/1 时拿到空响应）；1 次复测 ✅（正常返回 10 行）。结论：**可用但需重试封装**。
- **本系统映射**：**可扩展（公告监控首选）**——系统目前不落库公告；接入巨潮可实现"持仓/自选股公告监控"（业绩、减持、停复牌等），是对投研助手最实用的增量能力之一。落地时务必加重试 + 按日切片拉取。

### `stock_research_report_em`（akshare）
- **库/定位**：akshare · 东方财富个股研报（券商研究报告列表 + 盈利预测）。
- **签名与关键参数**：`ak.stock_research_report_em(symbol: str = "000001")`，symbol 为 6 位股票代码。
  ```python
  import akshare as ak
  df = ak.stock_research_report_em(symbol="000001")
  ```
- **返回字段（实测）**：`序号 / 股票代码 / 股票简称 / 报告名称 / 东财评级 / 机构 / 近一月个股研报数 / {2026,2027,2028}-盈利预测-收益 / {2026,2027,2028}-盈利预测-市盈率 / 行业 / 日期 / 报告PDF链接`，**227 行**（000001 平安银行）。
- **限制**：返回该股的"全部"研报列表但实侧有截断（本次时间跨度 2017-03-07 ~ 2026-08-25，约 9 年）；早期报告盈利预测字段为空（NaN）；无分页参数。
- **底层来源与稳定性**：`https://reportapi.eastmoney.com/report/list`（页面 `data.eastmoney.com/report/stock.jshtml`），PDF 托管 `pdf.dfcfw.com`。稳定接口，响应快。
- **实测记录（2026-09-06）**：✅ 0.7s。最新一条《平安银行2026年中报点评：净息差企稳，财富管理带动中收增长》（太平洋证券，买入，2026-08-25）；盈利预测齐全（2026E EPS 2.15 / PE 5.17，2027E 2.28 / 4.87，2028E 2.40 / 4.64）。注意：**评级是"东财评级"口径归一，非券商原始评级**。
- **本系统映射**：**可扩展（研报摘要能力首选）**——一行调用拿到研报列表 + 三年盈利预测 + PDF 直链，可直接支撑 Agent"研报观点汇总 / 盈利预测对比"工具；PDF 链接可再接入摘要管线。数据新鲜度约滞后 1~2 周（最新报告 8-25 vs 实测 9-06），可接受。

### `news`（tushare）
- **库/定位**：tushare pro · 新闻快讯聚合（新浪/华尔街见闻等源的长文本快讯）。
- **签名与关键参数**：
  ```python
  pro = ts.pro_api(TOKEN)
  df = pro.news(src="sina", start_date="2026-09-06 00:00:00", end_date="2026-09-06 23:59:59")
  ```
  src 支持 `sina / wallstreetcn / 10jqka / eastmoney / yuncaijing / fenghuang / jinrongjie` 等。
- **返回字段（实测）**：未取到（权限不足）。按文档为 `datetime / content / title / channels`。
- **限制**：按时间段查询；**需要单独积分权限**。
- **底层来源与稳定性**：tushare 服务端聚合各家快讯源，稳定性取决于 tushare 服务本身；数据在其服务器侧落库后分发，天然有延迟。
- **实测记录（2026-09-06）**：❌ 0.0s 即被拒：`抱歉，您没有接口(news)访问权限，权限的具体详情访问：https://tushare.pro/document/1?doc_id=108`（实测结论：当前 token 积分档位不含此接口）。
- **本系统映射**：**可替代但不推荐**——同样的快讯内容 akshare 免费直采（东财/富途/新浪，见上）已能拿到且时效更好（直连源站 vs tushare 中转落库）。除非未来 tushare 积分到位且需要历史快讯回捞，否则不必为此升级积分。

### `major_news`（tushare）
- **库/定位**：tushare pro · 长篇新闻通讯（带正文全文的要闻，区别于快讯短文本）。
- **签名与关键参数**：
  ```python
  df = pro.major_news(src="", start_date="2026-09-06 00:00:00", end_date="2026-09-06 23:59:59", fields="")
  ```
  可按 src 过滤来源、fields 裁剪字段。
- **返回字段（实测）**：未取到（权限不足）。按文档为 `title / content / pub_time / src`。
- **限制**：按时间段查询；**需要更高积分权限**（官方文档标注 5000 积分档）。
- **底层来源与稳定性**：tushare 服务端聚合，同 `news`。
- **实测记录（2026-09-06）**：❌ 0.1s 即被拒：`抱歉，您没有接口(major_news)访问权限，权限的具体详情访问：https://tushare.pro/document/1?doc_id=108`（当前 token 积分档位不含此接口）。
- **本系统映射**：**不可用（权限不足），且无刚需**——长篇正文可用于舆情/NLP 分析，但本系统当前无舆情落库；若未来做舆情，优先级低于先把免费快讯流（东财/富途）和巨潮公告落地。

---

### 实测统计（2026-09-06）

| 结果 | 数量 | 接口 |
|---|---|---|
| ✅ 成功 | 6 | `stock_info_global_em`、`stock_info_global_sina`、`stock_info_global_futu`、`stock_info_global_ths`、`stock_zh_a_disclosure_report_cninfo`（首测失败、复测成功，不稳定）、`stock_research_report_em` |
| ❌ 失败 | 6 | `stock_news_em`（无 UA 空响应，封装 bug）、`stock_info_global_cls`（超时挂起，老接口失效）、`stock_news_main_cx`（404，财新端点下线）、`news_economic_baidu`（返回结构变更）、`news` / `major_news`（tushare 积分权限不足） |

合计 12 个接口：**成功 6 / 失败 6**。

关键结论：
1. **快讯流别走 tushare**：akshare 免费直采的东财（200 条）+ 富途（50 条）+ 新浪/同花顺（各 20 条）已覆盖需求且时效分钟级；tushare 两接口双双权限不足。
2. **akshare 新闻类封装损坏率不低**（6 个里 4 个坏：`stock_news_em`/`cls`/`cx`/`baidu`），接入任何 akshare 新闻接口前必须先实测，且生产侧要加失败隔离。
3. **本系统 `get_news` 不受影响**：后端直连东财 search 接口而非 akshare 封装；唯一教训是直连时**必须带 User-Agent**。
4. 增量能力优先级建议：巨潮公告监控（`stock_zh_a_disclosure_report_cninfo`，加重试）> 东财 7×24 快讯落库 > 东财研报摘要（`stock_research_report_em`）。


---

## 五、其它市场（扩展能力盘点）

> 本节为「后续迭代」能力盘点：系统当前只做 A 股股票，未来可能扩展基金（组合配置）、港美股、期货/期权、外汇等。每子类选 1–2 个代表接口实测，其余以接口清单形式列出。
> 实测环境：akshare 1.16.72、tushare 1.4.21，实测日期 2026-09-06（周日，部分行情源非交易时段数据为空属正常）。

### 5.0 本节核心结论（先读）

- **akshare 覆盖面远大于 tushare**：基金 65 个、期货 59 个、期权 40 个接口，且免费无门槛；tushare 在港美股/外汇等品类需单独积分权限（本次 token 实测：港股 `hk_basic`/`hk_daily` 意外可用，美股 `us_basic` 调用成功但返回 0 行）。
- **本次网络环境下东财（eastmoney）`push2` 全市场行情类接口全部失败**（`RemoteDisconnected`，重试仍失败）：`fund_etf_spot_em`、`stock_hk_spot_em`、`stock_hk_hist`、`stock_us_spot_em`、`option_current_em`、`index_global_spot_em` 无一幸免；但东财**单标的 K 线/基金净值**接口（`stock_us_hist`、`fund_open_fund_info_em`）正常。即：东财"列表快照"类端点对本机出口有反爬/断连，"历史明细"类端点正常。**新浪源（sina）接口全部正常**，可作为港美股/ETF 日线的可靠兜底。
- **tushare 本次 token 的权限面**：基金（`fund_basic`/`fund_nav`）、期货合约（`fut_basic`）、港股（`hk_basic`/`hk_daily`）均可用；美股 `us_basic` 返回空表（未报权限错，疑似分类/权限问题）。

---

### 5.1 基金

#### `fund_open_fund_info_em`
- **库/定位**：akshare / 开放式基金历史净值（东财）
- **签名与关键参数**：`ak.fund_open_fund_info_em(symbol="000001", indicator="单位净值走势")`；`indicator` 可选 `单位净值走势`/`累计净值走势`/`累计收益率走势`/`同类排名走势`/`同类排名百分比`/`分红送配详情`/`拆分详情`
- **返回字段（实测）**：`净值日期, 单位净值, 日增长率`；6000 行（000001 华夏成长 2001-12-18 至今全历史）
- **限制**：单基金调用，批量需自行循环+限速；按 `indicator` 分次取
- **底层来源与稳定性**：东财 `fund.eastmoney.com/pingzhongdata` 页面内嵌 JSON，长期稳定，是 akshare 基金类最常用的接口之一
- **实测记录（2026-09-06）**：成功，1.3s，样本 `2001-12-18, 1.0, 0.0`
- **本系统映射**：**可扩展——组合配置引入场外基金**：为基金持仓提供净值序列、收益率计算与回测输入；`分红送配`/`拆分` 指示器可用于复权处理

#### `fund_etf_spot_em`
- **库/定位**：akshare / ETF 全市场实时快照（东财）
- **签名与关键参数**：`ak.fund_etf_spot_em()`，无参数，返回全市场 ETF 快照
- **返回字段（实测）**：未取到（接口失败）；官方文档字段为 `代码, 名称, 最新价, 涨跌幅, 成交额, 流通市值, 总市值` 等
- **限制**：东财 `push2` 行情端点，**本机出口被断连**；数据量约千行
- **底层来源与稳定性**：东财实时行情 push2 接口；正常使用环境下稳定，但属易被限流/封 IP 的大快照端点
- **实测记录（2026-09-06）**：失败（两次），`ConnectionError: RemoteDisconnected`，0.2s 即被拒
- **本系统映射**：备选——ETF 自选行情页；需先解决东财快照端点的网络可达性，否则用 `fund_etf_hist_sina`（历史）/`fund_etf_spot_ths`（同花顺快照）替代

#### `fund_etf_hist_sina`（备选实测，替代失败的快照接口）
- **库/定位**：akshare / ETF 历史日线（新浪）
- **签名与关键参数**：`ak.fund_etf_hist_sina(symbol="sh510300")`；`symbol` 带交易所前缀（`sh`/`sz`）
- **返回字段（实测）**：`date, open, high, low, close, volume`；3471 行（510300 自 2012-05-28 上市至今）
- **限制**：无复权因子、无成交额；需知道带前缀的代码
- **底层来源与稳定性**：新浪财经 K 线接口，老牌免费源，稳定
- **实测记录（2026-09-06）**：成功，0.2s
- **本系统映射**：**可扩展——ETF 定投/回测**的历史行情输入，与现有 A 股 K 线处理管线同构

#### tushare `fund_basic` / `fund_nav`
- **库/定位**：tushare / 基金基础信息、基金净值（需 token，基础积分可用）
- **签名与关键参数**：`pro.fund_basic(market="E", status="L", limit=10)`（`market`: E场内/O场外）；`pro.fund_nav(ts_code="000001.OF", start_date="20250801", end_date="20250901")`
- **返回字段（实测）**：
  - `fund_basic`：25 列，含 `ts_code, name, management(管理人), custodian(托管人), fund_type, found_date, list_date, m_fee(管理费), c_fee(托管费), benchmark, status, market` 等
  - `fund_nav`：9 列，`ts_code, ann_date, nav_date, unit_nav, accum_nav, accum_div, net_asset, adj_nav(复权净值), update_flag`；22 行
- **限制**：单次 limit 默认 3000；`fund_nav` 不提供分钟数据；高并发受积分限速
- **底层来源与稳定性**：tushare 官方维护的清洗后数据，schema 稳定，适合落库
- **实测记录（2026-09-06）**：均成功，`fund_basic` 0.4s / `fund_nav` <0.1s；样本 `000001.OF 20250901 unit_nav=1.075 adj_nav=6.9569`
- **本系统映射**：**可扩展——基金组合落库首选**：相比 akshare 网页抓取，tushare 字段规整（尤其 `adj_nav` 直接给复权净值），适合作为 collector 的基金数据源

**akshare 基金类其他主要接口**（共 65 个，未逐个实测）：
- 实时/行情：`fund_etf_spot_ths`（同花顺 ETF 快照）、`fund_lof_spot_em`、`fund_etf_hist_em`、`fund_etf_hist_min_em`、`fund_lof_hist_em`、`fund_hk_fund_hist_em`（港股基金）
- 目录/筛选：`fund_name_em`（全基金名录）、`fund_open_fund_rank_em`（开放式基金排行）、`fund_money_rank_em`（货币基金排行）、`fund_new_found_em`（新基金）、`fund_purchase_em`（申购状态）
- 持仓/配置：`fund_portfolio_hold_em`（重仓股）、`fund_portfolio_industry_allocation_em`（行业配置）、`fund_scale_change_em`（规模变动）、`fund_hold_structure_em`（持有人结构）、`fund_aum_em`（基金公司管理规模）
- 评价/画像：`fund_rating_all`（评级）、`fund_manager_em`（基金经理）、`fund_individual_basic_info_xq`（雪球基金档案）、`fund_value_estimation_em`（盘中估值）
- 报告：`fund_report_asset_allocation_cninfo` / `fund_report_industry_allocation_cninfo`（巨潮定期报告）

**tushare 基金类其他主要接口**：`fund_daily`（场内基金日线）、`fund_adj`（复权因子）、`fund_div`（分红）、`fund_portfolio`（持仓明细）、`fund_share`（份额规模）、`fund_manager`（基金经理）。

---

### 5.2 期货

#### `futures_main_sina`
- **库/定位**：akshare / 期货主力合约日线（新浪）
- **签名与关键参数**：`ak.futures_main_sina(symbol="RB0", start_date="20250801", end_date="20250901")`；`symbol` 为品种代码+`0` 表示主力连续（如 `RB0` 螺纹钢、`CU0` 沪铜）
- **返回字段（实测）**：`日期, 开盘价, 最高价, 最低价, 收盘价, 成交量, 持仓量, 动态结算价`；22 行
- **限制**：仅主力连续合约（`X0`），非具体月份合约；无复权/换月处理，跨月有跳空
- **底层来源与稳定性**：新浪期货行情，免费稳定；具体合约明细另有 `futures_zh_daily_sina`
- **实测记录（2026-09-06）**：成功，0.7s，样本 `2025-08-01 RB0 收 3203，持仓 1760700 手`
- **本系统映射**：**可扩展——宏观/大宗联动分析**：黑色、有色、农产品主力走势可作为行业研究的背景数据工具接入 Agent

#### tushare `fut_basic`
- **库/定位**：tushare / 期货合约信息（需 token）
- **签名与关键参数**：`pro.fut_basic(exchange="SHFE", fut_type="1", limit=10)`；`fut_type`: 1期货/2期权；交易所含 SHFE/DCE/CZCE/CFFEX/INE/GFEX
- **返回字段（实测）**：15 列，含 `ts_code, symbol, exchange, name, fut_code, trade_unit, per_unit, quote_unit, list_date, delist_date, last_ddate` 等
- **限制**：只含合约元数据，行情需 `fut_daily`（更高积分）
- **底层来源与稳定性**：官方整理，schema 稳定
- **实测记录（2026-09-06）**：成功，<0.1s，样本含已摘牌合约（`NI1612.SHF`），即覆盖历史合约
- **本系统映射**：备选——若后续做期货，合约字典用 `fut_basic`，行情/持仓用 `fut_daily`/`fut_holding`（需积分升级）

**akshare 期货类其他主要接口**（共 59 个）：
- 行情：`futures_zh_spot`（全市场实时，需指定交易所）、`futures_zh_realtime`、`futures_zh_daily_sina`（具体合约日线）、`futures_zh_minute_sina`、`futures_hist_em`（东财历史）、`futures_global_spot_em`/`futures_global_hist_em`（外盘）
- 基本面/仓单：`futures_inventory_em`（库存）、`futures_shfe_warehouse_receipt`（上期所仓单）、`futures_spot_price`（期现基差）、`futures_dce_position_rank`（持仓排名）、`futures_hog_core`（生猪数据）
- 规则/杂项：`futures_contract_detail`、`futures_fees_info`（手续费）、`futures_rule`（交易规则）

**tushare 期货类其他主要接口**：`fut_daily`（日线行情）、`fut_holding`（持仓排名）、`fut_settle`（结算价）、`fut_wsr`（仓单日报）、`fut_mapping`（主力/连续合约映射）。

---

### 5.3 期权

#### `option_current_em`
- **库/定位**：akshare / 金融期权全市场实时行情（东财）
- **签名与关键参数**：`ak.option_current_em()`，无参数
- **返回字段（实测）**：未取到（接口失败）；官方文档字段含 `代码, 名称, 最新价, 涨跌额, 涨跌幅, 成交量, 持仓量, 隐含波动率, 剩余交易日, 行权价` 等
- **限制**：东财 push2 端点，本机被断连
- **底层来源与稳定性**：东财期权行情页，正常网络下可用
- **实测记录（2026-09-06）**：失败（两次），`ConnectionError: RemoteDisconnected`
- **本系统映射**：远期备选——若做 50ETF/300ETF 期权择时，需先解决东财快照可达性；同期可用 `option_finance_board`（新浪金融期权）替代

#### `option_comm_info`（备选实测）
- **库/定位**：akshare / 商品期权合约信息与费用（新浪+交易所整理）
- **签名与关键参数**：`ak.option_comm_info(symbol="螺纹钢期权")`；`symbol` 为品种名+"期权"
- **返回字段（实测）**：17 列，`期权品种, 现价, 涨/跌停板, 成交量, 类型, 权利金, 开仓/平昨/平今/行权(费用), 每跳毛利/元, 手续费(开+平), 每跳净利/元, 交易所, 更新时间`；110 行（螺纹钢期权全部在挂牌合约）
- **限制**：偏"合约档案+费用"视角，非逐笔行情；金融期权（ETF/股指）走 `option_finance_*`/`option_cffex_*` 系列
- **底层来源与稳定性**：新浪商品期权页 + 交易所费率，更新略滞后（样本费率更新于 2026-09-01）
- **实测记录（2026-09-06）**：成功，6.9s（偏慢），样本 `rb2610C2600 现价 502.0`
- **本系统映射**：远期备选——商品期权链数据；更可能用于"期权对标的商品价格的影响"类投研工具

**akshare 期权类其他主要接口**（共 40 个）：
- 金融期权（上交所 ETF 期权）：`option_finance_board`、`option_sse_list_sina`/`option_sse_codes_sina`（合约列表）、`option_sse_spot_price_sina`（行情）、`option_sse_daily_sina`、`option_sse_greeks_sina`（希腊字母）、`option_sse_expire_day_sina`（到期日）、`option_risk_indicator_sse`（风险指标）、`option_daily_stats_sse`
- 股指期权（中金所）：`option_cffex_hs300_list_sina`/`_spot_sina`/`_daily_sina`、对应 `sz50`、`zz1000` 系列
- 商品期权：`option_commodity_contract_sina`、`option_commodity_hist_sina`、`option_dce_daily`/`option_czce_daily`/`option_shfe_daily`/`option_gfex_daily`（各所每日行情）
- 分析：`option_value_analysis_em`、`option_risk_analysis_em`、`option_premium_analysis_em`（东财期权分析页）

**tushare 期权类主要接口**：`opt_basic`（期权合约信息）、`opt_daily`（期权日线行情，均需积分）。

---

### 5.4 港股

#### `stock_hk_spot_em` / `stock_hk_hist`
- **库/定位**：akshare / 港股实时快照与单标的历史日线（东财）
- **签名与关键参数**：`ak.stock_hk_spot_em()`；`ak.stock_hk_hist(symbol="00700", period="daily", start_date="20250801", end_date="20250901", adjust="")`，`adjust` 支持 `qfq`/`hfq`
- **返回字段（实测）**：未取到（均失败）；`stock_hk_hist` 官方字段为 `日期, 开盘, 收盘, 最高, 最低, 成交量, 成交额, 振幅, 涨跌幅, 涨跌额, 换手率`
- **限制**：东财端点，本机快照与历史均被断连（但同为东财的美股历史 `stock_us_hist` 正常，说明港股历史走了不同子域/更严的反爬）
- **底层来源与稳定性**：东财港股行情；正常网络下 `stock_hk_hist` 是常用稳定接口
- **实测记录（2026-09-06）**：两者均失败（各两次），`ConnectionError: RemoteDisconnected`
- **本系统映射**：可扩展——港股扩展的**首选数据源**（字段全、支持复权），但落地前必须验证部署环境的东财可达性；兜底见下

#### `stock_hk_daily`（备选实测，新浪）
- **库/定位**：akshare / 港股历史日线（新浪）
- **签名与关键参数**：`ak.stock_hk_daily(symbol="00700", adjust="")`；`adjust` 支持 `qfq`/`hfq`；无日期区间参数，全量返回
- **返回字段（实测）**：`date, open, high, low, close, volume`；5465 行（00700 自 2004-06-16 上市全历史）
- **限制**：字段少（无成交额/换手率）；全量返回需自行截取区间
- **底层来源与稳定性**：新浪港股 K 线，老牌免费源
- **实测记录（2026-09-06）**：成功，0.5s
- **本系统映射**：**可扩展——港股日线兜底源**，与现有 A 股 K 线管线同构

#### tushare `hk_basic` / `hk_daily`
- **库/定位**：tushare / 港股基础信息与日线（官方标注需单独权限，本次 token 实测可用）
- **签名与关键参数**：`pro.hk_basic(list_status="L", limit=10)`；`pro.hk_daily(ts_code="00700.HK", start_date="20250801", end_date="20250901")`
- **返回字段（实测）**：
  - `hk_basic`：12 列，`ts_code, name, fullname, enname, market, list_status, list_date, trade_unit, isin, curr_type` 等
  - `hk_daily`：11 列，`ts_code, trade_date, open, high, low, close, pre_close, change, pct_chg, vol, amount`；22 行
- **限制**：权限按账号积分/购买情况而异（文档标注"需单独权限"，本 token 可用不代表所有账号可用）；无复权
- **底层来源与稳定性**：官方清洗数据，schema 稳定，适合落库
- **实测记录（2026-09-06）**：均成功，`hk_basic` 0.1s / `hk_daily` <0.1s；样本 `00700.HK 20250901 close=605.0 pct_chg=1.42`
- **本系统映射**：**可扩展——港股落库首选**（若账号权限覆盖），否则 akshare 新浪兜底

**akshare 港股类其他主要接口**（共 21 个）：`stock_hk_spot`（旧版全市场）、`stock_hk_main_board_spot_em`（主板）、`stock_hk_hist_min_em`（分钟）、`stock_hk_index_spot_em`/`stock_hk_index_daily_em`（港股指数）、`stock_hk_ggt_components_em`（港股通成分）、`stock_hk_hot_rank_em`（东财人气榜）、`stock_hk_valuation_baidu`（估值）、`stock_hk_indicator_eniu`（亿牛市盈率等）。

**tushare 港股类其他主要接口**：`hk_tradecal`（港股交易日历）、`hk_hold`（沪深港通持股）、`hk_mins`（分钟行情）。

---

### 5.5 美股

#### `stock_us_spot_em` / `stock_us_hist`
- **库/定位**：akshare / 美股实时快照与单标的历史日线（东财）
- **签名与关键参数**：`ak.stock_us_spot_em()`；`ak.stock_us_hist(symbol="105.AAPL", period="daily", start_date="20250801", end_date="20250901", adjust="")`——注意 `symbol` 是东财内部代码（`市场代码.股票代码`，AAPL 为 `105.AAPL`），需先经快照或搜索接口拿映射
- **返回字段（实测）**：
  - `stock_us_spot_em`：失败
  - `stock_us_hist`：`日期, 开盘, 收盘, 最高, 最低, 成交量, 成交额, 振幅, 涨跌幅, 涨跌额, 换手率`，21 行
- **限制**：代码映射是最大使用门槛（不能直接传 `AAPL`）；`stock_us_hist` 支持 `qfq`/`hfq` 复权
- **底层来源与稳定性**：东财美股行情；历史端点（push2his）稳定，快照端点（push2）本机被断连
- **实测记录（2026-09-06）**：快照失败（两次，`RemoteDisconnected`）；历史成功，0.5s，样本 `AAPL 2025-08-01 收 202.38`
- **本系统映射**：**可扩展——美股自选股日线**：若只做少量标的的历史数据（如纳指七巨头跟踪），`stock_us_hist` 完全够用，代码映射可手工维护一张小表；全市场扫描则受快照端点限制

#### tushare `us_basic`
- **库/定位**：tushare / 美股基础信息（官方标注需单独权限）
- **签名与关键参数**：`pro.us_basic(classify="EQTY", limit=10)`；`classify`: EQTY股票/ETF
- **返回字段（实测）**：6 列 `ts_code, name, enname, classify, list_date, delist_date`，**0 行**
- **限制**：官方文档标注美股系列需单独权限；本次调用未报权限错误但返回空表，结论记为"不可用（返回空）"，原因可能是权限不足或分类参数不匹配
- **底层来源与稳定性**：官方数据
- **实测记录（2026-09-06）**：调用成功但 0 行，0.1s
- **本系统映射**：暂不可扩展——美股走 akshare 东财历史接口

**akshare 美股类其他主要接口**（共 7 个）：`stock_us_daily`（新浪日线）、`stock_us_hist_min_em`（分钟）、`stock_us_famous_spot_em`（知名中概/明星股快照）、`stock_us_pink_spot_em`（粉单市场）、`stock_us_spot`（旧版）。

**tushare 美股类其他主要接口**：`us_daily`（日线）、`us_tradecal`（交易日历），均预期需单独权限。

---

### 5.6 外汇 / 全球指数 / 加密货币

#### `fx_spot_quote`
- **库/定位**：akshare / 中国银行外汇牌价（即期）
- **签名与关键参数**：`ak.fx_spot_quote()`，无参数
- **返回字段（实测）**：`货币对, 买报价, 卖报价`；25 行货币对（USD/CNY、EUR/CNY、100JPY/CNY 等），但**买/卖报价全为 NaN**
- **限制**：非交易时段/非工作日中行不报价（实测日为周日），返回骨架无报价；仅中行牌价，非实时银行间行情
- **底层来源与稳定性**：中行外汇牌价页，接口本身稳定，但数据有效性依赖交易时段
- **实测记录（2026-09-06）**：成功，0.2s，25 行骨架、报价 NaN（周日无报价）
- **本系统映射**：备选——若未来引入港美股/多币种资产，可用于汇率换算展示；实时性要求高的场景需另找源（如 `currency_latest`/`fx_quote_baidu`）

**同子类补充实测**：
- `index_global_spot_em`（东财全球指数快照）：失败，`RemoteDisconnected`（东财 push2 同病）
- `currency_boc_sina`（中行外汇牌价历史，新浪）：失败，`ValueError: Length mismatch`——akshare 1.16.72 解析逻辑与中行页面当前结构不匹配，属库本身的解析 bug，升级版本或换接口规避
- 加密货币（未实测，纯盘点）：`crypto`（历史行情）、`crypto_js_spot`（实时）、`crypto_bitcoin_cme`（CME 比特币期货）、`crypto_bitcoin_hold_report`（持仓报告）

**akshare 外汇/全球指数其他主要接口**：
- 外汇：`fx_pair_quote`（货币对报价）、`fx_quote_baidu`（百度外汇）、`fx_swap_quote`（掉期）、`currency_latest`/`currency_history`/`currency_time_series`（汇率历史与时序）、`currency_convert`（换算）
- 全球指数：`index_global_name_table`（指数名录）、`index_global_hist_em`（东财历史）、`index_global_hist_sina`（新浪历史）

**tushare 外汇类主要接口**：`fx_daily`（外汇日线）、`fx_obasic`（海外基础信息），均预期需单独积分权限，本次未测。

---

### 5.7 实测统计（2026-09-06）

| | 成功 | 失败 |
|---|---|---|
| akshare | 7（fund_open_fund_info_em、fund_etf_hist_sina、futures_main_sina、option_comm_info、stock_hk_daily、stock_us_hist、fx_spot_quote*） | 7（fund_etf_spot_em、option_current_em、stock_hk_spot_em、stock_hk_hist、stock_us_spot_em、index_global_spot_em、currency_boc_sina） |
| tushare | 5（fund_basic、fund_nav、fut_basic、hk_basic、hk_daily）+ 1 空表（us_basic 0 行） | 0 |
| **合计** | **13**（*fx_spot_quote 成功但周日无报价，值为 NaN） | **7** |

**失败归因**：
- 6/7 的失败是东财 `push2` 类行情端点 `RemoteDisconnected`（重试无效），历史明细类东财端点与新浪源全部正常 → 部署前需验证目标网络对东财快照端点的可达性
- `currency_boc_sina` 为 akshare 解析 bug（与页面结构不匹配）
- `us_basic` 返回空表，疑似权限/分类问题，未报错


---

## 相关文档

- [01-财经新闻与股市数据来源参考.md](01-财经新闻与股市数据来源参考.md)：全部外部来源的全景速查（本文档是其中 akshare/tushare 两库的深化）
- [architecture/05-数据类型与来源.md](../architecture/05-数据类型与来源.md)：本系统当前实际使用的数据类型与来源
- 实测脚本与原始结果留档：`.cache/research02/`（`test_*.py`、`*_results.json`；`.cache` 为 gitignore 的本地目录，不入库）

