import { test, expect, type APIRequestContext } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

/** 与 industry.spec.ts 同范式：CI 空库时行业联动用例无从发生，探测板面为空则跳过。 */
async function boardHasData(request: APIRequestContext): Promise<boolean> {
  const res = await request.get("/api/industry/board");
  if (!res.ok()) return true;
  const body: unknown = await res.json();
  return !(Array.isArray(body) && body.length === 0);
}

test.describe("/wiki 投资知识库", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过配置用例");

  test("概念预置 + 读书笔记 Markdown 渲染 + 原则规则", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("wk"), TEST_PASSWORD);

    await page.getByRole("link", { name: "知识库" }).click();
    await expect(page).toHaveURL(/\/wiki/, { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "投资知识库" })).toBeVisible();

    // 概念 tab：首次访问自动出现预置词条（seeding）
    await page.getByTestId("wiki-tab-concept").click();
    await expect(page.getByTestId("wiki-note-list").getByText("安全边际")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("wiki-note-list").getByText("护城河")).toBeVisible();

    // 读书笔记：创建 + 详情 Markdown 渲染
    await page.getByTestId("wiki-tab-book").click();
    await page.getByTestId("wiki-note-title").fill("《聪明的投资者》");
    await page.getByTestId("wiki-note-content").fill("## 核心观点\n- 市场先生是仆非主");
    await page.getByTestId("wiki-note-save").click();
    await expect(page.getByTestId("wiki-note-list").getByText("《聪明的投资者》")).toBeVisible({ timeout: 15_000 });
    await page.getByTestId("wiki-note-list").getByText("《聪明的投资者》").click();
    await expect(page.getByRole("heading", { name: "核心观点" })).toBeVisible();

    // 原则纪律：建规则（% 输入 → 0.2 落库 → 20% 展示）
    await page.getByTestId("wiki-tab-principle").click();
    await page.getByTestId("wiki-rule-threshold").fill("20");
    await page.getByTestId("wiki-rule-save").click();
    await expect(page.getByTestId("wiki-rule-list").getByText("单票仓位上限")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("wiki-rule-list").getByText("20%")).toBeVisible();
  });

  test("行业下钻页保存研究结论 → 知识库可见（MS-11 验收第二条）", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), "库中无行业板面数据（CI 空库），跳过行业联动用例");
    await registerAndApprove(page, uniqueUsername("wkj"), TEST_PASSWORD);

    await page.goto("/industry");
    await page.getByRole("button", { name: /银行/ }).first().click();
    await expect(page).toHaveURL(/\/industry\/\d+/, { timeout: 15_000 });

    await page.getByTestId("research-note-open").click();
    await expect(page.getByTestId("research-note-title")).toHaveValue(/研究结论$/);
    await page.getByTestId("research-note-content").fill("低估值区间，关注息差改善");
    await page.getByTestId("research-note-save").click();
    await page.getByTestId("research-note-view-link").click();

    await expect(page).toHaveURL(/\/wiki\?tab=research/, { timeout: 15_000 });
    await expect(page.getByTestId("wiki-note-list").getByText("研究结论")).toBeVisible({ timeout: 15_000 });
  });

  test("保存连点防重：双击保存按钮仅发一次创建请求", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("wkd"), TEST_PASSWORD);

    // 计数经反代发出的创建 POST（确定性断言，替代定时窗口）
    let createCount = 0;
    await page.route("**/api/wiki/entries", async (route) => {
      if (route.request().method() === "POST") createCount++;
      await route.continue();
    });

    await page.goto("/wiki?tab=book");
    const title = `连点笔记 ${Date.now().toString(36)}`;
    await page.getByTestId("wiki-note-title").fill(title);
    await page.getByTestId("wiki-note-content").fill("## 连点防重");
    await page.getByTestId("wiki-note-save").dblclick(); // 第二次点击落在 in-flight 禁用窗口内
    await expect(page.getByTestId("wiki-note-list").getByText(title)).toBeVisible({ timeout: 15_000 });
    expect(createCount).toBe(1);
  });
});
