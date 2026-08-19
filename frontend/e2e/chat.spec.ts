import { test, expect } from "@playwright/test";

test.describe("对话页", () => {
  test("空状态渲染示例问题", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByText("问行情 · 看走势 · 读财报")).toBeVisible();
    await expect(page.getByText(/帮我看看贵州茅台/)).toBeVisible();
    await expect(page.getByText(/今天大盘表现/)).toBeVisible();
    await expect(page.getByText(/搜索一下宁德时代/)).toBeVisible();
  });
});

// AI 真实对话依赖 DEEPSEEK_API_KEY，未配置时整组跳过。
test.describe("AI 对话", () => {
  test.skip(!process.env.DEEPSEEK_API_KEY, "未配置 DEEPSEEK_API_KEY，跳过真实对话");

  test("发送消息并收到助手回复", async ({ page }) => {
    await page.goto("/");
    const input = page.getByPlaceholder(/问行情、看走势、读财报/);
    await input.fill("用一句话介绍你自己");
    // 等待发送按钮可用（agent 就绪 isReady）
    const sendBtn = page.getByRole("button", { name: "发送" });
    await expect(sendBtn).toBeEnabled({ timeout: 30_000 });
    await sendBtn.click();

    // 运行开始：停止按钮出现
    await expect(page.getByRole("button", { name: "■ 停止" })).toBeVisible({ timeout: 30_000 });
    // 运行结束：停止按钮消失（真实 LLM 流式回答，超时放宽）
    await expect(page.getByRole("button", { name: "■ 停止" })).toBeHidden({ timeout: 180_000 });
    // 用户消息气泡已渲染（sidebar 会话标题也含同一文本，取最后一个元素）
    await expect(page.getByText("用一句话介绍你自己").last()).toBeVisible();
  });
});
