import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/allocation 资产配置", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过配置用例");

  test("登录后访问并套用模板创建方案", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("al"), TEST_PASSWORD);

    await page.getByRole("link", { name: "配置" }).click();
    await expect(page).toHaveURL(/\/allocation/, { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "资产配置" })).toBeVisible();
    await expect(page.getByTestId("deviation-chart")).toContainText("暂无生效方案");

    await page.getByRole("button", { name: "60/40 股债平衡" }).click();
    await page.getByRole("button", { name: "保存方案" }).click();

    await expect(page.getByTestId("plan-list").getByText("60/40 股债平衡")).toBeVisible({ timeout: 15_000 });
  });

  test("风险测评定档并可按推荐创建方案", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("asm"), TEST_PASSWORD);

    await page.getByRole("link", { name: "配置" }).click();
    await expect(page).toHaveURL(/\/allocation/, { timeout: 15_000 });

    await page.getByRole("button", { name: "开始测评" }).click();
    await expect(page.getByTestId("questionnaire-form")).toBeVisible({ timeout: 15_000 });

    // 3 题 A(5 分) + 5 题 B(4 分) = 35 → 成长（切点上边界）
    // 全部 exact: true——Q4 的「收入稳定」是「收入稳定且持续增长」的子串，不精确会触发严格模式违例
    await page.getByLabel("30 岁及以下", { exact: true }).check();
    await page.getByLabel("5 年以上", { exact: true }).check();
    await page.getByLabel("小部分，大部分另有安排", { exact: true }).check();
    await page.getByLabel("收入稳定", { exact: true }).check();
    await page.getByLabel("20%–30%", { exact: true }).check();
    await page.getByLabel("继续持有等待回升", { exact: true }).check();
    await page.getByLabel("偏向较高收益，容忍一定波动", { exact: true }).check();
    await page.getByLabel("5–10 年", { exact: true }).check();

    await page.getByRole("button", { name: "提交问卷" }).click();
    await expect(page.getByTestId("assessment-profile")).toContainText("成长", { timeout: 15_000 });

    await page.getByRole("button", { name: "按推荐创建方案" }).click();
    await expect(page.getByTestId("plan-list").getByText("测评推荐·成长")).toBeVisible({ timeout: 15_000 });
  });

  // 再平衡全链路（MS-08）：纯用户数据路径，空库可跑——
  // 现金转入 10000（唯一资产，无行情依赖）→ 永久组合 25×4 激活 →
  // 阈值触发（股票实际 0 vs 目标 25；现金实际 100 vs 目标 25）+ 金额全可手算（T=10000）。
  test("再平衡：现金转入后提醒触发、金额建议正确、ack 刷新锚点", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("rb"), TEST_PASSWORD);

    // 造数据：主账户现金转入 10000（总资产 T=10000，无持仓 → 无行情依赖）
    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });
    await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
    await page.getByRole("button", { name: "新建" }).click();
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();
    await page.getByLabel("现金账户").selectOption({ label: "主账户" });
    await page.getByLabel("转入转出").selectOption("DEPOSIT");
    await page.getByLabel("金额").fill("10000");
    await page.getByRole("button", { name: "录入" }).click();
    // 现金余额出现（总览卡展示金额，格式含千分位或裸数字）
    await expect(page.getByText(/10,?000/).first()).toBeVisible({ timeout: 15_000 });

    // 配置页：套用永久组合（25×4）并设为生效
    await page.getByRole("link", { name: "配置" }).click();
    await expect(page).toHaveURL(/\/allocation/, { timeout: 15_000 });
    await page.getByRole("button", { name: "永久组合" }).click();
    await page.getByRole("button", { name: "保存方案" }).click();
    await page.getByTestId("plan-list").getByText("永久组合").first().waitFor();
    await page.getByRole("button", { name: "设为生效" }).click();

    // 提醒卡：阈值触发（股票 0 vs 25）+ 金额断言（T=10000）
    await expect(page.getByTestId("rebalance-alert")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("rebalance-alert")).toContainText("股票");
    const stockRow = page.getByTestId("rebalance-row-STOCK");
    await expect(stockRow).toContainText("买入");
    await expect(stockRow).toContainText("2,500"); // 25% × 10000
    const cashRow = page.getByTestId("rebalance-row-CASH");
    await expect(cashRow).toContainText("卖出");
    await expect(cashRow).toContainText("7,500"); // (100% − 25%) × 10000
    // 债券/黄金同口径建议买入 2500（持仓侧恒 0，场外配置）
    await expect(page.getByTestId("rebalance-row-BOND")).toContainText("2,500");
    await expect(page.getByTestId("rebalance-row-GOLD")).toContainText("2,500");

    // 导航红点亮
    await expect(page.getByTestId("allocation-alert-dot")).toBeVisible();

    // ack：锚点刷新可见；偏离未变，阈值提醒不因 ack 消失
    await page.getByRole("button", { name: "已完成再平衡" }).click();
    await expect(page.getByTestId("rebalance-anchor")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("rebalance-anchor")).toContainText("上次再平衡");
    await expect(page.getByTestId("rebalance-alert")).toBeVisible();
  });
});
