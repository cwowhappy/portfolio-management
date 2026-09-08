# Issue #25 修复实施计划：审批卡片渲染（expiresAt:null 拒收）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 mcp-hitl 审批卡片在真实浏览器中正常渲染并可批准/拒绝续跑（修复 #25 的 FR-3 阻断），并补 CI 可跑的真实浏览器 e2e 钉住全链路。

**Architecture:** 方案 **H'（后端收窄版 codec）**——经 agentscope 官方钩子 `JsonUtils.setJsonCodec()` 换入 delegating codec，**仅** AG-UI 事件（`toJson(AguiEvent)`）的序列化剥离 null 字段（`expiresAt:null` → 字段缺省，前端 zod `optional()` 放行），其余 JSON（LLM 请求体/stateStore/工具参数）字节不变。不动前端依赖。后端集成测试断言 SSE 线上无 `"expiresAt"` 为 TDD 锚点；另补 CI 可跑的真实浏览器 e2e。

**Tech Stack:** Jackson 2（`com.fasterxml.jackson.databind`，与 `spring-boot-jackson2` 对齐）/ agentscope 2.0.3 `JsonUtils`/`JacksonJsonCodec`/`JsonCodec` / JUnit + MockMvc 集成测试 / vitest / Playwright / node:http（e2e MCP server）

**Spec:** `features/mcp-hitl/02-design/设计规格说明.md`（§三前端设计、§五已知限制、§七真机验收记录）；issue [#25](https://github.com/cwowhappy/portfolio-management/issues/25)。

## Global Constraints

- 覆盖门槛 ≥80%（指令/分支，`make test` 卡）；改代码须补测试（AGENTS.md）
- **Jackson 2 而非 Jackson 3**（AGENTS.md 明确；新 ObjectMapper 用 `com.fasterxml.jackson.databind.ObjectMapper`）
- 后端 DDD 分层：codec 属 agent 装配（`agent/`，与 `AgentConfig` 同包，ArchUnit 无需动白名单）；seed 放 `infrastructure/seed/`（仿 `AdminSeedRunner`）；domain 仓库接口保持纯 POJO
- `@ag-ui/client` 锁 `^0.0.59`、`@copilotkit/*` 锁 `1.70.1` 不动（本方案不动前端依赖）
- 提交信息用 conventional commits（中文描述）
- TDD：每个修复点先红后绿；真实浏览器行为必须有非 mock e2e 钉住（#25 的教训）

## 决策记录（2026-09-08 grilling 定稿，全方案对比）

字段背景：`expiresAt` 是 AG-UI pending interrupt 的过期时间；前端 `useInterrupt` 仅在 resolve 时判过期（`decision.kind === "expired"` 则拒绝续跑）。后端 `RequireUserConfirmEvent` 构造签名只有 `(replyId, toolCalls)`（javap 核实）——**没有过期概念**，converter 映射时序列化 `expiresAt:null`（= 永不过期）。前端 `@ag-ui/core 0.0.59` 的 `InterruptSchema` 为 `expiresAt: z.string().optional()`（`.d.ts` 亦 `string | undefined`）——null 与缺省在前端语义等价，但 schema 只认缺省/字符串，null 导致**整条 RUN_FINISHED 被拒收**。

| # | 方案 | 结论 | 关键理由（均经字节码/抓包核实） |
|---|---|---|---|
| A | pnpm patch `@ag-ui/core`（`.nullable()`） | 备选不采纳 | 最小改动且有 pnpm 升级 tripwire，但 fork 上游产物 |
| **H'** | **后端 delegating codec，仅 AguiEvent 剥 null（采纳）** | ✅ | agentscope 官方留钩子：`JsonUtils.setJsonCodec()`（volatile 静态，encoder 每次调用动态取）；自带 `JacksonJsonCodec(ObjectMapper)` 公开构造。爆炸半径 = bug 影响半径（仅 SSE 线上）；该后端的所有客户端受益；不动依赖产物 |
| H | 全局 codec 直接 NON_NULL | 否决 | `JsonUtils` 服务 agentscope 一切 JSON（LLM 请求/stateStore/工具参数），全局改动故障面远离症状 |
| B | Next 反代 SSE 改写 | 否决 | 流式分块边界处理，改坏波及所有 agent 流量，失败模式最晚暴露 |
| B2 | 后端 Servlet Filter 改写 | 否决 | `/agui/run` 由 starter 自动装配（`AguiMvcController` + `AguiEventEncoder`），无自有缝隙 |
| C | 升级 @ag-ui/core / canary | 否决 | latest 即坏版本 0.0.59；canary dist 整体重构，CopilotKit 1.70.1 下集成风险极高 |
| D | agentscope 升级/patch | 否决 | 无更新版本；Gradle 侧 patch jar 比 H' 重 |
| E | 前端运行时 monkey-patch | 否决 | `InterruptSchema` 模块内部不导出，得改 zod 内部结构 |

H' 安全性前置验证（已完成）：线上 null 字段仅 2 处（`expiresAt` + RUN_STARTED input 回显的 `toolCallId`）；`@ag-ui/core` 全库不存在「`.nullable()` 而非 `.optional()`」的字段（omission 零误伤）；null/缺省在前端到期检查语义等价。

**H' 对象初始化逻辑（字节码级核实，2026-09-08）**：
- `JsonUtils` 静态块饿汉式 `new JacksonJsonCodec()` 存入 volatile 字段；`AguiEventEncoder.encode()` 每次 `invokestatic getJsonCodec()` 动态取——set 只需早于第一个 `/agui/run` 请求。
- `createDefaultObjectMapper()` = `new ObjectMapper().registerModule(new JavaTimeModule()).configure(FAIL_ON_UNKNOWN_PROPERTIES, false)`，**仅此两条配置**（`areturn` 处终止）。
- `JacksonJsonCodec.toJson()` = `writeValueAsString` + `JsonProcessingException` → log + 包 `JsonException`，无特殊 writer。
- 全 agentscope 2.0.3 jar 扫描（zipgrep）：`setJsonCodec` 调用方仅 `JsonUtils` 自身，**无运行期覆盖者**。
- 装配用 `fallback.getObjectMapper().copy()` 而非手写默认——上游改默认配置时自动继承，避免两套默认漂移；`NON_NULL` 递归生效（嵌套的 `outcome.interrupts[].expiresAt` 同样被剥）。

范围外（Task 7 开独立 issue）：FR-8 的 CopilotKit v2 threadId 每次加载再生；登录后立发消息的就绪竞态（hydration abortRun）。

---

### Task 1: 后端 TDD 锚点——SSE 线上无 null 字段（RED）

**Files:**
- Modify: `backend/src/integrationTest/java/com/portfolio/invest/agui/AguiInterruptIntegrationTest.java`（ASK 场景 `givenWriteToolCall_whenRun_thenPermissionConfirmInterruptEmitted` 末尾追加断言）
- Modify: `backend/src/integrationTest/java/com/portfolio/invest/agui/McpHitlIntegrationTest.java`（写工具弹卡用例，:173-182 同 harness，同样追加）

**Interfaces:**
- Produces: 线格式契约「AG-UI SSE 不得含 `"expiresAt"`」（Task 2 实现的消费方）

- [ ] **Step 1: 写失败断言（两个文件同款）**

在两处既有 interrupt 断言块末尾（`AguiInterruptIntegrationTest` 的 `responseSchema` 断言之后；`McpHitlIntegrationTest` 的 `:182` toolName 断言之后）追加：

```java
        // #25：AG-UI 线上不得出现 null 字段（前端 @ag-ui/core InterruptSchema 只认缺省/字符串，
        // "expiresAt":null 会导致整条 RUN_FINISHED 被浏览器端 zod 拒收、审批卡片不渲染）
        assertThat(body).doesNotContain("\"expiresAt\"");
```

- [ ] **Step 2: 跑集成测试确认红**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.agui.AguiInterruptIntegrationTest" --console=plain`
Expected: FAIL —— `givenWriteToolCall...` 用例 `doesNotContain("\"expiresAt\"")` 断言失败（现行线含 `"expiresAt":null`）。其余用例绿。

- [ ] **Step 3: Commit（红态留档）**

```bash
git add backend/src/integrationTest/java/com/portfolio/invest/agui/AguiInterruptIntegrationTest.java backend/src/integrationTest/java/com/portfolio/invest/agui/McpHitlIntegrationTest.java
git commit -m "test(agui): 钉住 SSE 线上无 expiresAt——前端 schema 拒收 null 致卡片不渲染（#25 红）"
```

### Task 2: AguiEventNonNullCodec + 装配（GREEN）

**Files:**
- Create: `backend/src/main/java/com/portfolio/invest/agent/AguiEventNonNullCodec.java`
- Create: `backend/src/main/java/com/portfolio/invest/agent/AguiWireJsonConfig.java`
- Test: `backend/src/test/java/com/portfolio/invest/agent/AguiEventNonNullCodecTest.java`

**Interfaces:**
- Consumes: agentscope `io.agentscope.core.util.JsonUtils` / `JsonCodec` / `JacksonJsonCodec`、`io.agentscope.core.agui.event.AguiEvent`（接口方法 `getType()/getThreadId()/getRunId()/timestamp()/rawEvent()`，javap 核实）
- Produces: 全局生效的 codec（启动期装配）；`AguiEventNonNullCodec(JsonCodec delegate, JsonCodec aguiEventCodec)` 构造

- [ ] **Step 1: 写 codec 单元测试（先红）**

```java
package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AguiEventNonNullCodecTest {

    /** 测试替身：带一个 null 字段的 AG-UI 事件（真实事件的构造不公开，接口足够） */
    record FakeRunFinished(
            @JsonProperty("threadId") String threadId,
            @JsonProperty("runId") String runId,
            @JsonProperty("expiresAt") String expiresAt) implements AguiEvent {
        @Override public AguiEventType getType() { return AguiEventType.RUN_FINISHED; }
        @Override public String getThreadId() { return threadId; }
        @Override public String getRunId() { return runId; }
        @Override public Long timestamp() { return null; }
        @Override public Object rawEvent() { return null; }
    }

    record PlainDto(@JsonProperty("name") String name, @JsonProperty("remark") String remark) {}

    private final JsonCodec codec = new AguiEventNonNullCodec(
            new JacksonJsonCodec(),
            new JacksonJsonCodec(new JacksonJsonCodec().getObjectMapper()
                    .copy()
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)));

    @DisplayName("givenAguiEventWithNullField_whenToJson_thenNullFieldOmitted")
    @Test
    void givenAguiEventWithNullField_whenToJson_thenNullFieldOmitted() {
        String json = codec.toJson(new FakeRunFinished("t1", "r1", null));
        assertThat(json).contains("\"threadId\":\"t1\"");
        assertThat(json).doesNotContain("expiresAt");
    }

    @DisplayName("givenNonAguiEvent_whenToJson_thenDelegatesUnchanged")
    @Test
    void givenNonAguiEvent_whenToJson_thenDelegatesUnchanged() {
        JsonCodec plain = new JacksonJsonCodec();
        String viaCodec = codec.toJson(new PlainDto("x", null));
        assertThat(viaCodec).isEqualTo(plain.toJson(new PlainDto("x", null)));
    }
}
```

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.AguiEventNonNullCodecTest" --console=plain`
Expected: FAIL（`AguiEventNonNullCodec` 不存在，编译错误即红）。

- [ ] **Step 2: 实现 codec 与装配**

`AguiEventNonNullCodec.java`：

```java
package com.portfolio.invest.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonCodec;
import java.lang.reflect.Type;

/**
 * AG-UI 事件非空序列化 codec（#25）：agentscope 权限确认中断的 expiresAt 序列化为 null，
 * 而前端 @ag-ui/core 0.0.59 的 InterruptSchema 只认 缺省/字符串，null 会让整条 RUN_FINISHED
 * 被浏览器端 zod 拒收（审批卡片不渲染）。本 codec 仅对 AguiEvent 的 toJson 剥离 null 字段
 * （null→缺省，前端语义等价），其余序列化/反序列化全部委托默认实现——LLM 请求体、stateStore、
 * 工具参数的字节行为不变。上游对齐后（@ag-ui/core 收 null 或 agentscope 发缺省）回收本类。
 */
public class AguiEventNonNullCodec implements JsonCodec {

    private final JsonCodec delegate;
    private final JsonCodec aguiEventCodec;

    public AguiEventNonNullCodec(JsonCodec delegate, JsonCodec aguiEventCodec) {
        this.delegate = delegate;
        this.aguiEventCodec = aguiEventCodec;
    }

    @Override
    public String toJson(Object value) {
        return (value instanceof AguiEvent ? aguiEventCodec : delegate).toJson(value);
    }

    @Override
    public String toPrettyJson(Object value) {
        return delegate.toPrettyJson(value);
    }

    @Override
    public <T> T fromJson(String json, Class<T> type) {
        return delegate.fromJson(json, type);
    }

    @Override
    public <T> T fromJson(String json, TypeReference<T> type) {
        return delegate.fromJson(json, type);
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public <T> T convertValue(Object value, TypeReference<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public Object convertValue(Object value, Type type) {
        return delegate.convertValue(value, type);
    }
}
```

`AguiWireJsonConfig.java`：

```java
package com.portfolio.invest.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.core.util.JacksonJsonCodec;
import io.agentscope.core.util.JsonUtils;
import org.springframework.context.annotation.Configuration;

/**
 * AG-UI 线格式装配（#25）：启动期把全局 JsonCodec 换为 AguiEventNonNullCodec。
 * encoder 每次编码都经 JsonUtils.getJsonCodec() 动态获取，本配置只需早于第一个 /agui/run
 * 请求生效（Spring 上下文刷新期构造，天然满足）。
 */
@Configuration
class AguiWireJsonConfig {

    AguiWireJsonConfig() {
        JacksonJsonCodec fallback = new JacksonJsonCodec();
        JsonUtils.setJsonCodec(new AguiEventNonNullCodec(
                fallback,
                new JacksonJsonCodec(fallback.getObjectMapper()
                        .copy()
                        .setSerializationInclusion(JsonInclude.Include.NON_NULL))));
    }
}
```

- [ ] **Step 3: 跑 codec 单测转绿**

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.agent.AguiEventNonNullCodecTest" --console=plain`
Expected: PASS（两条用例）。

- [ ] **Step 4: 跑 Task 1 集成测试转绿**

Run: `cd backend && ./gradlew integrationTest --tests "com.portfolio.invest.agui.AguiInterruptIntegrationTest" --tests "com.portfolio.invest.agui.McpHitlIntegrationTest" --console=plain`
Expected: PASS（含新增线上断言——expiresAt 已缺省化）。

- [ ] **Step 5: 后端 test 源集全量（防 codec 影响其他序列化路径）**

Run: `cd backend && ./gradlew test --console=plain`
Expected: 全绿。

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/portfolio/invest/agent/AguiEventNonNullCodec.java backend/src/main/java/com/portfolio/invest/agent/AguiWireJsonConfig.java backend/src/test/java/com/portfolio/invest/agent/AguiEventNonNullCodecTest.java
git commit -m "fix(agui): AG-UI 事件序列化剥离 null 字段——审批卡片渲染恢复（#25）"
```

### Task 3: 钉住「中断回合后同线程续用」（#25 死会话回归，前端层）

**Files:**
- Modify: `frontend/tests/agui-stream.test.ts`

**Interfaces:**
- Consumes: 既有 `makeAgent`/`sseResponse` harness、`RUN_STARTED`/`RUN_FINISHED` 常量、`INTERRUPT` const（:155）
- Produces: 回归钉——中断回合（**线上无 expiresAt** 的线格式，与 Task 2 契约一致）后，同线程再发普通消息 runAgent 正常发出请求

背景：真机 phase7 曾见 interrupt 被拒后同线程消息在请求侧被拒（`agent.messages` 残留 `content:null`）。后端修复后 interrupt 正常入列、消息组装走正常路径，症状预期自愈；本测试钉住不回退。

- [ ] **Step 1: 写测试（注意 interrupt 用**无** expiresAt 的形状，对齐修复后的线格式）**

在既有 `resolve 后续跑` 用例之后追加：

```ts
  it("中断回合后不 resolve、同线程再发普通消息：runAgent 正常发出请求（#25 死会话回归钉）", async () => {
    const bodies: unknown[] = [];
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      bodies.push(JSON.parse(String(init.body)));
      return sseResponse(
        bodies.length === 1
          ? [
              RUN_STARTED,
              { type: "TOOL_CALL_START", toolCallId: "tc9", toolCallName: "test_write", parentMessageId: "a1" },
              { type: "TOOL_CALL_ARGS", toolCallId: "tc9", delta: '{"note":"x"}' },
              { type: "TOOL_CALL_END", toolCallId: "tc9" },
              { type: "RUN_FINISHED", threadId: "t1", runId: "r1", outcome: { type: "interrupt", interrupts: [INTERRUPT] } },
            ]
          : [RUN_STARTED, RUN_FINISHED],
      );
    });
    const agent = new HttpAgent({ url: "http://test.local/agui/run", fetch: fetchMock as unknown as HttpAgent["fetch"] });
    agent.addMessage({ id: "u1", role: "user", content: "写一条记录" });
    await agent.runAgent();
    expect(agent.pendingInterrupts).toHaveLength(1);
    // 不 resolve，直接再发普通消息（FR-8 场景 A 的同会话变体）
    agent.addMessage({ id: "u2", role: "user", content: "你好" });
    await agent.runAgent();
    expect(bodies).toHaveLength(2);
  });
```

- [ ] **Step 2: 跑测试**

Run: `cd frontend && pnpm vitest run tests/agui-stream.test.ts`
Expected: PASS。若 FAIL（第 2 次 fetch 未发出 = 客户端请求侧自拒）：把失败输出贴到 #25，并改走兜底——在 `tests/lib/copilotkit-route.test.ts` 既有 harness 上补「POST 含 `content:null` 消息 → `handlerBodies` 收到的已归一化（`normalizeNullContent` 行为）」断言，并在本 PR 内把死会话修到绿（#25 连带影响①不留尾）。红绿结果无论哪种都记录进 #25。

- [ ] **Step 3: Commit**

```bash
git add frontend/tests/agui-stream.test.ts
git commit -m "test(chat): 钉住中断回合后同线程续发——#25 死会话回归防护"
```

### Task 4: 真机复跑验收清单（原 §2 四项）并回填文档

**Files:**
- Modify: `features/mcp-hitl/02-design/设计规格说明.md`（§七表格更新）
- Modify: `features/mcp-hitl/03-plan/交接事项.md`（四项结论更新）
- 工件（不进仓库）：`/tmp/mcp-hitl-acceptance/`（driver.mjs、server.py、notes）

**Interfaces:**
- Consumes: Task 2 的 codec；验收期已建好的 DB 行（provider `hitl-test` id=4、用户 `hitl_acc_18472`）与 `/tmp/mcp-hitl-acceptance/` 驱动

- [ ] **Step 1: 重建环境（三个终端各一条命令）**

```bash
# ① MCP server（写工具触发审批）
cd /tmp/mcp-hitl-acceptance && rm -f notes.log && uvx --from 'mcp<2' python server.py
# ② 后端（.env 载入）
cd backend && JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.6-amzn" bash -c 'set -a; . ../.env; set +a; ./gradlew bootRun --console=plain'
# ③ 前端 dev
cd frontend && PORT=3000 ./node_modules/.bin/next dev -p 3000
```

- [ ] **Step 2: 跑 driver phase1（批准/拒绝路径 + 卡片消失时序 + 落盘断言）**

```bash
cd frontend && node /tmp/mcp-hitl-acceptance/driver.mjs phase1
```
Expected: 卡片出现 → 点批准后 `goneMs` 有值（记录毫秒数）→ `write-approved-1 已落盘 ✅` → 拒绝后未落盘、未重弹卡。

- [ ] **Step 3: 跑 driver phase2（线程切换）+ phase3（刷新场景）**

```bash
node /tmp/mcp-hitl-acceptance/driver.mjs phase2   # 切新对话卡片是否消失、切回是否保留——记录实测
node /tmp/mcp-hitl-acceptance/driver.mjs phase3   # 刷新后同线程发消息——记录实测（注意 threadId 再生现象可能改写表现，如实记录）
```

- [ ] **Step 4: 回填两份文档**（§七表格逐项更新；交接事项四项改为实测结果与证据截图路径）

- [ ] **Step 5: Commit**

```bash
git add features/mcp-hitl/02-design/设计规格说明.md features/mcp-hitl/03-plan/交接事项.md
git commit -m "docs(acceptance): #25 修复后真机复跑——卡片渲染/审批续跑/线程切换实测结论"
```

### Task 5: e2e 基建——node MCP server + 后端 env 门控种子 + webServer 接线

**Files:**
- Create: `scripts/e2e-mcp-server.mjs`（仓库根 scripts/，零依赖 node:http）
- Create: `backend/src/main/java/com/portfolio/invest/infrastructure/seed/HitlE2eSeedRunner.java`
- Modify: `backend/src/main/java/com/portfolio/invest/domain/mcp/McpConfigRepository.java`（加 seed 用方法）
- Modify: `backend/src/main/java/com/portfolio/invest/infrastructure/persistence/McpConfigRepositoryImpl.java`（实现）
- Test: `backend/src/test/java/com/portfolio/invest/infrastructure/seed/HitlE2eSeedRunnerTest.java`
- Modify: `frontend/playwright.config.ts`（webServer 加 MCP server 条目）
- Modify: `scripts/e2e-backend.sh`（透传 `E2E_HITL_MCP_URL`）
- Modify: `.github/workflows/ci.yml`（e2e job env 加 `E2E_HITL_MCP_URL`）

**Interfaces:**
- Produces: 环境变量 `E2E_HITL_MCP_URL`（非空时后端幂等种 provider `hitl-e2e` + endpoint 指向该 URL）；MCP server 监听 `127.0.0.1:8765`，工具 `write_note`（无 annotations，写 `${HITL_E2E_NOTES:-/tmp/hitl-e2e-notes.log}`）/`read_note`（readOnlyHint=true）；Task 6 消费这三个契约。

- [ ] **Step 1: 写 HitlE2eSeedRunnerTest（先红）**

```java
package com.portfolio.invest.infrastructure.seed;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.mcp.McpConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HitlE2eSeedRunnerTest {

    @DisplayName("givenEnvUrlMissing_whenRun_thenNoSeed")
    @Test
    void givenEnvUrlMissing_whenRun_thenNoSeed() throws Exception {
        McpConfigRepository repo = mock(McpConfigRepository.class);
        new HitlE2eSeedRunner(repo, null).run(null);
        verify(repo, never()).upsertSeedProvider(any());
    }

    @DisplayName("givenEnvUrlSet_whenRun_thenIdempotentSeed")
    @Test
    void givenEnvUrlSet_whenRun_thenIdempotentSeed() throws Exception {
        McpConfigRepository repo = mock(McpConfigRepository.class);
        when(repo.findProviderByCode("hitl-e2e"))
                .thenReturn(Optional.of(new com.portfolio.invest.domain.mcp.McpProvider(
                        99L, "hitl-e2e", "HITL e2e",
                        com.portfolio.invest.domain.mcp.AuthType.NONE, null, null, true, null)));
        new HitlE2eSeedRunner(repo, "http://127.0.0.1:8765/mcp").run(null);
        verify(repo).upsertSeedEndpoint(99L, "http://127.0.0.1:8765/mcp");
    }
}
```

注：`McpProvider` 为 domain record，构造参数以 `backend/src/main/java/com/portfolio/invest/domain/mcp/McpProvider.java` 真实签名为准（动手前先 Read；字段不同则按真实签名调整测试与 Runner，断言意图不变）。

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.infrastructure.seed.HitlE2eSeedRunnerTest" --console=plain`
Expected: FAIL（类不存在）。

- [ ] **Step 2: domain 仓库接口加方法 + 实现 + Runner**

`McpConfigRepository.java` 追加：

```java
    // e2e 种子用（HitlE2eSeedRunner，env 门控）：按自然键幂等
    Optional<McpProvider> findProviderByCode(String code);
    void upsertSeedProvider(McpProvider provider);
    void upsertSeedEndpoint(Long providerId, String url);
```

`HitlE2eSeedRunner.java`：

```java
package com.portfolio.invest.infrastructure.seed;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpConfigRepository;
import com.portfolio.invest.domain.mcp.McpProvider;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * e2e 专用 MCP provider 种子：E2E_HITL_MCP_URL 已配置时幂等写入 hitl-e2e provider（NONE 鉴权）
 * 与指向该 URL 的 endpoint，供 Playwright 真实浏览器用例触发写工具审批（issue #25 回归钉）。
 * 未配置时零副作用（与 AdminSeedRunner 同型的启动幂等种子）。
 */
@Component
public class HitlE2eSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(HitlE2eSeedRunner.class);
    static final String CODE = "hitl-e2e";

    private final McpConfigRepository repository;
    private final String mcpUrl;

    public HitlE2eSeedRunner(McpConfigRepository repository,
                             @Value("${E2E_HITL_MCP_URL:}") String mcpUrl) {
        this.repository = repository;
        this.mcpUrl = (mcpUrl == null || mcpUrl.isBlank()) ? null : mcpUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mcpUrl == null) return;
        Optional<McpProvider> existing = repository.findProviderByCode(CODE);
        Long providerId;
        if (existing.isPresent()) {
            providerId = existing.orElseThrow().id();
        } else {
            repository.upsertSeedProvider(new McpProvider(
                    null, CODE, "HITL e2e", AuthType.NONE, null, null, true, "Playwright e2e 专用"));
            providerId = repository.findProviderByCode(CODE).orElseThrow().id();
            log.info("已种子 e2e MCP provider {} -> {}", CODE, mcpUrl);
        }
        repository.upsertSeedEndpoint(providerId, mcpUrl);
    }
}
```

（`McpProvider` record 构造以真实签名为准；`upsertSeedEndpoint` 在 `McpConfigRepositoryImpl` 内按 url 查重后插入，`enabled=true`。）

Run: `cd backend && ./gradlew test --tests "com.portfolio.invest.infrastructure.seed.HitlE2eSeedRunnerTest" --console=plain`
Expected: PASS。

- [ ] **Step 3: 写 scripts/e2e-mcp-server.mjs（零依赖 streamable HTTP MCP server）**

```js
#!/usr/bin/env node
// e2e 专用最小 MCP server（Streamable HTTP，JSON 响应形态）：write_note 无 annotations（触发审批），
// read_note readOnlyHint=true（放行）。与后端 McpClientPool 的 streamableHttpTransport 客户端对齐。
import { createServer } from "node:http";
import { appendFileSync, readFileSync, existsSync } from "node:fs";

const PORT = 8765;
const NOTES = process.env.HITL_E2E_NOTES ?? "/tmp/hitl-e2e-notes.log";
const PROTOCOL = "2025-06-18";

const TOOLS = [
  {
    name: "write_note",
    description: "把一段内容追加写入笔记（写工具，触发审批）",
    inputSchema: { type: "object", properties: { content: { type: "string" } }, required: ["content"] },
  },
  {
    name: "read_note",
    description: "读取当前笔记内容（只读工具，不应触发审批）",
    inputSchema: { type: "object", properties: {} },
    annotations: { readOnlyHint: true },
  },
];

const json = (res, body) => {
  res.writeHead(200, { "Content-Type": "application/json", "Mcp-Session-Id": "e2e-session" });
  res.end(JSON.stringify(body));
};

createServer((req, res) => {
  if (req.method === "GET") {
    // webServer 探活 + streamable GET 通道占位：直接 200
    res.writeHead(200, { "Content-Type": "text/event-stream" });
    res.end();
    return;
  }
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    let msg;
    try { msg = JSON.parse(raw); } catch { json(res, { jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }); return; }
    const { id, method, params } = msg;
    if (method === "initialize") {
      json(res, { jsonrpc: "2.0", id, result: { protocolVersion: params?.protocolVersion ?? PROTOCOL, capabilities: { tools: { listChanged: false } }, serverInfo: { name: "hitl-e2e", version: "1.0.0" } } });
    } else if (method === "notifications/initialized") {
      res.writeHead(202); res.end();
    } else if (method === "tools/list") {
      json(res, { jsonrpc: "2.0", id, result: { tools: TOOLS } });
    } else if (method === "tools/call") {
      const name = params?.name;
      if (name === "write_note") {
        const content = params?.arguments?.content ?? "";
        appendFileSync(NOTES, content + "\n");
        json(res, { jsonrpc: "2.0", id, result: { content: [{ type: "text", text: `已写入：${content}` }] } });
      } else if (name === "read_note") {
        const text = existsSync(NOTES) ? readFileSync(NOTES, "utf8") : "(空)";
        json(res, { jsonrpc: "2.0", id, result: { content: [{ type: "text", text }] } });
      } else {
        json(res, { jsonrpc: "2.0", id, error: { code: -32602, message: `Unknown tool: ${name}` } });
      }
    } else {
      json(res, { jsonrpc: "2.0", id, error: { code: -32601, message: `Method not found: ${method}` } });
    }
  });
}).listen(PORT, "127.0.0.1", () => console.log(`hitl-e2e MCP server on http://127.0.0.1:${PORT}/mcp`));
```

- [ ] **Step 4: playwright webServer + 后端透传 + CI env 接线**

`frontend/playwright.config.ts` 的 `webServer` 数组**首位**插入（先起 MCP，再起依赖它的后端）：

```ts
      {
        command: "node ../scripts/e2e-mcp-server.mjs",
        url: "http://127.0.0.1:8765/mcp",
        timeout: 15_000,
        reuseExistingServer: !process.env.CI,
        env: { ...process.env, E2E_HITL_MCP_URL: "http://127.0.0.1:8765/mcp" },
      },
```

`scripts/e2e-backend.sh` 在 `exec ./gradlew bootRun` 前加透传（bootRun 继承进程 env；本地由 webServer env 提供，CI 由 workflow env 提供）：

```bash
export E2E_HITL_MCP_URL="${E2E_HITL_MCP_URL:-http://127.0.0.1:8765/mcp}"
```

CI：`.github/workflows/ci.yml` e2e job 的 `env:` 块加 `E2E_HITL_MCP_URL: http://127.0.0.1:8765/mcp`。

- [ ] **Step 5: 握手冒烟——经真实 Java 客户端验证 node server（Q7 决议）**

```bash
# ① 起 MCP server 与后端（或直接 pnpm test:e2e 的 webServer 预热；手动两步如下）
node scripts/e2e-mcp-server.mjs &
E2E_HITL_MCP_URL=http://127.0.0.1:8765/mcp ./gradlew bootRun &   # 待健康检查通过
# ② 管理员登录拿会话，走真实 McpClientPool → Java SDK → node server 列工具
set -a; . .env; set +a
curl -s -c /tmp/hitl-e2e-admin.jar -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\"}"
PROVIDER_ID=$(curl -s -b /tmp/hitl-e2e-admin.jar http://localhost:8080/api/mcp/providers | python3 -c "import json,sys; print(next(p['id'] for p in json.load(sys.stdin) if p['code']=='hitl-e2e'))")
curl -s -b /tmp/hitl-e2e-admin.jar http://localhost:8080/api/mcp/configs/$PROVIDER_ID/tools
```
Expected: 返回 `[{"name":"read_note",...},{"name":"write_note",...}]`（工具列表经 Java SDK 真握手取得——协议形态问题在此暴露，不留给浏览器用例）。

- [ ] **Step 6: 后端全量单测 + Commit**

```bash
cd backend && ./gradlew test --console=plain
git add scripts/e2e-mcp-server.mjs scripts/e2e-backend.sh backend/src frontend/playwright.config.ts .github/workflows/ci.yml
git commit -m "test(e2e): HITL e2e 基建——零依赖 MCP server + env 门控 provider 种子（#25）"
```

### Task 6: 真实浏览器 e2e 用例（CI 可跑）

**Files:**
- Create: `frontend/e2e/hitl.spec.ts`

**Interfaces:**
- Consumes: Task 5 的 `E2E_HITL_MCP_URL`（门控）、provider `hitl-e2e`、MCP 工具 `write_note`、落盘文件 `${HITL_E2E_NOTES:-/tmp/hitl-e2e-notes.log}`；`e2e/helpers.ts` 的 `TEST_PASSWORD`/`uniqueUsername`/`registerAndApprove`（既有）

- [ ] **Step 1: 写用例**

```ts
import { expect, test } from "@playwright/test";
import { existsSync, readFileSync, rmSync } from "node:fs";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// #25 回归钉：真实浏览器 → 真实后端（codec 修复后线上无 expiresAt）→ 真实 MCP server 全链路（非 mock）。
// 依赖 E2E_HITL_MCP_URL（playwright webServer 自动注入/CI 提供），未配置时整组跳过。
test.describe("MCP 写工具审批（HITL）", () => {
  test.skip(!process.env.E2E_HITL_MCP_URL, "未配置 E2E_HITL_MCP_URL，跳过 HITL 审批用例");
  test.setTimeout(180_000);

  const NOTES = process.env.HITL_E2E_NOTES ?? "/tmp/hitl-e2e-notes.log";
  const notesText = () => (existsSync(NOTES) ? readFileSync(NOTES, "utf8") : "");

  test("写工具弹卡 → 批准 → 落盘并续跑；拒绝 → 不落盘", async ({ page }) => {
    rmSync(NOTES, { force: true });
    const username = uniqueUsername("hitl");
    await registerAndApprove(page, username, TEST_PASSWORD);

    // 经同源 API 为当前用户启用 hitl-e2e provider。注意必须用 page.request（Page 绑定的
    // APIRequestContext，与浏览器共享会话 cookie）；顶层 request fixture 是 isolated 的、
    // 不带登录态（playwright 1.62.1 types/test.d.ts:7854 核实）
    const providers = await page.request.get("/api/mcp/providers");
    const hitl = ((await providers.json()) as Array<{ id: number; code: string }>)
      .find((p) => p.code === "hitl-e2e");
    expect(hitl, "种子 provider hitl-e2e 应存在").toBeTruthy();
    const enable = await page.request.put(`/api/mcp/configs/${hitl!.id}`, {
      data: { enabled: true, disabledTools: [] },
    });
    expect(enable.ok()).toBeTruthy();

    // 触发写工具：审批卡片出现在消息流尾部（FR-3）
    await page.getByPlaceholder(/问行情、看走势/).fill(
      '请调用 write_note 工具，把内容 "e2e-approved" 写入笔记。等我的确认结果，不要改用其他方式。',
    );
    await page.keyboard.press("Enter");
    const card = page.locator("div.tool-card", { hasText: "需要确认：" }).filter({ hasText: "write_note" });
    await card.waitFor({ state: "visible", timeout: 120_000 });

    // 批准 → 卡片消失 → 工具执行（落盘）→ Agent 续跑（FR-4）
    await card.getByRole("button", { name: "批准" }).click();
    await card.waitFor({ state: "detached", timeout: 15_000 });
    await expect.poll(() => notesText(), { timeout: 120_000 }).toContain("e2e-approved");

    // 拒绝路径：再触发一次，拒绝 → 不落盘
    await page.getByPlaceholder(/问行情、看走势/).fill(
      '请调用 write_note 工具，把内容 "e2e-denied" 写入笔记。等我的确认结果，不要改用其他方式。',
    );
    await page.keyboard.press("Enter");
    const card2 = page.locator("div.tool-card", { hasText: "需要确认：" }).filter({ hasText: "write_note" });
    await card2.waitFor({ state: "visible", timeout: 120_000 });
    await card2.getByRole("button", { name: "拒绝" }).click();
    await card2.waitFor({ state: "detached", timeout: 15_000 });
    await page.waitForTimeout(5_000); // 给续跑留窗口，若拒绝后仍落盘则 FAIL
    expect(notesText()).not.toContain("e2e-denied");
  });
});
```

注意：`/api/mcp/providers` 响应字段名以 `McpProviderView`（`backend/.../application/mcp/McpProviderView.java`）真实字段为准，写用例前先 Read 核对。

- [ ] **Step 2: 本地全链路跑（webServer 自动拉起三件套）**

```bash
cd frontend && rm -rf .next && E2E_HITL_MCP_URL=http://127.0.0.1:8765/mcp pnpm test:e2e e2e/hitl.spec.ts
```
两个坑（T6 实施时踩实）：① pnpm 传 filter 不加 `--`（`--` 会让 pnpm 吞掉后面的 playwright 参数而跑全量）；② `E2E_HITL_MCP_URL` 必须以环境变量前缀提供给测试 worker（webServer 的 `env` 只作用于被拉起的服务进程，不达 worker；CI 由 job env 提供）。（`.next` 先清——既有 e2e 复用旧构建产物的坑。）Expected: PASS。

- [ ] **Step 3: Commit**

```bash
git add frontend/e2e/hitl.spec.ts
git commit -m "test(e2e): 真实浏览器钉住 MCP 写工具审批全链路——卡片/批准/拒绝/落盘（#25）"
```

### Task 7: 收尾

- [ ] **Step 1: 全量回归**

```bash
make test          # 后端 + 前端 + collector（覆盖率门槛 ≥80%）
cd frontend && rm -rf .next && pnpm test:e2e   # 全量 e2e（CI 同款）
```

- [ ] **Step 2: 双向上游 issue（Q2 决议）**：
  - `@ag-ui/core`：InterruptSchema `expiresAt`（及全库 `.optional()` 字段的 null 容忍）——附本项目线格式证据（`"expiresAt":null` + zod 拒收路径截图/日志）；
  - agentscope：AG-UI 事件序列化建议 omit null（`RequireUserConfirmEvent` 无过期概念，`expiresAt:null` 与 TS 端缺省语义二义）。
  - 两处都链接回 #25，注明「上游对齐后回收 `AguiEventNonNullCodec`/`AguiWireJsonConfig`」。

- [ ] **Step 3: #25 评论回填**：修复方式（H' codec + 决策记录链接）、Task 3 红绿观察（请求侧拒绝点在哪层）、真机复跑结果、e2e 结果。

- [ ] **Step 4: 开范围外 follow-up issue**（两条）：① CopilotKit v2 threadId 每次加载再生 → FR-8 刷新场景前提失效 + 后端会话记忆脱钩的影响评估；② 登录后立发消息的就绪竞态（hydration abortRun 掐断在途运行）。

- [ ] **Step 5: PR**（`fix/mcp-hitl-expiresAt` → `main`），正文关联 `Fixes #25`，附验收清单复跑结论。

## Self-Review 记录

- Spec 覆盖：#25 正文三段——阻断（Task 1-2，后端线格式修复）、连带影响①死会话（Task 3）、连带影响②③范围外（Task 7 Step 4）+「修复后需补真实浏览器 e2e」（Task 5-6）+ 上游推动（Task 7 Step 2，Q2 决议）✓。
- 与旧版（pnpm patch 方案）的差异：前端 `agui-stream.test.ts` 不再新增 expiresAt:null 用例（H' 契约下后端不发 null，钉住前端 null-intolerance 等于钉 bug）；TDD 锚点移至后端集成测试的线上断言。
- 类型/签名核对：`JsonCodec` 七方法签名、`AguiEvent` 接口五方法（javap）、`JacksonJsonCodec(ObjectMapper)` 公开构造、`run()/runRequest()/lastEventOfType` harness（两集成测试同款）、`E2E_HITL_MCP_URL`/`HITL_E2E_NOTES`/`hitl-e2e` 全文一致；`McpProviderView` 与 `McpProvider` 字段标注「动手前先 Read 真实签名」。
- 占位符扫描：无 TBD/「适当处理」类步骤；Task 3 Step 2 兜底分支给出了具体替代断言与承诺（本 PR 内修到绿）。
