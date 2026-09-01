import { test, expect } from "@playwright/test";

test.describe("/industry 行业估值", () => {
  test("公开访问并渲染对比表与热力图", async ({ page }) => {
    await page.goto("/industry");
    await expect(page.getByText("行业估值")).toBeVisible();
    await expect(page.getByText("行业估值对比")).toBeVisible();
    await expect(page.getByText("估值热力图")).toBeVisible();
  });
});
