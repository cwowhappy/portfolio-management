# 代码审查报告 2026-10-04（架构与实现全量审查）

> 方法：五路并行只读审查（AI 对话内核 / 后端核心业务域 / 新交付域 MS-20~27 与认证安全 / 前端 / 采集与工程质量），对照产品功能（M01~M16）与 `docs/reviews/code-review-lessons.md` 历史经验清单做复发检查。全部 P1 发现已逐条 Read 源码复验，零误报入档。本轮为静态审查，未执行 `make test`。
> 背景：距上轮三端全量审查（2026-09-03）已一个月，期间交付 MS-08~MS-27 共 14 个里程碑，其中 intelligence / research(SOP) / im / alert（MS-20~27）为首次纳入审查。

## 总体评价

- **未发现 P0**；P1 级发现 9 项，集中在四个簇：金融计算边界条件（1）、多写端并发（2）、MCP 客户端生命周期（3）、认证面硬化（4 项）。均为修复成本低的确定性问题。
- **2026-09-03 轮 13 处 P1 修复全部保持**（@Version 乐观锁、部分唯一索引、clearAutomatically、ERP 真实化、异常映射补全），历史问题模式复发率极低——说明 lessons 沉淀机制在真实防复发。
- 新交付的 MS-20~27（智能情报 / SOP / 飞书 / 告警）首次审查，整体水准高于一般业务交付：认证面与推送管线有「失败隔离 + 幂等 + 护栏」三件套，MCP token 加密实现教科书级。
- 架构治理（DDD + ArchUnit + 四层测试 + 三端 80% 门禁 + 特性文档生命周期）是这个仓库最突出的资产；当前主要欠账是**认证面安全强度不一致**（验证码流硬化完整，登录/找回/会话吊销三个邻接面裸奔）和 **collector 运维韧性三件套**（依赖编排/告警抑制/悬挂 running）。

---

## 一、设计与实现亮点（值得固化）

### 架构治理
1. **DDD 纯度由 ArchUnit 强制，非自觉维护**：`domain/` 全量 grep 零 `org.springframework`/`jakarta`/`lombok` import；聚合不可变、`apply*` 返回新实例（`Position.java:74-137`）。21 条 ArchUnit 规则 + 新增能力域需登记白名单的机制，让分层防腐成为构建期硬约束。
2. **文档-特性-代码三层可追溯**：`features/` 固定编号生命周期（01 需求 → 03 计划 → 08 复盘）+ 交付回填 checklist（5 步）；技术模块文档锚定 file:line；历史审查 lessons 滚动沉淀且本轮验证有效。这套流程对长期维护的价值超过任何单一技术选型。
3. **质量门禁成体系**：三端覆盖率 ≥80% 挂 `check`；后端四层测试（约 190 test / 69 integrationTest / 21 bdd / 5 testFixtures）+ PIT/Descartes 变异测试作诊断仪；CI 全链路含 Playwright 真实浏览器 e2e（自动拉起三进程）；collector 测试 schema 由真实 alembic + Flyway 回放构建，消灭手工 DDL 漂移。

### 安全基线（MS-20~27 首次审查）
4. **MCP token AES-256-GCM 实现教科书级**：随机 12B nonce（`SecureRandom`）、构造即校验密钥、`v1:` 前缀密钥版本化、解密失败业务码 + 三层一致降级（读路径降级空列表 / 装配路径跳过 provider / 缺密钥 503）、审计日志不含明文、视图无 secret 字段（`AesGcmSecretCodec.java:54-123`）。
5. **邮箱验证码生命周期治理完整**：bcrypt 哈希落库、一次性消费、5 次尝试作废、60s 冷却 + 每日 10 封上限、SMTP 失败必须留痕、`MAIL_TEST_FIXED_CODE` 生产误配启动守卫（`EmailCodeService.java`、`SmtpMailSender.java:29-32`）。
6. **会话防护两件套**：登录成功先 `changeSessionId()` 再写认证上下文（`AuthController.java:93-100`）；改密吊销 remember-me；公开端点单一清单 `PublicEndpointPaths` 同时被 SecurityConfig 与 ActiveUserFilter 引用，杜绝名单漂移。

### 领域与数据正确性
7. **持仓账本 replay 单一事实源**：editTrade/补录分红不直接改数，全量 replay 交易+分红重建 Position（`PortfolioApplicationService.java:226-259`），与 analytics 重放显式对齐同日序——历史补录不漂移。
8. **analytics 是纯函数 + 已知答案测试**：TWR/Brinson-Fachler/MDD/XIRR/夏普全部静态纯函数，测试用手算值断言；归因残差显式留痕不隐藏；IRR 退化口径有标注（`TwrCalculatorTest.java:32-36`、`AttributionCalculator.java:16-17`）。
9. **筛选真正在 SQL 层 AND 组合**：12 条件动态拼 AND + 全参数绑定 + 排序列固定白名单 + LIMIT 绑定（`ScreeningRepositoryImpl.java:67-90`），匿名宽扫按完整条件键缓存 5min。
10. **collector 可靠性工程**：misfire coalesce + grace 3600 + `_run_task_job` 异常兜底；never_succeeded 冷启动补跑；熔断计数防重试放大（专项测试锚定）；14 张表幂等 upsert + seed reconcile；区间回填显式优先于 watermark（`jobs.py:135-179`、`plugins.py:837-849`、`writer.py:5-123`）。
11. **Agent 内核成熟**：工具错误统一契约（`ToolResultBlocks.java:12-64`，前端降级嗅探的跨栈约定）；图表双通道（emit 全量 spec 永不进 LLM + 返回值摘要进 stateStore）；userId 构造注入不进 `@ToolParam`（LLM 无法伪造他人身份）；`AguiEventNonNullCodec` 外科手术式修协议失配并写明回收条件；会话 create 防越权接管的历史教训落实为代码并有集成测试锁定；HITL/MCP/state 隔离均有真实装配集成测试（真实 MCP server、Testcontainers、假模型 SSE）。

### 前端工程
12. **流式三件套系统性防御**：防抖快照绑 threadId + hydratedThreadId 闸门双保险防跨线程串写；停止/卸载/切线程三路 flush 防丢尾；AssistantMessage memo 自定义比较器；useInterrupt 已处理态收进卡片内部 state（注释记录了 v1 真机根因）——历史三类问题均未复发且有 e2e 钉住。
13. **反代边界处理全面**：`getSetCookie()` 逐个 append、204/205/304 置空 body、15s 超时 + 502 JSON 兜底、CopilotKit 按请求注入 Cookie（`proxy.ts:70-92`、`route.ts:87-96`）。
14. **契约与规范防线**：zod 校验 375 处 + 双文件镜像 toEqual 锁定防漂移；`echarts.use()` 单一值导入入口 + eslint 禁裸入口/禁 rehype-raw；「组件禁直接 fetch」经 grep 验证零绕行。

---

## 二、不足

### P1（正确性 / 数据安全 / 安全，建议下一版本前修复）

**A. 金融计算边界（影响「收益分析」核心指标）**

1. **TWR/回撤/夏普对「非交易日外部流」错配——周末转入本金被计为收益**。
   `TwrCalculator.java:30` 按确切日期查流（`byDay.getOrDefault(cur.tradeDate(), ...)`），而 NAV 日集来自收盘交易日并集，`NavReconstructor` 把 `date ≤ t` 的流水在**下一个交易日**计入资产。周六转入 10 万 → 周一 V 跳升 → 流按周六键查不到 → `R=(V−0)/V_prev−1` 把本金记为收益（10 万组合转入 10 万即 +100% 单日）。TWR 累计、净值指数（MDD/夏普基底）、年度收益三处同病；`addCashTransaction` 只校「不晚于今日」不限交易日；测试全部用例流日期都在 NAV 日上，无该场景覆盖。建议：流日期归一到序列中 `≥ flowDate` 的首个 NAV 日（`TreeMap.ceilingEntry` 思路），或 NavReconstructor 在流日合成点位；补周六转入的已知答案测试。

**B. 多写端并发（影响「会话持久化」数据完整性）**

2. **多标签页并发写会话时整段互删（last-writer-wins 且 merge 非 union）**。
   前端 `agentMessagesToHistory`（`RuntimeProvider.tsx:84-102`）只输出本地 `msgs`，`existing` 仅用于回取 createdAt 不做并集；`ThreadArea.tsx:512-524` flush 前先 loadMessages 再整体 PUT；后端 `ConversationRepositoryImpl.java:57-61` delete+saveAll 全量替换，Conversation 无 `@Version`/If-Match。两个标签页同开会话，A 页新消息被 B 页随后 PUT 整段抹掉。单标签页竞态已被双保险修掉，这是残留的跨写端竞态。建议：`agentMessagesToHistory` 按 id 做 union，或后端加乐观锁返回 409 + 前端 409 后重灌合并。

3. **中文 IME 组合输入按 Enter 误发消息**。
   `ThreadArea.tsx:330-334` 的 `onKeyDown` 只判 `e.key === "Enter" && !e.shiftKey`，无 `e.nativeEvent.isComposing` 守卫——输入法选词确认按 Enter 时既吞掉选词又提交未完成拼音。同模式复发 4 处：`IntelligenceBoard.tsx:41`、`IntelligenceSettingsPage.tsx:240`、`AdminBoard.tsx:86,159`。对中文对话核心交互是高频缺陷。修复一行：`if (e.nativeEvent.isComposing) return;`。

**C. MCP 客户端生命周期（影响「MCP 数据源」安全与可用）**

4. **凭证轮换后缓存客户端永久使用旧 token**。
   `McpClientPool.java:23-25` 缓存 key 只有 `endpoint.id`，token 在构建时一次性注入 header；`McpAdminTokenController.java:31` 注释自述「token 轮换后需重启后端生效」。管理员因泄露轮换后，运行中的后端继续用旧 token 调外部数据源直至重启——安全操作缺口且无告警。建议：token 设置成功后 `evict(providerId)`（移除并 close），或 key 改 `(endpoint.id, tokenHash)` 自动失效。
5. **`listTools()` 无超时 + `initialize().block()` 持锁做网络 I/O + 两个死配置**。
   `UserToolkitFactory.java:69` `client.listTools().block()` 无超时包装；`McpClientPool.java:24,36` 在 `computeIfAbsent` 映射函数内 `initialize().block()`（持 bin 锁最长 connectTimeout 10s，同 bin 全阻塞）；`InvestProperties` 的 `mcp.toolTimeout`（30s）与 `poolMaxSize` 在 main 代码**零引用**——死配置给运维「有超时/有界」的错觉。挂死端点会让该用户每轮对话卡住且无异常抛出。建议：listTools 加 `Mono.timeout`；删除或实现两个死配置。

**D. 认证面硬化（MS-20~27 首次审查新发现，四项互为邻接面）**

6. **登录端点无尝试限流/锁定**。`AuthController.java:76-108` 只有密码错误 401；验证码流有 5 次作废 + 频控 + 日上限，登录面是 bcrypt 在线爆破敞口。建议：IP/username 维度失败计数 + 退避（项目已有 `TtlCache` 可承载）。
7. **找回密码可枚举账号存在性与状态**。`EmailCodeService.java:87-104` 对不存在/管理员/未绑邮箱/停用返回四种不同文案，攻击者可确认 username 存在、是否管理员、是否停用。建议：所有分支统一为中性响应「若账号可找回，验证码已发送」（可发信分支照发）。
8. **重置密码后 JSESSIONID 会话未吊销**。两条重置路径（自助 + 管理员）只删 remember-me token（`AuthApplicationService.java:69-77`、`UserAdminApplicationService.java:66-73`），被劫持会话在密码重置后依然有效至自然过期（默认 30min）。建议：注册 `HttpSessionEventPublisher` + `SessionRegistry` 按 principal 踢出，或文档化接受该风险。
9. **PdfFetcher 无主机白名单、跟随重定向（SSRF 纵深缺口）**。`PdfFetcher.java:104-116` 接受任意 http(s) URL，`followRedirects(NORMAL)` 可 302 到任意主机（含内网）。`pdf_url` 虽来自 collector 落库，但一旦解析链路被污染（东财/巨潮返回被篡改 URL），后端即成内网跳板。建议：主机白名单收敛（`static.cninfo.com.cn` / `pdf.dfcfw.com` 或配置化），重定向后复查目标主机。

**附 P1 边界项**：`/api/agent/status` 匿名可访问且回显 LLM `provider/model/baseUrl` 与 `MarketDataException.getMessage()`（可能含内网网关地址/上游 URL，`HealthController.java:62-91`）。建议收敛为 `keyConfigured` 布尔 + provider 名，异常 message 只留服务端日志。

### P2（设计债，按域归组）

**后端业务域**
- analytics replay 与 journal timeline N+1/无界载入未修：`AnalyticsApplicationService.java:463-475` 逐持仓查 trades+dividends；`JournalApplicationService.java:90-101` 逐持仓拉全部交易/分红再内存按区间过滤（journal 已下推日期，portfolio 侧未下推）。建议加 `findTradesByPortfolioIdInRange` 批量端口。
- `editTrade` 缺「日期不能晚于今日」校验：`PortfolioApplicationService.java:193-223`，其余五个写路径都有；未来日期买入导致写侧 replay 生效、analytics 永不应用 → 双边账本漂移。
- `deletePosition` 级联抹掉交易/分红史（`V1__baseline.sql:174,186` ON DELETE CASCADE）：realizedPnl 账本、交易统计、时间线事件全部蒸发，journal tradeId 软引用悬空。建议前端确认文案明示，或考虑软删除。
- `buy` 现金校验 TOCTOU：先 `cashBalance()` 读校验再写（`:156-171`），`@Version` 只护 position 行，同组并发买不同股票可双双透支（与「负现金=TWR 失真」的设计前提自相矛盾）。建议组行加版本/锁，或 DB check 约束兜底。
- `industryDistribution` 静默丢弃未映射行业持仓（`:395-408`）：行业分布合计 ≠ 总资产且无「未映射」桶。
- 小项：BacktestEngine.retOf O(n²)（`BacktestEngine.java:95-102`）；并发激活撞唯一索引返回 400 语义应为 409；screening 关键字未转义 `%`/`_`。

**新域（MS-20~27）**
- 验证码消费竞态：`EmailCodeService.verify:107-121` 读-判定-写非原子，并发可用同一码各成功一次。建议 `tryConsume` 走「UPDATE ... WHERE used_at IS NULL」行数判定（照绑定码 `BindingCodeRepositoryImpl.java:51-56` 先例）。
- `ActiveUserFilter` 每请求一次 DB 查询（`:34`）——停用即时生效的代价；高频场景可短 TTL 缓存 + 事件失效。
- 飞书入站单线程 + 无界队列（`FeishuWsClient.java:56-62`）：任意用户可发消息入队，恶意刷消息可堆积拖死 owner 对话；绑定命令（6 位码）无频控。建议队列有界 + 非 owner 丢弃 + 每 open_id 冷却。
- 留痕表无清理/无索引：`intelligence_push_log` 只增不删且幂等检查查询无 `(push_type, ref_table, ref_id, user_id)` 索引（`V3:194-206`）；`verification_code` 无滚动清理。
- 时钟注入不一致：`ResearchApplicationService.writeEvent:622-626`、`IntelligenceSubscription.java:68-80` 直用 `Instant.now()`，与同项目 A3 规则（注入 Clock）相悖。
- 告警「尽力而为」无升级路径：飞书未配置/失败仅 WARN 不重试，运维信号单一通道；关键告警建议落库或降级邮件（项目已有 SMTP）。

**前端**
- 图表点击深链断裂：`ChainGraphCard.tsx:45`、`unlisted/LandscapeChart.tsx:62` push `/market?code=...`，但 `MarketBoard` 不消费 `code` 参数——行业页 → 行情台联动失效。建议挂载时读 `?code=` → search → select。
- 会话历史回灌失败 = 输入区静默锁死：`ThreadArea.tsx:434-436,452,639`，`hydratedThreadId` 不更新则发送被静默 return，无错误提示无重试。建议失败渲染可操作错误态。
- `flushPersist` 并发无序号守卫：`ThreadArea.tsx:512-524` 运行停止 flush 与卸载 keepalive flush 重叠时旧快照可能后完成覆盖新快照。
- 工具卡外链未消毒：`toolRenderers.tsx:129-137,311-319,504-512` 把后端工具结果 `url/pdfUrl` 直接渲染 `<a href>`，与 `MarkdownView.tsx:17-23` 的信任模型不一致（外部新闻源 URL 若含 `javascript:` 可执行）。建议抽共用 `safeUrl()`。
- catch-all 反代未重编码/未滤 `..`：`app/api/market/[...path]/route.ts:13` 等直接拼 URL 解码段，`%2F`/`%3F` 可注入分隔符，解码段拼接后 WHATWG 归一化可逃逸前缀（后端鉴权仍在，非提权，但前端前缀不再是要塞）。建议逐段 `encodeURIComponent` + 拒绝 `..`。
- 设置页鉴权矩阵不一致：`settings/{mcp,skills,intelligence}`、`screener`、`valuation`、`industry` 未包 RequireAuth（market 公开是有意的，其余看不出意图）；`McpSettingsPage.tsx:33-55` 无防连点、非 Error 渲染 undefined、硬编码色值脱离 CSS 变量体系（深色主题失效）。

**collector**
- 任务间依赖仅靠 cron 时间顺序（`tasks/etf_tracking_error.yaml:12-13` 注释自认）：上游失败/延迟时下游用陈旧数据重算而不感知。建议 runner 级轻量前置检查「上游当日无 success run 则跳过并告警」。
- 数据 commit 与 run 记录分三个事务，进程 SIGKILL/OOM 留悬挂 running 且启动路径无 reaper。建议启动时批量置 failed `started_at < now() - 1 day`。
- 告警无去重/风暴抑制：终态失败每任务每 retry 一张卡，patrol 滞留项每交易日重复发。建议 (task_code, 错误签名) 滑动窗口合并。
- validator `range` 规则忽略 hard level（`validators/rules.py:66-76`）：hard 时仍只剔行记 issue，与其余规则语义不一致（当前 YAML 全 soft 未触发，属陷阱）。
- 边界：节假日手动 run `stock_valuation_daily` 会经 akshare spot 备源写「非交易日」行（`plugins.py:953-963,1013-1017`，对比 `EtfCloseSource:603-604` 有空帧保护），巡检口径不报警。建议对齐空帧保护。

### P3（打磨项，择要）
消息列表无虚拟化（长对话 O(n) Markdown 解析）；`aria-live` 包整个消息容器流式期间读屏器刷屏；K 线周期切换失败静默且标签与数据不符；移动端顶栏 12+ 链接无折叠；Dialog 无 Escape/focus trap（删除会话无确认 vs GroupManager 有 confirm，标准不一）；反代请求体无大小限制；`conversations.ts:47-54` keepalive PUT 受浏览器 64KB 限制超长对话静默丢失；`next.config.ts` 无 HSTS（视部署层）；`BacktestView` 未知 window 原样回显；domain 藏系统时钟（`Position.copy():136` vs `moveToGroup(now)` 风格不一）；`UserToolkitFactoryTest:287` `hasSize(16)` 计数断言易破；InvestSystemPrompt 缺反注入总则句；`research_draft` 工具 draftJson 无大小上限；TtlCache get-then-put 非原子（惊群有界）；`FeishuBindingRepositoryImpl:37` `getTimestamp()` 按 JVM 默认时区解释 timestamptz（DST 边界差一小时）；collector `news.py:133-136` 增量截断集合不按 source 过滤（有注释论证的裁量项）；docker-compose collector 无 healthcheck；`smoke.sh` 未挂 CI（腾讯 fixture 漂移探测是 CI 缺失的独特价值）；backend 缺 Flyway 迁移文件数守护测试（历史曾三次漏改版本断言）。

---

## 三、按产品功能域的对照结论

| 产品功能 | 实现评价 |
|---|---|
| 对话式投研问答 | 设计成熟（错误契约/双通道/HITL 真装配测试）；缺 IME 守卫直接伤害中文核心交互（P1-3） |
| 会话持久化 | 归属隔离与防越权接管是范本；多标签页互删 + 回灌锁死两个可用性缺口（P1-2、P2） |
| 收益分析 | 公式实现正确（已知答案测试）；非交易日流错配污染 TWR/回撤/夏普（P1-1，最优先） |
| 持仓组合 | replay 单一事实源优秀；deletePosition 级联抹史、buy TOCTOU、editTrade 日期校验缺口 |
| 资产配置 | 生效唯一性下沉 DB + @Version 保持；偏离度映射正确 |
| 估值/筛选 | SQL 层 AND 组合 + ERP 真实化落地；公开端点资源消耗有缓存+校验防护 |
| 行业研究 | 产业链图谱好；图表深链到行情台断裂（P2） |
| 用户管理/认证 | 验证码治理教科书级；登录限流/找回枚举/重置后会话吊销三个邻接面待硬化（P1 簇 D） |
| MCP/Skill | 装配与加密设计好；凭证轮换不失效 + listTools 无超时（P1 簇 C） |
| 投资日志 | timeline 合并正确但 N+1 无界载入（P2） |
| 智能情报/SOP（新） | 推送管线三件套 + 状态机合法性到位；留痕表索引/清理、时钟注入一致性待补 |
| 飞书集成（新） | 白名单 + 绑定码并发设计是范本；单线程无界队列 + 绑定命令无频控（P2） |
| 采集服务 | 历史 P0/P1 全部有代码级修复 + 测试锚定；依赖编排/告警抑制/悬挂 running 三处运维韧性欠账（P2） |
| 行情数据台 | 三客户端降级 + 限流完整；catch-all 反代编码缺口属加固项 |

---

## 四、修复优先级建议

- **第一批（正确性/安全，低成本高收益，8 项）**：TWR 非交易日流归一（补周六转入已知答案测试）→ IME `isComposing` 守卫（4 处）→ MCP 池加 `evict` + listTools 超时 + 删死配置 → 登录失败限流 → 找回文案中性化 → 多标签页会话 union/409 → PdfFetcher 主机白名单 → agent/status 字段收敛。
- **第二批（数据完整性/运维韧性）**：重置密码吊销会话、deletePosition 确认/软删、editTrade 日期校验、catch-all 反代编码、工具卡 safeUrl、回灌失败错误态、flushPersist 序号化、collector reaper + 告警窗口合并 + 上游依赖前置检查。
- **第三批（打磨）**：P3 清单随特性迭代捎带；建议把「认证面硬化三件套」「金融计算日历错配」「缓存 key 含凭证版本」「IME isComposing」四条新教训补入 `code-review-lessons.md`。

## 五、历史经验复发核验（2026-09-03 lessons 清单）

| 历史模式 | 状态 | 备注 |
|---|---|---|
| @Version 钱账聚合 | ✅ 保持 | Position/HoldingGroup/AllocationPlan/JournalEntry + 2 并发集成测试 |
| 生效唯一性 DB 索引 | ✅ 保持 | V1:232 部分唯一索引 + findFirst 容忍脏数据 |
| @Modifying clearAutomatically | ✅ 保持 | AllocationPlanJpaRepository:15 |
| ERP 近似冒充真实值 | ✅ 已真实化 | erpHistory 逐日对齐，股息率近似口径已文档标注 |
| 流式切线程竞态 | ✅ 单标签页已修 | 残留跨标签页竞态（本轮 P1-2） |
| mock 掩盖真实装配 | ✅ 系统性补上 | agui 集成测试套件 + collector 全量装配冒烟 |
| collector 事务/upsert/watermark/冷启动/misfire | ✅ 全部修复且有测试锚定 | 见审查亮点 10 |
| 前端 import type 擦除 / 直接 fetch / zod 漂移 | ✅ 有测试 + eslint 防线 | echarts-setup 单入口 + useSpy 模式 |
| N+1 查询 | ⚠️ 部分残留 | analytics replay / journal timeline / portfolio 读侧冗余 |
| 配置声明无人消费 | ⚠️ 复发（新模式） | `mcp.toolTimeout`/`poolMaxSize` 零引用——历史「静默失败」模式在 agent 层复发 |
| DTO 零校验 | ⚠️ 单点例外 | editTrade 未来日期；research_draft 无大小上限 |
| Flyway 版本断言漏改 | ⚠️ 当前无断言测试 | V1~V3 仅三文件风险低，建议补守护测试 |

**方法论结论**：历史 lessons 对本仓库防复发真实有效（12 项中 9 项完全保持、2 项部分残留、1 项以新模式复发）；本轮四个 P1 簇（日历错配/多写端并发/缓存凭证版本/认证面半硬化）建议按同样路径沉淀进 lessons。
