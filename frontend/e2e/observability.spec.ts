import { execFileSync } from "node:child_process";
import { test, expect } from "@playwright/test";
import { adminLogin } from "./helpers";

// MS-30 F1 观测看板真浏览器验收（Task 15）：admin 登录 → /admin 四区块渲染断言。
//
// 观测数据产生路径裁定：真跑一轮对话产生观测行需 DEEPSEEK key 且慢——本 spec 用 psql 直插
// 真实形态观测行（INSERT 形态沿 Task 7 集成测试 ObservabilityQueryRepositoryImplTest），
// 版本链由后端启动 PromptVersionRegistrar 自然登记（断言非空即可），eval_run 插一行含
// baseline 的历史；对话观测写链已有 Task 9 集成测试背书，真对话不在本 spec 义务内。
//
// 数据清理：观测表 user_id 弱引用无 FK，不在 e2e-cleanup.sh 义务内（V5 设计性规避）——
// 本 spec 自行 INSERT/DELETE 对称清理，全部插入行带固定标记（conversation_id / asset_key /
// question_bank_hash），播种前先按标记 DELETE 实现崩溃残留自愈；eval 恒一基准（部分唯一
// 索引）占用先清后插，测试结束恢复原基准行。
//
// 门控：依赖种子管理员（ADMIN_USERNAME/ADMIN_PASSWORD，沿 admin.spec.ts 同款）与 psql
// 可达后端所连库（连接参数沿 e2e-cleanup.sh 约定）；缺一则整组跳过（CI runner 无 psql 时
// 自动让路，本地验收为主战场）。

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

// psql/DB 可用性探测（模块加载即探测，供 describe 级 skip 判定）
const dbAvailable = (() => {
  try {
    sql("SELECT 1 FROM tool_invocation_obs LIMIT 1");
    return true;
  } catch {
    return false;
  }
})();

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

// —— 插入行固定标记（清理与自愈的定位键）——
const OBS_CONV = "e2e-obs-conv"; // tool_invocation_obs / turn_observation 的 conversation_id
const RUBRIC_KEY = "rubric.e2e-obs-4e2e"; // prompt_asset_version 补注目标的 asset_key
// eval_run 无业务标记列，借 question_bank_hash（64 字符，UI 不展示）作定位键
const EVAL_HASH = "e2e" + "0".repeat(61);

/** 原基准行 id（播种时一次性捕获，清理时恢复；null = 原本无基准）。 */
let savedBaselineId: number | null = null;
/** 本轮插入的 eval_run 主键（断言 #id 行用）。 */
let evalRunId = 0;

/** 播种：清残留 → 捕获/让位恒一基准 → 插观测行（真实形态，Task 7 集成测试同款列集）。 */
function seed() {
  // 崩溃残留自愈：先按标记清上一轮（含半途而废的运行）
  sql(`DELETE FROM eval_run WHERE question_bank_hash = '${EVAL_HASH}'`);
  sql(`DELETE FROM prompt_asset_version WHERE asset_key = '${RUBRIC_KEY}'`);
  sql(`DELETE FROM tool_invocation_obs WHERE conversation_id = '${OBS_CONV}'`);
  sql(`DELETE FROM turn_observation WHERE conversation_id = '${OBS_CONV}'`);

  // 恒一基准让位：捕获现有基准行（排除本标记残留），清位后由插入行顶上
  const existing = sql(
    `SELECT id FROM eval_run WHERE baseline AND question_bank_hash IS DISTINCT FROM '${EVAL_HASH}' LIMIT 1`,
  );
  if (existing) savedBaselineId = Number(existing);
  sql(`UPDATE eval_run SET baseline = false WHERE baseline`);

  // ① 工具调用观测 4 行：成功×3（含 MCP 徽标行 + 数据截止行）+ 失败×1，时延错开供聚合。
  // called_at 取秒级偏移（近 now）：trace 表按 called_at 倒序取第 0 页，常驻库会累积并行
  // e2e（chat 等真对话）写入的真实观测行——分钟级偏移会被历史行压出第 0 页（审查修复实测踩坑）
  sql(`INSERT INTO tool_invocation_obs (user_id, conversation_id, message_id, tool_name,
        args, result_text, spec_count, as_of, as_of_kind, mcp, failed, duration_ms, called_at)
      VALUES (NULL, '${OBS_CONV}', 'msg-1', 'e2e_obs_quote',
        '{"code":"600519"}'::jsonb, 'E2E观测：贵州茅台快照', 2, '2026-10-09', 'DELAYED',
        false, false, 180, now() - interval '50 seconds')`);
  sql(`INSERT INTO tool_invocation_obs (user_id, conversation_id, message_id, tool_name,
        args, result_text, spec_count, mcp, failed, duration_ms, called_at)
      VALUES (NULL, '${OBS_CONV}', 'msg-2', 'e2e_obs_kline',
        '{"code":"600519","period":"daily"}'::jsonb, 'E2E观测：日K 120 根', 1,
        false, false, 320, now() - interval '40 seconds')`);
  sql(`INSERT INTO tool_invocation_obs (user_id, conversation_id, message_id, tool_name,
        args, result_text, spec_count, mcp, failed, duration_ms, called_at)
      VALUES (NULL, '${OBS_CONV}', 'msg-3', 'e2e_obs_mcp_tushare',
        '{"ts_code":"600519.SH"}'::jsonb, 'E2E观测：tushare 财务指标', 1,
        true, false, 900, now() - interval '30 seconds')`);
  sql(`INSERT INTO tool_invocation_obs (user_id, conversation_id, message_id, tool_name,
        args, result_text, spec_count, mcp, failed, duration_ms, called_at)
      VALUES (NULL, '${OBS_CONV}', 'msg-4', 'e2e_obs_error',
        '{"code":"000001"}'::jsonb, 'E2E观测：行情源超时', 0,
        false, true, 45, now() - interval '20 seconds')`);

  // ② 轮观测 3 行：token/时延齐备（成本按日折线 + 轮时延 p50/p95 的数据源）
  for (const [prompt, completion, duration, minutesAgo] of [
    [1200, 300, 1200, 55],
    [1500, 450, 2400, 35],
    [900, 200, 4800, 15],
  ] as const) {
    sql(`INSERT INTO turn_observation (user_id, conversation_id, message_id, prompt_tokens,
          completion_tokens, total_tokens, duration_ms, tool_count, failed, created_at)
        VALUES (NULL, '${OBS_CONV}', 'msg-t', ${prompt}, ${completion},
          ${prompt + completion}, ${duration}, 1, false, now() - interval '${minutesAgo} minutes')`);
  }

  // ③ eval 运行历史 1 行：COMPLETED + NONE（可作基准资格），baseline=true 顶位。
  // psql -tAc 对 INSERT ... RETURNING 先印值再印命令标签（如 "5\nINSERT 0 1"），取纯数字行
  const insertEvalOut = sql(`INSERT INTO eval_run (triggered_by, status, started_at, finished_at, total_pass,
        total_fail, total_error, by_category, prompt_versions, question_bank_hash,
        alert_status, baseline, baseline_candidate, verdict_reasons, duration_ms, report_path)
      VALUES ('MANUAL', 'COMPLETED', now() - interval '3 minutes', now() - interval '1 minute',
        28, 2, 0,
        '{"行情事实":[10,1,0],"指标计算":[10,1,0],"幻觉诱导":[8,0,0]}'::jsonb,
        '{"system.invest":1}'::jsonb, '${EVAL_HASH}',
        'NONE', true, false, '[]'::jsonb, 120000, NULL)
      RETURNING id`);
  evalRunId = Number(insertEvalOut.split("\n").find((line) => /^\d+$/.test(line.trim())));
  if (!Number.isInteger(evalRunId) || evalRunId <= 0) {
    throw new Error(`eval_run 播种未取回主键：${JSON.stringify(insertEvalOut)}`);
  }

  // ④ 补注目标：EVAL_RUBRIC 版本行 v1（note=NULL → 未注记徽标，PUT 真链路的标的）
  sql(`INSERT INTO prompt_asset_version (asset_type, asset_key, version, content_hash, note, registered_at)
      VALUES ('EVAL_RUBRIC', '${RUBRIC_KEY}', 1, '${"e2e0".repeat(16)}', NULL, now())`);
}

/** 清理：按标记删插入行 + 恢复原基准行（与 seed 对称）。 */
function cleanup() {
  sql(`DELETE FROM eval_run WHERE question_bank_hash = '${EVAL_HASH}'`);
  if (savedBaselineId != null) {
    sql(`UPDATE eval_run SET baseline = true WHERE id = ${savedBaselineId}`);
  }
  sql(`DELETE FROM prompt_asset_version WHERE asset_key = '${RUBRIC_KEY}'`);
  sql(`DELETE FROM tool_invocation_obs WHERE conversation_id = '${OBS_CONV}'`);
  sql(`DELETE FROM turn_observation WHERE conversation_id = '${OBS_CONV}'`);
}

test.describe("观测看板四区块（MS-30 F1）", () => {
  test.skip(!dbAvailable, "psql 不可用或观测表不存在（后端未起/V5 未迁移），跳过观测看板 e2e");
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过观测看板 e2e");

  test.beforeEach(() => {
    seed();
  });

  test.afterAll(() => {
    cleanup();
  });

  test("SQL 直插观测数据 → 四区块渲染 + 补注真链路", async ({ page }) => {
    await adminLogin(page);
    await page.goto("/admin");

    const section = page.locator('section[aria-label="可观测性与评测"]');
    await expect(section).toBeVisible({ timeout: 15_000 });

    // ———— ① 工具调用明细：精确筛选真链路 + 行级内容断言 ————
    // 行级断言一律走「工具名（精确）」筛选（后端 tool_name 等值收窄到种子行、总数=1）：
    // 无筛选视图只展示 called_at 倒序第 0 页（20 行），常驻库累积的并行 e2e 真实观测行
    // 会把种子行挤出首页（审查修复实测踩坑），筛选视图对累积免疫、断言确定性。
    const trace = section.locator('section[aria-label="工具调用明细"]');
    await expect(trace.locator("tbody tr").first()).toBeVisible({ timeout: 15_000 });
    await expect(trace.getByTestId("trace-empty")).toHaveCount(0);

    async function filterByTool(tool: string) {
      await trace.getByLabel("工具名（精确）").fill(tool);
      await trace.getByRole("button", { name: "查询" }).click();
      await expect(trace.getByText(/共 1 条/)).toBeVisible();
    }

    // MCP 徽标（mcp=true 行）
    await filterByTool("e2e_obs_mcp_tushare");
    await expect(trace.locator("tbody tr").getByText("MCP", { exact: true })).toBeVisible();

    // 成功态 + 数据截止列：种子行 as_of='2026-10-09' 渲染（其余行 as_of 为 NULL 显示「—」）
    await filterByTool("e2e_obs_quote");
    await expect(trace.locator("tbody tr").getByText("成功", { exact: true })).toBeVisible();
    await expect(trace.locator("tbody tr").getByText("2026-10-09", { exact: true })).toBeVisible();

    // 失败态
    await filterByTool("e2e_obs_error");
    await expect(trace.locator("tbody tr").getByText("失败", { exact: true })).toBeVisible();

    // ———— ② 成本看板：按日折线 + 按工具条形 ECharts canvas 真渲染（非仅容器 div）————
    const cost = section.locator('section[aria-label="成本看板"]');
    await expect(cost.getByTestId("cost-empty")).toHaveCount(0);
    await expect(cost.getByTestId("cost-tokens-line").locator("canvas")).toBeVisible({ timeout: 15_000 });
    await expect(cost.getByTestId("cost-tool-bar").locator("canvas")).toBeVisible();

    // ———— ③ 时延看板：p50/p95 两卡片数值各自非空 + 双图 canvas ————
    const latency = section.locator('section[aria-label="时延看板"]');
    await expect(latency.getByTestId("latency-empty")).toHaveCount(0);
    // p50/p95 分别断言（审查 I2：.first() 只护 DOM 序在前的 p50，p95 单独回归——如 zod 漏
    // p95Ms 渲染「—」——须各自拦截）。卡片形态为 <p>轮时延 p50</p><p>1200 ms</p>（LatencyCharts.tsx
    // 卡片区），锚定标题取紧邻兄弟 <p> 即数值节点，toHaveText 锚定全串。
    const p50Value = latency
      .getByText("轮时延 p50", { exact: true })
      .locator("xpath=following-sibling::p[1]");
    const p95Value = latency
      .getByText("轮时延 p95", { exact: true })
      .locator("xpath=following-sibling::p[1]");
    await expect(p50Value).toHaveText(/^\d+ ms$/, { timeout: 15_000 });
    await expect(p95Value).toHaveText(/^\d+ ms$/);
    await expect(latency.getByTestId("latency-turn-line").locator("canvas")).toBeVisible();
    await expect(latency.getByTestId("latency-tool-bar").locator("canvas")).toBeVisible();

    // ———— ④a 版本链：启动登记自然产生的 SYSTEM_PROMPT 条目 + 版本行 + 当前徽标 ————
    const assets = section.locator('div[aria-label="提示词版本链"]');
    const systemPrompt = assets.getByTestId("prompt-asset-system.invest");
    await expect(systemPrompt).toBeVisible({ timeout: 15_000 });
    await expect(systemPrompt.getByText("系统提示词")).toBeVisible();
    await expect(systemPrompt.locator("li").first()).toContainText(/v\d+/); // 版本行在场
    await expect(systemPrompt.getByText("当前", { exact: true })).toBeVisible();

    // 补注真链路（PUT /api/admin/prompt-assets/{id}/note）：未注记徽标 → 补注 → 徽标消失
    const rubric = assets.getByTestId(`prompt-asset-${RUBRIC_KEY}`);
    await expect(rubric).toBeVisible({ timeout: 15_000 });
    const rubricRow = rubric.locator("li").first();
    await expect(rubricRow.getByText("未注记")).toBeVisible();
    await rubricRow.getByRole("button", { name: "补注" }).click();
    await rubricRow.getByLabel("版本说明").fill("e2e 补注：观测看板验收");
    await rubricRow.getByRole("button", { name: "保存" }).click();
    await expect(rubricRow.getByText("未注记")).toHaveCount(0, { timeout: 15_000 });
    await expect(rubricRow.getByText("e2e 补注：观测看板验收")).toBeVisible();

    // ———— ④b eval 运行历史：插入行渲染（手动/完成/时长）+ baseline 星标 ————
    const runs = section.locator('div[aria-label="评测运行历史"]');
    const runRow = runs.locator(`tbody tr:has(td:text-is("#${evalRunId}"))`);
    await expect(runRow).toBeVisible({ timeout: 15_000 });
    await expect(runRow.getByText("手动")).toBeVisible();
    await expect(runRow.getByText("完成", { exact: true })).toBeVisible();
    await expect(runRow.getByText("2 分钟")).toBeVisible();
    // baseline=true：星标实心 + aria-label 为「取消基准」（区别于 ☆/「置为基准」）
    const baselineBtn = runRow.getByRole("button", { name: `取消基准（运行 ${evalRunId}）` });
    await expect(baselineBtn).toBeVisible();
    await expect(baselineBtn).toContainText("★");
  });
});
