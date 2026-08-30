import { test, expect } from "@playwright/test";
import {
  adminLogin,
  login,
  logout,
  registerAndApprove,
  TEST_PASSWORD,
  uniqueUsername,
} from "./helpers";

// 管理后台操作流：停用/启用、重置密码。依赖种子管理员（ADMIN_USERNAME/ADMIN_PASSWORD），
// 未配置则整体跳过。注册→审核是有状态流程，关闭重试避免重复注册冲突。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("管理后台用户操作", () => {
  test.describe.configure({ retries: 0 });
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过管理操作流");

  test("停用后用户登录被拒，启用后恢复", async ({ page }) => {
    const user = uniqueUsername("toggle");
    await registerAndApprove(page, user, TEST_PASSWORD);
    await logout(page);

    // 管理员停用该用户
    await adminLogin(page);
    await page.goto("/admin");
    const row = page.locator("tr", { hasText: user });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole("button", { name: "停用" }).click();
    // 刷新后按钮翻转为「启用」，确认停用生效
    await expect(row.getByRole("button", { name: "启用" })).toBeVisible();
    await logout(page);

    // 停用后登录被拒（后端 403 ACCOUNT_DISABLED）
    await login(page, user, TEST_PASSWORD);
    await expect(page.getByText(/账号已被停用/)).toBeVisible();

    // 管理员重新启用
    await adminLogin(page);
    await page.goto("/admin");
    await row.getByRole("button", { name: "启用" }).click();
    await expect(row.getByRole("button", { name: "停用" })).toBeVisible();
    await logout(page);

    // 启用后可正常登录进入对话页
    await login(page, user, TEST_PASSWORD);
    await expect(page).toHaveURL("/", { timeout: 15_000 });
    await expect(page.getByText("问行情 · 看走势 · 读财报")).toBeVisible({ timeout: 15_000 });
  });

  test("重置密码后新密码可登录、旧密码不可登录", async ({ page }) => {
    const user = uniqueUsername("resetpwd");
    const newPassword = "Newpass456";
    await registerAndApprove(page, user, TEST_PASSWORD);
    await logout(page);

    // 管理员通过弹窗重置密码
    await adminLogin(page);
    await page.goto("/admin");
    const row = page.locator("tr", { hasText: user });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole("button", { name: "重置密码" }).click();
    const dialog = page.getByRole("dialog", { name: `为 ${user} 重置密码` });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("新密码", { exact: true }).fill(newPassword);
    await dialog.getByLabel("确认新密码").fill(newPassword);
    await dialog.getByRole("button", { name: "确认重置" }).click();
    // 提交成功后弹窗关闭
    await expect(dialog).toHaveCount(0);
    await logout(page);

    // 旧密码登录被拒（后端 401 BAD_CREDENTIALS）
    await login(page, user, TEST_PASSWORD);
    await expect(page.getByText(/用户名或密码错误/)).toBeVisible();

    // 新密码登录成功
    await login(page, user, newPassword);
    await expect(page).toHaveURL("/", { timeout: 15_000 });
    await expect(page.getByText("问行情 · 看走势 · 读财报")).toBeVisible({ timeout: 15_000 });
  });
});
