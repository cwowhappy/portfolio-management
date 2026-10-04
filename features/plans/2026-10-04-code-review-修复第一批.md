# 2026-10-04 审查修复 · 第一批（P1）实施计划

> 输入：`docs/reviews/code-review-2026-10-04.md`（9 项 P1）。本计划执行**第一批 8 项** + 1 项顺手 P2（B9）。第二、三批（P2/P3）另行计划。
> 分支：`chore/code-review-2026-10-04`。纪律：TDD（先红后绿）+ 目录隔离并行 + 全量 `make test` 收口（ lessons：隔离跑 ≠ 全量绿）。

## 执行分组与文件归属（同一工作树并行，禁止越界改文件）

| 代理 | 任务 | 允许触碰 | 禁止触碰 |
|---|---|---|---|
| BE-A | B1 TWR 非交易日流 | `domain/analytics/**`、对应 test/integrationTest | GlobalExceptionHandler、InvestProperties |
| BE-B | B3 MCP 生命周期 + B7 Pdf 白名单 + B8 status 收敛 | `agent/McpClientPool.java`、`agent/UserToolkitFactory.java`、`application/mcp/**`、PdfFetcher 所在包、新建 `IntelligencePdfProperties`、`web/HealthController.java`、`config/InvestProperties.java`（仅 Mcp 段）、`scripts/smoke.sh`、模块文档 03 | GlobalExceptionHandler、application/conversation、infrastructure/security |
| BE-C | B4 登录限流 + B5 找回文案 + B6 后端 + B9 editTrade | `application/auth/**`、`infrastructure/security/**`、`web/AuthController.java`、`application/conversation/**`、conversation 的 persistence 文件、`web/ConversationController.java`、`web/GlobalExceptionHandler.java`（仅加会话 409 映射）、`application/portfolio/PortfolioApplicationService.java`（仅 editTrade 段） | InvestProperties、agent/**、web/HealthController |
| FE-A | B2 IME + B6 前端 + B8 前端消费 | `frontend/components/chat/ThreadArea.tsx`、`components/intelligence/**`、`components/admin/AdminBoard.tsx`、`lib/conversations.ts`、`RuntimeProvider`（chat 内）、agent status 的前端消费方与 zod schema、`frontend/tests/**` | lib/proxy.ts、各 api 文件签名（可扩展） |

后端三个代理**串行**执行（共享 Gradle 构建目录，避免并发编译冲突）；FE-A 与后端并行。

## 契约决策（已拍板，执行中不再回头确认）

1. **B6 会话并发**：不加 `@Version` 列（避免 Flyway 迁移），改用 `updated_at` 乐观校验——PUT 携带 If-Match（后端响应与 GET 均返回 `updatedAt`）；`UPDATE ... WHERE id=? AND user_id=? AND updated_at=?` 影响行数=0 → 抛 `ConversationConflictException` → 409。前端收到 409：GET 服务端消息 → 按 id 与服务端做 union → 重试一次；仍 409 则横幅报错。`agentMessagesToHistory` 同步改为 union 语义。
2. **B3 凭证轮换**：Spring 事件解耦方向——`application/mcp` 发布 `McpTokenRotatedEvent(providerId)`，`agent/McpClientPool` 监听后按 endpoint 驱逐并 close（保持 application→agent 单向，ArchUnit 不破）。池 key 维持 `endpoint.id`。`toolTimeout` 真实接线（`listTools().block(Duration)`），`poolMaxSize` 属死配置直接删除。
3. **B4 登录限流**：按 username 计数（非 IP），5 次失败锁 5 分钟，429 + `Retry-After`；成功清零。实现为新组件放 `infrastructure/security`，`AuthController` 调用；429 直接走 ResponseEntity，不改 GlobalExceptionHandler。
4. **B5 找回文案**：所有存在性/状态分支统一响应「如果该账号可以找回密码，验证码已发送至绑定邮箱」；仅真正可找回时发信。`EmailCodeServiceTest` 随之改口径（该测试当前固化了错误行为）。
5. **B8 status 收敛**：`llm()` 返回 `{provider, model, keyConfigured}`，删 `baseUrl`；行情探活失败响应固定「行情源探活失败」，详情仅服务端日志。`smoke.sh`、模块文档 03、前端 zod schema 同步。
6. **B7 pdf 白名单**：新建独立 `@ConfigurationProperties("invest.intelligence.pdf")`（不动 InvestProperties 主文件，避让 BE-B 的 Mcp 段编辑）；默认 hosts：`static.cninfo.com.cn`、`pdf.dfcfw.com`；初始 URL 与每次重定向目标均校验，https only。
7. **B1 TWR**：流日期归一到「序列中 ≥ flowDate 的首个 NAV 日」（`TreeMap.ceilingEntry` 语义），与 `NavReconstructor` 的「flow 计入首个 ≥ 其日期的 NAV 点」严格对齐；流日期晚于最后 NAV 日 → 忽略（尚未反映在任一 NAV 中）。三处计算器（TWR/RiskMetrics/AnnualReturn）同口径。

## B1 · TWR 非交易日外部流归一（BE-A）

测试清单（先红）：
- [ ] `saturdayTransferIsNotCountedAsReturn`：NAV 周一至周五，周六转入 10 万，周一 NAV 含本金 → TWR 不含 +100% 跳变（已知答案 ≈ 仅真实涨跌）
- [ ] `flowOnNonTradingHolidayAppliesToNextNavDay`：节假日流入在下一交易日扣除
- [ ] `flowAfterLastNavDateIgnored`：晚于末次 NAV 的流水不影响本序列
- [ ] `flowBeforeFirstNavDateAppliesToFirstNavDay`：与 NavReconstructor 计入规则一致
- [ ] RiskMetrics/AnnualReturn 同场景各 1 条（MDD 基底不再被本金跳变污染）
- [ ] 既有全部用例保持绿（回归）

实现：`TwrCalculator`/`RiskMetricsCalculator`/`AnnualReturnCalculator` 的 `byDay` 构建前，先用 `NavSeries` 的 tradeDate 有序集合归一流日期（复用同一工具方法，放 `domain/analytics`）。

## B2 · IME isComposing 守卫（FE-A）

测试清单：
- [ ] 每处 `onKeyDown`：`isComposing=true` 时 Enter 不触发提交、不 preventDefault（5 处：ThreadArea、IntelligenceBoard、IntelligenceSettingsPage、AdminBoard×2）
实现：`if (e.nativeEvent.isComposing) return;`（Enter 分支首部；Shift+Enter 路径不受影响）。

## B3 · MCP 客户端生命周期（BE-B）

测试清单：
- [ ] token 轮换事件后 `acquire` 重建 client 且新 header 生效（旧 wrapper 被 close）
- [ ] `listTools` 超过 `toolTimeout` 抛超时（embedded 延迟 server）
- [ ] 池 `@PreDestroy` 关闭全部 wrapper
- [ ] 移除 `poolMaxSize` 后配置面干净（grep 零引用）

## B4 · 登录失败限流（BE-C）

测试清单：
- [ ] 连续 5 次密码错误 → 第 6 次 429 + Retry-After（正确密码也被拒）
- [ ] 锁定期内不重置计数；5 分钟后自动解锁
- [ ] 成功登录清零计数
- [ ] 未锁定路径行为不变（401 文案不变）
- [ ] 切片测试：不同 username 互不影响

## B5 · 找回密码文案中性化（BE-C）

测试清单：
- [ ] 不存在/管理员/未绑邮箱/停用 四分支响应体完全一致（中性文案）
- [ ] 仅可找回账号真实发信（四分支发信与否断言保留）
- [ ] 验证码校验/消费逻辑不变（回归）

## B6 · 会话并发（BE-C 后端 + FE-A 前端）

后端测试清单：
- [ ] PUT 携带过期 If-Match → 409 + 服务端消息体不变
- [ ] PUT 无 If-Match → 向后兼容放行（或要求携带，二选一需与前端同时上线——**选放行**，前端逐步接入）
- [ ] 影响行数=0 时不发生任何 delete/replace
- [ ] GET/POST 响应含 `updatedAt`
前端测试清单：
- [ ] `agentMessagesToHistory` union：服务端 {1,2} + 本地 {2,3} → {1,2,3}（createdAt 保留原值）
- [ ] flush 收到 409 → GET 合并 → 重试一次成功
- [ ] 重试仍 409 → 错误横幅，本地草稿不丢

## B7 · PdfFetcher 主机白名单（BE-B）

测试清单：
- [ ] 非白名单主机（含内网 IP/域名）→ 业务异常，不发起请求
- [ ] 302 到白名单外主机 → 拒绝
- [ ] http（非 https）→ 拒绝
- [ ] 白名单主机 + https 正常抓取（回归）

## B8 · /api/agent/status 字段收敛（BE-B 后端 + FE-A 前端）

测试清单：
- [ ] 响应含 `provider/model/keyConfigured`，不含 `baseUrl`
- [ ] 行情探活失败 → 响应固定文案，不含异常 message
- [ ] `smoke.sh` 相关段通过（若依赖被删字段则适配）
- [ ] 前端 zod schema 通过 + 状态展示正常

## B9 · editTrade 未来日期校验（BE-C，顺手 P2）

测试清单：
- [ ] editTrade 日期晚于今日 → 400 + 与其他写路径一致的 errorCode
- [ ] 今日/历史日期正常（回归）

## 收口

1. 各代理自检：目标测试类全绿 + 编译通过 +（后端）`./gradlew compileJava compileTestJava`；（前端）`pnpm vitest run` 相关文件 + `pnpm lint`。
2. 全量 `make test`（一次跑全，不允许 `| tail` 吞退出码）—— lessons：收口以全量为准。
3. 文档同步检查：README 端点表、模块文档 03-接口设计、AGENTS.md（仅当有流程/配置变化）。
4. 提交：按域分 commit（fix(analytics)/fix(agent,mcp)/fix(auth,conversation,portfolio)/fix(frontend)）。
