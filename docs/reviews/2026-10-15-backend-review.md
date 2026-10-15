# 后端深度代码审查报告（2026-10-15，第四轮）

- 范围：`backend/`（分支 `chore/code-review-2026-10-15`，Spring Boot 4 + DDD 洋葱分层 + ArchUnit 24 条规则）
- 方法：上轮 20 条目逐项复审（复发检查，对照 git 历史 aa850f56^ / 24c61aa9^ 逐字比对）+ 上轮 §5 遗留专项（L1/L2）+ 新角度抽查（eval/wiki/memory 域、BDD 质量）+ 全仓量化复扫（catch 模式、归一化 8 行窗口重复段、行数分布）
- 基线：main 823 个 Java 文件 / 48,420 行；测试 366 个（test 257 / integrationTest 86 / bdd 23 / testFixtures 6——bdd 为场景步骤类数），测试:主代码 ≈ 1.33:1

## 一、架构总览与上轮修复复审结论

包结构占比与第三轮基本一致（application 34% / infrastructure 25% / domain 18% / agent 15% / web 7%）。上轮 20 条目复审结论：**18 条完全达标，2 条等价但有可记录的微差**。逐项要点：

| 条目 | 复审结论 | 证据 |
|---|---|---|
| B1 AlertMailer 横切 | 达标（1 处微差） | 4 处调用点语义逐字等价；唯 EvalHarvester 原日志「eval 告警邮件降级失败」经统一后丢失 `eval ` 前缀（`AlertMailer.java:42` 统一话术，现 `EvalHarvester.java:432`）——日志检索性微降，非行为差异 |
| B2 ExtractionBatchService 模板 | 达标 | 与三份原版（git show aa850f56^）逐段比对：inputTokens=null 不计护栏（`ExtractionBatchService.java:110`）仅命中公告杂项空抽取路径（原版该路径亦不计量，等价）；LLM 不可用整批跳过（:106）先于护栏检查、与原版一致；FAILED 按日去重（:129-135）等价。单测覆盖 8 个批行为（`NewsExtractionServiceTest.java:70-222`） |
| B3 HarnessAgentFactory 合一 | 达标 | `baseBuilder`（`HarnessAgentFactory.java:99`）承载公共装配，两链只剩 5 处差异覆盖；prompt 字节比对测试仍在 |
| B4 parseAsOf 收敛 | 达标 | 单一实现 `ConfidenceScorer.parseAsOfInstant:156`，`ReportWorkflowOrchestrator.parseAsOf:224` 一行委托；fixture 五形态全覆（`ReportWorkflowOrchestratorTest.java:425-434`），半截时间统一 null（复盘 §3 确认消灭真实分歧） |
| B5 8 处 @Modifying 注释 | 达标 | 抽查 `ConversationJpaRepository.java:18-20`、`McpProviderJpaRepository.java:13-17`：均含「同事务无同实体 save/读回」安全性论证注释 |
| B6/B7 门面拆分 | 达标 | 见 §二.1；@Transactional 逐方法对照 15=15（上轮grep 16 系类注释 `**@Transactional 留在门面方法上**` 误计） |
| B8 EastmoneyQuotePort | 达标 | 接线完整：`OrchestratingMarketDataService.java:39` 构造注入、`EastmoneyQuotePortImpl` @Component 组合 `EastmoneyFormat.parseQuote`；domain 无残留 `parseQuote(JsonNode)` 重载 |
| B9 观测吞并计数 | 达标 | collection/record 双点计数（`ObservabilityAgentMiddleware.java:125`/`:144`），SimpleMeterRegistry 断言测试（`ObservabilityAgentMiddlewareTest.java:396-442`） |
| industry CSV 骨架 | 达标 | `CurationCsvParser` 承载文件级门禁+共享校验助手+错误聚齐；子类只剩表头/列索引/模板列长/行校验/产物（`FundingEventCsvParser.java:40-96`），无该上提未上提 |
| 文档同步 | 达标 | 01-分包规范与 AGENTS.md 已按 18 域/24 条规则写入 |

## 二、做得好的地方（附证据）

1. **门面拆分质量高，零「修出来的新问题」**。`ResearchApplicationService` 304 行（原 733）每个方法体严格一行委托，命令 record 全保留在门面（对外契约面不动）；7 个子服务只持有 repository/assembler/clock，**互相零注入**（逐文件核构造器），无横向调用新纠缠；门面方法间无互调 → 无自调用事务失效面。`IntelligenceQueryService`（366 行，原 637）与 `InvestTools`（237 行，原 712）同标准；InvestTools 的 @Tool 注解签名逐字节保留，eval 资产 hash 对账不受影响。
2. **模板方法切分点选得准**。`ExtractionBatchService` 把「易变的话术/阈值/落库」沉给子类、「不变的调度/护栏/去重」上提模板，且把公告特有的 try 内 lengthSkip 以 `ItemStep.lengthSkip()` 回报（`ExtractionBatchService.java:98-102`），注释明确「同轮防重选语义一致」——两个子类（新闻/政策）的 `extractAndPersist` 已压缩到 10 行以内。
3. **eval 调度链的工程化护栏密度是全仓最高档**。`EvalScheduler` 逐条消化了历史审查项：互斥 CAS 覆盖两轨全程（:274）、停机旗先于杀进程（:529，I1）、eval 子进程 orphan 清理（:532，I4）、env 白名单 clear 后放行（:355）、陈旧报告双轨删除（:244）、Boot 3.2+ nested code source 三形态解析（:474）。`EvalHarvester` 落库尽力而为（任何异常兜底标 FAILED 不上抛，:128-140）、先判后写（:195 先于 :218）、per-run 报告归档防基准串档（:378）。
4. **wiki seeding 的隐式并发安全有 DB 兜底**。`WikiApplicationService.java:72-82` 的「查标记→插条目→插标记」在 READ_COMMITTED 下存在 TOCTOU 窗口，但 `wiki_seed_state.user_id` 是主键（`V1__baseline.sql:472`），并发双跑必有一方插标记撞 PK 整体回滚——条目不会双份。`seedSopTemplates` 先补概念 seeding 再标 SOP 的顺序防「标记行谎报」（:89-91）注释交代清楚。
5. **观测系统自身失败不再静默**。上轮 §三.8 的整改超出预期：吞并计数带 stage 标签（collection/record）经 Micrometer 全局注册表直达 /actuator/metrics，测试用 addRegistry/removeRegistry 防泄漏（`ObservabilityAgentMiddlewareTest.java:402-421`）。

## 三、不足与风险

### 高

本轮无高危正确性/安全性缺陷（domain 零 Spring 依赖复扫为真；空 catch=0——2 处注释型空体 `ObservabilityAgentMiddleware.java:237`、`AnnouncementExtractor.java:168` 均有回退路径+理由注释，属合理模式）。

### 中

1. **EvalScheduler 看护移交存在孤儿窗口**：`triggerNow` 中 `starter.start` 成功后 `watchExecutor.execute`（`EvalScheduler.java:228`）若抛 `RejectedExecutionException`（@PreDestroy 已 shutdown executor 与手动触发精确并发的窗口），`handedOff=false` 走 finally 释放互斥——但子进程已起、无看护：不收割、不超时、不被停机清理命中（`liveProcess` 虽置位，进程已脱离看护循环）。eval 子进程单跑最长可达 timeout-minutes（默认 120 分钟），孤儿直接烧 LLM 预算且 eval_run 行永滞 RUNNING。建议 execute 包 try/catch：失败即 `destroyForcibly` + `markFailed` + 释放互斥。
2. **persistence 层发现新的誊写大簇（上轮未覆盖面）**：5 个 RepositoryImpl（IntelligenceNews/Announcement/Policy/DailyBrief + EvalRun）共享 ≥23 个归一化 8 行窗口——动态 JPQL `StringBuilder where + List<Object> args` 逐字段 null/blank 装配（`IntelligenceNewsRepositoryImpl.java:74-100` 为典型），`dayStart` 在 3 个文件逐字誊写（`IntelligenceNewsRepositoryImpl.java:195`、IntelligenceAnnouncement/Policy 同位）。上轮誊写簇清零说的是 application 层；infrastructure 此簇同根因（每加一个过滤字段誊写 3-5 份，bug 修复成本 ×5）。建议抽 `WhereClauseBuilder`（append 条件短语+占位 args，dayStart/dayEnd 内置）。

### 低

3. **三 extractor 的 parse 入口残余誊写**：`NewsExtractor.java:70-90`、`PolicyExtractor.java:79-99`、`AnnouncementExtractor.java:81-101` 的「blank 检查→stripFences→readTree→root 对象校验」5 窗口全三共享——上轮 LlmJsonParseSupport 抽了字段助手，parse 头未下沉。建议补 `LlmJsonParseSupport.readObject(text): JsonNode|null`（约 15 行，消灭最后 5 窗口）。
4. **EvalHarvester 告警在终态落库之后**：`updateHarvested`（`EvalHarvester.java:218`）先于 `sendDegradedAlert`（:227）；告警路径上 `versionShortCode` 的 `IllegalStateException`（:471，SHA-256 缺席）理论上会把已落 COMPLETED 行经 harvest 兜底覆写为 FAILED。SHA-256 恒可用、几乎不可达，备注级；若顺手，把短码计算挪到落库前即可。
5. **EvalScheduler 命令构造 10 行近重复**：`command()`（:389）与 `extractionCommand()`（:403）仅差一参，可合并为 `command(evalJar, dataDir, extraArg...)`。
6. **BDD 覆盖空白**：34 个场景（上轮报 23 系低估）经 glue 直注门面——research-flow 3 场景覆盖复盘/证伪/立项走的就是拆分后的门面委托链，拆分被传递性钉住，无需补。但 **eval 域零 BDD**（触发→RUNNING→收割→历史链全在单测/集成层），且抽取批（`ExtractionBatchService`）无 BDD——后者属内部调度链路、单测已覆盖 8 行为，可接受；前者建议后续补 1 个「手动触发 202→行可见→收割终态」场景（优先级低）。
7. **ExtractionBatchService 为新闻/政策构造无用 batchStart**：`extractPending`（`ExtractionBatchService.java:60`）的 `Instant batchStart` 只有公告的 `afterBatches` 消费，另两域白构造一次 `Instant.now`——无害，模板统一性的代价，不处理亦可。

## 四、技术债清单（量化）

| 指标 | 上轮 | 本轮 | 说明 |
|---|---|---|---|
| >500 行类 | 7（最大 733） | 4（最大 InvestProperties 707） | Research/IntelligenceQuery/InvestTools 拆分达标；AnalyticsApplicationService 625、EvalScheduler 539、PortfolioApplicationService 522 存续（EvalScheduler 复杂度有充分文档化理由） |
| 空 catch | 0 | 0 | 2 处注释型空体有回退+注释，不计 |
| catch 总数 / 仅 log 模式 | —（仅 log 47） | 207 / 94 | 94 处绝大多数为批处理「跳过本条」合理模式（抽样核对） |
| TODO/FIXME/HACK | 0 | 0 | 全仓扫描 |
| 复制粘贴簇 | 3 簇 18 文件对 | application 层清零；**persistence 新簇 5 impl ≥23 窗口**；extractor 入口残余 5 窗口 | record 形状相似（Quote/RawQuote、EvalRun 三件套）不计——分层表示的可接受平行 |
| @Modifying 无注释 | 8 | 0 | 8 处均有 B5 复核注释 |
| BDD 场景 | 23（低估） | 34 | eval 域零覆盖 |
| domain wire 知识 | parseQuote 已下沉 | parseOverview/parseSearch/parseKline/parseFinancials/parseNews 仍留 domain | 见 §五 L2 |
| ArchUnit 规则 vs 文档 | 已同步 24 | 24 = 24 | 一致 |

## 五、改进建议（按优先级）

**P1（本轮可直接执行）**

1. **L1：分包规范写入「门面漂移」条例**（文档-only，防 B6/B7 模式再漂移）。落位：`docs/technology/conventions/01-后端DDD分包规范.md` §五「落位规则」表新增一行，风格对齐现有 7 行表格：
   > `| 新增用例先落对应族 *ApplicationService，门面只加一行委托 | 门面（ResearchApplicationService/IntelligenceQueryService 等）方法体仅限一行委托 + 对外 record/DTO；新用例实现禁止写在门面内（事务边界留在门面 @Transactional，实现在族服务）——2026-10-12 拆分模式的防漂移条例 |`
   并在 §六「执行与演进」补一句：「评审 checklist 项：PR 中门面方法体出现多于一行委托即打回」。
2. **修复 EvalScheduler 看护移交孤儿窗口**：`watchExecutor.execute` 包 try/catch（RejectedExecutionException → `liveProcess.destroyForcibly()` + `markFailed(runId)` + 释放互斥），补一个「executor 已 shutdown 时触发」单测。

**P2（下一轮修复波）**

3. **L2：domain 残余东财 wire 知识下沉**（复盘 §5.2 锚定项）。现状：`MarketDataParser.parseSearch`（:222，`QuotationCodeTable/Data/SecurityTypeName/Code/MktNum/Name`）、`parseOverview`（:291，`data.diff[].f12/f14/f2/f4/f3`）、`parseKline`（:159，`data.klines`）、`parseFinancialIndicators`（:249，`result.data[].REPORT_DATE/EPSJB/...`）、`parseNews`（:267，`result.cmsArticleWebOld`），由 `OrchestratingMarketDataService`（:54/:99/:107/:170/:178）直调。可执行设计（照 RawQuote 已验证模式，约 1 人日、8-10 文件）：
   - domain 新增 5 个供应商无关中间 record：`RawIndexQuote(code,name,price,change,changePct)`、`RawStockHit(code,name,mktNum,typeName)`（非 A 股过滤决策一并下沉——`SecurityTypeName` 判断是 wire 知识）、`RawKlineBar(date,open,close,high,low,volume,amount,amplitude)`、`RawFinancialIndicator(reportDate,eps,bps,revenue,profit,roe,grossMargin)`、`RawNewsItem(title,content,media,date,url)`；
   - `EastmoneyFormat` 增 `extractOverview/extractSearch/extractKline/extractFinancials/extractNews` 五提取方法（字段码+JSON 路径导航全在里面，约 120 行）；
   - `MarketDataParser` 五方法改签 `List<Raw*>`：只留归一化/空校验/时间戳组装，彻底零 wire 知识；
   - 端口：`EastmoneyQuotePort` 扩为 `EastmoneyMarketDataPort`（五方法），application 注入点不变、impl 组合 extract+parse；ArchUnit 白名单沿用 `application.market` 端口先例，无需新登记；
   - 红线：异常语义逐字保持（data 缺失/空数组抛 BAD_RESPONSE 的文案与触发点不变）；现有 `MarketDataParserTest` 的 JsonNode fixture 平移到 `EastmoneyFormat` 侧，domain 侧改 Raw→DTO fixture，全部离线可测。
   - 备注：新浪/腾讯文本兜底解析（`parseSinaQuote` 等 5 个）同属 wire 知识留 domain，工作量另计，建议列 L2-二期而非本轮混入。
4. **persistence 动态 where 誊写簇抽 `WhereClauseBuilder`**：条件短语 append + args 占位 + dayStart/dayEnd 内置，5 个 RepositoryImpl 收敛（预计 -150 行）。

**P3（卫生项，可随手）**

5. `LlmJsonParseSupport.readObject(text)` 下沉三 extractor parse 头（-30 行，灭最后 5 窗口）。
6. eval 域补 1 个 BDD 场景（手动触发→202→RUNNING 行→收割终态→历史可见）。
7. EvalHarvester 短码计算挪到落库前（灭理论上的「COMPLETED 被覆写 FAILED」窗口）；EvalScheduler 命令构造合一；WikiApplicationService seeding 注释点明「并发安全由 wiki_seed_state.user_id 主键兜底」。

## 附：审查方法说明

复审比对样本：git show aa850f56^ / 24c61aa9^ 的 News/Policy/Announcement 抽取服务、三 CSV 解析器、EvalHarvester/MacroHealthService/PrincipleAlertService/ResearchFalsifierScanService 邮件降级块、ReportWorkflowOrchestrator.parseAsOf、8 处 @Modifying；现行代码逐文件核读：ExtractionBatchService、AlertMailer、ConfidenceScorer、ResearchApplicationService 门面+7 子服务、IntelligenceQueryService 门面+4 子服务、InvestTools+agent.tools 7 子组件、CurationCsvParser+2 子类、EastmoneyFormat/EastmoneyQuotePort(Impl)/OrchestratingMarketDataService、ObservabilityAgentMiddleware+测试、HarnessAgentFactory、EvalScheduler/EvalHarvester/EvalTriggerController、WikiApplicationService/wiki_seed_state DDL、BDD 18 个 feature 34 场景+glue 注入点。量化复扫：catch 全仓启发式（207 处逐类归组）、归一化（剥注释/import/字符串/数字）8 行窗口重复段、行数 top 榜。未覆盖：145 个 RepositoryImpl 逐条 SQL（抽读了重复簇代表）、collector/frontend 端、运行时验证（依赖 CI）。
