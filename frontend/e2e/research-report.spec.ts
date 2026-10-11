import { execFileSync } from "node:child_process";
import { expect, test, type Page, type Route } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// MS-32 E1：研报流真浏览器验收（第⑧区 ReportsPanel → 确认/拒绝双流 + 进度轮询 + 配额拒绝，
// 另承接 F4 报告 Concern #4 的背板关闭真浏览器兜底）。
//
// 造数路径：e2e 用户经 helpers.registerAndApprove（UI 注册→审核→登录——仓内唯一造用户先例，
// 会话 Cookie 由浏览器持有，无需 psql 造用户/session）；研究域行（项目/SUCCEEDED 任务/
// PENDING_CONFIRM 研报/锚点/假设/配额/wiki 锚定基准）经 psql 直插真实形态行（V8 列集，
// sql()/探测降级沿 memory.spec / observability.spec 先例），零 LLM 依赖。
//
// 锚点重查四态的确定性策略（B12 ReportAnchorRechecker 白名单直调）：
// - FRESH/CHANGED：锚点 tool=search_wiki（重查白名单内、本地 DB 读、零网络）——种一条
//   wiki 基准条目钉数值「1741 亿元」；假设 1 snippet 与现值同值 → FRESH（现值一致），
//   假设 2 snippet「9999 亿元」与现值漂移超 changed-relative 容差 → CHANGED（currentValue
//   重查文本为 1741）。注：snippet/正文均带空格「1741 亿元」，NumberExtractor 单位后缀
//   紧邻才归一乘数——空格形态两脚同为裸值 1741，比对自洽；
// - UNVERIFIABLE：锚点 tool=miao_quant（不在重查白名单）→ dispatch null → 无法重验，
//   popover 现值（重查）不可得。
//
// 断言证据优先 DB 落库：confirm 后 psql 复核 research_report.status=CONFIRMED 与
// journal_entry「研报定稿」事件行（title/content 逐字）；reject 后复核 rejected_reason；
// 配额 402 后复核无任务行落库——页面绿之上钉持久化。
//
// 门控双态（沿 memory.spec 口径）：psql 组依赖 psql 可达后端所连库（research_report_task
// 存在 = 后端已起且 V8 已迁移）+ 种子管理员，缺一整组跳过（CI runner 无 psql 时自动让路，
// 本地验收为主战场）；背板关闭用例走 route 注入（SSE 工具调用 + 项目列表 fulfill），
// 不依赖 psql，仅需种子管理员。

const PG = {
  host: process.env.PGHOST ?? "localhost",
  port: process.env.PGPORT ?? "5432",
  user: process.env.POSTGRES_USER ?? "invest",
  password: process.env.POSTGRES_PASSWORD ?? "invest",
  database: process.env.POSTGRES_DB ?? "invest",
};

/** 执行单条 SQL，返回 -tA 纯文本首行（INSERT..RETURNING 时 psql 会在值后追印命令标签
 *  「INSERT 0 1」，须取首行——SELECT 标量同口径）；出错抛异常（ON_ERROR_STOP）。 */
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
    .trim()
    .split("\n")[0] ?? "";
}

// psql/DB 可用性探测（模块加载即探测；research_report_task 存在 = e2e 后端已起且 V8 已迁移）
const dbAvailable = (() => {
  try {
    sql("SELECT 1 FROM research_report_task LIMIT 1");
    return true;
  } catch {
    return false;
  }
})();

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

// —— 种子内容 ——

const REPORT_TITLE = "贵州茅台深度研报（e2e 验收）";
const REPORT_MD = [
  "# 贵州茅台深度研报",
  "",
  "品牌护城河与提价能力构成长期确定性，当前估值处于历史中位以下。",
  "",
  "## 关键假设",
  "",
  "1. 2024 年营收 1741 亿元（重查现值一致）",
  "2. 传闻口径 9999 亿元（重查现值漂移）",
  "3. 经销商约 3000 家（不可重查）",
].join("\n");
const REPORT_LIMITATIONS = "部分口径来自传闻，需以定期报告为准";
// wiki 锚定基准（FRESH/CHANGED 共用一条：锚定与现值同读此条，零网络确定性）
const WIKI_TITLE = "e2e-锚点基准";
const WIKI_CONTENT = "锚点重查基准：贵州茅台 2024 年营收 1741 亿元，重查现值钉此数值。";

/** 注册后查 userId（沿 memory.spec.registerUserId；清理由 global-teardown e2e-cleanup.sh 统一承担）。 */
function registerUserId(username: string): number {
  const id = Number(sql(`SELECT id FROM app_user WHERE username = '${username}'`));
  expect(Number.isInteger(id) && id > 0).toBe(true);
  return id;
}

/** 种研究项目（V2 列集，NEW_ANALYSIS/ACTIVE——详情读模型对裸行自洽：strategy null、产物计数零）。 */
function seedProject(userId: number, title: string): number {
  return Number(
    sql(`INSERT INTO research_project (user_id, stock_code, stock_name, industry_code, title, current_stage, status)
         VALUES (${userId}, '600519', '贵州茅台', 'BK0477', '${title}', 'NEW_ANALYSIS', 'ACTIVE')
         RETURNING id`),
  );
}

/** 种 wiki 锚定基准条目（search_wiki 重查命中源；BOOK_NOTE 无种子副作用）。 */
function seedWikiAnchorBase(userId: number): void {
  sql(`INSERT INTO wiki_entry (user_id, type, title, content)
       VALUES (${userId}, 'BOOK_NOTE', '${WIKI_TITLE}', $md$${WIKI_CONTENT}$md$)`);
}

/**
 * 种「SUCCEEDED 任务 + PENDING_CONFIRM 研报 + 三态锚点/假设」（确认/拒绝双流共用形态）：
 * 锚点 1/2 绑 search_wiki（FRESH/CHANGED 确定性对照），锚点 3 白名单外（UNVERIFIABLE）。
 */
function seedPendingConfirmReport(userId: number, projectId: number): number {
  const taskId = Number(
    sql(`INSERT INTO research_report_task (project_id, user_id, status, stage, token_usage, started_at, finished_at)
         VALUES (${projectId}, ${userId}, 'SUCCEEDED', 'VALIDATING', 12345, now(), now())
         RETURNING id`),
  );
  const reportId = Number(
    sql(`INSERT INTO research_report (project_id, task_id, user_id, status, title, content_md, limitations)
         VALUES (${projectId}, ${taskId}, ${userId}, 'PENDING_CONFIRM', '${REPORT_TITLE}',
                 $md$${REPORT_MD}$md$, '${REPORT_LIMITATIONS}')
         RETURNING id`),
  );
  const args = `'{"keyword": "${WIKI_TITLE}"}'::jsonb`;
  const freshAnchor = Number(
    sql(`INSERT INTO research_report_anchor (report_id, snippet, occ, state, tool_name, args, raw)
         VALUES (${reportId}, '1741 亿元', 1, 'SOURCED', 'search_wiki', ${args}, '1741 亿元')
         RETURNING id`),
  );
  const changedAnchor = Number(
    sql(`INSERT INTO research_report_anchor (report_id, snippet, occ, state, tool_name, args, raw)
         VALUES (${reportId}, '9999 亿元', 1, 'SOURCED', 'search_wiki', ${args}, '9999 亿元')
         RETURNING id`),
  );
  const unverifiableAnchor = Number(
    sql(`INSERT INTO research_report_anchor (report_id, snippet, occ, state, tool_name, args)
         VALUES (${reportId}, '3000 家', 1, 'UNVERIFIED', 'miao_quant', '{"symbol": "600519.SH"}'::jsonb)
         RETURNING id`),
  );
  sql(`INSERT INTO research_report_assumption (report_id, seq, statement, anchor_id) VALUES
       (${reportId}, 1, '2024 年营收 1741 亿元（现值一致对照）', ${freshAnchor}),
       (${reportId}, 2, '传闻口径 9999 亿元（现值漂移对照）', ${changedAnchor}),
       (${reportId}, 3, '经销商约 3000 家（不可重查对照）', ${unverifiableAnchor})`);
  return reportId;
}

/** 业务时区当日（后端 ReportTokenQuotaService 日界 Asia/Shanghai；en-CA 输出 YYYY-MM-DD）。 */
const shanghaiToday = (): string =>
  new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Shanghai" }).format(new Date());

test.describe("研报确认/拒绝/轮询/配额（MS-32 E1，psql 造数）", () => {
  test.skip(!dbAvailable, "psql 不可用或 research_report_task 不存在（后端未起/V8 未迁移），跳过研报流 e2e");
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过研报流 e2e");

  test("确认流：草稿 markdown/四态徽标浮层 → 确认定稿 → journal「研报定稿」事件（DB 复核）", async ({
    page,
  }) => {
    test.setTimeout(120_000);
    const username = uniqueUsername("rrc");
    await registerAndApprove(page, username, TEST_PASSWORD);
    const userId = registerUserId(username);
    seedWikiAnchorBase(userId);
    const projectId = seedProject(userId, "贵州茅台核心资产研究（确认流）");
    const reportId = seedPendingConfirmReport(userId, projectId);

    // 详情页第⑧区（F3 挂载）：列表行 + 最新 PENDING_CONFIRM 默认展开
    await page.goto(`/research/${projectId}`);
    const panel = page.getByTestId("reports-panel");
    await expect(panel).toBeVisible({ timeout: 15_000 });
    const item = page.getByTestId(`report-item-${reportId}`);
    await expect(item).toBeVisible({ timeout: 15_000 });
    await expect(item.getByText("待确认", { exact: true })).toBeVisible();
    await expect(item.getByRole("button", { name: `收起研报「${REPORT_TITLE}」` })).toHaveAttribute(
      "aria-expanded",
      "true",
    );

    // ———— 草稿 markdown 渲染（含「## 关键假设」章节）+ 局限注记 ————
    const detail = page.getByTestId(`report-detail-${reportId}`);
    await expect(detail).toBeVisible({ timeout: 15_000 });
    const content = page.getByTestId("report-content");
    await expect(content.getByRole("heading", { name: "贵州茅台深度研报" })).toBeVisible();
    await expect(content.getByRole("heading", { name: "关键假设" })).toBeVisible();
    await expect(content.getByText("品牌护城河与提价能力构成长期确定性")).toBeVisible();
    await expect(page.getByTestId("report-limitations")).toContainText(
      `局限注记：${REPORT_LIMITATIONS}`,
    );

    // ———— 关键假设：FRESH/CHANGED/UNVERIFIABLE 三态徽标（锚点行 data-anchor-state 定位） ————
    const assumptions = page.getByTestId("report-assumptions");
    await expect(assumptions).toBeVisible();
    const freshRow = page.getByTestId("report-assumption-1");
    await expect(freshRow).toHaveAttribute("data-anchor-state", "FRESH");
    await expect(freshRow.getByTestId("report-anchor-badge")).toHaveText("现值一致");
    await expect(freshRow.getByText("2024 年营收 1741 亿元（现值一致对照）")).toBeVisible();
    const changedRow = page.getByTestId("report-assumption-2");
    await expect(changedRow).toHaveAttribute("data-anchor-state", "CHANGED");
    await expect(changedRow.getByTestId("report-anchor-badge")).toHaveText("现值有变");
    const unverifiableRow = page.getByTestId("report-assumption-3");
    await expect(unverifiableRow).toHaveAttribute("data-anchor-state", "UNVERIFIABLE");
    await expect(unverifiableRow.getByTestId("report-anchor-badge")).toHaveText("无法重验");

    // CHANGED 徽标浮层：漂移措辞 + 工具/参数锚定真相 + 现值重查对照（asOf null → 无数据时点行）
    await changedRow.getByTestId("report-anchor-badge").click();
    const changedPop = changedRow.getByTestId("report-anchor-popover");
    await expect(changedPop).toHaveCSS("opacity", "1");
    await expect(changedPop).toContainText("锚定数值现值已漂移");
    await expect(changedPop).toContainText("search_wiki");
    await expect(changedPop).toContainText(`keyword：${WIKI_TITLE}`);
    await expect(changedPop).toContainText("现值（重查）：1741");
    await expect(changedPop).not.toContainText("数据时点：");
    // 关闭再验下一行（开着的浮层 pointer-events-auto 垂叠在下一假设行上会拦截点击；
    // Escape 收 open 态后移开鼠标，释放 group-hover 通道——沿 trust-provenance 先例）
    await changedRow.getByTestId("report-anchor-badge").press("Escape");
    await page.mouse.move(0, 0);
    await expect(changedPop).toHaveCSS("opacity", "0");
    // UNVERIFIABLE 浮层：白名单外工具 → 现值不可得
    await unverifiableRow.getByTestId("report-anchor-badge").click();
    const unverifiablePop = unverifiableRow.getByTestId("report-anchor-popover");
    await expect(unverifiablePop).toHaveCSS("opacity", "1");
    await expect(unverifiablePop).toContainText("现值（重查）：不可得");

    // ———— 确认卡在场 → 确认定稿（waitForResponse 钉 confirm API）→ 终态即时刷新 ————
    const confirmCard = page.getByTestId("report-confirm-card");
    await expect(confirmCard.getByRole("button", { name: "确认定稿" })).toBeVisible();
    const confirmed = page.waitForResponse(
      (r) => r.request().method() === "POST" && /\/api\/research\/reports\/\d+\/confirm$/.test(r.url()),
    );
    await confirmCard.getByRole("button", { name: "确认定稿" }).click();
    expect((await confirmed).status()).toBe(200);
    await expect(confirmCard.getByText("已定稿", { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(confirmCard.getByRole("button", { name: "确认定稿" })).toHaveCount(0);
    await expect(confirmCard.getByRole("button", { name: "拒绝", exact: true })).toHaveCount(0);
    // 列表行 chip：展开的详情（含确认卡终态 chip）嵌套在 item li 内，取 DOM 首个（头行 chip）
    await expect(item.getByText("已定稿", { exact: true }).first()).toBeVisible({ timeout: 15_000 });

    // ———— DB 落库复核（证明力高于页面绿）：状态机迁移 + journal 研报定稿事件行 ————
    expect(sql(`SELECT status FROM research_report WHERE id = ${reportId}`)).toBe("CONFIRMED");
    expect(Number(sql(`SELECT count(*) FROM journal_entry WHERE user_id = ${userId} AND title = '研报定稿'`))).toBe(1);
    expect(
      sql(`SELECT content FROM journal_entry WHERE user_id = ${userId} AND title = '研报定稿'`),
    ).toBe(`研报「${REPORT_TITLE}」定稿`);
  });

  test("拒绝流：理由必填拦截 → 提交拒绝 → REJECTED 态 + rejected_reason 落库", async ({ page }) => {
    test.setTimeout(120_000);
    const username = uniqueUsername("rrj");
    await registerAndApprove(page, username, TEST_PASSWORD);
    const userId = registerUserId(username);
    seedWikiAnchorBase(userId);
    const projectId = seedProject(userId, "贵州茅台核心资产研究（拒绝流）");
    const reportId = seedPendingConfirmReport(userId, projectId);

    await page.goto(`/research/${projectId}`);
    const item = page.getByTestId(`report-item-${reportId}`);
    const confirmCard = page.getByTestId("report-confirm-card");
    await expect(confirmCard).toBeVisible({ timeout: 15_000 });

    // 展开拒绝理由框：空理由提交禁用 + 必填提示（前端镜像后端 REJECT reason 必填）
    await confirmCard.getByRole("button", { name: "拒绝", exact: true }).click();
    const reasonBox = page.getByLabel("拒绝理由");
    await expect(reasonBox).toBeVisible();
    const submit = confirmCard.getByRole("button", { name: "提交（拒绝）" });
    await expect(submit).toBeDisabled();
    await expect(confirmCard.getByText("拒绝研报须填写理由")).toBeVisible();

    // 填理由 → 可提交 → REJECT 落库（响应体回读 + DB 复核 rejected_reason）
    const reason = "锚点重查现值漂移超容差，结论不成立，拒绝后重新生成";
    await reasonBox.fill(reason);
    await expect(submit).toBeEnabled();
    const rejected = page.waitForResponse(
      (r) => r.request().method() === "POST" && /\/api\/research\/reports\/\d+\/confirm$/.test(r.url()),
    );
    await submit.click();
    const response = await rejected;
    expect(response.status()).toBe(200);
    expect(((await response.json()) as { status: string }).status).toBe("REJECTED");
    await expect(confirmCard.getByText("已拒绝", { exact: true })).toBeVisible({ timeout: 15_000 });
    // 列表行 chip：展开详情嵌套在 item li 内，取 DOM 首个（头行 chip）
    await expect(item.getByText("已拒绝", { exact: true }).first()).toBeVisible({ timeout: 15_000 });

    expect(sql(`SELECT status FROM research_report WHERE id = ${reportId}`)).toBe("REJECTED");
    expect(sql(`SELECT rejected_reason FROM research_report WHERE id = ${reportId}`)).toBe(reason);
    // 拒绝不写 journal 研报定稿事件（B 裁定：拒绝可重生成=发新任务，不留事件）
    expect(
      Number(sql(`SELECT count(*) FROM journal_entry WHERE user_id = ${userId} AND title = '研报定稿'`)),
    ).toBe(0);
  });

  test("进度轮询：RUNNING 阶段文案 → latest 轮询发出 → DB 升级 SUCCEEDED → 停轮询+收割展开", async ({
    page,
  }) => {
    test.setTimeout(120_000);
    const username = uniqueUsername("rrp");
    await registerAndApprove(page, username, TEST_PASSWORD);
    const userId = registerUserId(username);
    const projectId = seedProject(userId, "贵州茅台核心资产研究（轮询）");
    const taskId = Number(
      sql(`INSERT INTO research_report_task (project_id, user_id, status, stage, token_usage, started_at)
           VALUES (${projectId}, ${userId}, 'RUNNING', 'COLLECTING', 8642, now())
           RETURNING id`),
    );

    // latest 轮询请求计数（挂载即拉 1 次 + 5s 间隔轮询）
    let latestCalls = 0;
    page.on("request", (r) => {
      if (/\/api\/research\/projects\/\d+\/report-tasks\/latest/.test(r.url())) latestCalls += 1;
    });

    await page.goto(`/research/${projectId}`);
    // RUNNING 阶段文案 + token 用量 + 活跃任务期禁发 + 空列表引导
    await expect(page.getByTestId("report-task-stage")).toHaveText("数据收集中", { timeout: 15_000 });
    await expect(page.getByTestId("report-task-usage")).toHaveText("已耗 token 8642");
    await expect(page.getByTestId("report-generate-button")).toBeDisabled();
    await expect(page.getByTestId("report-generate-button")).toHaveText("生成中…");
    await expect(page.getByTestId("reports-empty")).toBeVisible();

    // 轮询循环在跑：挂载 1 次之外，5s 间隔再拉——8s 窗口内 ≥2 次
    await expect
      .poll(() => latestCalls, { timeout: 8_000 })
      .toBeGreaterThanOrEqual(2);

    // DB 升级：产物研报落库 + 任务行迁移 SUCCEEDED（真实形态：产物随成功落库）→ 下一跳收割
    const reportId = Number(
      sql(`INSERT INTO research_report (project_id, task_id, user_id, status, title, content_md)
           VALUES (${projectId}, ${taskId}, ${userId}, 'PENDING_CONFIRM', '轮询收割研报', '# 轮询收割研报')
           RETURNING id`),
    );
    sql(`UPDATE research_report_task SET status = 'SUCCEEDED', stage = 'VALIDATING', finished_at = now()
         WHERE id = ${taskId}`);

    // 终态展示 + 收割（RUNNING→SUCCEEDED 会话内收割：刷新列表并自动展开新研报）
    await expect(page.getByTestId("report-task-progress")).toContainText("研报已生成", { timeout: 15_000 });
    await expect(page.getByTestId(`report-detail-${reportId}`)).toBeVisible({ timeout: 15_000 });

    // 停轮询：终态后 6.5s（> 5s 间隔）无新 latest 请求
    const settled = latestCalls;
    await page.waitForTimeout(6_500);
    expect(latestCalls).toBe(settled);
  });

  test("配额拒绝：当日算力用尽 → 发起 402 → 行内固定文案，无任务行落库", async ({ page }) => {
    test.setTimeout(120_000);
    const username = uniqueUsername("rrq");
    await registerAndApprove(page, username, TEST_PASSWORD);
    const userId = registerUserId(username);
    const projectId = seedProject(userId, "贵州茅台核心资产研究（配额）");
    // 当日配额行打满（application.yml daily-token-budget=500000；assertCanStart used>=budget → 402）
    sql(`INSERT INTO report_token_quota (user_id, quota_date, tokens_used)
         VALUES (${userId}, '${shanghaiToday()}', 500000)`);

    await page.goto(`/research/${projectId}`);
    await expect(page.getByTestId("reports-panel")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("reports-empty")).toBeVisible();

    // 发起（window.confirm 二次确认接受）→ 402 → 固定行内文案（402/409 映射，F2 裁定）
    page.once("dialog", (d) => d.accept());
    const started = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" && /\/api\/research\/projects\/\d+\/report-tasks$/.test(r.url()),
    );
    await page.getByTestId("report-generate-button").click();
    expect((await started).status()).toBe(402);
    await expect(page.getByTestId("report-start-error")).toHaveText("今日研报算力已用完，明日恢复");

    // DB 复核：402 在任务直插前拦截，无任务行产生
    expect(
      Number(sql(`SELECT count(*) FROM research_report_task WHERE project_id = ${projectId}`)),
    ).toBe(0);
  });
});

// ———— F4 审查建议承接：DraftCard 背板点击关闭（jsdom 覆盖盲区，真浏览器兜底） ————

/** AG-UI SSE 帧（@ag-ui/encoder EventEncoder.encodeSSE 同格式，沿 trust-provenance.spec 先例）。 */
type AguiEvent = Record<string, unknown>;

function sseBody(events: AguiEvent[]): string {
  return events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("");
}

/** 浏览器侧拦截 agent run：回脚本化 SSE（research_draft 工具调用 + 围栏结果），零 LLM 依赖。 */
async function stubAgentRun(page: Page, buildEvents: (threadId: string) => AguiEvent[]): Promise<void> {
  await page.route("**/api/copilotkit/agent/*/run", async (route: Route) => {
    let threadId = "e2e-stub-thread";
    try {
      const body = route.request().postDataJSON() as { threadId?: string } | null;
      if (body && typeof body.threadId === "string" && body.threadId) threadId = body.threadId;
    } catch {
      // 非 JSON 体：以缺省 threadId 兜底
    }
    await route.fulfill({
      status: 200,
      headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" },
      body: sseBody(buildEvents(threadId)),
    });
  });
}

/** 项目列表 fulfill 形态（ProjectView，fixture 取 researchSchemas 契约字段全集）。 */
const PICKER_PROJECT = {
  id: 7,
  stockCode: "600519",
  stockName: "贵州茅台",
  industryCode: "BK0477",
  title: "贵州茅台核心资产研究",
  currentStage: "NEW_ANALYSIS",
  status: "ACTIVE",
  intelligenceAlertEnabled: true,
  createdAt: "2026-10-11T08:00:00Z",
  updatedAt: "2026-10-11T08:00:00Z",
};

test.describe("对话草稿卡背板关闭（MS-32 F4 补真浏览器，route 注入）", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过草稿卡 e2e");

  test("策略草稿卡 → 打开项目选择浮层 → 背板点击关闭 + 焦点返还", async ({ page }) => {
    test.setTimeout(90_000);

    // 项目列表 route 注入（picker 只读展示，不落库——本用例只验背板关闭，无需 psql 种真项目）
    await page.route(
      (url) => url.pathname === "/api/research/projects" && url.searchParams.get("status") === "ACTIVE",
      async (route) => {
        await route.fulfill({
          status: 200,
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify([PICKER_PROJECT]),
        });
      },
    );

    // SSE 注入 research_draft 工具调用：TOOL_CALL_* + 围栏结果 → DraftCard（STRATEGY 变体）
    const draftJson = JSON.stringify({
      specVersion: 1,
      stage: "STRATEGY",
      thesis: "品牌护城河与提价能力带来长期确定性",
      valuationLow: 1500,
      valuationHigh: 1800,
      positionPlan: "首仓 20%，估值下限附近加至 40%",
      buyConditions: "PE 回落至历史 30% 分位以下分批买入",
      riskItems: [{ kind: "价格", predicate: "跌破", threshold: 1400, note: "触发复盘" }],
    });
    const toolResult = "已按对话整理策略草稿：\n```research-draft\n" + draftJson + "\n```";
    await stubAgentRun(page, (threadId) => [
      { type: "RUN_STARTED", threadId, runId: "e2e-draft-run" },
      { type: "TEXT_MESSAGE_START", messageId: "e2e-draft-a1", role: "assistant" },
      {
        type: "TOOL_CALL_START",
        toolCallId: "e2e-draft-tc1",
        toolCallName: "research_draft",
        parentMessageId: "e2e-draft-a1",
      },
      { type: "TOOL_CALL_ARGS", toolCallId: "e2e-draft-tc1", delta: '{"stage":"STRATEGY"}' },
      { type: "TOOL_CALL_END", toolCallId: "e2e-draft-tc1" },
      {
        type: "TOOL_CALL_RESULT",
        messageId: "e2e-draft-tr1",
        toolCallId: "e2e-draft-tc1",
        content: toolResult,
      },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "e2e-draft-a1", delta: "策略草稿已生成，可保存到研究项目。" },
      { type: "TEXT_MESSAGE_END", messageId: "e2e-draft-a1" },
      { type: "RUN_FINISHED", threadId, runId: "e2e-draft-run" },
    ]);

    await registerAndApprove(page, uniqueUsername("rrd"), TEST_PASSWORD);
    const input = page.getByPlaceholder(/问行情、看走势、读财报/);
    await input.fill("帮我整理贵州茅台的策略草稿");
    const send = page.getByRole("button", { name: "发送" });
    await expect(send).toBeEnabled({ timeout: 30_000 });
    await send.click();

    // 草稿卡渲染（research_draft 渲染器 → DraftCard STRATEGY 变体）
    await expect(page.getByText("投研草稿 · 策略")).toBeVisible({ timeout: 30_000 });
    const saveBtn = page.getByRole("button", { name: "保存到项目" });
    await expect(saveBtn).toBeVisible();

    // 打开项目选择浮层：列表行渲染（route 注入的 ACTIVE 项目）
    await saveBtn.click();
    const dialog = page.getByTestId("project-picker-dialog");
    await expect(dialog).toBeVisible();
    await expect(page.getByTestId("project-picker-row-7")).toContainText("贵州茅台");

    // 背板点击关闭（jsdom 覆盖盲区）：点浮层背板（内卡片之外）→ 关闭、草稿卡仍在、焦点返还触发元素
    await dialog.click({ position: { x: 8, y: 8 } });
    await expect(dialog).toHaveCount(0);
    await expect(saveBtn).toBeVisible();
    await expect(saveBtn).toBeFocused();
  });
});
