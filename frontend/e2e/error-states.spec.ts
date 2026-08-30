import { test, expect } from "@playwright/test";

// 错误态 e2e：用 page.route 在浏览器侧拦截制造后端故障，无需真实后端参与业务链路。
// 断言目标是「显示错误文案而非白屏」，不依赖真实行情数据。

const fakeHit = [{ code: "600519", name: "贵州茅台", market: "SH", marketName: "上海" }];

const fakeApprovedUser = {
  id: 1,
  username: "e2e_error_state",
  role: "USER",
  status: "APPROVED",
  enabled: true,
};

function json502(message: string) {
  return {
    status: 502,
    contentType: "application/json",
    body: JSON.stringify({ message }),
  };
}

test.describe("后端故障时的错误态渲染", () => {
  test("行情接口 502 → 行情页显示错误文案而非白屏", async ({ page }) => {
    // 搜索放行（返回假命中），其余行情接口全部 502
    await page.route("**/api/market/**", (route) => {
      const url = route.request().url();
      if (url.includes("/api/market/search")) {
        return route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(fakeHit),
        });
      }
      return route.fulfill(json502("行情服务暂不可用"));
    });

    await page.goto("/market");
    await page.getByPlaceholder(/输入股票名称或代码搜索/).fill("600519");
    await page.getByRole("button", { name: /贵州茅台/ }).click();

    // 报价/K线/财务/新闻 502 → MarketBoard 渲染错误横幅
    await expect(page.getByText("行情服务暂不可用")).toBeVisible({ timeout: 15_000 });
    // 不是白屏：页面主结构仍在
    await expect(page.getByPlaceholder(/输入股票名称或代码搜索/)).toBeVisible();
  });

  test("持仓接口 502 → 持仓页显示错误文案而非白屏", async ({ page }) => {
    // 跳过真实登录链路：直接拦截会话探测接口，返回已审核用户
    await page.route("**/api/auth/me", (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(fakeApprovedUser),
      }),
    );
    await page.route("**/api/portfolio/**", (route) =>
      route.fulfill(json502("持仓服务暂不可用")),
    );

    await page.goto("/portfolio");
    await expect(page.getByText("加载失败：持仓服务暂不可用")).toBeVisible({ timeout: 15_000 });
  });
});
