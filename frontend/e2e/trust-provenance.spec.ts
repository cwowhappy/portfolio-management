import { expect, test, type Locator, type Page, type Route } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// MS-29 F6 信任溯源真浏览器验收。确定性策略（不依赖真 LLM）：
// - 持久化链（F4 携带 / F5 回灌）：经同源 API 种子会话（PUT messages 带 trust payload），
//   断言三态角标 + 浮层 + 横幅/disclaimer 渲染、刷新仍在、切会话重开同验；
// - 实时链（F1 事件接入 / F2 角标 / F3 横幅）：Playwright route 在浏览器侧拦截
//   POST /api/copilotkit/agent/invest/run（providers.tsx useSingleEndpoint=false 的 REST 传输；
//   浏览器侧是 ProxiedCopilotRuntimeAgent extends HttpAgent，直接消费 AG-UI SSE——
//   `data: {事件JSON}\n\n`，@ag-ui/encoder EventEncoder.encodeSSE 同格式），注入脚本化
//   信任事件流：错值文本 → trust.correction 原位替换 → trust.anchors 满配 payload；
// - 不误附对照：无 advice/confidence 键的 anchors → 横幅与 disclaimer 缺席（含刷新后）。
//
// 选择器契约（F2/F3 报告）：trust-anchor-badge[data-anchor-state]（wrapper span 同带
// data-anchor-state）、trust-anchor-popover（role=tooltip，常驻 DOM opacity 切换）、
// confidence-banner / confidence-signal[data-signal] / confidence-suggestion /
// disclaimer-note / disclaimer-by。

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

/** AG-UI SSE 帧（@ag-ui/encoder EventEncoder.encodeSSE 同格式，见 tests/agui-stream.test.ts 先例）。 */
type AguiEvent = Record<string, unknown>;

function sseBody(events: AguiEvent[]): string {
  return events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("");
}

/** 浏览器侧拦截 agent run：按请求体 threadId 回脚本化 SSE（真实 /info 等其余请求不受影响）。 */
async function stubAgentRun(page: Page, buildEvents: (threadId: string) => AguiEvent[]): Promise<void> {
  await page.route("**/api/copilotkit/agent/*/run", async (route: Route) => {
    let threadId = "e2e-stub-thread";
    try {
      const body = route.request().postDataJSON() as { threadId?: string } | null;
      if (body && typeof body.threadId === "string" && body.threadId) threadId = body.threadId;
    } catch {
      // 非 JSON 体：以缺省 threadId 兜底
    }
    await route.fulfill({
      status: 200,
      headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" },
      body: sseBody(buildEvents(threadId)),
    });
  });
}

/** 三态角标（sup，F2 契约 testid + data-anchor-state）。 */
const badge = (page: Page, state?: string): Locator =>
  page.locator(
    state
      ? `[data-testid="trust-anchor-badge"][data-anchor-state="${state}"]`
      : "[data-testid=\"trust-anchor-badge\"]",
  );

/** 角标包裹元素（span.group，同带 data-anchor-state；按 snippet 文本消歧）。 */
const anchorWrap = (page: Page, state: string, snippet: string): Locator =>
  page.locator(`span[data-anchor-state="${state}"]`).filter({ hasText: snippet });

/** hover 角标 → 浮层 opacity 升为 1（常驻 DOM、group-hover 切换；opacity-0 对 Playwright 仍“可见”）。 */
async function hoverBadge(page: Page, state: string, snippet: string): Promise<Locator> {
  const wrap = anchorWrap(page, state, snippet);
  await wrap.locator(`[data-testid="trust-anchor-badge"][data-anchor-state="${state}"]`).hover();
  const popover = wrap.getByTestId("trust-anchor-popover");
  await expect(popover).toHaveCSS("opacity", "1");
  return popover;
}

/** 等待 RuntimeProvider 自动创建首个会话并返回其 id（page.request 与浏览器共享会话 Cookie）。 */
async function firstConversationId(page: Page): Promise<string> {
  let convId = "";
  await expect
    .poll(
      async () => {
        const list = (await (await page.request.get("/api/conversations")).json()) as Array<{
          id: string;
        }>;
        convId = list[0]?.id ?? "";
        return convId !== "";
      },
      { timeout: 15_000 },
    )
    .toBe(true);
  return convId;
}

/** 正文 → 横幅 → disclaimer → 反馈条的 DOM 序（F3 接线锁定的落位，真浏览器复验）。 */
async function expectAdvisoryOrder(page: Page): Promise<void> {
  const ok = await page.evaluate(() => {
    const content = document.querySelector("span[data-anchor-state]");
    const banner = document.querySelector("[data-testid='confidence-banner']");
    const disclaimer = document.querySelector("[data-testid='disclaimer-note']");
    const feedback = document.querySelector("button[aria-label='回答有帮助']");
    if (!content || !banner || !disclaimer || !feedback) return false;
    const follows = (a: Element, b: Element) =>
      (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
    return follows(content, banner) && follows(banner, disclaimer) && follows(disclaimer, feedback);
  });
  expect(ok, "正文 → 横幅 → disclaimer → 反馈条 DOM 序").toBe(true);
}

test.describe("信任溯源（MS-29）", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过信任溯源用例");

  // ———— 场景 A：API 种子会话（F4 携带落库 + F5 回灌重建，不经 LLM/SSE） ————

  test("种子 payload 回灌：三态角标 + 浮层（含 verified 措辞）+ 横幅/disclaimer + 刷新仍在 + 切会话重开", async ({
    page,
  }) => {
    test.setTimeout(90_000);
    await registerAndApprove(page, uniqueUsername("trustseed"), TEST_PASSWORD);
    const convId = await firstConversationId(page);

    const userQuestion = "请分析贵州茅台 2024 年的营收、净利润与市场传闻份额";
    // 注记：三个 unverified 数字使 stats.unverified=3，与 unverified_ratio 信号契约自洽（F3 Fix 轮 1 口径）
    const content =
      "贵州茅台 2024 年营业收入 1741 亿元，净利润 862 亿元（MCP 口径）；" +
      "另据市场传闻份额约 25%、经销商约 3000 家、基酒产能约 5.6 万吨，供参考。";
    const payload = {
      v: 1,
      anchors: [
        {
          snippet: "1741 亿元",
          occ: 1,
          state: "verified",
          tool: "get_financial_metrics",
          args: { code: "600519", metric: "revenue" },
          asOf: "2025-04-02",
          asOfKind: "data",
          raw: "174143000000.00",
        },
        {
          snippet: "862 亿元",
          occ: 1,
          state: "sourced",
          tool: "miao_quant",
          args: { symbol: "600519.SH" },
          asOf: "2026-10-06 09:31:00",
          asOfKind: "call",
        },
        { snippet: "25%", occ: 1, state: "unverified" },
        { snippet: "3000 家", occ: 1, state: "unverified" },
        { snippet: "5.6 万吨", occ: 1, state: "unverified" },
      ],
      stats: { verified: 1, sourced: 1, unverified: 3 },
      advice: { flag: true, by: "both", text: "本回答由 AI 生成，可能包含未经核实的信息，不构成投资建议。" },
      confidence: { signals: ["unverified_ratio:0.6", "stale_financials:1"] },
    };
    const now = Date.now();
    const put = await page.request.put(`/api/conversations/${convId}/messages`, {
      data: [
        { id: "e2e-seed-u1", role: "user", content: userQuestion, createdAt: now },
        {
          id: "e2e-seed-a1",
          role: "assistant",
          content,
          createdAt: now + 1,
          payload: JSON.stringify(payload),
        },
      ],
    });
    expect(put.status()).toBe(200);

    // 回灌渲染：F5 rebuild → 三态角标按 anchors 数组序落位
    await page.reload();
    await expect(page.getByText(userQuestion)).toBeVisible({ timeout: 15_000 });
    await expect(badge(page)).toHaveCount(5, { timeout: 15_000 });
    await expect(badge(page, "verified")).toHaveCount(1);
    await expect(badge(page, "sourced")).toHaveCount(1);
    await expect(badge(page, "unverified")).toHaveCount(3);
    // 角标缀于对应 snippet 之后（包裹元素含 snippet；序号 = anchors 数组序）
    await expect(anchorWrap(page, "verified", "1741 亿元").locator("sup")).toHaveText("1");
    await expect(anchorWrap(page, "sourced", "862 亿元").locator("sup")).toHaveText("2");
    await expect(anchorWrap(page, "unverified", "25%").locator("sup")).toHaveText("3");

    // 浮层（真 hover 后 opacity→1）：verified 措辞契约 + 工具/args/数据时间戳/raw 四行，
    // 且不出现「已核实」（决策 #13：verified 仅承诺数值一致）
    const verifiedPop = await hoverBadge(page, "verified", "1741 亿元");
    await expect(verifiedPop).toContainText("数值与工具返回一致");
    await expect(verifiedPop).not.toContainText("已核实");
    await expect(verifiedPop).toContainText("get_financial_metrics");
    await expect(verifiedPop).toContainText("code：600519");
    await expect(verifiedPop).toContainText("数据时间戳：2025-04-02");
    await expect(verifiedPop).toContainText("工具返回原值：174143000000.00");
    // sourced：已溯源未校验 + 调用时刻（决策 #17：不与数据时间戳混同），无 raw 行
    const sourcedPop = await hoverBadge(page, "sourced", "862 亿元");
    await expect(sourcedPop).toContainText("已溯源未校验");
    await expect(sourcedPop).toContainText("调用时刻：2026-10-06 09:31:00");
    await expect(sourcedPop).not.toContainText("工具返回原值");
    // unverified：无工具/时间戳/raw 行
    const unverifiedPop = await hoverBadge(page, "unverified", "25%");
    await expect(unverifiedPop).toContainText("未溯源：无工具数据支撑");
    await expect(unverifiedPop).not.toContainText("工具：");

    // 低置信横幅：unverified_ratio 计数口径（3 处）+ stale_financials + 核实路径建议（data-signal 稳定断言）
    const banner = page.getByTestId("confidence-banner");
    await expect(banner).toBeVisible();
    await expect(page.locator('[data-testid="confidence-signal"][data-signal="unverified_ratio"]')).toHaveText(
      "3 处数字未溯源",
    );
    await expect(page.locator('[data-testid="confidence-signal"][data-signal="stale_financials"]')).toHaveText(
      "数据时间戳陈旧",
    );
    await expect(page.getByTestId("confidence-suggestion")).toContainText("建议重问最新价或查看东方财富行情页");
    // disclaimer：后端透传文案 + by 小标签（both→两者）
    await expect(page.getByTestId("disclaimer-note")).toContainText("不构成投资建议");
    await expect(page.getByTestId("disclaimer-by")).toHaveText("两者");
    await expectAdvisoryOrder(page);

    // 刷新后角标仍在（payload 回灌幂等）
    await page.reload();
    await expect(badge(page)).toHaveCount(5, { timeout: 15_000 });
    await expect(banner).toBeVisible();
    await expect(page.getByTestId("disclaimer-note")).toBeVisible();

    // 跨会话重开同验：新对话（空历史 → store 全清、角标归零）→ 切回种子会话（rebuild 重建）
    await page.getByRole("button", { name: /新对话/ }).click();
    await expect(page.getByText("问行情 · 看走势 · 读财报")).toBeVisible({ timeout: 15_000 });
    await expect(badge(page)).toHaveCount(0);
    await page.locator("aside li").filter({ hasText: "贵州茅台" }).first().click();
    await expect(badge(page)).toHaveCount(5, { timeout: 15_000 });
    await expect(banner).toBeVisible();
  });

  // ———— 场景 B：SSE 脚本注入（F1 事件接入 → F2 角标 → F3 横幅 + 修正原位替换 + 落库回读） ————

  test("实时信任事件流：correction 原位替换错值 + 三态角标 + 横幅/disclaimer + 持久化刷新仍在", async ({
    page,
  }) => {
    test.setTimeout(90_000);
    const messageId = "e2e-live-a1";
    // 流内文本含错值两处「1741 亿元」；correction 替换第 2 处（F1 契约：correction 先于 anchors，
    // occ 定义在改写前原文）
    const streamedContent =
      "贵州茅台 2024 年营收 1741 亿元，环比口径 1741 亿元；净利润 862 亿元；另据市场传闻份额约 25%。";
    // anchors 为替换后终态锚定（B5 契约：anchors 在 correction 后到达，snippet 即 replacement 形态）
    const payload = {
      v: 1,
      anchors: [
        {
          snippet: "1741 亿元",
          occ: 1,
          state: "verified",
          tool: "get_financial_metrics",
          args: { code: "600519", metric: "revenue" },
          asOf: "2025-04-02",
          asOfKind: "data",
          raw: "174143000000.00",
        },
        {
          snippet: "1708 亿元",
          occ: 1,
          state: "verified",
          tool: "get_financial_metrics",
          args: { code: "600519", metric: "revenue_qoq" },
          asOf: "2025-04-02",
          asOfKind: "data",
          raw: "170795000000.00",
        },
        {
          snippet: "862 亿元",
          occ: 1,
          state: "sourced",
          tool: "miao_quant",
          args: { symbol: "600519.SH" },
          asOf: "2026-10-06 09:31:00",
          asOfKind: "call",
        },
        { snippet: "25%", occ: 1, state: "unverified" },
      ],
      stats: { verified: 2, sourced: 1, unverified: 1 },
      correction: { notes: ["环比口径已按工具返回修正"] },
      advice: { flag: true, by: "self", text: "本回答包含未经核实的市场传闻，不构成投资建议。" },
      confidence: { signals: ["corrections:1", "unverified_ratio:0.25"] },
    };
    await stubAgentRun(page, (threadId) => [
      { type: "RUN_STARTED", threadId, runId: "e2e-live-run" },
      { type: "TEXT_MESSAGE_START", messageId, role: "assistant" },
      { type: "TEXT_MESSAGE_CONTENT", messageId, delta: streamedContent },
      { type: "TEXT_MESSAGE_END", messageId },
      {
        type: "CUSTOM",
        name: "trust.correction",
        value: {
          messageId,
          snippet: "1741 亿元",
          occ: 2,
          replacement: "1708 亿元",
          note: "环比口径已按工具返回修正",
        },
      },
      { type: "CUSTOM", name: "trust.anchors", value: { messageId, payload } },
      { type: "RUN_FINISHED", threadId, runId: "e2e-live-run" },
    ]);

    await registerAndApprove(page, uniqueUsername("trustlive"), TEST_PASSWORD);
    const input = page.getByPlaceholder(/问行情、看走势、读财报/);
    await input.fill("贵州茅台 2024 年营收和环比口径怎么样");
    const send = page.getByRole("button", { name: "发送" });
    await expect(send).toBeEnabled({ timeout: 30_000 });
    await send.click();

    // 修正原位替换：第 2 处「1741 亿元」→「1708 亿元」，第 1 处保持
    await expect(page.getByText("环比口径 1708 亿元")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText("环比口径 1741 亿元")).toHaveCount(0);

    // 三态角标落位（替换后文本上锚定：verified×2 / sourced×1 / unverified×1）
    await expect(badge(page)).toHaveCount(4);
    await expect(badge(page, "verified")).toHaveCount(2);
    await expect(badge(page, "sourced")).toHaveCount(1);
    await expect(badge(page, "unverified")).toHaveCount(1);
    await expect(anchorWrap(page, "verified", "1708 亿元").locator("sup")).toHaveText("2");
    const replacedPop = await hoverBadge(page, "verified", "1708 亿元");
    await expect(replacedPop).toContainText("数值与工具返回一致");

    // 低置信横幅：corrections（修正介入的可见信号）+ unverified_ratio 计数
    await expect(page.getByTestId("confidence-banner")).toBeVisible();
    await expect(page.locator('[data-testid="confidence-signal"][data-signal="corrections"]')).toHaveText(
      "校验修正已介入",
    );
    await expect(page.locator('[data-testid="confidence-signal"][data-signal="unverified_ratio"]')).toHaveText(
      "1 处数字未溯源",
    );
    // disclaimer（by=self→自声明）
    await expect(page.getByTestId("disclaimer-note")).toContainText("不构成投资建议");
    await expect(page.getByTestId("disclaimer-by")).toHaveText("自声明");

    // 持久化链（F4）：替换后 content + 终态 payload 落库（防抖 flush + 运行结束即刷，轮询到为准）
    const convId = await firstConversationId(page);
    await expect
      .poll(
        async () => {
          const view = (await (
            await page.request.get(`/api/conversations/${convId}/messages`)
          ).json()) as { messages: Array<{ id: string; payload?: string | null }> };
          const a = view.messages.find((m) => m.id === messageId);
          return a?.payload?.includes("1708 亿元") ?? false;
        },
        { timeout: 20_000 },
      )
      .toBe(true);

    // 刷新后仍在（F5 回灌）：替换后文本 + 角标 + 横幅/disclaimer 全量重建
    await page.reload();
    await expect(page.getByText("环比口径 1708 亿元")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("环比口径 1741 亿元")).toHaveCount(0);
    await expect(badge(page)).toHaveCount(4);
    await expect(badge(page, "verified")).toHaveCount(2);
    await expect(page.getByTestId("confidence-banner")).toBeVisible();
    await expect(page.getByTestId("disclaimer-note")).toBeVisible();
  });

  // ———— 场景 C：不误附对照（无 advice/confidence 键 → 横幅与 disclaimer 缺席，含刷新后） ————

  test("非建议回答不误附：无 advice/confidence 的 anchors → 横幅与 disclaimer 缺席", async ({ page }) => {
    test.setTimeout(90_000);
    const messageId = "e2e-plain-a1";
    const payload = {
      v: 1,
      anchors: [
        {
          snippet: "1741 亿元",
          occ: 1,
          state: "verified",
          tool: "get_financial_metrics",
          args: { code: "600519", metric: "revenue" },
          asOf: "2025-04-02",
          asOfKind: "data",
          raw: "174143000000.00",
        },
      ],
      stats: { verified: 1, sourced: 0, unverified: 0 },
      // 故意不带 advice / confidence（缺键 = 无信号无建议，B6/B7 缺省语义）
    };
    await stubAgentRun(page, (threadId) => [
      { type: "RUN_STARTED", threadId, runId: "e2e-plain-run" },
      { type: "TEXT_MESSAGE_START", messageId, role: "assistant" },
      { type: "TEXT_MESSAGE_CONTENT", messageId, delta: "贵州茅台 2024 年营收 1741 亿元。" },
      { type: "TEXT_MESSAGE_END", messageId },
      { type: "CUSTOM", name: "trust.anchors", value: { messageId, payload } },
      { type: "RUN_FINISHED", threadId, runId: "e2e-plain-run" },
    ]);

    await registerAndApprove(page, uniqueUsername("trustplain"), TEST_PASSWORD);
    const input = page.getByPlaceholder(/问行情、看走势、读财报/);
    await input.fill("贵州茅台 2024 年营收是多少");
    const send = page.getByRole("button", { name: "发送" });
    await expect(send).toBeEnabled({ timeout: 30_000 });
    await send.click();

    // 角标正常而横幅/disclaimer 零渲染（对照组：同 payload 形态仅缺 advice/confidence）。
    // 断言文本取角标切分前的连续段：角标 sup 缀于「1741 亿元」后，含句号的整句不再是
    // 任何元素的连续文本（首次运行实测）
    await expect(page.getByText("贵州茅台 2024 年营收 1741 亿元")).toBeVisible({ timeout: 30_000 });
    await expect(badge(page, "verified")).toHaveCount(1);
    await expect(page.getByTestId("confidence-banner")).toHaveCount(0);
    await expect(page.locator('[data-testid="confidence-signal"]')).toHaveCount(0);
    await expect(page.getByTestId("confidence-suggestion")).toHaveCount(0);
    await expect(page.getByTestId("disclaimer-note")).toHaveCount(0);
    await expect(page.getByTestId("disclaimer-by")).toHaveCount(0);

    // 刷新后（payload 已持久化、回灌重建）仍不误附
    await page.reload();
    await expect(badge(page, "verified")).toHaveCount(1, { timeout: 15_000 });
    await expect(page.getByTestId("confidence-banner")).toHaveCount(0);
    await expect(page.getByTestId("disclaimer-note")).toHaveCount(0);
  });
});
