import { test, expect } from "@playwright/test";

test.describe("/valuation 市场估值仪表盘", () => {
  test("公开访问并渲染指标", async ({ page }) => {
    await page.goto("/valuation");
    await expect(page.getByText("市场估值仪表盘")).toBeVisible();
    await expect(page.getByText("全A PE 中位数")).toBeVisible();
    await expect(page.getByText("市场情绪温度计")).toBeVisible();
  });
});
