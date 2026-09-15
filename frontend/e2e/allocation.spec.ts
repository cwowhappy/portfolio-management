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
});
