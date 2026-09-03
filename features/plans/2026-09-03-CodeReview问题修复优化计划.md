# 2026-09-03 CodeReview 问题修复优化计划

> 来源：`docs/reviews/code-review-2026-09-03.md`（三端全量双轴审查，65 条：P0×0 / P1×13 / P2×46 / P3×6）。
> 执行策略：**P1 优先 → P2 → P3**；逐项确认后按 backend/frontend/collector 目录隔离并行修复；严格 TDD（红-绿-重构）；每批修复后 `make test` 全量验证（注意 `set -o pipefail`，勿吞退出码）。
> 分叉点（⚠️ 需拍板）共 **6 处**，见各节标注。其余为机械修复（倾向方案即默认执行）。

---

## 〇、全局分叉点速览（6 处）

| # | 分叉点 | 倾向方案 | 主要替代 | 取舍 |
|---|---|---|---|---|
| F1 | ERP 历史分位（Spec-B-P1） | 实现真实 ERP 序列（股息率−10Y 国债逐日） | UI 标注「近似」 | 真实实现数据正确、国债已回填边际成本低；近似改动小但数据仍失真 |
| F2 | 钱账聚合并发（Std-B-P1） | 加 `@Version` 乐观锁（Flyway V9） | 写路径 `FOR UPDATE` 悲观锁 | @Version 覆盖全部写路径、标准做法；悲观锁逐条改写路径 |
| F3 | 分红双路径（Std-B-P1） | 统一到 replay 重建 | 存 totalAmount 不重放 | 统一后「聚合可由事件流确定性导出」；替代改字段语义 |
| F4 | collector 单源降级（Spec-C-P1） | 补 all_a_valuation 备源（规格明文唯一双源示例），其余标注后置 | 全部标注后置 | 真接备源工作量大；务实先补明文示例 + 保证单源失败路径正确 |
| F5 | 指数股息率恒 None（Spec-C-P1） | 实现 dividend_fetch，与 F1 联动 | 明确后置 | ERP 真实实现需股息率历史，一并做省返工 |
| F6 | 切线程串写（Std-F-P1） | 历史回灌就绪前不启动防抖 + pending 带 threadId 校验 | 仅 pending 校验 | 双保险，竞态更稳 |

---

## 一、backend 修复项

### P1（3 项）

- **B-1 `[P1]` deactivateAll 缺 clearAutomatically**（`AllocationPlanJpaRepository.java:15-17` + `AllocationApplicationService.activatePlan:57-62`）
  - 倾向：`@Modifying(clearAutomatically = true, flushAutomatically = true)`，并调整 `activatePlan` 顺序为「先 deactivate 再 requirePlan→activate」或「先 activate 保存再 deactivate」，确保幂等。
  - 替代：仅 `clearAutomatically=true`（最小改动）。
  - 取舍：加 clear 即可消除脏读，顺序调整是二次加固。
  - 测试：切片/集成测试覆盖「重复激活已生效方案 → 仍是唯一 active」。

- **B-2 `[P1]` 钱账聚合无乐观锁**（`PositionJpaEntity` 等 5 实体无 `@Version`）
  - 倾向：F2——给 Position/Portfolio/HoldingGroup/AllocationPlan/JournalEntry 加 `@Version` + version 列（Flyway V9），并发冲突映射 409。
  - 测试：集成测试并发 buy 双提交 → 一条 409、账本与 trade 一致。

- **B-3 `[P1]` 分红双变更路径**（`PortfolioApplicationService.addCashDividend:217-228` vs `replay:182-215`）
  - 倾向：F3——`addCashDividend`/`addStockDividend` 存事件后走 `replay` 重建（与 editTrade 同路径）。
  - 测试：补录历史分红后 editTrade 重放，账本数字与事件流确定性一致。

### P2（21 项，按根因归组）

**错误码模型缺失（B2）**
- B-4 `domain/screening` 无 `ScreeningErrorCode` → 新增常量，替换 `ScreeningApplicationService` 裸字符串与 `GlobalExceptionHandler` 硬编码。
- B-5 `Position.java:71` 裸字符串 `"SELL_EXCEEDS_QUANTITY"` → 引用 `PortfolioErrorCode`。
- B-6 `domain/valuation/ValuationException` 死代码 + 无 `ValuationErrorCode` → 补错误码常量 + GlobalExceptionHandler 映射（或删死代码）。

**wire 边界校验缺失（H8/H4）**
- B-7 `BuyCommand/SellCommand/EditTradeCommand` fee 无 `@DecimalMin("0")`、stockCode/stockName 无 `@Size` → 补 Bean Validation 对齐 DB 列。
- B-8 `ConversationController.saveMessages` 裸 `@RequestBody` 无 `@Valid`、wire 零校验 → 补 `@Valid` + 字段约束。
- B-9 `Position.applyBuy/applySell/applyCashDividend` 负数/零不设防 → 域层加非负校验（防御纵深）。

**精度与不变量**
- B-10 `Percentile.java:17` double 算分位 → 改 BigDecimal（C3）。
- B-11 `AllocationPlan` 权重和校验无容差 + DB `NUMERIC(18,4)` round → wire 层限制小数位（如 ≤4 位）+ 域校验加容差（与 Spec-B 权重容差合并，见 P-B-4）。

**匿名端点资源边界（E2）**
- B-12 `/api/screening/**` 匿名宽扫无缓存/限流 → 加缓存（按查询参数 key，含 TTL）+ 限流装饰器。
- B-13 `/api/valuation/industries` 未缓存 → 与 overview/history 同样加缓存。

**静默失败 / 语义错位**
- B-14 `quoteQuietly` catch RuntimeException 吞掉不记日志 → 至少记 warn（含 code），并区分 RATE_LIMITED。
- B-15 `ActiveUserFilter.shouldNotFilter` 与 `permitAll` 集合不一致 → 统一为同一份公开端点清单。
- B-16 `deleteGroup` 不拦 cash_transaction 流水 + CASCADE 删光 → 拦「有现金流水则拒删」或改为不级联删流水。
- B-17 `PortfolioController` 创建返回 200 vs 201 → 统一 201。

**一致性 / 契约**
- B-18 `AllocationApplicationService.mapHoldings` 用中文文案「权益/现金」作契约 → 改用枚举/稳定 code。
- B-19 `MarketController/ScreeningController/ValuationOverviewView` 直接暴露 domain 对象 → 补 DTO 或显式记录取舍。
- B-20 `JournalApplicationService.timeline` 整表载入内存 → 日期范围下推到仓库查询。
- B-21 `CacheConfig` MAX_ENTRIES/TTL 硬编码 → 读 `InvestProperties`（对齐 market 缓存先例）。
- B-22 `InvestProperties` remember-me key 硬编码兜底 → 缺失时启动报错或强制随机（去兜底）。
- B-23 `Conversation.java` 标题 UTF-16 码元截断 → 按码点截断。
- B-24 `ChatMessage.role` 裸 String → 域枚举。
- B-25 `StockRef` 与 `MarketDataParser` 市场判定重复 → 收敛到一处。

> 注：backend P2 实为 21 项（B-4~B-25），编号已按归组合并为 22 行（B-4~B-25 共 22 项，含 B-11 与 Spec 合并）。以下 Spec 侧引用 `P-B-4`。

---

## 二、frontend 修复项

### P1（3 项）

- **F-1 `[P1]` 切线程跨线程串写**（`ThreadArea.tsx:404-420`）→ F6：历史回灌就绪前不启动防抖 + pending 带 threadId 校验。测试：模拟慢加载切线程断言不串写。
- **F-2 `[P1]` industryCode 带入断裂**（`ScreenerBoard.tsx:12` + `IndustryBoard.tsx:31`）→ ScreenerBoard 读 `useSearchParams` 初始化 `params.industryCode`，ScreeningForm 预填行业。测试：带 `?industryCode=` 挂载断言预填。
- **F-3 `[P1]` 估值页免责声明缺失**（`ValuationBoard.tsx:44-68`）→ 加 `<Disclaimer />`。测试：断言渲染。

### P2（4 项）

- F-4 反代 query 透传不一致（portfolio/allocation/conversations）→ 统一用 `relay` 语义追加 search。
- F-5 5 条 GET 路由绕过 `relay()` → 收敛到 `relay()`（或抽公共 GET 转发 helper）。
- F-6 lib 层 `request/get` 重复 → 抽公共 http 客户端。
- F-7 看板 reload 无序号守卫 → 加 `requestSeqRef`（与 MarketBoard.select 同款）。

### P3（2 项）

- F-8 `ScreeningResultsTable` 流动比率列超契约 → 移除或补进契约（与后端对齐后决定）。
- F-9 journal 日期过滤 UI 缺失 → 加 from/to 过滤控件。

### 其余（Spec 侧，见 §四）

- P-F 累计分红卡 scope-creep、破净数量缺失、journal 表单校验缺失、AI 401 不跳登录——见 Spec 修复节。

---

## 三、collector 修复项

### P1（3 项）

- **C-1 `[P1]` UPSERT 漏 enabled**（`repositories/tasks.py:18-27`）→ DO UPDATE 加 `enabled=EXCLUDED.enabled`；seed 增加 reconcile（删除不在 YAML 的任务，至少 warn）。测试：真实 PG 下 enabled 翻转生效。
- **C-2 `[P1]` TreasuryCurveSource 忽略区间**（`plugins.py:146-168`）→ fetch 读 params start/end 按区间过滤。测试：指定区间回填只写区间内行。
- **C-3 `[P1]` 单源无降级 + 指数股息率恒 None**（`tasks/*.yaml`、`jobs.py:242`）→ 见 F4/F5 分叉。

### P2（4 项）

- C-4 `validators/rules.py` 内建规则 7 缺 4（not_null/type/unique/allowed_values）→ 补齐实现 + 测试。
- C-5 `StockFinancialSource` 并发 4 拉取无节流 → 加 RateLimiter（FR-11）。
- C-6 `runner.py` skipped 不落库、无 running 态/时长 → 补 task_run 前置行 + skipped 记录（FR-10）。
- C-7 `StockFinancialSource` 未剔 ST/退市 → 对齐 `StockValuationDailySource` 的 ST/退过滤（P1 口径）。

### P3（2 项）

- C-8 `cli.py list` `--enabled-only` 形同虚设 → 支持列出全部/启用两种模式 + 展示上次运行状态。
- C-9 `index_constituent` 只 upsert 不删 → 半年任务先删后插（或加生效日期维度）。

### 其余（Spec 侧，见 §四）

---

## 四、Spec 轴修复项（前端/backend 的「需求符合度」修复）

### backend Spec
- **P-B-1 `[P1]` ERP 分位近似** → F1。
- P-B-2 `[P2]` 编辑持仓改分组缺失 → 补 `EditTradeCommand.groupId` + 端点/服务。
- P-B-3 `[P2]` 现价逐只串行 → 补 `MarketDataService.quoteBatch`。
- P-B-4 `[P2]` 权重和校验无容差 → 与 B-11 合并（wire 限小数位 + 域容差）。
- P-B-5 `[P2]` 复盘不绑定个股未强制 → 域校验 REVIEW 分支强制 stockCode/tradeId 为空。
- P-B-6 `[P3]` 已清仓仍在列表 → `findPositionsByPortfolioId` 过滤 quantity=0（确认前端后再定）。
- P-B-7 `[P3]` 历史走势只沪深300 → 补其余 4 指数（或规格标注收窄）。

### frontend Spec
- P-F-1 `[P1]` 删除分组交互缺失 → GroupManager 加删除 + confirm。
- P-F-2 `[P2]` 累计分红卡 scope-creep → 移除或补进 FR-C1 契约。
- P-F-3 `[P2]` 破净股数量未渲染 → ValuationBoard 渲染 `netBreakerCount`。
- P-F-4 `[P2]` journal 表单校验缺失 → EntryEditor 补 stockCode/targetPrice/period 校验。
- P-F-5 `[P2]` AI 401 不跳登录 → runAgent 401 时触发跳登录。

---

## 五、文档同步（修复收尾，必做）

- `AGENTS.md`：make test 范围/端点表/约束如有变化同步。
- `README.md`：端点表、功能全览。
- 模块文档：接口契约（若 B-18/B-19 改了 DTO/契约）。

## 六、经验沉淀

- `docs/code-review-lessons.md` 追加「2026-09-03 轮」：幂等边界（@Modifying 批量 + 持久化上下文）、声明式启停失效（upsert 漏字段 + 不 reconcile）、区间回填被增量逻辑架空、流式切线程竞态、wire→domain 边界校验缺失——见修复闭环后补。
