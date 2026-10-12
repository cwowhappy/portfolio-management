# Hook forRemoval 专项实施计划（P1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（本仓既定执行方式）来逐任务实施本计划。步骤用 checkbox（`- [ ]`）跟踪。
> 分支：`chore/hook-migration`（规格已入库 @ 6b327c65）| 状态：进行中——T1–T5 收口（全量门禁绿：test 2422 / integrationTest 383 / bdd 34，JaCoCo 聚合指令 96.18% / 分支 84.56%），剩 Task 6 真流回归 + PR

**Goal:** ObservabilityAgentHook / ReportUsageHook 无损迁移为单点 onAgent 拦截 Middleware 并删除旧 Hook，TrustAgentHook 留守并文档化重开触发条件——main 的 `io.agentscope.core.hook` import 面收敛到 TrustAgentHook 一处。

**Architecture:** 两枚新中间件各只覆写 `onAgent`：入口做回合重置（trust 报告 identity 基线快照替代 PRE_CALL），`doOnNext` 过滤 `ModelCallEndEvent`（usage 累计，reasoning+summary 双路）与 `AgentResultEvent`（正常轮落库），`doOnError` 承接 failed 轮——外层完成信号天然晚于核心内 Trust hook 的 POST_CALL，替代 priority=200 装配序。公开 API `totalTokens()` / `drainTruthInvocations()` 签名不变。

**Tech Stack:** AgentScope Java 2.0.4（MiddlewareBase / AgentEvent / ChatUsage）+ Spring Boot 4 + JUnit5/AssertJ/Mockito + Reactor。

**Spec:** [02-设计规格/设计规格说明.md](../02-设计规格/设计规格说明.md)（含能力矩阵、三条时序事实、职责映射表、15 文件迁移面、Trust 留守触发条件）；需求与验收见 [01-需求规格/需求规格说明.md](../01-需求规格/需求规格说明.md)。

## Global Constraints

- **红线**：TrustAgentHook 行为零变更；AG-UI wire 契约零变更；`build(Long)`/`buildReportAgent` 除中间件装配外零变更；观测两表落库口径与字段零变更。
- 公开 API 不变：`totalTokens() → long`、`drainTruthInvocations() → List<ToolInvocation>`（排空语义）。
- 新测试类 GWT 命名（`given/when/then` 前缀 + `@DisplayName` 中文，ArchUnit 强制）。
- 覆盖率 ≥80%（JaCoCo 指令+分支挂 check）；ArchUnit 21 条规则全过。
- 全量门禁前必须 `cleanTest cleanIntegrationTest`（防 up-to-date 复用争议）。
- 不动前端、schema、Makefile；ADR/research/features 历史文档即使命中旧类名也不改（现状陈述 vs 历史保真逐处判定）。
- 提交前缀 `feat(agent):`/`refactor(agent):`/`docs:`，尾行 Co-Authored-By；不 push（Task 6 统一推）。

## Review Focus

1. **落库口径漂移**：usage 事件源从 Msg.getUsage()（POST_REASONING/SUMMARY）换成 ModelCallEndEvent.getUsage()——若两者数值不同源，turn_observation 三分量与研报 tokenUsage 静默漂移 → Task 2 集成口径测试 + Task 6 smoke tokenUsage>0 钉住。
2. **真值快照时序**：doOnComplete 时 TrustContext 池若已被清理/换新（如未来上游改动），快照恒空 → 研报校验门假红 → Task 3 BDD 剧本 + 集成测试真值链路钉住。
3. **doOnError 吞并**：落库自身异常若外抛会**替换原始 Agent 错误**（错误面被观测面覆盖）→ Task 2 `givenRecorderThrows` 同款用例迁到 doOnError 路径。
4. **回合态跨请求残留**：中间件实例必须随 agent 按请求构建（factory 内 new）——若被提升为单例 bean 即跨用户串数据 → Task 4 装配测试断言 factory 每次构建新实例（现有 factory 测试模式天然覆盖，须保留）。
5. **BDD 剧本模拟失真**：剧本 onAgent 模拟若与真实装配行为分叉（如漏 doOnComplete），drain 通道契约测试变假绿 → Task 3 剧本注释保留字节码行为论证 + 集成测试真实 HarnessAgent 兜底。

---

### Task 1: ReportUsageMiddleware（TDD，独立新增，旧 Hook 共存）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/agent/report/ReportUsageMiddleware.java`
- Test: `backend/src/test/java/com/portfolio/invest/agent/report/ReportUsageMiddlewareTest.java`

**Interfaces:**
- Consumes: `MiddlewareBase.onAgent(Agent, RuntimeContext, AgentInput, Function<AgentInput,Flux<AgentEvent>>)`；`ModelCallEndEvent(String replyId, ChatUsage usage)` / `getUsage()`；`TrustContext.current(rc).invocations()`；`ToolInvocation`。
- Produces: `public final class ReportUsageMiddleware implements MiddlewareBase`；`public long totalTokens()`；`public List<ToolInvocation> drainTruthInvocations()`（Task 3 涟漪与 Task 6 smoke 消费）。

- [ ] **Step 1: 写失败测试**

```java
package com.portfolio.invest.agent.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.middleware.AgentInput;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * ReportUsageMiddleware 单元测试（hook-migration，沿 ReportUsageHookTest 口径迁移）：
 * usage 事件源改 ModelCallEndEvent（reasoning+summary 双路同事件）；真值快照在 onAgent
 * 完成时点（doOnComplete）——与旧 POST_CALL 时序等价。真值注池走反射
 * （TrustContext.fresh/record 包私有于 trust 包，ReportSteps:311 B0 Ruling-1 同款取舍）。
 */
class ReportUsageMiddlewareTest {

    @AfterEach
    void cleanFallback() {
        TrustContext.reset();
    }

    @Test
    @DisplayName("usage 累计：两次 ModelCallEndEvent (100,50)+(30,20) → totalTokens=300")
    void givenModelCallEndEvents_whenAgentCompletes_thenTokensAccumulated() {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-1").build();

        mw.onAgent(null, ctx, new AgentInput(List.of()), input -> Flux.just(
                (AgentEvent) new ModelCallEndEvent("r-1", new ChatUsage(100, 50, 0.01)),
                new ModelCallEndEvent("r-2", new ChatUsage(30, 20, 0.01)),
                new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.totalTokens()).isEqualTo(300L);
    }

    @Test
    @DisplayName("usage 缺席安全跳过：模型缺报不炸中间件，读数不变小为 0")
    void givenNullUsage_whenAgentCompletes_thenSkippedSafely() {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-2").build();

        mw.onAgent(null, ctx, new AgentInput(List.of()), input -> Flux.just(
                (AgentEvent) new ModelCallEndEvent("r-1", null),
                new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.totalTokens()).isZero();
    }

    @Test
    @DisplayName("真值快照：完成时点入队，drain 排空后为空（防跨轮重复）")
    void givenTruthPool_whenAgentCompletes_thenDrainedOnce() throws Exception {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-3").build();
        seedTruth(ctx, truth("get_quote"));

        mw.onAgent(null, ctx, new AgentInput(List.of()),
                input -> Flux.just((AgentEvent) new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.drainTruthInvocations()).hasSize(1);
        assertThat(mw.drainTruthInvocations()).isEmpty();
    }

    @Test
    @DisplayName("无真值池完成：安全空快照（current 新建空池）")
    void givenEmptyPool_whenAgentCompletes_thenNothingDrained() {
        ReportUsageMiddleware mw = new ReportUsageMiddleware();
        RuntimeContext ctx = RuntimeContext.builder().userId("7").sessionId("report-4").build();

        mw.onAgent(null, ctx, new AgentInput(List.of()),
                input -> Flux.just((AgentEvent) new AgentResultEvent(msg("m-1")))).blockLast();

        assertThat(mw.drainTruthInvocations()).isEmpty();
    }

    // ———— 夹具 ————

    private static Msg msg(String id) {
        return Msg.builder().id(id).role(MsgRole.ASSISTANT).textContent("轮收尾").build();
    }

    private static ToolInvocation truth(String toolName) {
        return new ToolInvocation(toolName, "{}", null, 0, null, null, List.of(),
                null, ToolInvocation.AsOfKind.CALL, false, false, 5L);
    }

    /** 反射注真值（TrustContext.fresh/record 包私有——ReportSteps 同款，跨包测试唯一通道）。 */
    private static void seedTruth(RuntimeContext rc, ToolInvocation invocation) throws Exception {
        Method fresh = TrustContext.class.getDeclaredMethod("fresh", RuntimeContext.class);
        fresh.setAccessible(true);
        Object pool = fresh.invoke(null, rc);
        Method record = TrustContext.class.getDeclaredMethod("record", ToolInvocation.class);
        record.setAccessible(true);
        record.invoke(pool, invocation);
    }
}
```

**注意**：`truth(...)` 夹具的 `ToolInvocation` 构造参数以 `ToolInvocation.java` 实际签名为准（计划按 record 组件序推定，实现者先 Read 该文件核对分量序与类型，不匹配则按真实签名修正夹具——语义不变：一条含 toolName 的成功 CALL 实录）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.report.ReportUsageMiddlewareTest" --console=plain`
Expected: 编译失败 `cannot find symbol: class ReportUsageMiddleware`

- [ ] **Step 3: 最小实现**（逻辑自 ReportUsageHook 迁移；事件源替换见注释）

```java
package com.portfolio.invest.agent.report;

import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 研报任务用量 + 真值采集中间件（hook-migration，自 ReportUsageHook 等价迁移）：
 * per-task 实例（非 Spring bean），编排器每任务 new 一枚经 {@code HarnessAgentFactory
 * .buildReportAgent(userId, usageMiddleware)} 挂入，双职责——
 *
 * <p><strong>① token 累计：</strong>事件源自 Hook 的 POST_REASONING/POST_SUMMARY Msg usage
 * 改为 {@link ModelCallEndEvent#getUsage()}（单点 onAgent 层过滤；reasoning 与 summary 双路
 * 模型调用各发一枚，逐次累加口径不变——设计规格 §2.2 映射表）。配额卡点与计费观测读数口
 * （{@code totalTokens()}），usage 缺席安全跳过。
 *
 * <p><strong>② 真值池轮末快照：</strong>onAgent 完成时点（doOnComplete）读
 * {@code TrustContext.current(ctx).invocations()} 入并发队列，编排器每轮 call 返回后
 * {@link #drainTruthInvocations()} 排空。时序等价性：onAgent 链包在 runLifecycle 外侧
 * （2.0.4 ReActAgent.buildAgentStream），doOnComplete 晚于核心内 Trust hook 的 POST_CALL
 * 与 state 落盘；ctx 即 ensureSessionDefaults 派生 rc（与工具装饰器同源，PR #110 实证）——
 * 旧「hook 的 runtimeContext 与工具记录同源」论证原样成立（MS-32 类注释②）。采集异常仅
 * WARN 降级（任务将在校验门如实红，不阻断 agent 调用）。
 *
 * <p><strong>线程安全：</strong>token 用 {@link LongAdder}、真值用
 * {@link ConcurrentLinkedQueue}（完成信号线程写、编排器线程排空，防御性并发安全——同旧 Hook）。
 */
public final class ReportUsageMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ReportUsageMiddleware.class);

    private final LongAdder totalTokens = new LongAdder();
    private final ConcurrentLinkedQueue<ToolInvocation> truthInvocations = new ConcurrentLinkedQueue<>();

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnNext(this::accumulate)
                .doOnComplete(() -> collectTruth(ctx));
    }

    /** 整任务 token 累计读数（input+output 之和，token）——签名/语义沿 ReportUsageHook。 */
    public long totalTokens() {
        return totalTokens.sum();
    }

    /**
     * 排空已采集真值（编排器每轮 call 返回后消费并入任务级池；排空语义防跨轮重复累计）
     * ——签名/语义沿 ReportUsageHook。
     */
    public List<ToolInvocation> drainTruthInvocations() {
        List<ToolInvocation> drained = new ArrayList<>();
        ToolInvocation invocation;
        while ((invocation = truthInvocations.poll()) != null) {
            drained.add(invocation);
        }
        return drained;
    }

    private void accumulate(AgentEvent event) {
        if (event instanceof ModelCallEndEvent end && end.getUsage() != null) {
            totalTokens.add(end.getUsage().getInputTokens() + (long) end.getUsage().getOutputTokens());
        }
    }

    private void collectTruth(RuntimeContext ctx) {
        try {
            truthInvocations.addAll(TrustContext.current(ctx).invocations());
        } catch (Exception e) {
            log.warn("report truth collect: 回合完成真值快照异常（任务将在校验门如实红）: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: 4 用例全 PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/report/ReportUsageMiddleware.java \
        backend/src/test/java/com/portfolio/invest/agent/report/ReportUsageMiddlewareTest.java
git commit -m "feat(agent): ReportUsageMiddleware——onAgent 单点拦截的研报用量与真值采集

自 ReportUsageHook 等价迁移（hook-migration）：usage 事件源改
ModelCallEndEvent（reasoning+summary 双路），真值快照移至 doOnComplete
（时序等价）；totalTokens/drainTruthInvocations 签名不变。旧 Hook 暂共存，
Task 3 切换后删除。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: ObservabilityAgentMiddleware（TDD，独立新增，旧 Hook 共存）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/agent/observability/ObservabilityAgentMiddleware.java`
- Test: `backend/src/test/java/com/portfolio/invest/agent/observability/ObservabilityAgentMiddlewareTest.java`

**Interfaces:**
- Consumes: 同 Task 1 事件面 + `AgentResultEvent(Msg).getResult()`；`ObservabilityRecorder.recordTurn(TurnObservation, List<ToolCallObservation>)`（域端口）；`TrustAgentHook.lastReport()`（六键并集源）；`TrustWireMessageIdMiddleware.RC_KEY`。
- Produces: `public final class ObservabilityAgentMiddleware implements MiddlewareBase`；构造 `ObservabilityAgentMiddleware(ObservabilityRecorder, TrustAgentHook, ObjectMapper)` 与包私有 `(…, Clock)`（测试钉 createdAt）。Task 4 factory 接线消费。

- [ ] **Step 1: 写失败测试**（自 `ObservabilityAgentHookTest` 口径迁移；完整断言体以原文件为蓝本逐条搬——此处给新事件源脚手架与关键差异用例）

```java
package com.portfolio.invest.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import com.portfolio.invest.domain.observability.TurnObservation;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.middleware.AgentInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * ObservabilityAgentMiddleware 单元测试（hook-migration，沿 ObservabilityAgentHookTest
 * 14 用例口径迁移）：事件源改 onAgent 层——PRE_CALL→入口重置、POST_REASONING/SUMMARY→
 * ModelCallEndEvent、POST_CALL→AgentResultEvent、ErrorEvent→doOnError。
 * 真值注池沿真实 decorator 播种先例（ObservabilityAgentHookTest:160）。
 */
class ObservabilityAgentMiddlewareTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneId.of("UTC"));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObservabilityRecorder recorder = mock(ObservabilityRecorder.class);
    private final TrustAgentHook trustHook =
            new TrustAgentHook(new InvestProperties().getTrust());

    private ObservabilityAgentMiddleware newMiddleware() {
        return new ObservabilityAgentMiddleware(recorder, trustHook, MAPPER, FIXED_CLOCK);
    }

    private static RuntimeContext ctx(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static Flux<AgentEvent> normalStream(String replyId, ChatUsage usage, Msg result) {
        return Flux.just((AgentEvent) new ModelCallEndEvent(replyId, usage),
                new AgentResultEvent(result));
    }

    @AfterEach
    void cleanFallback() {
        TrustContext.reset();
    }

    @Test
    @DisplayName("usage 逐轮累计：两枚 ModelCallEndEvent (100,50)/(30,20) → 130/70/200")
    void givenUsageAcrossModelCalls_whenTurnEnds_thenTokensAccumulated() {
        ObservabilityAgentMiddleware mw = newMiddleware();
        RuntimeContext rc = ctx("7", "conv-1");

        mw.onAgent(null, rc, new AgentInput(List.of()), input -> Flux.just(
                (AgentEvent) new ModelCallEndEvent("r-1", new ChatUsage(100, 50, 0.01)),
                new ModelCallEndEvent("r-2", new ChatUsage(30, 20, 0.01)),
                new AgentResultEvent(msg("m-1")))).blockLast();

        TurnObservation turn = capturedTurn();
        assertThat(turn.promptTokens()).isEqualTo(130);
        assertThat(turn.completionTokens()).isEqualTo(70);
        assertThat(turn.totalTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("全程无 usage：token 三分量为 null（缺失不进插值口径不变）")
    void givenUsageMissing_whenTurnEnds_thenTokenFieldsNull() { /* 同上脚手架，usage 全传 null；断言三分量 null */ }

    @Test
    @DisplayName("正常轮：AgentResultEvent 落库 failed=false，createdAt=固定 Clock")
    void givenResultEvent_whenTurnEnds_thenRecordedWithFixedClockTimestamp() {
        // normalStream + ArgumentCaptor；断言 failed()=false 与 recordedAt/createdAt 固定值（原 :230 用例口径）
    }

    @Test
    @DisplayName("recorder 抛异常：正常轮与 failed 轮都不外抛，流原样继续")
    void givenRecorderThrows_whenRecording_thenErrorSwallowedAndStreamUnharmed() {
        // doThrow on recorder.recordTurn —— 正常轮 blockLast 不抛；doOnError 路径：
        // Flux.error 经 onAgent 后 blockLast 抛【原始】错误（非观测异常）——Review Focus #3 钉子
    }

    @Test
    @DisplayName("failed 轮：doOnError 落库 failed=true + 错误摘要截断 200 码点")
    void givenDownstreamError_whenTurnFails_thenFailedTurnRecordedWithTruncatedSummary() { /* 原 :263 用例口径，事件源 Flux.error */ }

    @Test
    @DisplayName("防双写：doOnError 已记录后迟到 AgentResultEvent 不再落第二条")
    void givenErrorThenLateResult_whenBothSeen_thenRecordedOnce() { /* 原 :284 用例口径 */ }

    @Test
    @DisplayName("trust 并集 + identity 护栏：本轮 trust 已处理并入六键；未处理 → trustStats=null")
    void givenTrustReportStates_whenTurnEnds_thenTrustStatsMergedOrNull() { /* 原 :297/:323 双用例口径合并迁移 */ }

    @Test
    @DisplayName("标识符：userId/sessionId 直取 ctx；messageId 优先 RC_KEY 复合 id 回退 Msg id")
    void givenIdentifiers_whenTurnEnds_thenTakenFromContextOrFallback() { /* 原 :345/:360 双用例：rc.put(TrustWireMessageIdMiddleware.RC_KEY, "…-text") */ }

    @Test
    @DisplayName("工具实录映射：TrustContext 当轮实录 → 观测行全字段（decorator 播种）")
    void givenToolInvocationsInTrustContext_whenTurnEnds_thenMappedObservations() { /* 原 :377 用例整体迁移（decorator 播种 helper 原样搬） */ }

    private TurnObservation capturedTurn() {
        ArgumentCaptor<TurnObservation> captor = ArgumentCaptor.forClass(TurnObservation.class);
        verify(recorder).recordTurn(captor.capture(), any());
        return captor.getValue();
    }

    private static Msg msg(String id) { /* 同 Task 1 */ }
}
```

大括号注释的用例体：实现者以 `ObservabilityAgentHookTest` 对应用例（DisplayName 已标注原行号）的断言体为蓝本，只替换「触发方式」为新事件源——断言与口径**逐字保留**。原 `:419 hook 顺序` 用例**不迁移**（priority 机制随 Hook 删除退役，装配次序归 Task 4 factory 测试）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.observability.ObservabilityAgentMiddlewareTest" --console=plain`
Expected: 编译失败 `cannot find symbol: class ObservabilityAgentMiddleware`

- [ ] **Step 3: 最小实现**（方法体自 ObservabilityAgentHook 逐段迁移；差异仅事件源与 ctx 传参）

```java
package com.portfolio.invest.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.agent.trust.ToolInvocation;
import com.portfolio.invest.agent.trust.TrustAgentHook;
import com.portfolio.invest.agent.trust.TrustContext;
import com.portfolio.invest.agent.trust.TrustWireMessageIdMiddleware;
import com.portfolio.invest.domain.observability.ObservabilityRecorder;
import com.portfolio.invest.domain.observability.ToolCallObservation;
import com.portfolio.invest.domain.observability.TurnObservation;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.Msg;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * 轮级观测中间件（hook-migration，自 ObservabilityAgentHook 等价迁移）：单点 onAgent 拦截
 * ——入口回合重置与起点、doOnNext 过滤 ModelCallEndEvent（usage 双路累计）与
 * AgentResultEvent（正常轮落库）、doOnError（failed 轮落库）。设计规格 §2.1 映射表。
 *
 * <p><strong>装配序机制替换：</strong>旧 Hook 依赖 priority=200 保证晚于 trust 读取
 * lastReport()；中间件在 onAgent 链外侧，完成信号天然晚于核心内 Trust hook 的 POST_CALL
 * ——无 priority、注册序不敏感（设计规格 §一事实 1）。
 *
 * <p><strong>有意简化（设计规格 §三.1）：</strong>时延起点 RC typed 属性取消，降级实例
 * 字段 startNanos（原 fallbackStartNanos 转正；全仓无跨组件读者）。
 *
 * <p><strong>降级红线（NFR-3）：</strong>全部拦截逻辑异常吞并留痕（观测 ERROR/真值 WARN），
 * 事件原样放行；doOnError 落库异常不得覆盖原始 Agent 错误。
 */
public final class ObservabilityAgentMiddleware implements MiddlewareBase {

    /** failed 轮错误摘要上限（码点）：human-facing 摘要，全文走 ERROR 日志。 */
    static final int ERROR_SUMMARY_MAX_CHARS = 200;

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAgentMiddleware.class);
    private static final DateTimeFormatter CALL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ObservabilityRecorder recorder;
    private final TrustAgentHook trustHook;
    private final ObjectMapper mapper;
    private final Clock clock;

    private volatile boolean turnRecorded;
    private volatile TrustTurnReport turnStartReport;
    private volatile Long startNanos;
    private long promptTokens;
    private long completionTokens;
    private boolean usageSeen;

    public ObservabilityAgentMiddleware(ObservabilityRecorder recorder, TrustAgentHook trustHook,
            ObjectMapper mapper) {
        this(recorder, trustHook, mapper, Clock.systemUTC());
    }

    ObservabilityAgentMiddleware(ObservabilityRecorder recorder, TrustAgentHook trustHook,
            ObjectMapper mapper, Clock clock) {
        this.recorder = recorder;
        this.trustHook = trustHook;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        onTurnStart();
        return next.apply(input)
                .doOnNext(event -> onEvent(ctx, event))
                .doOnError(error -> safeRecord(ctx, null, true, error));
    }

    // ———— 回合边界（入口即旧 PRE_CALL：重置 + trust 报告 identity 基线 + 起点） ————

    private void onTurnStart() {
        turnRecorded = false;
        promptTokens = 0;
        completionTokens = 0;
        usageSeen = false;
        turnStartReport = trustHook == null ? null : trustHook.lastReport();
        startNanos = System.nanoTime();
    }

    // ———— 事件分流（usage 累计 / 正常轮落库） ————

    private void onEvent(RuntimeContext ctx, AgentEvent event) {
        try {
            if (event instanceof ModelCallEndEvent end) {
                accumulate(end.getUsage());
            } else if (event instanceof AgentResultEvent result) {
                safeRecord(ctx, result.getResult(), false, null);
            }
        } catch (Exception e) {
            log.error("observability.middleware: 轮级采集失败已吞并（error={}）", e.getMessage(), e);
        }
    }

    private void accumulate(ChatUsage usage) {
        if (usage == null) {
            return;
        }
        promptTokens += usage.getInputTokens();
        completionTokens += usage.getOutputTokens();
        usageSeen = true;
    }

    /** 落库自吞并（Review Focus #3：doOnError 路径的异常不得覆盖原始 Agent 错误）。 */
    private void safeRecord(RuntimeContext ctx, Msg finalMessage, boolean failed, Throwable error) {
        try {
            record(ctx, finalMessage, failed, error);
        } catch (Exception e) {
            log.error("observability.middleware: 轮级落库失败已吞并（failed={}, error={}）",
                    failed, e.getMessage(), e);
        }
    }

    // ———— 落库组装（自 Hook record 原样迁移；runtimeContext 字段 → ctx 参数） ————

    private void record(RuntimeContext rc, Msg finalMessage, boolean failed, Throwable error) {
        if (turnRecorded) {
            return;
        }
        turnRecorded = true;
        Long userId = userId(rc);
        String conversationId = rc == null ? null : rc.getSessionId();
        String messageId = messageId(rc, finalMessage);
        List<ToolCallObservation> tools = mapTools(rc, userId, conversationId, messageId);
        TurnObservation turn = new TurnObservation(
                userId, conversationId, messageId,
                usageSeen ? (int) promptTokens : null,
                usageSeen ? (int) completionTokens : null,
                usageSeen ? (int) (promptTokens + completionTokens) : null,
                durationMs(), tools.size(), failed, errorSummary(error),
                trustStats(), Instant.now(clock));
        recorder.recordTurn(turn, tools);
    }

    /** trust 并集六键标量子集（identity 护栏：本轮 trust 无新报告 → null）——原样迁移。 */
    private Map<String, Object> trustStats() { /* 自 ObservabilityAgentHook.trustStats 逐字迁移（211-228 行） */ }

    /** 当轮工具实录 → 观测行（TrustContext.current(rc)，历史池不进观测）——原样迁移。 */
    private List<ToolCallObservation> mapTools(RuntimeContext rc, Long userId,
            String conversationId, String messageId) { /* 自 Hook mapTools 逐字迁移（231-255 行） */ }

    /** args 序列化失败降级 NULL——原样迁移。 */
    private String argsJson(ToolInvocation invocation) { /* 自 Hook argsJson 逐字迁移（258-270 行） */ }

    /** calledAt best-effort（CALL 线 asOf 即调用时刻）——原样迁移。 */
    private Instant calledAt(ToolInvocation invocation) { /* 自 Hook calledAt 逐字迁移（273-282 行） */ }

    /** 时延：startNanos 折毫秒，下限 0 防时钟回退，无起点 null（RC 键已简化为实例字段）。 */
    private Long durationMs() {
        Long start = startNanos;
        if (start == null) {
            return null;
        }
        return Math.max(0L, (System.nanoTime() - start) / 1_000_000L);
    }

    private static Long userId(RuntimeContext rc) { /* 自 Hook userId 逐字迁移（298-311 行） */ }

    private static String messageId(RuntimeContext rc, Msg finalMessage) { /* 自 Hook messageId 逐字迁移（313-321 行，含 RC_KEY 复合 id） */ }

    private static String errorSummary(Throwable error) { /* 自 Hook errorSummary 逐字迁移（324-334 行） */ }
}
```

「逐字迁移」标注的私有方法：实现者 Read `ObservabilityAgentHook.java` 对应行段原样复制（该方法内对 `runtimeContext` 字段的引用改为参数 `rc`），**不改口径**。

- [ ] **Step 4: 跑测试确认通过**（同 Step 2，全部用例含补全体 PASS）
- [ ] **Step 5: 提交**（同 Task 1 模式，信息 `feat(agent): ObservabilityAgentMiddleware——onAgent 单点拦截的轮级观测（priority 装配序退役）`）

### Task 3: ReportUsage 切换（涟漪 + 删旧 Hook）

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/report/ReportAgentRunnerFactory.java`（接口签名 :19）
- Modify: `backend/src/main/java/com/portfolio/invest/agent/report/HarnessReportAgentRunner.java`（:28/:35 参数类型）
- Modify: `backend/src/main/java/com/portfolio/invest/agent/report/ReportWorkflowOrchestrator.java`（:37-41/:112/:118/:132/:144/:184/:277/:283 全部 `ReportUsageHook`→`ReportUsageMiddleware` + javadoc 引用）
- Modify: `backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java`（buildReportAgent 参数与装配）
- Modify: `backend/src/bdd/java/com/portfolio/invest/bdd/CucumberSpringConfig.java`（ScriptedReportRunnerFactory :173/:182-186）
- Modify: `backend/src/bdd/java/com/portfolio/invest/bdd/steps/ReportSteps.java`（:39 注释 + 类型引用）
- Modify: `backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryReportTest.java`（:9/:129/:141 类型替换 + 装配断言改 middleware 面）
- Modify: `backend/src/test/java/com/portfolio/invest/agent/report/ReportWorkflowOrchestratorTest.java`（:67/:279/:439/:551-552/:625/:636/:657 类型替换；`charge` 夹具事件源改 `new ModelCallEndEvent("charge", new ChatUsage(tokens, 0, 0.01))` 合成流过 `onAgent`）
- Modify: `backend/src/test/java/com/portfolio/invest/agent/report/HarnessReportAgentRunnerTest.java`（:27）
- Modify: `backend/src/integrationTest/java/com/portfolio/invest/agent/report/ReportWorkflowOrchestratorIntegrationTest.java`（:47 注释/:207 `charge` 夹具同上）
- Modify: `backend/src/test/java/com/portfolio/invest/agent/trust/ReportProbeTest.java`（:31/:153 探针 3 改 ModelCallEndEvent 事件源，探针标题与注释同步）
- Delete: `backend/src/main/java/com/portfolio/invest/agent/report/ReportUsageHook.java`
- Delete: `backend/src/test/java/com/portfolio/invest/agent/report/ReportUsageHookTest.java`

**Interfaces:**
- Consumes: Task 1 `ReportUsageMiddleware(totalTokens()/drainTruthInvocations())`。
- Produces: `ReportAgentRunnerFactory.create(Long, ReportUsageMiddleware)`（新签名，Task 6 无消费但 BDD fake 与生产 bean `ReportTaskExecutorConfig` 依赖）；`buildReportAgent(Long, ReportUsageMiddleware)`。

- [ ] **Step 1: 全部类型替换 + factory 切装配**（机械替换 `ReportUsageHook`→`ReportUsageMiddleware`；`HarnessAgentFactory.buildReportAgent` 内 `.hook(usageHook)` → `.middleware(usageMiddleware)`，javadoc ⑤「追加 ReportUsageHook」同步改；Orchestrator javadoc :37-41 的 ReportUsageHook 引用同步改）
- [ ] **Step 2: BDD 剧本改 onAgent 模拟**——`CucumberSpringConfig:182-186` 的 `usageHook.setRuntimeContext(derived); usageHook.onEvent(new PostCallEvent(...)).block()` 替换为：

```java
usageMiddleware.onAgent(null, derived, new AgentInput(List.of()),
        input -> reactor.core.publisher.Flux.just(
                (io.agentscope.core.event.AgentEvent) new io.agentscope.core.event.AgentResultEvent(
                        Msg.builder().id("round-" + System.nanoTime()).role(MsgRole.ASSISTANT)
                                .textContent("轮收尾").build())))
        .blockLast();
```

剧本类注释「轮末代拟 harness 接线」段同步改写（模拟 onAgent 完成流；**drain 通道契约注释原样保留**）。
- [ ] **Step 3: 删除两个旧文件**（`git rm`）
- [ ] **Step 4: 编译三源集**

Run: `cd backend && ./gradlew compileJava compileTestJava compileIntegrationTestJava compileBddJava --console=plain`
Expected: PASS（残留引用会在此暴露——全仓 grep `ReportUsageHook` 应只剩 docs 历史文档）

- [ ] **Step 5: 跑 report/agent 包 + BDD**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.*" --console=plain && ./gradlew bdd --console=plain`
Expected: 全 PASS（BDD 剧本即 Review Focus #2/#5 钉子）

- [ ] **Step 6: 提交**（信息 `refactor(agent): 研报用量/真值链切换 ReportUsageMiddleware 并删除 ReportUsageHook`）

### Task 4: Observability 切换 + 桥接瘦身 + 删旧 Hook

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java`（build :90-92 与 buildReportAgent :128-131：`.hook(new ObservabilityAgentHook(...))` → `.middleware(observability)`（局部变量 `ObservabilityAgentMiddleware observability = new ObservabilityAgentMiddleware(observabilityRecorder, trustHook, mapper)`）；桥接消费者瘦身为 `new RuntimeContextBridgeMiddleware(trustHook::setRuntimeContext)`）
- Modify: `backend/src/main/java/com/portfolio/invest/agent/RuntimeContextBridgeMiddleware.java`（javadoc「三个生产 Hook」→「TrustAgentHook（留守，触发条件见 features/hook-migration/02-设计规格 §五）」）
- Modify: `backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryTest.java`（:73-95 装配用例：hooks 断言仅剩 Trust 单元素；middlewares 断言含 observability/bridge/wireId；`consumerCount()==1`；桥接 order==MAX_VALUE 保留）
- Modify: `backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryReportTest.java`（同款：hooks 仅 Trust，middlewares 四件套含 usage）
- Delete: `backend/src/main/java/com/portfolio/invest/agent/observability/ObservabilityAgentHook.java`
- Delete: `backend/src/test/java/com/portfolio/invest/agent/observability/ObservabilityAgentHookTest.java`

**Interfaces:**
- Consumes: Task 2 `ObservabilityAgentMiddleware(ObservabilityRecorder, TrustAgentHook, ObjectMapper)`。
- Produces: **验收口径达成**——`grep -r "io.agentscope.core.hook" backend/src/main` 仅剩 TrustAgentHook。

- [ ] **Step 1: factory 双装配点切换 + 桥接瘦身 + javadoc**（`build(Long)` 除「Hook 装配→中间件装配」外零变更——红线）
- [ ] **Step 2: 删除两个旧文件**（`git rm`）
- [ ] **Step 3: 适配两 factory 测试**（断言面按上；`ObservabilityAgentHook`/`ObservabilityAgentHookTest` 引用清零）
- [ ] **Step 4: 验收 grep + agent 包全量**

Run: `grep -rn "io.agentscope.core.hook" backend/src/main --include="*.java" | grep -v TrustAgentHook; cd backend && ./gradlew test --tests "com.portfolio.invest.agent.*" --console=plain`
Expected: grep 零输出；agent 包全 PASS

- [ ] **Step 5: 提交**（信息 `refactor(agent): 轮级观测切换 ObservabilityAgentMiddleware——Hook 引用面收敛至 TrustAgentHook`）

### Task 5: 全量门禁 + 文档与留守注记

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/trust/TrustAgentHook.java`（javadoc 钉版段追加留守裁决与触发条件，指向 02-设计规格 §五；`@SuppressWarnings`/逻辑零改动）
- Modify: `AGENTS.md:61`（`report/` 描述「`ReportUsageHook` 用量与真值钩子」→「`ReportUsageMiddleware` 用量与真值中间件」）
- Modify: `docs/technology/conventions/01-后端DDD分包规范.md:61`（同款替换）
- Modify: `docs/technology/modules/16-评测与可观测性.md`（:4 代码锚定行、:13/:50 机制描述、:91 测试清单——ObservabilityAgentHook→ObservabilityAgentMiddleware，事件源描述同步；现状陈述才改）
- Modify: `features/hook-migration/03-实施计划/P1-hook-migration.md`（状态行回填）

- [ ] **Step 1: 全量门禁**

Run: `cd backend && ./gradlew cleanTest cleanIntegrationTest cleanBdd --console=plain && make test-backend`
Expected: 全绿（test/integrationTest/bdd/ArchUnit/JaCoCo 双门槛）

- [ ] **Step 2: 文档现状陈述替换**（逐处 grep 判定「现状 vs 历史」；`docs/roadmap/2026-10-05` 与 `docs/function/modules/19` 与 research 调研为历史保真不动；modules/01 若有机理句提及 hook 链一并对齐）
- [ ] **Step 3: `make check-links` 零断链**（注意：**不要**用 `| tail` 吞退出码）
- [ ] **Step 4: 提交**（信息 `docs: hook-migration 现状注记对齐 + TrustAgentHook 留守触发条件`）

### Task 6: 真流回归 + PR

**Files:** 无代码改动（验证 + 回填 + PR）。

- [ ] **Step 1: `make smoke`**——观测证据链不降：turn_observation 落行 user_id 非空、tokenUsage>0（研报路，Review Focus #1 钉子）、trust Custom 事件正常（Trust 零改动旁证）。红了先归因（环境 vs 回归）。
- [ ] **Step 2: 清 `.next` 后 `cd frontend && pnpm test:e2e`**——全绿（含 HITL 审批卡）。
- [ ] **Step 3: 回填计划状态行（已完成，PR #N 占位）+ `features/README.md` 索引状态更新 + `make check-links`；提交并 `git push -u origin chore/hook-migration`；`gh pr create --base main`（标题 `refactor: Hook forRemoval 专项——两观测 Hook 迁 Middleware，Trust 留守`；描述含规格链接、能力矩阵结论、门禁与 smoke/e2e 数字、Trust 触发条件；尾行 Generated with Claude Code）。**不合并 PR**（合并由用户拍板）。**复盘总结（08-复盘总结/）合并后回填**（特性流程惯例）。

---

## 计划偏差预留（执行时裁决）

- `ToolInvocation` 夹具构造签名以真实文件为准（Task 1 已注明核对步骤）。
- onAgent 层事件可见性若与 §一事实 2 不符（某事件不上行），以「落库口径等价」为准绳找最近等价事件源，裁决入账。
- `charge` 夹具若被多处测试以不同形态复用，逐处替换不抽公共（YAGNI）。
