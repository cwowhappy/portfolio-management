import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/journal 投资决策记录", () => {
  test.describe.configure({ retries: 0 });
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过配置用例");

  test("登录后访问并创建研究笔记", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("jr"), TEST_PASSWORD);

    await page.getByRole("link", { name: "决策" }).click();
    await expect(page).toHaveURL(/\/journal/, { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "投资决策记录" })).toBeVisible();
    await expect(page.getByText(/暂无事件/)).toBeVisible();

    await page.getByRole("button", { name: "记录" }).click();
    await page.getByRole("button", { name: "研究笔记" }).click();
    await page.getByPlaceholder("标题").fill("白酒行业研究");
    await page.getByPlaceholder("内容（Markdown）").fill("这是研究笔记内容");
    await page.getByRole("button", { name: "保存记录" }).click();

    await expect(page.getByTestId("entry-list").getByText("白酒行业研究")).toBeVisible({ timeout: 15_000 });
  });
});
