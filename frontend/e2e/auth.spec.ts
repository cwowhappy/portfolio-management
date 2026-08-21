import { test, expect } from "@playwright/test";
import { adminLogin, login, logout, registerUser, TEST_PASSWORD, uniqueUsername } from "./helpers";

// 认证流依赖启动时种子的管理员（ADMIN_USERNAME/ADMIN_PASSWORD），未配置则整体跳过。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

// 注册→审核→登录 是有状态流程（跨用例共享同一用户名），必须串行；
// 关闭重试：重试会用同一用户名再次注册，对持久化后端产生“用户名已存在”冲突。
test.describe("用户管理认证流", () => {
  test.describe.configure({ retries: 0 });
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过认证流");

  const approvedUser = uniqueUsername("approve");
  const rejectedUser = uniqueUsername("reject");

  test.describe.serial("注册→审核→登录 全流程", () => {
    test("1. 注册新用户后显示等待审核", async ({ page }) => {
      await registerUser(page, approvedUser, TEST_PASSWORD);
      await expect(page.getByText(/等待管理员审核/)).toBeVisible();
    });

    test("2. 管理员登录并在 /admin 审核通过该用户", async ({ page }) => {
      await adminLogin(page);
      await page.goto("/admin");
      const row = page.locator("li", { hasText: approvedUser });
      await expect(row).toBeVisible({ timeout: 15_000 });
      await row.getByRole("button", { name: "通过" }).click();
      // 通过后从“待审核用户”列表消失
      await expect(page.locator("li", { hasText: approvedUser })).toHaveCount(0);
      // “全部用户”表中该用户状态为“已通过”
      const tr = page.locator("tr", { hasText: approvedUser });
      await expect(tr).toBeVisible();
      await expect(tr.getByText("已通过")).toBeVisible();
    });

    test("3. 该用户登录后进入对话页", async ({ page }) => {
      await login(page, approvedUser, TEST_PASSWORD);
      await expect(page).toHaveURL("/", { timeout: 15_000 });
      // 到达对话页：空状态与输入框渲染。只断言到达聊天 UI，不校验真实模型回复（不依赖 AI Key）。
      await expect(page.getByText("问行情 · 看走势 · 读财报")).toBeVisible({ timeout: 15_000 });
      await expect(page.getByPlaceholder(/问行情、看走势、读财报/)).toBeVisible();
    });

    test("4. 注册另一用户→管理员拒绝→该用户登录被拒", async ({ page }) => {
      await registerUser(page, rejectedUser, TEST_PASSWORD);
      await expect(page.getByText(/等待管理员审核/)).toBeVisible();
      await adminLogin(page);
      await page.goto("/admin");
      const row = page.locator("li", { hasText: rejectedUser });
      await expect(row).toBeVisible({ timeout: 15_000 });
      await row.getByRole("button", { name: "拒绝" }).click();
      await expect(page.locator("li", { hasText: rejectedUser })).toHaveCount(0);
      await logout(page);
      await login(page, rejectedUser, TEST_PASSWORD);
      await expect(page.getByText(/已被拒绝/)).toBeVisible();
    });
  });
});

// 无需登录即可验证的重定向行为（RequireAuth 会把未认证访客送往 /login）。
test.describe("未登录访问", () => {
  test("5. 未登录访问首页被重定向到登录页", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/login/, { timeout: 15_000 });
  });
});
