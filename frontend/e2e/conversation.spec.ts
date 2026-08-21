import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// 会话持久化依赖已审核登录态（注册后须由种子管理员审核），故依赖 ADMIN_USERNAME/ADMIN_PASSWORD。
// 每个用例用唯一用户名，与持久化后端互不冲突，可安全并行/重试。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("会话持久化", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过会话持久化用例");

  test("1. 登录后自动创建首个会话，刷新后仍显示在侧边栏", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("conv"), TEST_PASSWORD);

    // RuntimeProvider 挂载时列表为空 → 自动创建首个会话（默认标题“新会话”）
    const sidebarItem = page.locator("aside li").first();
    await expect(sidebarItem).toBeVisible({ timeout: 15_000 });
    await expect(sidebarItem.getByText("新会话")).toBeVisible();

    // 刷新：会话从服务端列表重新加载，仍显示在侧边栏（持久化）
    await page.reload();
    const afterReload = page.locator("aside li").first();
    await expect(afterReload).toBeVisible({ timeout: 15_000 });
    await expect(afterReload.getByText("新会话")).toBeVisible();
  });

  test("2. 首条消息写入后侧边栏标题 = 前 24 字并跨刷新持久", async ({ page }) => {
    const msgText = "请帮我分析一下贵州茅台这只股票的最新走势与估值情况如何呢";
    const title24 = msgText.slice(0, 24); // 与后端 TITLE_MAX=24 对齐

    await registerAndApprove(page, uniqueUsername("convtitle"), TEST_PASSWORD);
    const sidebarItem = page.locator("aside li").first();
    await expect(sidebarItem).toBeVisible({ timeout: 15_000 });

    // 取首个会话 id：page.request 与浏览器共享会话 Cookie，等价于已登录请求
    const list = await page.request.get("/api/conversations");
    expect(list.status()).toBe(200);
    const metas = (await list.json()) as Array<{ id: string }>;
    expect(metas.length).toBeGreaterThanOrEqual(1);
    const convId = metas[0].id;

    // 写入首条用户消息（不经 UI 发送，不依赖 LLM），触发后端 renameIfDefault 生成标题
    const put = await page.request.put(`/api/conversations/${convId}/messages`, {
      data: [{ id: "e2e-msg-1", role: "user", content: msgText, createdAt: Date.now() }],
    });
    expect(put.status()).toBe(204);

    // 刷新：会话仍在侧边栏，标题 = 首条消息前 24 字
    await page.reload();
    const titled = page.locator("aside li").first().getByText(title24);
    await expect(titled).toBeVisible({ timeout: 15_000 });
  });

  test("3. 新建会话后列表出现两条", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("conv2"), TEST_PASSWORD);
    await expect(page.locator("aside li")).toHaveCount(1, { timeout: 15_000 });

    await page.getByRole("button", { name: /新对话/ }).click();
    await expect(page.locator("aside li")).toHaveCount(2, { timeout: 15_000 });
  });

  test("4. 删除非当前会话后列表移除", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("conv3"), TEST_PASSWORD);
    await expect(page.locator("aside li")).toHaveCount(1, { timeout: 15_000 });

    // 新建一个会话；新建后当前线程切到新会话（其按钮带 aria-current）
    await page.getByRole("button", { name: /新对话/ }).click();
    await expect(page.locator("aside li")).toHaveCount(2, { timeout: 15_000 });

    // 删除列表中非当前会话，避免触发 deleteThread 对当前线程的“删除后自动新建”
    const nonCurrent = page.locator("aside li:not(:has(button[aria-current]))").first();
    await expect(nonCurrent).toBeVisible();
    await nonCurrent.hover();
    await nonCurrent.getByRole("button", { name: "删除会话" }).click();
    await expect(page.locator("aside li")).toHaveCount(1, { timeout: 15_000 });
  });
});

// 未登录访问会话接口 → 401；不依赖登录态与管理员种子。
test.describe("未登录会话接口", () => {
  test("5. 未登录 GET /api/conversations 返回 401", async ({ request }) => {
    const res = await request.get("/api/conversations");
    expect(res.status()).toBe(401);
  });
});
