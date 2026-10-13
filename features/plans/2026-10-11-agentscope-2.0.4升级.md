# AgentScope Java 2.0.3 → 2.0.4 升级计划（2026-10-11）

> 依据：[docs/research/2026-10-11-AgentScope-Java版本演进调研.md](../../docs/research/2026-10-11-AgentScope-Java版本演进调研.md)（版本现状/逐版本变更/影响评估）
> 分支：`chore/upgrade-agentscope-2.0.4`
> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development（本仓既定执行方式）来逐任务实施本计划。步骤用 checkbox（`- [ ]`）跟踪。
> 状态：**已完成（2026-10-12，PR #110）**——smoke §1–§8 全绿（§6 首轮环境假红处置后二轮全绿）、e2e 全绿（HITL 2/2 补跑通过）；§9 研报节两红（模型侧归因非升级回归），详见文末「执行偏差记录」

**Goal:** 四构件 2.0.3 → 2.0.4，三个 `RuntimeContextAware` Hook 的 rc 供给迁到自建桥接中间件，行为零回归。

**Architecture:** 2.0.4 删除了 `io.agentscope.core.hook.RuntimeContextAware`（PR #3279，官方口径「context 访问点收敛到 middleware 与工具参数」），但 Hook 系本身仍在（`@Deprecated(forRemoval)` 未兑现）。因此不做整体 Hook→Middleware 重写（TrustAgentHook 的 Msg 改写锚点是 MS-29 B5 实证取舍，Middleware 无等价口），只新建一枚 `RuntimeContextBridgeMiddleware` 在 `onAgent` 入口把派生 rc 送抵三个 Hook 的 `setRuntimeContext`（保留为普通方法、签名不变），时序契约与 2.0.3 框架侧回调一致。先在 2.0.3 上完成全部改造（行为中性：桥接与框架回调同 rc 幂等），最后一步切版本号。

**Tech Stack:** Spring Boot 4 + AgentScope Java 2.0.4（core / extensions-model-openai / agui-spring-boot-starter / harness）+ JUnit5/AssertJ + Reactor。

**Spec:** 调研报告 §四/§五（影响评估与建议：升 2.0.4、唯一硬碰撞即本计划主体；2.1.0-BETA1 不采）。

## Global Constraints

- 目标版本 **2.0.4**，禁止引入 2.1.0-BETA1（预发布 + harness memory/session 大重构）。
- **对话链路红线**：`HarnessAgentFactory.build(Long)` 与 `systemPrompt(userId)` 行为零变更（既有类注释红线，同包字节级比对测试锁定）。
- `setRuntimeContext` 方法名与签名不变（2.0.3 框架回调位→普通方法）：六处测试文件 + `CucumberSpringConfig` + `ReportWorkflowOrchestratorIntegrationTest` 直接调用它，改名即断。
- 覆盖率门槛 ≥80%（JaCoCo 指令+分支，挂 `make test`）；ArchUnit 21 条规则不得违例；`make check-links` 零断链。
- 提交信息按仓内惯例（`feat(agent):`/`refactor(agent):`/`chore(deps):`/`docs:` 前缀），尾行 Co-Authored-By。
- 不动 schema（Flyway 无涉）、不动前端。

## 0. 已核实事实（计划依据，2.0.4 源码实证）

以下均在 repo1 的 agentscope-core/harness **2.0.4 sources jar** 逐条核实（对照 2.0.3 同款）：

1. `io.agentscope.core.hook.RuntimeContextAware` **已删除**（2.0.3 存在）；`Hook`/`HookEvent`/`PreCallEvent`/`PostReasoningEvent`/`PostSummaryEvent`/`PostCallEvent` 均存续。
2. Hook 事件构造器签名两版一致：`PostReasoningEvent(Agent, String, GenerateOptions, Msg)`、`PostSummaryEvent` 同构、`PostCallEvent(Agent, Msg)`、`PreCallEvent(Agent, List<Msg>)`——既有测试直接 new 事件的代码零改动。
3. `MiddlewareBase`（`io.agentscope.core.middleware`）五拦截点 `onAgent/onReasoning/onActing/onModelCall/onSystemPrompt`，均收 `(Agent, RuntimeContext ctx, InputX, next)`；`order()` 大者最外层（`ReActAgent` 内 `middlewares.sort(comparingInt(MiddlewareBase::order).reversed())`）。
4. **时序**：`onAgent` 链包在整个 `runLifecycle`（shutdown guard、序列化门、pre/post hooks、tracing）外侧——PRE_CALL 钩子在链内侧触发。桥接在 onAgent 入口 set rc ⇒ 先于 PRE_CALL，与 2.0.3 `setRuntimeContext` 契约一致（两 Hook 的 PRE_CALL 处理均立即用 rc）。
5. **同源性**：`HarnessAgent.call/streamEvents` 入口 `ensureSessionDefaults(ctx?:empty())` 派生 `effective` rc 再传 `delegate.call(msgs, effective)`——中间件 onAgent 收到的 ctx 即 effective（2.0.4 HarnessAgent.java:748-751 实证）。`TrustContext` 主通道是 **rc typed 属性**（`rc.put(TrustContext.class, pool)`）而非 rc 身份，工具侧 `buildMergedRuntimeContext` 的实例拷贝复制 extras、池引用随行——桥接对拷贝不敏感（S1 型「真值池恒空」不复发的依据）。
6. `ReActAgent.beforeAgentExecution` 做 `ctx.setAgentState(scope.state)`（在桥接之后、PRE_CALL 之前）——Hook 在 POST_REASONING 读 `getAgentState().getContext()` 时序不变。
7. 内省口存续：`HarnessAgent.getDelegate()`、`AgentBase.getHooks()`、`ReActAgent.getMiddlewares()`（2.0.4 均在）——既有 `HarnessAgentFactoryTest:83-93` 装配断言风格可沿用。
8. 其余符号：`AgentEventEmitter.fromContext(ContextView)`、`ChatUsage.getInputTokens()/getOutputTokens()`（`io.agentscope.core.model`，两版同包）、`HarnessAgent.Builder.hook()/middleware()/middlewares(List)` 均存续。
9. `AgentInput` 为 record：`new AgentInput(List.of())`；`AgentEndEvent(String replyId)` 可作测试事件。
10. 调研已对照项目 59 个 `io.agentscope` import：除 `RuntimeContextAware` 外无编译碰撞。

## Review Focus

（计划自检：spec 隐含但无任务测试覆盖、最可能咬人的五类输入/失效模式，每条标注归属任务）

1. **rc 缺席（null ctx）**：裸调用/异常装配下桥接送 null 给 Hook——行为必须同 2.0.3 缺席语义（池走 ThreadLocal 兜底、观测记 null userId）→ Task 1 null 用例锁定「null 照送不拦截」。
2. **桥接时序错位（晚于 PRE_CALL）**：`TrustContext.fresh` 挂不上池 ⇒ 工具真值全丢（S1 复发形态）→ 由事实 4 的 order()=MAX_VALUE 保证；真流实证归 Task 6（smoke 的 trust.anchors 事件 + 观测落行）。
3. **hook/桥接装配失同步**：未来加 Hook 忘桥接（静默 rc-null 回归）→ Task 3 以 `consumerCount == hooks.size()` 断言锁定。
4. **研报真值池不同源**：派生 rc 实例不一致 ⇒ `ReportUsageHook` 真值池恒空（S1 原案）→ 事实 5 保证 + 既有 `ReportWorkflowOrchestratorIntegrationTest` 回归（Task 4 全量跑）。
5. **版本切换的测试面破裂**：2.0.4 接口若新增抽象方法，测试桩 `STUB_AGENT`（`TrustAgentHookTest.java:36` 匿名 `Agent` 实现）编译即红 → Task 4 编译点全量暴露，兜底 `make test-backend`。

---

### Task 1: RuntimeContextBridgeMiddleware（TDD）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/agent/RuntimeContextBridgeMiddleware.java`
- Test: `backend/src/test/java/com/portfolio/invest/agent/RuntimeContextBridgeMiddlewareTest.java`

**Interfaces:**
- Consumes: `io.agentscope.core.middleware.MiddlewareBase`（2.0.3/2.0.4 双版同签名）
- Produces: `public final class RuntimeContextBridgeMiddleware implements MiddlewareBase`；构造 `RuntimeContextBridgeMiddleware(Consumer<RuntimeContext>... consumers)` 与 `(List<Consumer<RuntimeContext>>)`；包私有 `int consumerCount()`（Task 3 断言口）；`order()` 恒 `Integer.MAX_VALUE`。

- [x] **Step 1: 写失败测试**

```java
package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * rc 桥接中间件单元测试（agentscope 2.0.4 升级）：ctx 依序送抵全部消费者后放行 next；
 * order 最外层；null ctx 照送（Hook 侧自防 null，与 2.0.3 setRuntimeContext 缺席语义一致）。
 * agent 参数不解引用（传 null），事件流原样透传。
 */
class RuntimeContextBridgeMiddlewareTest {

    @Test
    @DisplayName("onAgent 把 ctx 依序送抵全部消费者后放行 next，事件流原样透传")
    void deliversCtxThenDelegates() {
        List<String> seq = new ArrayList<>();
        List<RuntimeContext> seen = new ArrayList<>();
        RuntimeContext ctx = RuntimeContext.builder().userId("42").sessionId("s-1").build();
        RuntimeContextBridgeMiddleware mw = new RuntimeContextBridgeMiddleware(
                rc -> { seen.add(rc); seq.add("c1"); },
                rc -> { seen.add(rc); seq.add("c2"); });
        AgentEndEvent end = new AgentEndEvent("r-1");

        Flux<AgentEvent> out = mw.onAgent(null, ctx, new AgentInput(List.of()),
                input -> { seq.add("next"); return Flux.just(end); });

        assertThat(out.collectList().block()).containsExactly(end);
        assertThat(seen).containsExactly(ctx, ctx);
        assertThat(seq).containsExactly("c1", "c2", "next");
    }

    @Test
    @DisplayName("order 恒为 Integer.MAX_VALUE——中间件链降序排序下最外层、先于一切")
    void orderIsOutermost() {
        assertThat(new RuntimeContextBridgeMiddleware(rc -> { }).order())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("null ctx 照送不拦截（裸调用路径，Hook 侧自防 null）")
    void nullCtxStillDelivered() {
        List<RuntimeContext> seen = new ArrayList<>();
        RuntimeContextBridgeMiddleware mw = new RuntimeContextBridgeMiddleware(seen::add);
        mw.onAgent(null, null, new AgentInput(List.of()), input -> Flux.empty())
                .collectList().block();
        assertThat(seen).singleElement().isNull();
    }

    @Test
    @DisplayName("consumerCount 暴露消费者数（装配断言口）")
    void consumerCountReflectsConsumers() {
        assertThat(new RuntimeContextBridgeMiddleware(rc -> { }, rc -> { }).consumerCount())
                .isEqualTo(2);
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.RuntimeContextBridgeMiddlewareTest" --console=plain`
Expected: 编译失败，`cannot find symbol: class RuntimeContextBridgeMiddleware`

- [x] **Step 3: 最小实现**

```java
package com.portfolio.invest.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * rc 桥接中间件（agentscope 2.0.4 升级）：2.0.4 删除
 * {@code io.agentscope.core.hook.RuntimeContextAware}（PR #3279，官方口径「context 访问点
 * 收敛到 middleware 与工具参数」），三个生产 Hook（TrustAgentHook/ObservabilityAgentHook/
 * ReportUsageHook）的 rc 供给改由本中间件承担——onAgent 入口把派生 rc 逐个送抵消费者
 * （{@code Hook::setRuntimeContext} 方法引用），时序先于 runLifecycle 内的 PRE_CALL 钩子
 * （onAgent 链包在整个生命周期外侧，2.0.4 ReActAgent.buildAgentStream 源码实证），与 2.0.3
 * 框架侧 setRuntimeContext 时序契约一致。
 *
 * <p><strong>同源性（TrustContext 池键安全）：</strong>HarnessAgent 入口
 * {@code ensureSessionDefaults} 派生的 effective rc 即到达本中间件的 ctx；TrustContext 主通道
 * 是 rc typed 属性而非 rc 身份，工具侧 {@code buildMergedRuntimeContext} 的实例拷贝复制
 * extras、池引用随行——桥接对拷贝不敏感（S1 型「真值池恒空」不复发）。
 *
 * <p><strong>order()=Integer.MAX_VALUE：</strong>链按 order 降序，桥接恒为最外层、先于一切
 * （含 TrustWireMessageIdMiddleware）。null 容忍：ctx 可能缺席（裸调用路径），消费者
 * （Hook setter）自防 null。2.0.3 上与框架侧回调同 rc 幂等共存（升级前行为中性）。
 */
public final class RuntimeContextBridgeMiddleware implements MiddlewareBase {

    private final List<Consumer<RuntimeContext>> consumers;

    @SafeVarargs
    public RuntimeContextBridgeMiddleware(Consumer<RuntimeContext>... consumers) {
        this(List.of(consumers));
    }

    public RuntimeContextBridgeMiddleware(List<Consumer<RuntimeContext>> consumers) {
        this.consumers = List.copyOf(consumers);
    }

    /** 装配断言口（HarnessAgentFactoryTest 消费者数与 hook 数同源锁定）。 */
    int consumerCount() {
        return consumers.size();
    }

    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        for (Consumer<RuntimeContext> consumer : consumers) {
            consumer.accept(ctx);
        }
        return next.apply(input);
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.RuntimeContextBridgeMiddlewareTest" --console=plain`
Expected: 4 个用例全 PASS

- [x] **Step 5: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/RuntimeContextBridgeMiddleware.java \
        backend/src/test/java/com/portfolio/invest/agent/RuntimeContextBridgeMiddlewareTest.java
git commit -m "feat(agent): rc 桥接中间件——onAgent 入口向 Hook 消费者送抵派生 RuntimeContext

2.0.4 删除 RuntimeContextAware 的替代供给通道；order=MAX_VALUE 恒最外层，
先于 PRE_CALL（与 2.0.3 框架回调时序契约一致）。升级前行为中性（同 rc 幂等）。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 2: 三个 Hook 摘除 RuntimeContextAware

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/trust/TrustAgentHook.java`（:13 import、:65 类签名、:98-99 `@Override`、:48-50 javadoc）
- Modify: `backend/src/main/java/com/portfolio/invest/agent/observability/ObservabilityAgentHook.java`（:20、:78、:130-131、:74-76）
- Modify: `backend/src/main/java/com/portfolio/invest/agent/report/ReportUsageHook.java`（:11、:53、:61-62、:48-51）

**Interfaces:**
- Consumes: Task 1 的桥接中间件（javadoc 引用）。
- Produces: 三个 Hook 各保留 `public void setRuntimeContext(RuntimeContext rc)` 为**普通公开方法**（签名/方法体不变）——测试与编排器的直接调用面零改动。

- [x] **Step 1: 跑三个 Hook 既有测试确认基线绿**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.trust.TrustAgentHookTest" --tests "com.portfolio.invest.agent.observability.ObservabilityAgentHookTest" --tests "com.portfolio.invest.agent.report.ReportUsageHookTest" --console=plain`
Expected: 全 PASS（行为基线）

- [x] **Step 2: 逐文件三处机械修改 + javadoc 注记**

每个文件同款三处（以 TrustAgentHook 为例）：

```java
// 1) 删 import：
- import io.agentscope.core.hook.RuntimeContextAware;

// 2) 类签名：
- public class TrustAgentHook implements Hook, RuntimeContextAware {
+ public class TrustAgentHook implements Hook {

// 3) setRuntimeContext 去掉 @Override（方法体原样保留），上方加注释：
    /** rc 供给口：2.0.3 由框架经 RuntimeContextAware 回调；2.0.4 起由
     *  {@link com.portfolio.invest.agent.RuntimeContextBridgeMiddleware} 在 onAgent 入口送抵
     *  （时序契约一致，先于 PRE_CALL）。签名不变——测试/编排器直接调用兼容面。 */
    public void setRuntimeContext(RuntimeContext rc) {
        this.runtimeContext = rc;
    }
```

javadoc 钉版注记三处同款改写（各文件类注释里「Hook API 2.0.3 标记 forRemoval，仓库钉版 2.0.3」段）：

```java
// 旧（TrustAgentHook:48-50 例）：
//   Middleware 路线（无弃用标记）只能改事件流、够不到 state/返回值的 Msg 改写——不采用，
//   详见任务报告取舍记录。Hook API 2.0.3 标记 forRemoval，仓库钉版 2.0.3，升级时需随上游
//   替代口迁移。
// 新：
//   Middleware 路线（无弃用标记）只能改事件流、够不到 state/返回值的 Msg 改写——不采用，
//   详见任务报告取舍记录（MS-29 B5 历史结论保真）。Hook API 2.0.4 仍在（forRemoval 未
//   兑现）、仓库钉版 2.0.4；rc 供给自 2.0.4 起经 RuntimeContextBridgeMiddleware（框架侧
//   RuntimeContextAware 已删，PR #3279）。
```

`@SuppressWarnings("deprecation")`（onEvent 上）三处全部**保留**（Hook 仍 deprecated）。

- [x] **Step 3: 复跑 Step 1 三套测试**

Run: 同 Step 1
Expected: 全 PASS（摘接口不改行为；测试直接调 `setRuntimeContext`，普通方法照常可达）

- [x] **Step 4: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/trust/TrustAgentHook.java \
        backend/src/main/java/com/portfolio/invest/agent/observability/ObservabilityAgentHook.java \
        backend/src/main/java/com/portfolio/invest/agent/report/ReportUsageHook.java
git commit -m "refactor(agent): 三 Hook 摘除 RuntimeContextAware——setRuntimeContext 保留为普通方法

2.0.4 已删该接口；方法签名不变，六处测试与编排器直接调用零改动；
rc 供给由 Task 1 桥接中间件承担（下一任务接线）。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 3: HarnessAgentFactory 双装配点接线

**Files:**
- Modify: `backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java`（build :64-94、buildReportAgent :104-133）
- Test: `backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryTest.java`（扩 :73-95 既有装配用例）
- Test: `backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryReportTest.java`（同款扩）

**Interfaces:**
- Consumes: Task 1 `RuntimeContextBridgeMiddleware(Consumer<RuntimeContext>...)` 与 `consumerCount()`；Task 2 三 Hook 的 `setRuntimeContext(RuntimeContext)`。
- Produces: `build(Long)` / `buildReportAgent(Long, ReportUsageHook)` 装配含桥接（对话链路行为零变更红线不破——装配面增一枚行为中性中间件）。

- [x] **Step 1: 扩装配测试（先红）**

`HarnessAgentFactoryTest` 既有用例「MS-29 B5 + MS-30 B6 挂载」追加桥接断言：

```java
// 既有断言（hooks 序、TrustWireMessageIdMiddleware）保持不动，追加：
List<io.agentscope.core.middleware.MiddlewareBase> middlewares = agent.getDelegate().getMiddlewares();
RuntimeContextBridgeMiddleware bridge = middlewares.stream()
        .filter(RuntimeContextBridgeMiddleware.class::isInstance)
        .map(RuntimeContextBridgeMiddleware.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("rc 桥接中间件未装配"));
// 防「加 hook 忘桥接」：消费者数与 hook 数同源
assertThat(bridge.consumerCount()).isEqualTo(hooks.size());
assertThat(bridge.order()).isEqualTo(Integer.MAX_VALUE);
```

`HarnessAgentFactoryReportTest` 同款（buildReportAgent 路径，`hooks.size()` 为 3：trust/observability/usage）。

- [x] **Step 2: 跑测试确认失败**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.HarnessAgentFactoryTest" --tests "com.portfolio.invest.agent.HarnessAgentFactoryReportTest" --console=plain`
Expected: 新增断言 FAIL——`rc 桥接中间件未装配`

- [x] **Step 3: 接线（两装配点同款）**

`build(Long)`（观测 hook 提局部变量以便方法引用）：

```java
TrustAgentHook trustHook = new TrustAgentHook(trust);
ObservabilityAgentHook observabilityHook =
        new ObservabilityAgentHook(observabilityRecorder, trustHook, mapper);
```

builder 链（其余行不动）：

```java
                .hook(trustHook)
                .hook(observabilityHook)
                // rc 桥接（2.0.4 RuntimeContextAware 删除的供给替代）：最外层先于 PRE_CALL
                .middleware(new RuntimeContextBridgeMiddleware(
                        trustHook::setRuntimeContext, observabilityHook::setRuntimeContext))
                .middleware(new TrustWireMessageIdMiddleware())
```

`buildReportAgent` 同款，桥接多一个消费者：

```java
                .hook(trustHook)
                .hook(observabilityHook)
                .hook(usageHook)
                .middleware(new RuntimeContextBridgeMiddleware(
                        trustHook::setRuntimeContext, observabilityHook::setRuntimeContext,
                        usageHook::setRuntimeContext))
                .middleware(new TrustWireMessageIdMiddleware())
```

- [x] **Step 4: 跑两套装配测试确认通过**

Run: 同 Step 2
Expected: 全 PASS

- [x] **Step 5: 跑 agent 包全量单测（装配无外溢）**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.*" --console=plain`
Expected: 全 PASS

- [x] **Step 6: 提交**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/HarnessAgentFactory.java \
        backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryTest.java \
        backend/src/test/java/com/portfolio/invest/agent/HarnessAgentFactoryReportTest.java
git commit -m "feat(agent): HarnessAgentFactory 双装配点接线 rc 桥接（consumer 数与 hook 数同源锁定）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 4: 版本切换 2.0.3 → 2.0.4

**Files:**
- Modify: `backend/build.gradle:105-112`

**Interfaces:**
- Consumes: Task 1-3 已就位（2.0.4 上唯一编译碰撞 RuntimeContextAware 已摘）。
- Produces: 四构件 2.0.4 依赖树（starter 直依 Boot 4.0.3、BOM 锁 Jackson 2.21.1，调研 §4.1 已证兼容）。

- [x] **Step 1: 改版本号**

```groovy
    // AgentScope Java 2.0.4（2.0.3→2.0.4：RuntimeContextAware 删除 → rc 供给改
    // RuntimeContextBridgeMiddleware；HITL 审批链路修复 #3100/#3104、并发隔离 #3279/#3283；
    // 版本演进与影响评估见 docs/research/2026-10-11-AgentScope-Java版本演进调研.md）
    implementation 'io.agentscope:agentscope-core:2.0.4'
    // DeepSeek 模型支持（OpenAI 兼容扩展）
    implementation 'io.agentscope:agentscope-extensions-model-openai:2.0.4'
    // AG-UI 协议端点
    implementation 'io.agentscope:agentscope-agui-spring-boot-starter:2.0.4'
    // Harness Agent：会话持久化 / 上下文压缩 / 长期记忆
    implementation 'io.agentscope:agentscope-harness:2.0.4'
```

- [x] **Step 2: 编译（主代码 + 三测试源集）**

Run: `cd backend && ./gradlew compileJava compileTestJava compileIntegrationTestJava compileBddJava --console=plain`
Expected: PASS。**应急口**：若 `STUB_AGENT` 桩（`TrustAgentHookTest.java:36` 等匿名 `Agent` 实现）因 2.0.4 接口新增抽象方法编译红——为桩补空实现（同文件既有桩风格），逐个记录到交付注记。

- [x] **Step 3: 后端全量门禁**

Run: `make test-backend`
Expected: 全绿（单元+切片 / Testcontainers 集成 / BDD / ArchUnit / JaCoCo ≥80%）
重点回归面：`ReportWorkflowOrchestratorIntegrationTest`（真值池同源）、HITL 相关 BDD 场景（#3100/#3104 修复面）、`TrustEventWireContractTest`。

- [x] **Step 4: 提交**

```bash
git add backend/build.gradle
git commit -m "chore(deps): agentscope 2.0.3 → 2.0.4（四构件）

HITL 审批链路修复（resume 后保留工具结果 #3100、拒绝发 tool-result+reason #3104）、
并发隔离（#3279/#3283）、ChatUsage 细分 token；Jackson 2.21.1 / Boot 4.0.3 兼容面
调研已证。唯一硬碰撞 RuntimeContextAware 已由 Task 1-3 桥接承接。

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 5: 活文档版本注记

**Files:**
- Modify: `AGENTS.md`（项目概览「AgentScope Java 2.0.3」→ 2.0.4）
- Modify: `README.md`、`docs/technology/README.md`、`docs/technology/architecture/02-技术栈与工程结构.md`、`docs/technology/modules/01-Agent实现.md`、`docs/technology/modules/07-HITL人工审批.md`

**Interfaces:** 无代码接口；只改「现状陈述」类版本字符串。

- [x] **Step 1: 逐文件 grep `2\.0\.3`，仅改现状陈述**

历史事实保真不动：ADR（0001/0010/0011）、`docs/technology/research/**`、`features/**`、build.gradle 注释里「自 2.0.3 实现，PR #2495」（该句陈述的是映射特性引入时点，保留）。
`01-Agent实现.md` / `07-HITL人工审批.md` 中「钉版 2.0.3」类现状句改为 2.0.4 并补一句 rc 桥接（指向 `RuntimeContextBridgeMiddleware`）。

- [x] **Step 2: 链接门禁**

Run: `make check-links`
Expected: OK 零断链

- [x] **Step 3: 提交**

```bash
git add AGENTS.md README.md docs/technology/
git commit -m "docs: 活文档 agentscope 版本注记 2.0.3 → 2.0.4（现状陈述；历史事实保真）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

### Task 6: 真流回归（smoke + e2e）

**Files:** 无代码改动（验证任务，产出记录进 PR 描述与计划状态行）。

- [x] **Step 1: make smoke（真实行情 + AI 对话）**

Run: `make smoke`
Expected: 全绿。桥接真流证据链：trust.anchors/trust.correction Custom 事件携带 rc 源 messageId、turn_observation 落行非零、研报真值池非空。

- [x] **Step 2: 前端 e2e（HITL 审批卡）**

Run: `cd frontend && pnpm test:e2e`
Expected: 全绿（2.0.4 #3100/#3104 修复面 + 桥接后 SSE 流完整性；注意 e2e 复用 .next 须先清——见 dev 教训）。

- [x] **Step 3: 回填计划状态行与索引**

`features/plans/2026-10-11-agentscope-2.0.4升级.md` 头部状态改「已完成（YYYY-MM-DD，PR #N）」，勾选全部任务框；`features/plans/README.md` 索引表加行。开 PR（目标 main，标题 `chore: agentscope 2.0.3 → 2.0.4（rc 桥接迁移）`），调研报告与计划随 PR 同行。

---

## 计划偏差预留（执行时裁决）

- **Task 4 编译红的非预期面**：调研为 import 级对照，方法级/字节码级差异（如 `GenerateOptions` 构造、`Msg.builder()` 面）若冒头，按「最小桩适配 + 交付注记记录」处理，不顺手重构。
- **2.0.5 patch 抢跑**：上游 main 已进 2.0.5-SNAPSHOT 双轨；若执行期间 2.0.5 正式发布，仍按本计划升 2.0.4（变更面已勘明），2.0.5 另立小任务。

---

## 执行偏差记录（2026-10-12，Task 6 真流回归）

- **smoke §9 两红（归因：模型侧，非升级回归）**：两次真 LLM 全链分别以 `VALIDATION_FAILED`（taskId=18，205,457 tokens——草稿缺字面「## 关键假设」标题行，反馈重生成轮耗尽）与 `QUOTA_TASK_CAP`（taskId=19，456,581 tokens > 400k 单任务上限——DRAFTING 自校正轮冗长化，消息数 19→31）终态。归因依据：①失败路径（提示词模板/校验门/重生成循环/配额逻辑）本分支零 diff；②桥接真流证据链全绿——研报内部轮 trust 日志 `verified=93/203`、`sourced=130`，真值池活跃非空（S1「真值池恒空」形态不复现）；③§1–§8 两轮全绿。当日配额台账已结算 662,038/500,000，无法当日三跑复核；台账显示昨日（MS-32 收口期）亦有 538k 用量——研报工作流贴近 cap 运行为既有状态。**遗留观察项**：若 2.0.4 上 §9 持续红，优先排查 2.0.4 ChatUsage 计数口径变化对 cap 的影响（本次无法在授权范围内做 2.0.3 对照 A/B）。
- **环境假红一处（已自行处置，与升级无关）**：首轮 smoke §6 邮件红——`MAIL_SMTP_PASSWORD` 值含 `#`，后端经 `make dev-backend` 启动时被 make `-include` 截断（dev-process-lessons #44 既有教训），SMTP 526 认证失败；改 bash source 完整值重启后端后 §6 真 SMTP 发信绿（分支零触碰 mail/auth 文件）。
- **e2e**：清 `.next` fresh build 后全量 **76 passed / 1 flaky（chat SSE 用例重试通过）/ 20 skipped / 0 failed**；跳过中 18 例为数据态门控（industry/intelligence/screener/wiki 空库双态——高频复发模式 #6 既有接受态）、2 例为 HITL env 门控（本地测试进程缺 `E2E_HITL_MCP_URL`）；后者已带 env 定向补跑 **2/2 通过**（写工具弹卡批准/拒绝不落盘 + 两卡并列逐张批准——2.0.4 #3100/#3104 修复面真浏览器全链验证）。
- **教训回填项未落库（待主会话重裁）**：裁决项「Gradle `--tests` 过滤跑的结果会被后续 `:test` up-to-date 复用」经本机复现实验**证伪**——Gradle 9.7.0 下过滤跑（结果集 1 个 XML）后紧接全量 `:test` 会重新执行（257 个 XML），反向（全量→过滤跑）同样重新执行（257→1）；过滤器参与 up-to-date 输入计算，两种顺序均无「过滤结果冒充全量」的假绿。按「不实测不写」红线未写入 dev-process-lessons，实验证据见 Task 6 报告。

## 执行偏差记录（2026-10-13，§9 遗留观察项到期复跑）

- **复跑两红，ChatUsage 计数口径假设否定，遗留观察项收口**：配额日重置实证（发起响应 `quotaRemaining=500,000`，对照前日结算 662,038/500,000），main=ce28b92e 复跑 §9 两用例——taskId=29 `VALIDATION_FAILED`（369,853 tokens，VALIDATING 阶段，32 处数字误述自动修正重试耗尽）、taskId=30 `VALIDATION_FAILED`（202,726 tokens，14 处同因）；**QUOTA_TASK_CAP 未复现**（0/2，两任务均 <400k cap）。2.0.3 对照 A/B（本地缓存 jar 字节码）：`OpenAIResponseParser` 两版 `outputTokens` 均=DeepSeek `completionTokens` 直传、`inputTokens` 均=`promptTokens`，2.0.4 仅新增 `reasoningTokens`/`cacheCreationTokens` 独立字段（源自 `completion_tokens_details` 等，app 的 `ReportUsageMiddleware` 只加 input+output 不消费）——**无「outputTokens 含 reasoning」的口径变化**。动态对账：任务 `token_usage` 与 `turn_observation` 逐轮 prompt+completion 之和精确相等（29：184,373+40,447+31,080+113,953=369,853 ✓；30：41,161+52,325+25,562+83,678=202,726 ✓），逐轮 completion 2.5k–18k 量级正常、prompt 单轮高达 177k（33 工具调用上下文自重），用量为真实消耗非计数膨胀。**残红归因转向**：两 run 复现相同数字误述对（「-0.16%」应为「16.75%」等），指向校验门/真值池锚点与草稿数值语义错位（或模型自校正顽固），属 MS-32 研报域质量问题、非升级回归亦非计数问题——是否专项由产品侧另裁。
