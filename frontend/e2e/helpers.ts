import { type Page, expect } from "@playwright/test";

// 密码策略：至少 8 位，含字母和数字（与后端 PasswordPolicy 对齐）。
export const TEST_PASSWORD = "Passw0rd123";

const ADMIN_USERNAME = process.env.ADMIN_USERNAME;
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD;

/** 生成每次运行唯一的用户名，避免对持久化后端重复注册触发“用户名已存在”。 */
export function uniqueUsername(prefix: string): string {
  const rand = Math.random().toString(36).slice(2, 8);
  return `e2e_${prefix}_${Date.now().toString(36)}${rand}`.toLowerCase();
}

/** 前往 /register 提交注册，并等待“注册成功”提示出现。 */
export async function registerUser(page: Page, username: string, password: string): Promise<void> {
  await page.goto("/register");
  await page.getByPlaceholder("用户名").fill(username);
  await page.getByPlaceholder(/至少 8 位/).fill(password);
  await page.getByPlaceholder("再次输入密码").fill(password);
  await page.getByRole("button", { name: "注 册" }).click();
  await expect(page.getByText(/注册成功/)).toBeVisible();
}

/** 前往 /login 以用户名/密码登录；成功后由前端 `router.replace("/")` 跳转。 */
export async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByPlaceholder("用户名").fill(username);
  await page.getByPlaceholder("密码").fill(password);
  await page.getByRole("button", { name: "登 录" }).click();
}

/** 点击顶部导航“退出”，并等待跳转回 /login。 */
export async function logout(page: Page): Promise<void> {
  await page.getByRole("button", { name: "退出" }).click();
  await expect(page).toHaveURL(/\/login/, { timeout: 15_000 });
}

/** 管理员登录（依赖 ADMIN_USERNAME/ADMIN_PASSWORD 已注入环境）。 */
export async function adminLogin(page: Page): Promise<void> {
  if (!ADMIN_USERNAME || !ADMIN_PASSWORD) {
    throw new Error("未配置 ADMIN_USERNAME/ADMIN_PASSWORD，无法执行管理员操作");
  }
  await login(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL("/", { timeout: 15_000 });
}

/**
 * 完整走一遍“注册 → 管理员审核通过 → 退出 → 以该用户登录”，
 * 结束时停留在对话页（/）。供需要已审核登录态的用例复用。
 */
export async function registerAndApprove(page: Page, username: string, password: string): Promise<void> {
  await registerUser(page, username, password);
  await expect(page.getByText(/等待管理员审核/)).toBeVisible();
  await adminLogin(page);
  await page.goto("/admin");
  const row = page.locator("li", { hasText: username });
  await expect(row).toBeVisible({ timeout: 15_000 });
  await row.getByRole("button", { name: "通过" }).click();
  // 通过后从“待审核用户”列表消失
  await expect(page.locator("li", { hasText: username })).toHaveCount(0);
  await logout(page);
  await login(page, username, password);
  await expect(page).toHaveURL("/", { timeout: 15_000 });
}
