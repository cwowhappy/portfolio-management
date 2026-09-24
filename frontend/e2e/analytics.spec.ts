import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// /analytics 由 RequireAuth 包裹，需已审核用户登录；注册后须由种子管理员审核，
// 故与 portfolio.spec.ts 一致依赖 ADMIN_USERNAME/ADMIN_PASSWORD。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

// 造数据说明（与 portfolio.spec.ts 同法，最小路径）：
// - 先「现金转入」再买入（issue #45 起买入校验分组现金，负现金形态从源头阻断）；
// - 断言不依赖 close 回填：首事件日 = 今日，窗口 [今日,今日]，序列仅今日 quoteBatch
//   实时价单点 → TWR/年化为 0；转入构成外部现金流 → IRR 走 XIRR 真解，
//   均属预期短序列形态。精确数值对拍在 AnalyticsApplicationServiceTest 已知答案用例
//   （转入+买入+涨10% → TWR 20%），e2e 不依赖实时行情做脆化精确断言。
test.describe("/analytics 收益分析", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过收益分析用例");

  test("登录后访问渲染空态", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("ana"), TEST_PASSWORD);

    await page.getByRole("link", { name: "收益分析" }).click();
    await expect(page).toHaveURL(/\/analytics/, { timeout: 15_000 });

    await expect(page.getByRole("heading", { name: "收益分析" })).toBeVisible();
    await expect(page.getByTestId("analytics-empty")).toBeVisible();
  });

  test("转入并买入后四块渲染 + 单点短序列口径", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("ana"), TEST_PASSWORD);

    // 造数据走 /portfolio UI：建组 → 现金转入 → 买入（分组下拉细节注释见 portfolio.spec.ts）
    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });
    await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
    await page.getByRole("button", { name: "新建" }).click();
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();
    await page.getByLabel("现金账户").selectOption({ label: "主账户" });
    await page.getByLabel("转入转出").selectOption("DEPOSIT");
    await page.getByLabel("金额", { exact: true }).fill("200000");
    await page.getByRole("button", { name: "录入" }).click();
    await expect(page.getByText("现金 200000.00")).toBeVisible({ timeout: 15_000 });
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
    // 单点短序列口径（issue #45 验收回归）：TWR 累计 0.00%；总资产 = 市值 + 49995 现金 > 0
    // （负现金失真形态已被买入校验从源头阻断）。
    await expect(overview.getByText("TWR 累计").locator("..").getByText("0.00%")).toBeVisible();
    const totalText = await overview.getByText("总资产").locator("..").innerText();
    const total = Number(totalText.replace(/[^0-9.]/g, ""));
    expect(total).toBeGreaterThan(49995);
  });

  test("转入并买入后风险指标与归因渲染（回填窗口口径）", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("risk"), TEST_PASSWORD);

    // 造数据与上例同法：建组 → 现金转入 → 买入，但转入/买入日期回填 14 天前——
    // risk-stats/attribution 端点均要求 nav ≥2 个日点（首事件=当日 → 单点 → 204 走块级空态），
    // 回填后窗口 [past, today] 含多个交易日收盘，风险卡才有数据可渲染。
    // 断言只看渲染存在性而非数值（MDD/夏普对实时行情脆化，精确对拍在单测已知答案用例）。
    const pastDate = new Date(Date.now() - 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });
    await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
    await page.getByRole("button", { name: "新建" }).click();
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();
    await page.getByLabel("现金账户").selectOption({ label: "主账户" });
    await page.getByLabel("转入转出").selectOption("DEPOSIT");
    await page.getByLabel("金额", { exact: true }).fill("200000");
    // 两处「日期」输入（现金表单 aria-label / 买入表单 label）同名，按各自操作按钮的容器锚定
    await page.getByRole("button", { name: "录入" }).locator("..").getByLabel("日期").fill(pastDate);
    await page.getByRole("button", { name: "录入" }).click();
    await expect(page.getByText("现金 200000.00")).toBeVisible({ timeout: 15_000 });
    await page.getByLabel("分组").selectOption({ label: "主账户" });
    await page.getByLabel("代码").fill("600519");
    await page.getByLabel("名称").fill("贵州茅台");
    await page.getByRole("button", { name: "买入" }).locator("..").getByLabel("日期").fill(pastDate);
    await page.getByLabel("价格").fill("1500");
    await page.getByLabel("数量").fill("100");
    await page.getByRole("button", { name: "买入" }).click();
    await expect(page.getByTestId("position-table").getByText("贵州茅台")).toBeVisible({ timeout: 15_000 });

    await page.getByRole("link", { name: "收益分析" }).click();
    await expect(page).toHaveURL(/\/analytics/, { timeout: 15_000 });
    // 多日窗口 → risk 端点非 204，卡片必渲染（Board 等 6 接口齐返回才出 loading，
    // nav 含 quoteBatch 实时行情重试，首块给足超时；风险/归因与其同批出现）。
    await expect(page.getByTestId("risk-stats")).toBeVisible({ timeout: 20_000 });
    // attribution 受后端行业/基准窗口交集影响：section 与 empty 双态皆合法
    await expect(page.getByTestId("attribution-section").or(page.getByTestId("attribution-empty"))).toBeVisible();
  });
});
