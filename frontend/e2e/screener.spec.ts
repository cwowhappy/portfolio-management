import { test, expect } from "@playwright/test";

test.describe("/screener 价值筛选器", () => {
  test("公开访问并渲染表单", async ({ page }) => {
    await page.goto("/screener");
    await expect(page.getByText("价值筛选器")).toBeVisible();
    await expect(page.getByText("估值水平")).toBeVisible();
    await expect(page.getByText(/不构成投资建议/)).toBeVisible();
  });
});
