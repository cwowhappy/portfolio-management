import { test, expect, type APIRequestContext } from "@playwright/test";
import fs from "node:fs";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/screener 价值筛选器", () => {
  test("公开访问并渲染表单", async ({ page }) => {
    await page.goto("/screener");
    await expect(page.getByText("价值筛选器")).toBeVisible();
    await expect(page.getByText("估值水平")).toBeVisible();
    await expect(page.getByText(/不构成投资建议/)).toBeVisible();
  });

  // 管线级断言（CI e2e 为空库，无行情/成分股数据；数据正确性由后端集成测试覆盖）
  test("自选标签页匿名显示登录引导", async ({ page }) => {
    await page.goto("/screener");
    await page.getByTestId("tab-watchlist").click();
    await expect(page.getByTestId("watchlist-panel")).toBeVisible();
    await expect(page.getByTestId("watchlist-panel")).toContainText(/登录/);
  });

  test("登录后自选标签页渲染搜索与空列表", async ({ page }) => {
    test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过登录用例");
    await registerAndApprove(page, uniqueUsername("wl"), TEST_PASSWORD);
    await page.goto("/screener");
    await page.getByTestId("tab-watchlist").click();
    await expect(page.getByTestId("watchlist-panel")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByPlaceholder("搜索代码或名称添加")).toBeVisible();
    await expect(page.getByTestId("watchlist-panel")).toContainText(/暂无自选/); // 空库无行情数据也成立
  });

  test("指数范围下拉可选并进入筛选；CSV 可下载且文件名带时间戳", async ({ page }) => {
    await page.goto("/screener");
    await expect(page.getByLabel("指数范围")).toBeVisible();
    await page.getByLabel("指数范围").selectOption("000300");

    // PE<999999 保证至少一个条件（空库下结果为空集，仍能导出仅表头 CSV）
    await page.getByLabel("PE-TTM <").fill("999999");
    await page.locator("form button[type=\"submit\"]").click(); // 与「筛选」tab 同名，用 submit 定位

    const download = page.waitForEvent("download");
    await page.getByTestId("export-csv").click();
    const d = await download;
    expect(d.suggestedFilename()).toMatch(/^screening-\d{8}-\d{6}\.csv$/);
  });

  // ── 基金 tab（MS-14 P3，ETF 四维筛选）──
  test("基金 tab 公开渲染四维表单与 TE 口径提示，未提交不渲染结果表", async ({ page }) => {
    await page.goto("/screener");
    await page.getByTestId("tab-fund").click();
    // 四控件：三数值（label 即单位口径）+ 类别下拉
    await expect(page.getByLabel("费率 < %（年化，0.6=0.6%）")).toBeVisible();
    await expect(page.getByLabel("规模 > 亿元")).toBeVisible();
    await expect(page.getByLabel("跟踪误差 <（小数，0.05=5%）")).toBeVisible();
    await expect(page.getByLabel("类别")).toBeVisible();
    // TE 口径提示：收盘价自算口径，与官方净值口径不可直接对比（表单常驻文案）
    await expect(page.getByText(/跟踪误差为收盘价口径（含分红\/折溢价噪声）/)).toBeVisible();
    // 空态：未提交前不渲染结果表（CI 空库/本地未查询两态皆成立）
    await expect(page.getByText(/筛选结果（/)).toHaveCount(0);
  });

  test("基金筛选结果 CSV 可下载且文件名带时间戳", async ({ page }) => {
    await page.goto("/screener");
    await page.getByTestId("tab-fund").click();
    // 类别即条件（空库下结果为空集，仍渲染导出链接与仅表头 CSV）
    await page.getByLabel("类别").selectOption("宽基");
    await page.locator("form button[type=\"submit\"]").click();
    await expect(page.getByText(/筛选结果（\d+/)).toBeVisible();

    const download = page.waitForEvent("download");
    await page.getByTestId("fund-export-csv").click();
    const d = await download;
    expect(d.suggestedFilename()).toMatch(/^fund-screening-\d{8}-\d{6}\.csv$/);

    // 口径注（issue #56）：表头末列口径 + 注释行（CI 空库仅表头也含两片段）
    const path = await d.path();
    const csvText = path ? fs.readFileSync(path, "utf-8").replace(/^\uFEFF/, "") : "";
    expect(csvText).toContain("跟踪误差(%,收盘价口径)");
    expect(csvText).toContain("# 注：跟踪误差为收盘价口径（含分红/折溢价噪声）与官方净值口径不可直接对比");
  });
});

/**
 * 基金数据面门控（照 industry.spec boardHasData 范式）：CI e2e 空库无 etf_basic 数据，
 * 探测「宽基+费率<0.6」查询为空则跳过；接口非 200 时不门控（返回 true），
 * 让用例以可见失败暴露真实回归。探测同时钉住 510300 在结果集内（⭐ 用例依赖该行）。
 */
async function fundScreeningHas510300(request: APIRequestContext): Promise<boolean> {
  const res = await request.get(`/api/screening/funds?category=${encodeURIComponent("宽基")}&feeRateMax=0.6`);
  if (!res.ok()) return true;
  const body: unknown = await res.json();
  return Array.isArray(body) && body.some((r) => (r as { fundCode?: string }).fundCode === "510300");
}

const SKIP_NO_FUNDS = "库中无 ETF 筛选数据（CI 空库），跳过基金数据依赖用例";

test.describe("/screener 基金 tab（MS-14 P3 数据链路）", () => {
  test("筛选提交走真后端渲染结果行，⭐ 加入自选后自选 tab 出现 ETF 行", async ({ page, request }) => {
    test.skip(!(await fundScreeningHas510300(request)), SKIP_NO_FUNDS);
    test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD，跳过登录用例");
    await registerAndApprove(page, uniqueUsername("fund"), TEST_PASSWORD);
    await page.goto("/screener");
    await page.getByTestId("tab-fund").click();
    await page.getByLabel("类别").selectOption("宽基");
    await page.getByLabel("费率 < %（年化，0.6=0.6%）").fill("0.6");
    await page.locator("form button[type=\"submit\"]").click();

    // 真后端 /api/screening/funds 反代链路（mock 盲区教训）：不断言具体行数，只断言 ≥1 行
    await expect(page.getByText(/筛选结果（\d+/)).toBeVisible({ timeout: 15_000 });
    const rows = page.locator("table tbody tr");
    await expect(rows.first()).toBeVisible();
    expect(await rows.count()).toBeGreaterThanOrEqual(1);
    // TE 列（行末格）：收盘价口径 ×100 两位小数（如 3.18），未知为「—」，疑似拆分遮蔽（issue #56）
    await expect(rows.first().locator("td").last()).toHaveText(/^(\d+\.\d{2}|—|—（疑似拆分\/异常）)$/);

    // ⭐ 加入自选（aria-label 随状态翻转）
    const row510300 = rows.filter({ hasText: "510300" });
    await row510300.getByRole("button", { name: "加自选 510300" }).click();
    await expect(row510300.getByRole("button", { name: "移除自选 510300" })).toBeVisible({ timeout: 15_000 });

    // 自选 tab：510300 行出现（现价列实时行情非空数值或「—」均可）
    await page.getByTestId("tab-watchlist").click();
    const panel = page.getByTestId("watchlist-panel");
    await expect(panel).toBeVisible({ timeout: 15_000 });
    const wlRow = panel.locator("tr", { hasText: "510300" }).first();
    await expect(wlRow).toBeVisible({ timeout: 15_000 });
    await expect(panel.getByTestId("watchlist-price-510300")).toHaveText(/^(\d+\.\d{2}|—)$/);
  });

  /** TE 遮蔽门控：探测降序首行是否 TE>0.3（CI 空库无数据则跳过；接口非 200 不门控，让回归可见失败）。 */
  async function fundHasSuspectTe(request: APIRequestContext): Promise<boolean> {
    const res = await request.get("/api/screening/funds?trackingErrorMax=5&sortBy=tracking_error_1y&sortDirection=DESC&limit=1");
    if (!res.ok()) return true;
    const body: unknown = await res.json();
    const first = Array.isArray(body) ? (body[0] as { trackingError1y?: number | null }) : null;
    return !!first && typeof first.trackingError1y === "number" && first.trackingError1y > 0.3;
  }

  test("TE>30% 行遮蔽为疑似拆分/异常，不展示假精度数值", async ({ page, request }) => {
    test.skip(!(await fundHasSuspectTe(request)), "库中无 TE>30% 基金（CI 空库），跳过遮蔽用例");
    await page.goto("/screener");
    await page.getByTestId("tab-fund").click();
    // TE < 5（=500%）放行高 TE 行；提交后默认 TE 升序，点表头翻降序 → 首行即最高 TE
    await page.getByLabel("跟踪误差 <（小数，0.05=5%）").fill("5");
    await page.locator("form button[type=\"submit\"]").click();
    await expect(page.getByText(/筛选结果（\d+/)).toBeVisible({ timeout: 15_000 });
    await page.getByRole("columnheader", { name: /跟踪误差/ }).click();
    await expect(page.locator("table tbody tr").first().locator("td").last())
      .toHaveText("—（疑似拆分/异常）");
  });
});

