import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// /portfolio 由 RequireAuth 包裹，需已审核用户登录；注册后须由种子管理员审核，
// 故与 auth.spec.ts 一致依赖 ADMIN_USERNAME/ADMIN_PASSWORD。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/portfolio 持仓组合管理", () => {
  // 注册→审核→登录 是有状态流程（跨用例共享后端），重试会用同一用户名再次注册造成冲突。
  test.describe.configure({ retries: 0 });
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过持仓用例");

  test("登录后访问并渲染空态", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("pf"), TEST_PASSWORD);

    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });

    await expect(page.getByRole("heading", { name: "持仓组合" })).toBeVisible();
    await expect(page.getByTestId("position-table").getByText("暂无持仓")).toBeVisible();
    await expect(page.getByRole("button", { name: "买入" })).toBeVisible();
  });

  test("创建账户分组并买入后出现持仓", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("pf"), TEST_PASSWORD);

    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });

    // BuyForm 的分组下拉在挂载时取 groups[0]，新建账户前为空；先创建一个 ACCOUNT 分组，
    // 否则买入时 groupId 为空导致后端「分组不存在」。默认类型即「账户」。
    await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
    await page.getByRole("button", { name: "新建" }).click();
    // 等待分组刷新：分组切换标签出现「主账户」。
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();

    // BuyForm 的分组下拉不会随 groups 更新而自动选中，显式选中刚建的账户分组。
    await page.getByLabel("分组").selectOption({ label: "主账户" });

    await page.getByLabel("代码").fill("600519");
    await page.getByLabel("名称").fill("贵州茅台");
    await page.getByLabel("价格").fill("1500");
    await page.getByLabel("数量").fill("100");
    await page.getByRole("button", { name: "买入" }).click();

    const table = page.getByTestId("position-table");
    // 全量跑时真实行情接口可能拖慢后端响应（买入后需重新加载总览），给足超时；
    // 本用例禁用重试（重复注册会撞唯一用户名），超时不足会直接红。
    await expect(table.getByText("贵州茅台")).toBeVisible({ timeout: 15_000 });
    await expect(table.getByText("暂无持仓")).toHaveCount(0);
  });
});
