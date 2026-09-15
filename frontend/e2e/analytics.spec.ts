import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// /analytics 由 RequireAuth 包裹，需已审核用户登录；注册后须由种子管理员审核，
// 故与 portfolio.spec.ts 一致依赖 ADMIN_USERNAME/ADMIN_PASSWORD。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

// 造数据说明（与 portfolio.spec.ts 同法，最小路径）：
// - 不做「现金转入」：买入不校验现金余额（先例 portfolio.spec.ts 直接买入），
//   总资产 = 市值 + 负现金，仅断言四块渲染不校验数值符号；
// - 断言不依赖 close 回填：首事件日 = 今日，窗口 [今日,今日]，序列仅今日 quoteBatch
//   实时价单点 → TWR/年化为 0、IRR 无解显示「—」，均属预期短序列形态。
test.describe("/analytics 收益分析", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过收益分析用例");

  test("登录后访问渲染空态", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("ana"), TEST_PASSWORD);

    await page.getByRole("link", { name: "收益分析" }).click();
    await expect(page).toHaveURL(/\/analytics/, { timeout: 15_000 });

    await expect(page.getByRole("heading", { name: "收益分析" })).toBeVisible();
    await expect(page.getByTestId("analytics-empty")).toBeVisible();
  });

  test("买入后四块渲染（容忍短序列）", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("ana"), TEST_PASSWORD);

    // 造数据走 /portfolio UI：建组 → 买入（分组下拉细节注释见 portfolio.spec.ts）
    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });
    await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
    await page.getByRole("button", { name: "新建" }).click();
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();
    await page.getByLabel("分组").selectOption({ label: "主账户" });
    await page.getByLabel("代码").fill("600519");
    await page.getByLabel("名称").fill("贵州茅台");
    await page.getByLabel("价格").fill("1500");
    await page.getByLabel("数量").fill("100");
    await page.getByRole("button", { name: "买入" }).click();
    await expect(page.getByTestId("position-table").getByText("贵州茅台")).toBeVisible({ timeout: 15_000 });

    await page.getByRole("link", { name: "收益分析" }).click();
    await expect(page).toHaveURL(/\/analytics/, { timeout: 15_000 });
    // 四块断言：overview 首块给足超时（页面等 4 个接口齐返回才出 loading，nav 含
    // quoteBatch 实时行情重试）；其余块与 overview 同一渲染批次出现。
    const overview = page.getByTestId("analytics-overview");
    await expect(overview).toBeVisible({ timeout: 20_000 });
    await expect(overview.getByText("总资产")).toBeVisible();
    await expect(page.getByTestId("nav-chart")).toBeVisible();
    await expect(page.getByTestId("annual-table")).toBeVisible();
    await expect(page.getByTestId("trade-stats")).toBeVisible();
  });
});
