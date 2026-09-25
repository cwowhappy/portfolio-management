import { test, expect, type Page } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// /portfolio 由 RequireAuth 包裹，需已审核用户登录；注册后须由种子管理员审核，
// 故与 auth.spec.ts 一致依赖 ADMIN_USERNAME/ADMIN_PASSWORD。
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/portfolio 持仓组合管理", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过持仓用例");

  test("登录后访问并渲染空态", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("pf"), TEST_PASSWORD);

    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });

    await expect(page.getByRole("heading", { name: "持仓组合" })).toBeVisible();
    await expect(page.getByTestId("position-table").getByText("暂无持仓")).toBeVisible();
    await expect(page.getByRole("button", { name: "买入" })).toBeVisible();
  });

  test("创建账户分组并买入后出现持仓", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("pf"), TEST_PASSWORD);

    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });

    // BuyForm 的分组下拉在挂载时取 groups[0]，新建账户前为空；先创建一个 ACCOUNT 分组，
    // 否则买入时 groupId 为空导致后端「分组不存在」。默认类型即「账户」。
    await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
    await page.getByRole("button", { name: "新建" }).click();
    // 等待分组刷新：分组切换标签出现「主账户」。
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();

    // 买入校验分组现金（issue #45）：先现金转入，余额刷新出现在分组列表行。
    await page.getByLabel("现金账户").selectOption({ label: "主账户" });
    await page.getByLabel("转入转出").selectOption("DEPOSIT");
    await page.getByLabel("金额", { exact: true }).fill("200000");
    await page.getByRole("button", { name: "录入" }).click();
    await expect(page.getByText("现金 200000.00")).toBeVisible({ timeout: 15_000 });

    // BuyForm 的分组下拉不会随 groups 更新而自动选中，显式选中刚建的账户分组。
    await page.getByLabel("分组").selectOption({ label: "主账户" });

    await page.getByLabel("代码").fill("600519");
    await page.getByLabel("名称").fill("贵州茅台");
    await page.getByLabel("价格").fill("1500");
    await page.getByLabel("数量").fill("100");
    await page.getByRole("button", { name: "买入" }).click();

    const table = page.getByTestId("position-table");
    // 全量跑时真实行情接口可能拖慢后端响应（买入后需重新加载总览），给足超时；
    // 本用例禁用重试（重复注册会撞唯一用户名），超时不足会直接红。
    await expect(table.getByText("贵州茅台")).toBeVisible({ timeout: 15_000 });
    await expect(table.getByText("暂无持仓")).toHaveCount(0);
  });

  test("现金不足买入被拒（issue #45）", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("pfc"), TEST_PASSWORD);

    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });
    await page.getByPlaceholder("分组名（如 华泰）").fill("主账户");
    await page.getByRole("button", { name: "新建" }).click();
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "主账户" })).toBeVisible();
    // 仅转入小额，买入远超余额
    await page.getByLabel("现金账户").selectOption({ label: "主账户" });
    await page.getByLabel("转入转出").selectOption("DEPOSIT");
    await page.getByLabel("金额", { exact: true }).fill("1000");
    await page.getByRole("button", { name: "录入" }).click();
    await expect(page.getByText("现金 1000.00")).toBeVisible({ timeout: 15_000 });

    await page.getByLabel("分组").selectOption({ label: "主账户" });
    await page.getByLabel("代码").fill("600519");
    await page.getByLabel("名称").fill("贵州茅台");
    await page.getByLabel("价格").fill("1500");
    await page.getByLabel("数量").fill("100");
    await page.getByRole("button", { name: "买入" }).click();

    // 后端 INSUFFICIENT_CASH → BuyForm 展示可行动错误（含可用/需求数字），持仓不新增
    await expect(page.getByText(/现金不足：可用 1000/)).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("position-table").getByText("贵州茅台")).toHaveCount(0);
  });

  // ---- CSV 批量导入（MS-14 P1）----

  const CSV_HEADER = "日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注\n";

  /** 六行六类型标准文件（与后端 CsvImportIntegrationTest.SIX_ROW_CSV 同源），分组列=组名。 */
  const sixRowCsv = (group: string): string =>
    CSV_HEADER +
    `2024-01-03,DEPOSIT,,,${group},,,200000.00,初始入金\n` +
    `2024-01-05,BUY,600519,贵州茅台,${group},1680.00,100,5.00,首次建仓\n` +
    `2024-06-20,SELL,600519,贵州茅台,${group},1750.50,50,5.00,减仓\n` +
    `2024-07-01,CASH_DIVIDEND,600519,贵州茅台,${group},25.63,,,\n` +
    `2024-07-01,STOCK_DIVIDEND,600519,贵州茅台,${group},0.05,,,\n` +
    `2024-08-10,WITHDRAW,,,${group},,,1000.00,出金\n`;

  /** 打开批量导入对话框 → 上传 CSV 内容 → 点「导入」，返回到结果态。 */
  async function uploadCsv(page: Page, csv: string): Promise<void> {
    await page.getByTestId("import-open").click();
    const dialog = page.getByTestId("import-dialog");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("CSV 文件").setInputFiles({
      name: "import.csv",
      mimeType: "text/csv",
      buffer: Buffer.from(csv, "utf8"),
    });
    // exact：避免「导入中…」被前缀匹配
    await dialog.getByRole("button", { name: "导入", exact: true }).click();
  }

  test("CSV 批量导入：错误行反馈（行号+原因表渲染，持仓不变）", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("pfimp"), TEST_PASSWORD);

    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });

    // 两行分组名故意不存在（L3 分组校验先于代码校验，文案确定，不依赖库内行情/快照数据）
    const badCsv =
      CSV_HEADER +
      "2024-01-03,DEPOSIT,,,不存在的组,,,10000.00,错误行1\n" +
      "2024-01-05,BUY,600519,贵州茅台,也是不存在的组,1680.00,100,5.00,错误行2\n";
    await uploadCsv(page, badCsv);

    // 行错误表：表头为物理行 1，两数据行分别为 2/3，均报「分组不存在」
    const errorTable = page.getByTestId("import-row-errors");
    await expect(errorTable).toBeVisible({ timeout: 15_000 });
    await expect(errorTable.getByRole("cell", { name: "2", exact: true })).toBeVisible();
    await expect(errorTable.getByRole("cell", { name: "3", exact: true })).toBeVisible();
    await expect(errorTable.getByText(/分组不存在「不存在的组」/)).toBeVisible();
    await expect(errorTable.getByText(/分组不存在「也是不存在的组」/)).toBeVisible();

    // all-or-nothing：文件未导入任何行，持仓保持空态
    await expect(page.getByTestId("position-table").getByText("暂无持仓")).toBeVisible();
  });

  test("CSV 批量导入：六行标准文件成功导入并出现持仓", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("pfims"), TEST_PASSWORD);

    await page.getByRole("link", { name: "持仓" }).click();
    await expect(page).toHaveURL(/\/portfolio/, { timeout: 15_000 });

    // 先在页面创建同名账户分组（L3 要求分组已存在）
    await page.getByPlaceholder("分组名（如 华泰）").fill("导入测试组");
    await page.getByRole("button", { name: "新建" }).click();
    await expect(page.getByTestId("group-tabs").getByRole("button", { name: "导入测试组" })).toBeVisible();

    await uploadCsv(page, sixRowCsv("导入测试组"));

    await expect(page.getByTestId("import-success")).toHaveText("成功导入 6 笔", { timeout: 15_000 });

    // 成功后 reload 重拉：持仓列表出现 600519（数量 (100−50)×1.05 = 52.5）
    const table = page.getByTestId("position-table");
    await expect(table.getByText("贵州茅台")).toBeVisible({ timeout: 15_000 });
    await expect(table.getByText("600519")).toBeVisible();
    await expect(table.getByText("52.5", { exact: true })).toBeVisible();
    await expect(table.getByText("暂无持仓")).toHaveCount(0);
  });
});
