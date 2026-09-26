import { test, expect, type APIRequestContext } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

test.describe("/industry 行业估值", () => {
  test("公开访问并渲染对比表与热力图", async ({ page }) => {
    await page.goto("/industry");
    await expect(page.getByRole("heading", { name: "行业估值" })).toBeVisible();
    await expect(page.getByText("行业估值对比")).toBeVisible();
    await expect(page.getByText("估值热力图")).toBeVisible();
  });
});

/**
 * MS-09 用例依赖回填数据（industry_valuation / stock_valuation_daily / 成分映射）。
 * CI e2e 起的是空库（ci.yml e2e job 仅 Flyway 迁移 + 种子管理员），板面为空时行点击/下钻
 * 无从发生——探测板面接口为空则跳过，与 ADMIN_USERNAME / DEEPSEEK_API_KEY 门控同范式。
 * 接口非 200 时不门控（返回 true），让用例以可见失败暴露真实回归，避免空转绿灯。
 */
async function boardHasData(request: APIRequestContext): Promise<boolean> {
  const res = await request.get("/api/industry/board");
  if (!res.ok()) return true;
  const body: unknown = await res.json();
  return !(Array.isArray(body) && body.length === 0);
}

const SKIP_NO_DATA = "库中无行业板面数据（CI 空库），跳过 MS-09 数据依赖用例";

test.describe("/industry 行业研究（MS-09）", () => {
  test("board 渲染分位列与景气列", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry");
    const table = page.getByTestId("industry-board-table");
    await expect(table).toBeVisible({ timeout: 15_000 });
    await expect(table.getByText("PE 5y分位")).toBeVisible();
    await expect(table.getByText("PB 5y分位")).toBeVisible();
    await expect(table.getByText("景气")).toBeVisible();
    // 回填后至少一行行业名（申万一级固定含银行），且分位格有数值（xx.x%）而非全「—」
    await expect(table.getByRole("button", { name: /银行/ })).toBeVisible();
    await expect(table.getByText(/\d+\.\d+%/).first()).toBeVisible();
  });

  test("行点击进入下钻页并渲染成员排名", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry");
    await page.getByTestId("industry-board-table").getByRole("button", { name: /银行/ }).click();
    await page.waitForURL(/\/industry\/801780/);
    const stocks = page.getByTestId("industry-stocks-table");
    await expect(stocks).toBeVisible({ timeout: 15_000 });
    await expect(stocks.getByText(/成员排名（\d+/)).toBeVisible();
    await expect(stocks.getByText(/总市值\(亿\)/)).toBeVisible();
  });

  test("下钻页排序切换", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry");
    await page.getByTestId("industry-board-table").getByRole("button", { name: /银行/ }).click();
    await page.waitForURL(/\/industry\/801780/);
    const stocks = page.getByTestId("industry-stocks-table");
    await expect(stocks).toBeVisible({ timeout: 15_000 });
    const firstRow = () => stocks.getByRole("row").nth(1);
    const before = (await firstRow().textContent()) ?? "";
    // 默认 total_mv DESC；点击表头切 ASC：表头出现「↑」，首行由最大市值换为最小市值成员
    await stocks.getByText(/总市值\(亿\)/).click();
    await expect(stocks.getByText(/总市值\(亿\)\s*↑/)).toBeVisible();
    await expect(firstRow()).not.toHaveText(before);
  });

  test("直接访问下钻页渲染成员表与返回链接", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    // 深链不经过板面点击：成员表直接可用，页头行业名仍经板面接口解析，返回链接指向 /industry
    await page.goto("/industry/801780");
    const stocks = page.getByTestId("industry-stocks-table");
    await expect(stocks).toBeVisible({ timeout: 15_000 });
    await expect(stocks.getByText(/成员排名（\d+/)).toBeVisible();
    await expect(page.getByRole("heading", { name: /银行/ })).toBeVisible();
    await expect(page.getByText(/← 行业榜单/)).toBeVisible();
  });
});

// 关注持久化需注册用户并经种子管理员审核（照 portfolio.spec 的 ADMIN seed 门控范式）
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);
const SKIP_NO_ADMIN = "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过关注持久化用例";

test.describe("/industry 行业对比与关注（MS-14 P2）", () => {
  test("公开切对比视图：搜索过滤、勾选两行业渲染并排对比表", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry");
    await page.getByTestId("view-compare").click();
    const picker = page.getByTestId("industry-compare-picker");
    await expect(picker).toBeVisible({ timeout: 15_000 });
    // 行业列表默认渲染多家行业（申万一级），搜索框可用
    const rows = picker.locator("label");
    expect(await rows.count()).toBeGreaterThan(1);
    // 搜索「银行」：列表过滤至仅剩银行一行（名称 includes 匹配，非银金融等被滤除）
    await picker.getByLabel("搜索行业").fill("银行");
    await expect(rows).toHaveCount(1);
    await expect(rows).toContainText("银行");
    // 勾选银行；清空搜索后从列表头部另勾一个非银行行业
    await rows.locator("input").check();
    await picker.getByLabel("搜索行业").fill("");
    let otherName = "";
    for (let i = 0; i < (await rows.count()); i++) {
      const name = (await rows.nth(i).innerText()).trim();
      if (!name || name === "银行") continue;
      await rows.nth(i).locator("input").check();
      otherName = name;
      break;
    }
    expect(otherName).toBeTruthy();
    // 对比表渲染：thead 1 行 + 两家选中行业各 1 行
    const table = page.getByTestId("industry-compare-table");
    await expect(table).toBeVisible();
    await expect(table).toContainText("银行");
    await expect(table).toContainText(otherName);
    await expect(table.getByRole("row")).toHaveCount(3);
  });

  test("登录后关注银行：对比视图默认勾选，刷新后仍默认勾选（持久化）", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    test.skip(!hasAdminSeed, SKIP_NO_ADMIN);
    await registerAndApprove(page, uniqueUsername("indw"), TEST_PASSWORD);
    await page.goto("/industry");
    // 榜单视图 ⭐ 关注银行（801780）：aria-label 随关注态由「关注」翻转为「取消关注」
    const board = page.getByTestId("industry-board-table");
    await board.getByRole("button", { name: "关注 801780" }).click();
    await expect(board.getByRole("button", { name: "取消关注 801780" })).toBeVisible({ timeout: 15_000 });
    // 切对比视图：关注集默认勾选（未手动改动前 selected 派生自 watchedCodes）
    await page.getByTestId("view-compare").click();
    const picker = page.getByTestId("industry-compare-picker");
    await expect(picker).toBeVisible({ timeout: 15_000 });
    await expect(picker.locator("label", { hasText: "银行" }).locator("input")).toBeChecked();
    // reload：视图状态回榜单，重切对比后关注集来自后端 industry_watch → 银行仍默认勾选
    await page.reload();
    await page.getByTestId("view-compare").click();
    await expect(page.getByTestId("industry-compare-picker").locator("label", { hasText: "银行" }).locator("input")).toBeChecked();
    // 清理：对比视图 ⭐ 取关银行（防污染后续运行；用户本身已按次唯一）
    await picker.getByRole("button", { name: "取消关注 801780" }).click();
    await expect(picker.getByRole("button", { name: "关注 801780" })).toBeVisible({ timeout: 15_000 });
  });

  test("未登录点 ⭐ 跳转登录页并携带 redirect=/industry", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    // ⭐ 随行业行渲染，空库无行可点 → 同样走 boardHasData 门控
    await page.goto("/industry");
    await page.getByTestId("view-compare").click();
    await page.getByTestId("industry-compare-picker").getByRole("button", { name: "关注 801780" }).click();
    await expect(page).toHaveURL(/\/login/);
    const url = new URL(page.url());
    expect(url.searchParams.get("redirect")).toBe("/industry");
    await expect(page.getByPlaceholder("用户名")).toBeVisible();
  });
});

/**
 * MS-10 未上市与融资（P2）：V19 种子行业 801080 电子（策展 5 家恒定——V19.1 只改日期不改
 * 行数）；公开读侧双态断言（.or() 写法照 analytics.spec 先例——未策展行业出 unlisted-empty，
 * 种子行业出全景卡）；登录态策展增删与导入行错误走 registerAndApprove + 管理员种子门控。
 */
test.describe("/industry 未上市与融资（MS-10）", () => {
  test("公开切未上市 tab：V19 种子行业全景卡有值（策展 5 家）且竞争格局 canvas 存在", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry/801080");
    await page.getByTestId("tab-unlisted").click();
    // 双态兜底（口径照 analytics.spec .or() 先例）：种子库全景卡 / 未策展空态至少其一可见
    const overview = page.getByTestId("unlisted-overview");
    await expect(overview.or(page.getByTestId("unlisted-empty"))).toBeVisible({ timeout: 20_000 });
    // 本地真库 + V19 种子：全景卡有值——上市数 >0（textContent 连排，正则锚定指标名后数值）、
    // 策展头部数恒 5 家（V19 种子行数恒定，V19.1 仅改日期）、口径脚注在场
    await expect(overview).toBeVisible();
    await expect(overview).toContainText(/上市公司[1-9]\d* 家/);
    await expect(overview).toContainText("策展头部企业5 家");
    await expect(overview).toContainText("策展名单与月度摘录融资事件，非全量口径");
    // F09 竞争格局气泡：ECharts canvas 真渲染（非仅容器 div）
    await expect(page.getByTestId("landscape-chart").locator("canvas")).toBeVisible({ timeout: 15_000 });
  });

  test("登录后新增策展企业→表格可见→删除后消失", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    test.skip(!hasAdminSeed, SKIP_NO_ADMIN);
    await registerAndApprove(page, uniqueUsername("ms10"), TEST_PASSWORD);
    await page.goto("/industry/801080");
    await page.getByTestId("tab-unlisted").click();
    await expect(page.getByTestId("unlisted-companies-table")).toBeVisible({ timeout: 15_000 });

    const name = `E2E策展${Date.now().toString(36)}`;
    await page.getByTestId("curation-edit-open").click();
    await page.getByTestId("unlisted-company-dialog").getByLabel("企业名称（必填）").fill(name);
    // 页头「保存研究结论」入口同为含「保存」的按钮名——限定对话框内精确匹配
    await page.getByTestId("unlisted-company-dialog").getByRole("button", { name: "保存", exact: true }).click();
    const table = page.getByTestId("unlisted-companies-table");
    await expect(table.getByText(name)).toBeVisible({ timeout: 15_000 });

    // 行内删除（管理列）→ 名字从名单消失
    //（playwright 1.62 实测 getByRole 第二参 hasText 不生效，须显式 .filter()）
    await table.getByRole("row").filter({ hasText: name })
      .getByRole("button", { name: "删除" }).click();
    await expect(table.getByText(name)).toHaveCount(0, { timeout: 15_000 });
  });

  test("批量导入行错误：表头对、行错轮次 → 行级错误清单含行号", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    test.skip(!hasAdminSeed, SKIP_NO_ADMIN);
    await registerAndApprove(page, uniqueUsername("ms10e"), TEST_PASSWORD);
    await page.goto("/industry/801080");
    await page.getByTestId("tab-unlisted").click();
    await expect(page.getByTestId("curation-panel")).toBeVisible({ timeout: 15_000 });

    // 表头八列精确匹配（L1 过），数据行轮次非法（L2 行级错）——all-or-nothing 不落库
    const badCsv = [
      "industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note",
      `801080,E2E错轮次企业,半导体设备,X轮,2026-08-15,10.00,e2e 行错误用例,e2e`,
      "",
    ].join("\n");
    await page.getByTestId("curation-import-open").click();
    await page.getByTestId("curation-import-dialog").getByLabel("CSV 文件")
      .setInputFiles({ name: "bad-round.csv", mimeType: "text/csv", buffer: Buffer.from(badCsv, "utf-8") });
    // 入口按钮「批量导入」含「导入」字样——限定对话框内精确匹配
    await page.getByTestId("curation-import-dialog").getByRole("button", { name: "导入", exact: true }).click();
    const errors = page.getByTestId("curation-import-row-errors");
    await expect(errors).toBeVisible({ timeout: 15_000 });
    await expect(errors).toContainText("2"); // 表头为行 1，首个数据行行号 2
    await expect(errors).toContainText(/轮次/);
  });

  test("竞争格局：种子行业 landscape-chart 容器与 canvas 在场", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry/801080");
    await page.getByTestId("tab-unlisted").click();
    await expect(page.getByTestId("landscape-chart")).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId("landscape-chart").locator("canvas")).toBeVisible({ timeout: 15_000 });
  });
});
