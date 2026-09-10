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

    // 触发写工具：审批卡片出现在消息流尾部（FR-3）。
    // 先 fill 再等发送按钮可用（isReady；chat.spec 同款顺序——按钮 disabled={!ready || !draft}，
    // 空 draft 时恒 disabled），Enter 在未就绪时会被吞
    await page.getByPlaceholder(/问行情、看走势/).fill(
      '请调用 write_note 工具，把内容 "e2e-approved" 写入笔记。等我的确认结果，不要改用其他方式。',
    );
    await expect(page.getByRole("button", { name: "发送" })).toBeEnabled({ timeout: 30_000 });
    await page.keyboard.press("Enter");
    const card = page.locator("div.tool-card", { hasText: "需要确认：" }).filter({ hasText: "write_note" });
    await card.waitFor({ state: "visible", timeout: 120_000 });

    // 批准 → 卡片消失 → 工具执行（落盘）→ Agent 续跑（FR-4）
    await card.getByRole("button", { name: "批准" }).click();
    await card.waitFor({ state: "detached", timeout: 15_000 });
    await expect.poll(() => notesText(), { timeout: 120_000 }).toContain("e2e-approved");

    // 拒绝路径：再触发一次，拒绝 → 不落盘。
    // 先等首轮运行彻底结束（停止按钮消失）：submit() 在 isRunning 时直接 return，
    // 落盘即发第二条会被吞、消息滞留输入框（chat.spec 同款“运行结束”判定）
    await expect(page.getByRole("button", { name: "■ 停止" })).toBeHidden({ timeout: 120_000 });
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
