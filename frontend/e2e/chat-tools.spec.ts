import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

// MS-12：5 个新工具纳入对话链路（真实 LLM，双门控同 chat.spec）。
// 断言信号说明：具名 ChartCard 渲染器接管工具卡的渲染（ToolCallCard 标签不再显示），
// 故断言图表卡的实际渲染产物——筛选表格标题「筛选结果（」与空持仓的 ChartCard 降级折叠卡
// （analyze_portfolio 空持仓返回纯文本，ChartCard 降级形态与 get_valuation 冷库同款）。
test.describe("AI 工具二期", () => {
  test.skip(!process.env.DEEPSEEK_API_KEY, "未配置 DEEPSEEK_API_KEY，跳过真实对话");
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过");

  test("筛选工具：提问选股 → 筛选结果表格卡渲染", async ({ page }) => {
    test.setTimeout(240_000); // 真 LLM 生成 18 参工具调用可能超默认 30s
    await registerAndApprove(page, uniqueUsername("mst"), TEST_PASSWORD);
    const input = page.getByPlaceholder(/问行情、看走势、读财报/);
    await input.fill("帮我筛选 ROE 大于 15% 且 PE 小于 20 的股票");
    const sendBtn = page.getByRole("button", { name: "发送" });
    await expect(sendBtn).toBeEnabled({ timeout: 30_000 });
    await sendBtn.click();
    await expect(page.getByText("筛选结果（").first()).toBeVisible({ timeout: 180_000 });
    await expect(page.getByRole("button", { name: "■ 停止" })).toBeHidden({ timeout: 180_000 });
  });

  test("持仓工具：空持仓用户问组合 → 工具调用 + 引导回复", async ({ page }) => {
    test.setTimeout(240_000);
    await registerAndApprove(page, uniqueUsername("msp"), TEST_PASSWORD);
    const input = page.getByPlaceholder(/问行情、看走势、读财报/);
    await input.fill("帮我分析一下我的持仓组合怎么样");
    const sendBtn = page.getByRole("button", { name: "发送" });
    await expect(sendBtn).toBeEnabled({ timeout: 30_000 });
    await sendBtn.click();
    // analyze_portfolio 被调用的可观测信号：ChartCard 降级折叠卡（空持仓纯文本结果）
    await expect(page.getByText("数据异常（原始结果折叠）").first()).toBeVisible({ timeout: 180_000 });
    await expect(page.getByRole("button", { name: "■ 停止" })).toBeHidden({ timeout: 180_000 });
  });
});
