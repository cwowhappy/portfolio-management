import { test, expect } from "@playwright/test";

test.describe("应用导航", () => {
  test("首页标题与顶部导航", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveTitle(/砚台/);
    await expect(page.getByRole("link", { name: "对话" })).toBeVisible();
    await expect(page.getByRole("link", { name: "行情台" })).toBeVisible();
  });

  test("导航到行情台", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: "行情台" }).click();
    await expect(page).toHaveURL(/\/market/);
    await expect(page.getByPlaceholder(/输入股票名称或代码搜索/)).toBeVisible();
  });
});
