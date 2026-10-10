import { execFileSync } from "node:child_process";
import { mkdirSync, rmSync, rmdirSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { expect, test } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// MS-31 E1：记忆与画像真浏览器验收（Task brief 场景全列 + F4 评审遗留 Escape 承接）。
//
// 造数路径（B0② 探针公式）：e2e 后端以 cwd=<repo>/backend 跑 bootRun，workspace 配置相对路径
// `.agentscope/workspace` → 记忆文件绝对路径 <repo>/backend/.agentscope/workspace/<userId>/
// {MEMORY.md, memory/yyyy-MM-dd.md}。本 spec 在测试进程里用 node fs 直接种文件（userId 注册后
// 经 psql 查 app_user.id，psql 连接参数/探测模式沿 observability.spec.ts 先例），零 LLM 依赖。
//
// 自愈与对称清理：只操作本 spec 自己注册用户的 workspace 目录（绝不碰 .agentscope 既有用户
// 目录）；播种前先删同路径残留（上一轮崩溃自愈），afterAll 删种下的文件 + 空目录回收 +
// user_profile_config 行（app_user 行由 global-teardown 的 e2e-cleanup.sh 统一清理）。
//
// 归一化两层删解耦：被删策展行与日账本 bullet 行文本互不包含、账本行体均 ≥12 字符但与被删
// 行无子串关系，断言不耦合 deleteCuratedLine 的跨账本清除行为（该行为由后端测试背书）。
//
// 门控分两组：管理用例（fs 造数 + userId 查询 + DB 清理）依赖 psql 可达后端所连库
// （user_profile_config 存在 = 后端已起且 V7 已迁移）+ 种子管理员，缺一则整组跳过（CI runner
// 无 psql 时自动让路，本地验收为主战场）；交互兜底用例（P3③ K 线公开行情端点零依赖、
// F4 Escape 仅在行业映射缺失时才需 psql 补种）尽量在 CI 也可跑。

const PG = {
  host: process.env.PGHOST ?? "localhost",
  port: process.env.PGPORT ?? "5432",
  user: process.env.POSTGRES_USER ?? "invest",
  password: process.env.POSTGRES_PASSWORD ?? "invest",
  database: process.env.POSTGRES_DB ?? "invest",
};

/** 执行单条 SQL，返回 -tA 纯文本结果（首行）；出错抛异常（ON_ERROR_STOP）。 */
function sql(statement: string): string {
  return execFileSync(
    "psql",
    [
      "-h", PG.host, "-p", PG.port, "-U", PG.user, "-d", PG.database,
      "-v", "ON_ERROR_STOP=1", "-tAc", statement,
    ],
    { env: { ...process.env, PGPASSWORD: PG.password } },
  )
    .toString()
    .trim();
}

// psql/DB 可用性探测（模块加载即探测；user_profile_config 存在 = e2e 后端已起且 V7 已迁移）
const dbAvailable = (() => {
  try {
    sql("SELECT 1 FROM user_profile_config LIMIT 1");
    return true;
  } catch {
    return false;
  }
})();

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

// —— 造数路径公式与播种登记（afterAll 对称清理的定位键）——

const WORKSPACE_ROOT = resolve(process.cwd(), "..", "backend", ".agentscope", "workspace");

/** 本轮注册用户的 id（清 user_profile_config 行）；重试同进程累积，幂等清理。 */
const createdUserIds: number[] = [];
/** 本轮种下的记忆文件绝对路径（先删后写实现崩溃残留自愈）。 */
const seededFiles: { userId: number; path: string }[] = [];

/** 注册后查 userId 并登记（供 afterAll 清理 user_profile_config）。 */
function registerUserId(username: string): number {
  const id = Number(sql(`SELECT id FROM app_user WHERE username = '${username}'`));
  expect(Number.isInteger(id) && id > 0).toBe(true);
  createdUserIds.push(id);
  return id;
}

/** 种记忆文件（幂等：先删同路径残留再写），路径登记供 afterAll 对称删除。 */
function seedMemoryFile(userId: number, rel: string, content: string): void {
  const file = resolve(WORKSPACE_ROOT, String(userId), rel);
  rmSync(file, { force: true }); // 上一轮崩溃残留自愈
  mkdirSync(resolve(file, ".."), { recursive: true });
  writeFileSync(file, content, "utf8");
  seededFiles.push({ userId, path: file });
}

// —— 种子内容（形态沿 WorkspaceMemoryFileAdapterTest 解析用例；策展/账本文本互不关联）——

// 策展层 3 行（含只读标题行）；文件含空行，loadCurated 非空行过滤后恰 3 行
const CURATED = "# MEMORY.md — Curated Long-Term Memory\n\n## 偏好\n- 偏好白酒股\n";
// 日账本 09 日：FLUSH + 外部事件两节（kind 徽标两形态）；外部事件节头日期与文件名异日（真实形态）
const LEDGER_09 =
  "## Memory Flush — 2026-10-09T13:58:45Z\n- 记录本次调研的行业观点备查\n\n" +
  "## 外部事件摘要 — 2026-10-10（09:00 ~ 18:00）\n- 三季报预告密集披露，注意业绩雷\n";
// 日账本 10 日：单节（节删后整文件清空的用例标的）
const LEDGER_10 = "## Memory Flush — 2026-10-10T09:30:00Z\n- 连续三日跟踪板块资金流向变化\n";

// Escape 用例的行业映射标记行：下钻页 stocks 端点要求 shenwan_industry_mapping 有行业码行
// （existsIndustry 门禁），本地空库没有而 CI e2e-seed.sql 已种 801780——仅在缺失时补种固定
// 标记行（stock_code 即标记），afterAll 对称删除；播种前先按标记删实现崩溃残留自愈。
const ESC_MAPPING_STOCK = "e2e-esc-801780";
let seededEscMapping = false;

/** 清理（文件级 afterAll，覆盖两组 describe）：与播种对称——删种下的文件、回收自建空目录
 * （非空即留，不误删他人目录）、清 user_profile_config 行与行业映射标记行。 */
test.afterAll(() => {
  if (!dbAvailable) return;
  for (const { path } of seededFiles) {
    rmSync(path, { force: true });
  }
  for (const uid of new Set(createdUserIds)) {
    for (const dir of [
      resolve(WORKSPACE_ROOT, String(uid), "memory"),
      resolve(WORKSPACE_ROOT, String(uid)),
    ]) {
      try {
        rmdirSync(dir);
      } catch {
        // 目录非空或不存在：留待人工，绝不动非空目录
      }
    }
    sql(`DELETE FROM user_profile_config WHERE user_id = ${uid}`);
  }
  if (seededEscMapping) {
    sql(`DELETE FROM shenwan_industry_mapping WHERE stock_code = '${ESC_MAPPING_STOCK}'`);
    seededEscMapping = false;
  }
});

test.describe("记忆与画像管理（MS-31 E1，fs 造数）", () => {
  test.skip(!dbAvailable, "psql 不可用或 user_profile_config 不存在（后端未起/V7 未迁移），跳过记忆管理 e2e");
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过记忆管理 e2e");

  test("fs 种记忆 → 分栏/kind 徽标/按日倒序 → 行删除 reload 持久 → 节删除", async ({ page }) => {
    const username = uniqueUsername("mem");
    await registerAndApprove(page, username, TEST_PASSWORD);
    const userId = registerUserId(username);
    seedMemoryFile(userId, "MEMORY.md", CURATED);
    seedMemoryFile(userId, "memory/2026-10-09.md", LEDGER_09);
    seedMemoryFile(userId, "memory/2026-10-10.md", LEDGER_10);

    // 顶部导航入口（F1）进入管理页
    await page.getByRole("link", { name: "记忆与画像" }).click();
    await expect(page).toHaveURL(/\/settings\/memory/, { timeout: 15_000 });
    await expect(page.getByTestId("memory-settings")).toBeVisible();

    // ———— 分栏上：策展层 3 行，标题行只读（无行级操作按钮），普通行有删除 ————
    const curated = page.getByTestId("memory-curated-list");
    await expect(curated).toBeVisible({ timeout: 15_000 });
    await expect(curated.getByText("# MEMORY.md — Curated Long-Term Memory", { exact: true })).toBeVisible();
    await expect(curated.getByText("## 偏好", { exact: true })).toBeVisible();
    await expect(curated.getByText("- 偏好白酒股", { exact: true })).toBeVisible();
    await expect(curated.getByTestId("memory-curated-delete-0")).toHaveCount(0);
    await expect(curated.getByTestId("memory-curated-edit-0")).toHaveCount(0);
    await expect(curated.getByTestId("memory-curated-delete-2")).toBeVisible();

    // ———— 分栏下：日账本按日倒序（10 日在前 1 节、09 日在后 2 节）————
    const ledgers = page.getByTestId("memory-ledger-list");
    await expect(ledgers.locator("summary").nth(0)).toContainText("2026-10-10");
    await expect(ledgers.locator("summary").nth(0)).toContainText("（1 节）");
    await expect(ledgers.locator("summary").nth(1)).toContainText("2026-10-09");
    await expect(ledgers.locator("summary").nth(1)).toContainText("（2 节）");

    // kind 徽标：FLUSH→对话记忆 / EXTERNAL_EVENT→事件摘要（首日默认展开；次日展开后断言）
    await expect(
      page.getByTestId("memory-section-2026-10-10-0").getByText("对话记忆", { exact: true }),
    ).toBeVisible();
    const day09 = ledgers.locator("details").filter({
      has: page.locator("summary", { hasText: "2026-10-09" }),
    });
    await day09.locator("summary").click();
    await expect(
      page.getByTestId("memory-section-2026-10-09-0").getByText("对话记忆", { exact: true }),
    ).toBeVisible();
    const external = page.getByTestId("memory-section-2026-10-09-1");
    await expect(external.getByText("事件摘要", { exact: true })).toBeVisible();
    await expect(external.getByText("## 外部事件摘要")).toBeVisible();

    // ———— 行删除：策展 bullet 行删后消失，其余行与日账本节不受牵连（两层删解耦）————
    await curated.getByTestId("memory-curated-delete-2").click();
    await expect(curated.getByText("- 偏好白酒股", { exact: true })).toHaveCount(0, { timeout: 15_000 });
    await expect(curated.getByText("## 偏好", { exact: true })).toBeVisible();
    await expect(external).toBeVisible();

    // reload 持久（服务端真删文件，非乐观 UI）
    await page.reload();
    await expect(curated.getByText("- 偏好白酒股", { exact: true })).toHaveCount(0);
    await expect(curated.getByText("# MEMORY.md — Curated Long-Term Memory", { exact: true })).toBeVisible();

    // ———— 节删除（window.confirm 接受）：唯一节的日账本整文件消失 + reload 持久 ————
    page.once("dialog", (d) => d.accept());
    await page.getByTestId("memory-section-delete-2026-10-10-0").click();
    await expect(ledgers.locator("details")).toHaveCount(1, { timeout: 15_000 });
    await page.reload();
    await expect(ledgers.locator("details")).toHaveCount(1);
    await expect(ledgers.locator("summary").first()).toContainText("2026-10-09");

    // 多节文件删其中一节：该节消失、另一节与文件保留（reload 后 09 日已是首个，默认展开）
    page.once("dialog", (d) => d.accept());
    await page.getByTestId("memory-section-delete-2026-10-09-1").click();
    await expect(page.getByTestId("memory-section-2026-10-09-1")).toHaveCount(0, { timeout: 15_000 });
    await expect(
      page.getByTestId("memory-section-2026-10-09-0").getByText("对话记忆", { exact: true }),
    ).toBeVisible();
    await expect(day09.locator("summary")).toContainText("（1 节）");
  });

  test("画像授权：默认全关 → 开 style/watchlist → 预览可见 → 保存回执 → reload 持久", async ({ page }) => {
    const username = uniqueUsername("pf");
    await registerAndApprove(page, username, TEST_PASSWORD);
    registerUserId(username);

    await page.goto("/settings/memory");
    const form = page.getByTestId("profile-config-form");
    await expect(form).toBeVisible({ timeout: 15_000 });

    // 默认 opt-in 全关
    for (const key of ["style", "industry", "watchlist", "holdings"] as const) {
      await expect(form.getByTestId(`profile-toggle-${key}`)).not.toBeChecked();
    }

    // 折叠预览：持仓段空数据也有真实渲染（ProfileSectionRenderer 直出「所见即所注入」）；
    // 风格段无测评数据源 → 暂不可用（null 段显式降级，不拖垮整页）
    await form.getByTestId("profile-preview-holdings").locator("summary").click();
    await expect(form.getByTestId("profile-preview-holdings")).toContainText("持仓 0 只");
    await form.getByTestId("profile-preview-style").locator("summary").click();
    await expect(form.getByTestId("profile-preview-style").getByText("暂不可用")).toBeVisible();

    // 开两段 → 全量 PUT → 保存回执（waitForResponse 卡 PUT 响应，非乐观按钮态）
    await form.getByTestId("profile-toggle-style").check();
    await form.getByTestId("profile-toggle-watchlist").check();
    const receipt = page.waitForResponse(
      (r) => r.url().endsWith("/api/memory/profile-config") && r.request().method() === "PUT",
    );
    await form.getByTestId("profile-config-save").click();
    expect((await receipt).status()).toBe(200);
    await expect(form.locator('[role="alert"]')).toHaveCount(0);

    // reload 持久：DB 落库回读（保存回执覆盖本地后的下一轮 GET）
    await page.reload();
    await expect(form).toBeVisible({ timeout: 15_000 });
    await expect(form.getByTestId("profile-toggle-style")).toBeChecked();
    await expect(form.getByTestId("profile-toggle-watchlist")).toBeChecked();
    await expect(form.getByTestId("profile-toggle-industry")).not.toBeChecked();
    await expect(form.getByTestId("profile-toggle-holdings")).not.toBeChecked();
  });

  test("空态：无记忆文件用户 → 空态引导可见、无列表", async ({ page }) => {
    const username = uniqueUsername("memz");
    await registerAndApprove(page, username, TEST_PASSWORD);
    registerUserId(username);

    await page.goto("/settings/memory");
    const empty = page.getByTestId("memory-empty-state");
    await expect(empty).toBeVisible({ timeout: 15_000 });
    await expect(empty).toContainText("还没有任何记忆。");
    await expect(page.getByTestId("memory-curated-list")).toHaveCount(0);
    await expect(page.getByTestId("memory-ledger-list")).toHaveCount(0);
    // 画像授权区照常可用（记忆空不影响页面可用性）
    await expect(page.getByTestId("profile-toggle-style")).toBeVisible();
  });
});

test.describe("MS-31 交互兜底（公开端点；Escape 行业映射按需补种）", () => {
  test("K线周期切换失败：route abort → 标签还原 + 错误行可见（P3③）", async ({ page }) => {
    test.setTimeout(120_000); // 真实行情接口，超时放宽（沿 market.spec）

    // 深链选中贵州茅台：初始日K 随选股 Promise.all 加载完成（「走势」区可见即四路齐备）
    await page.goto("/market?code=600519");
    await expect(page.getByText("走势 · 前复权")).toBeVisible({ timeout: 60_000 });

    // 拦后续 K 线请求（abort 网络层失败；行情端点公开，本用例无登录态依赖）
    await page.route("**/api/market/kline/**", (route) => route.abort());
    await page.getByRole("button", { name: "周K" }).click();
    await expect(page.getByText("K线获取失败，已还原为日K")).toBeVisible({ timeout: 15_000 });
    // 标签还原：日K 仍激活（color-up 高亮类）、周K 未激活——消除「标签已翻数据未换」错位态
    await expect(page.getByRole("button", { name: "日K", exact: true })).toHaveClass(/color-up/);
    await expect(page.getByRole("button", { name: "周K", exact: true })).not.toHaveClass(/color-up/);
  });

  // F4 评审遗留承接：useDialogA11y（Escape/初始焦点/焦点返还/Tab 圈定）此前只有 jsdom 单测，
  // 此处真浏览器断言 ResearchNoteDialog 全行为（浮层打开→圈定→Escape 关→焦点返还）。
  // 下钻页浮层不依赖板面数据（成员表空也渲染页头入口），只要求行业码在映射表——CI 已由
  // e2e-seed.sql 种 801780，本地空库探测 stocks 端点 404 时经 psql 补种标记行（见文件头注释）。
  test("F4 Escape：研究结论浮层 Tab 圈定 + 关闭 + 焦点返还（真浏览器）", async ({ page, request }) => {
    test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过浮层可达性用例");

    const stocks = await request.get("/api/industry/801780/stocks");
    if (!stocks.ok()) {
      test.skip(!dbAvailable, "行业映射缺失（下钻页 404）且 psql 不可用，无法补种 801780，跳过浮层可达性用例");
      sql(`DELETE FROM shenwan_industry_mapping WHERE stock_code = '${ESC_MAPPING_STOCK}'`); // 崩溃残留自愈
      const existing = sql("SELECT count(*) FROM shenwan_industry_mapping WHERE industry_code = '801780'");
      if (existing === "0") {
        sql(`INSERT INTO shenwan_industry_mapping (stock_code, stock_name, industry_code, industry_name)
             VALUES ('${ESC_MAPPING_STOCK}', 'E2E浮层银行', '801780', '银行')`);
        seededEscMapping = true;
      }
    }

    await registerAndApprove(page, uniqueUsername("esc"), TEST_PASSWORD);

    // 深链下钻页（银行 801780）：成员表可为空，页头「保存研究结论」入口照常渲染
    await page.goto("/industry/801780");
    const open = page.getByTestId("research-note-open");
    await expect(open).toBeVisible({ timeout: 15_000 });

    await open.click();
    const dialog = page.getByTestId("research-note-dialog");
    await expect(dialog).toBeVisible();
    // 初始焦点入浮层（首个可聚焦元素 = 标题输入）
    await expect(page.getByTestId("research-note-title")).toBeFocused();

    // Tab 圈定：首位 Shift+Tab → 末位（取消按钮）；末位 Tab → 回首位
    await page.keyboard.press("Shift+Tab");
    await expect(dialog.getByRole("button", { name: "取消" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(page.getByTestId("research-note-title")).toBeFocused();

    // Escape 关闭 + 焦点返还触发元素
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
    await expect(open).toBeFocused();
  });
});
