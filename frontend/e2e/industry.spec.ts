import { test, expect, type APIRequestContext, type Locator } from "@playwright/test";
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
    await expect(stocks.getByText(/成员排名（[1-9]\d*/)).toBeVisible();
    await expect(stocks.getByText(/总市值\(亿\)/)).toBeVisible();
    // 营收历史注记：2026-09-26 前 dev 库 revenue 曾全空（income 合码 9-17 晚于末次采集 9-05，任务未重跑），
    // 彼时以「营收列恒 —」为空态锚；补数后改为下方双锚（有数锚 + 仍有空态行）。
    // 营收链路端到端有数（2026-09-26 补数后口径）：营收列仅在有值时渲染「报告期角标
    // yyyy-mm-dd」（IndustryStockTable revenueReportDate span），角标存在 ⟺ revenue 非空——
    // 结构性锚点，跨季度稳定。取银行业总市值第一的工商银行（601398，必披露营收）。
    const icbcRow = stocks.getByRole("row").filter({ hasText: "工商银行" }).first();
    await expect(icbcRow.getByText(/^\d{4}-\d{2}-\d{2}$/)).toBeVisible();
    // 数据不足空态仍须覆盖：营收未披露（income 无合并口径匹配）的行渲染「—」——
    // 50 行首页存在此类行（少数披露不全个股），仅断言存在性
    await expect(stocks.getByText("—").first()).toBeVisible();
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
    await expect(stocks.getByText(/成员排名（[1-9]\d*/)).toBeVisible();
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
  test("公开切未上市 tab：V19 种子行业全景卡有值（策展头部数=接口口径）且竞争格局 canvas 存在", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    // 解耦共享库残留/用户新增行（P2 评审收口 #3）：策展头部数以 overview 接口当下值为准，
    // 不再断言 V19 种子常量 5——接口非 200 直接失败暴露真实回归（照 boardHasData 口径注释）
    const overviewRes = await request.get("/api/industry/801080/unlisted/overview");
    expect(overviewRes.ok()).toBeTruthy();
    const overviewJson = (await overviewRes.json()) as { curatedCount: number };

    await page.goto("/industry/801080");
    await page.getByTestId("tab-unlisted").click();
    // 双态兜底（口径照 analytics.spec .or() 先例）：种子库全景卡 / 未策展空态至少其一可见
    const overview = page.getByTestId("unlisted-overview");
    await expect(overview.or(page.getByTestId("unlisted-empty"))).toBeVisible({ timeout: 20_000 });
    // 本地真库 + V19 种子：全景卡有值——上市数 >0（textContent 连排，正则锚定指标名后数值）、
    // 策展头部数 = 接口 curatedCount（页面与接口同库直查，读侧无缓存 §九#4）、口径脚注在场
    await expect(overview).toBeVisible();
    await expect(overview).toContainText(/上市公司[1-9]\d* 家/);
    await expect(overview).toContainText(`策展头部企业${overviewJson.curatedCount} 家`);
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

/**
 * MS-28 P2-F1 图谱节点点击深链行情台：ECharts 节点画在 canvas 上（无 DOM 可点），按 tier 色
 * （accent/up/ink-dim，运行时取 CSS 变量——与 ChainGraphCard 取色同源）扫描 canvas 像素定位
 * 节点；legend 同色 marker 在 canvas 底带，扫描排除底部 40px。force 布局动画期节点在动，
 * 轮询重扫；命中像素是节点内部实色像素，悬停可出 tooltip（name｜环节·层级）。
 */
async function findGraphNodePixel(canvas: Locator): Promise<{ x: number; y: number }> {
  const deadline = Date.now() + 10_000;
  for (;;) {
    const hit = await canvas.evaluate((el: SVGElement | HTMLElement) => {
      const canvasEl = el as HTMLCanvasElement;
      const style = getComputedStyle(document.documentElement);
      const hexToRgb = (name: string) => {
        const hex = style.getPropertyValue(name).trim();
        return hex.length === 7
          ? [
              parseInt(hex.slice(1, 3), 16),
              parseInt(hex.slice(3, 5), 16),
              parseInt(hex.slice(5, 7), 16),
            ]
          : null;
      };
      const tierColors = ["--color-accent", "--color-up", "--color-ink-dim"]
        .map(hexToRgb)
        .filter((c): c is number[] => c !== null);
      const ctx = canvasEl.getContext("2d");
      if (!ctx || !canvasEl.clientWidth || !canvasEl.clientHeight || tierColors.length === 0) {
        return null;
      }
      const dprX = canvasEl.width / canvasEl.clientWidth;
      const dprY = canvasEl.height / canvasEl.clientHeight;
      const img = ctx.getImageData(0, 0, canvasEl.width, canvasEl.height).data;
      const bottom = canvasEl.height - 40 * dprY; // legend 底带同色 marker，排除
      for (let y = 0; y < bottom; y += 2) {
        for (let x = 0; x < canvasEl.width; x += 2) {
          const i = (y * canvasEl.width + x) * 4;
          const r = img[i] ?? 0;
          const g = img[i + 1] ?? 0;
          const b = img[i + 2] ?? 0;
          const near = tierColors.some(
            ([tr, tg, tb]) => Math.abs(r - tr) <= 8 && Math.abs(g - tg) <= 8 && Math.abs(b - tb) <= 8,
          );
          if (near) return { x: x / dprX, y: y / dprY };
        }
      }
      return null;
    });
    if (hit) return hit;
    if (Date.now() > deadline) {
      throw new Error("canvas 未扫到 tier 色节点像素（force 布局未渲染或取色不符）");
    }
    await new Promise((r) => setTimeout(r, 400));
  }
}

/**
 * MS-10 产业链图谱（P3）：V20 种子保证锂电池链经上市成员行业映射（300750 宁德时代 等 →
 * 801730 电力设备）派生出现在该行业下钻页；登录态全文档编辑器建链→图卡出现→删除链消失。
 */
test.describe("/industry 产业链图谱（MS-10 P3）", () => {
  test("公开切产业链 tab：V20 种子锂电池链图卡与 canvas 在场", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    await page.goto("/industry/801730");
    await page.getByTestId("tab-chain").click();
    // V20 种子链 id 稳定（锂电池=1），但共享 dev 库可能有用户新链——按卡片文本锚定锂电池
    const card = page.locator('[data-testid^="chain-card-"]').filter({ hasText: "锂电池" });
    await expect(card).toBeVisible({ timeout: 20_000 });
    await expect(card.locator("canvas")).toBeVisible({ timeout: 15_000 });
    // 链描述头在场（V20 种子描述含「动力电池全产业链」）
    await expect(card).toContainText("动力电池全产业链");
  });

  // 锂电池种子 8 成员全上市挂真实 A 股代码（V1 基线种子，原 V20）——任意节点点击都应深链
  const CHAIN_NAME_BY_CODE: Record<string, string> = {
    "002460": "赣锋锂业", "002466": "天齐锂业", "002812": "恩捷股份", "600884": "杉杉股份",
    "300750": "宁德时代", "300014": "亿纬锂能", "000625": "长安汽车", "601127": "赛力斯",
  };

  test("图谱上市节点点击深链行情台：/market?code= 自动选中该股（MS-28 P2-F1）", async ({ page, request }) => {
    // 板面数据必需：fetchIndustryStocks 对库中不存在的行业报「行业不存在」（整页走错误分支，
    // tab 不渲染）——本地空板面库实测确认，门控口径与同组用例一致
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    test.skip(!hasAdminSeed, SKIP_NO_ADMIN);
    test.setTimeout(120_000); // 注册审核 + 真实行情接口（超时口径照 market.spec）
    await registerAndApprove(page, uniqueUsername("ms28f1"), TEST_PASSWORD);
    await page.goto("/industry/801730");
    await page.getByTestId("tab-chain").click();
    const card = page.locator('[data-testid^="chain-card-"]').filter({ hasText: "锂电池" });
    await expect(card).toBeVisible({ timeout: 20_000 });
    const canvas = card.locator('[data-testid^="chain-graph-"]').locator("canvas");
    await expect(canvas).toBeVisible({ timeout: 15_000 });
    // force 布局动画先停一拍再扫像素，降低节点仍在漂移的概率
    await page.waitForTimeout(1_500);

    // 像素定位节点 → 悬停 tooltip 确认节点名（含 ｜ 分隔的环节信息）→ 同点位点击；
    // 布局仍在动导致悬空时（tooltip 不出）重扫重试，最多 3 轮
    const box = (await canvas.boundingBox())!;
    let nodeName = "";
    for (let attempt = 0; attempt < 3 && !nodeName; attempt++) {
      const node = await findGraphNodePixel(canvas);
      await page.mouse.move(box.x + node.x, box.y + node.y);
      const tooltip = card.locator("div").filter({ hasText: /｜/ }).last();
      try {
        await expect(tooltip).toContainText(/｜/, { timeout: 2_000 });
        nodeName = ((await tooltip.textContent()) ?? "").split("｜")[0]?.trim() ?? "";
        // tooltip 已确认节点在指针下——趁布局未再漂移立即同点位点击
        await page.mouse.click(box.x + node.x, box.y + node.y);
      } catch {
        // 节点漂移致悬空：重扫重试
      }
    }
    expect(nodeName).not.toBe("");
    await page.waitForURL(/\/market\?code=\d{6}/, { timeout: 15_000 });
    const code = new URL(page.url()).searchParams.get("code") ?? "";
    // 点击的节点与落点 URL 互证（代码→股名取自种子台账）
    expect(nodeName).toBe(CHAIN_NAME_BY_CODE[code] ?? `<未知代码 ${code}>`);

    // 深链消费：行情台自动搜索该代码并选中首个命中，搜索框文本为该股名（真实接口，放宽超时）
    const input = page.getByPlaceholder(/输入股票名称或代码搜索/);
    await expect(input).toHaveValue(nodeName, { timeout: 60_000 });
    await expect(page.getByText("走势 · 前复权")).toBeVisible({ timeout: 60_000 });
  });

  test("登录后新建链（两环节各一上市成员）→ 图卡出现 → 编辑器删除链消失", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), SKIP_NO_DATA);
    test.skip(!hasAdminSeed, SKIP_NO_ADMIN);
    await registerAndApprove(page, uniqueUsername("ms10c"), TEST_PASSWORD);
    await page.goto("/industry/801730");
    await page.getByTestId("tab-chain").click();
    // tab 内容就绪双态（.or() 先例）：V20 种子图卡 / 未映射行业空态至少其一可见
    await expect(page.locator('[data-testid^="chain-card-"]').first()
      .or(page.getByTestId("chain-empty"))).toBeVisible({ timeout: 20_000 });

    const chainName = `E2E链${Date.now().toString(36)}`;
    await page.getByTestId("chain-add").click();
    const dialog = page.getByTestId("chain-editor-dialog");
    await dialog.getByLabel("链名（必填）").fill(chainName);
    // 第一环节（默认一成员）：环节名 + 上市成员代码/展示名
    await dialog.getByTestId("stage-name-0").fill("锂矿");
    await dialog.getByTestId("member-code-0-0").fill("300750");
    await dialog.getByTestId("member-name-0-0").fill("宁德时代");
    // 添加第二环节 + 另一上市成员
    await dialog.getByTestId("chain-add-stage").click();
    await dialog.getByTestId("stage-name-1").fill("整车");
    await dialog.getByTestId("member-code-1-0").fill("000625");
    await dialog.getByTestId("member-name-1-0").fill("长安汽车");
    await dialog.getByRole("button", { name: "保存", exact: true }).click();

    // 全文档保存成功 → 图卡出现且 canvas 真渲染
    const card = page.locator('[data-testid^="chain-card-"]').filter({ hasText: chainName });
    await expect(card).toBeVisible({ timeout: 20_000 });
    await expect(card.locator("canvas")).toBeVisible({ timeout: 15_000 });

    // 行内编辑 → 删除链（window.confirm 由 dialog handler 接受）→ 图卡消失
    const cardTestId = await card.getAttribute("data-testid");
    const chainId = cardTestId!.split("-").pop();
    await page.getByTestId(`chain-edit-${chainId}`).click();
    // Playwright 默认 dismiss 对话框会使 confirm 返回 false——显式 accept
    page.once("dialog", (d) => d.accept());
    await page.getByTestId("chain-delete").click();
    await expect(page.locator('[data-testid^="chain-card-"]').filter({ hasText: chainName }))
      .toHaveCount(0, { timeout: 15_000 });
  });
});
