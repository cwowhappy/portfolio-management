import { test, expect } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// 研究项目主链路（MS-25 / D14 范围）：空态 → F05 预填立项（withTemplate）→ 详情页三态进度
// （NEW_ANALYSIS 自动完成角标）→ 策略暂存（含估值区间，刷新验证持久化）→ 定稿 →
// STRATEGY 完成角标翻转 → /journal 时间线并入 RESEARCH_EVENT（T5 联动）。

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

test.describe("/research 研究项目主链路", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过配置用例");

  test("立项→三态进度→策略暂存→定稿→journal 研究事件", async ({ page }) => {
    // 主链路含注册→管理员审核→多页流转，放宽单测 30s 默认时限
    test.setTimeout(120_000);
    await registerAndApprove(page, uniqueUsername("rs"), TEST_PASSWORD);

    // 列表空态引导（F07）：全新用户无项目，展示「发起研究/去筛选器找标的」引导
    await page.goto("/research");
    await expect(page.getByTestId("research-empty")).toBeVisible();

    // F05 预填立项：模拟筛选器/行业中心「发起研究」带 code/name/industry 跳转立项表单
    const params = new URLSearchParams({ code: "600519", name: "贵州茅台", industry: "BK0477" });
    await page.goto(`/research/new?${params.toString()}`);
    await expect(page.getByLabel("标的代码")).toHaveValue("600519");
    await expect(page.getByLabel("标的名称")).toHaveValue("贵州茅台");
    await expect(page.getByLabel("行业代码")).toHaveValue("BK0477");
    // withTemplate 缺省勾选（带入新分析 SOP 模板）
    await expect(page.getByLabel("带入新分析 SOP 模板")).toBeChecked();
    await page.getByLabel("项目标题").fill("贵州茅台核心资产研究");
    await page.getByRole("button", { name: "立项" }).click();

    // 立项成功 → 跳详情页
    await expect(page).toHaveURL(/\/research\/\d+$/, { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "贵州茅台核心资产研究" })).toBeVisible();

    // 三态进度（D22）：NEW_ANALYSIS 立项即齐套产物 → 完成·自动；STRATEGY 未开始；REVIEW 未开始
    const stages = page.getByTestId("stage-progress");
    const newAnalysis = stages.locator("li[data-stage=NEW_ANALYSIS]");
    await expect(newAnalysis.getByText("完成", { exact: true })).toBeVisible();
    await expect(newAnalysis.getByText("自动", { exact: true })).toBeVisible();
    await expect(stages.locator("li[data-stage=STRATEGY]").getByText("未开始", { exact: true })).toBeVisible();
    await expect(stages.locator("li[data-stage=REVIEW]").getByText("未开始", { exact: true })).toBeVisible();

    // 关联记录（F08 反查）：立项 + 模板已带入（withTemplate=true）两条研究事件已并入
    const notes = page.getByTestId("linked-notes");
    await expect(notes.getByText("立项：贵州茅台")).toBeVisible();
    await expect(notes.getByText("模板已带入")).toBeVisible();

    // 策略：建草稿 → 六字段暂存（含估值区间）
    await page.getByRole("button", { name: "创建草稿" }).click();
    await page.getByLabel("投资逻辑").fill("品牌护城河与提价能力带来长期确定性");
    await page.getByLabel("估值下限").fill("1500");
    await page.getByLabel("估值上限").fill("1800");
    await page.getByLabel("仓位计划").fill("首仓 20%，估值下限附近加至 40%");
    await page.getByLabel("买入条件").fill("PE 回落至历史 30% 分位以下分批买入");
    await page.getByLabel("风险提示").fill("消费降级与政策压制风险");
    const draftSaved = page.waitForResponse(
      (r) => r.request().method() === "PUT" && /\/api\/research\/projects\/\d+\/strategy$/.test(r.url()),
    );
    await page.getByRole("button", { name: "暂存草稿" }).click();
    expect((await draftSaved).ok()).toBeTruthy();

    // 刷新验证暂存已持久化：仍是草稿态、字段值来自服务端读模型
    await page.reload();
    await expect(page.getByRole("button", { name: "暂存草稿" })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel("投资逻辑")).toHaveValue("品牌护城河与提价能力带来长期确定性");
    await expect(page.getByLabel("估值下限")).toHaveValue("1500");
    await expect(page.getByLabel("估值上限")).toHaveValue("1800");

    // 定稿（D13 显式动作）：FINALIZED 只读视图 + 估值区间展示
    await page.getByRole("button", { name: "定稿" }).click();
    await expect(page.getByText("已定稿")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("1500 ~ 1800")).toBeVisible();

    // STRATEGY 完成角标翻转：未开始 → 完成·自动（定稿即 1 件产物，S6 唯一计算点推导）
    const strategyStage = stages.locator("li[data-stage=STRATEGY]");
    await expect(strategyStage.getByText("完成", { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(strategyStage.getByText("自动", { exact: true })).toBeVisible();
    // 定稿事件写入关联记录
    await expect(notes.getByText("策略定稿")).toBeVisible({ timeout: 15_000 });

    // 列表页可见新项目（F07）：标题 + 当前阶段角标（立项后仍在「新分析」阶段）
    await page.goto("/research");
    const list = page.getByTestId("project-list");
    await expect(list.getByText("贵州茅台核心资产研究")).toBeVisible();
    await expect(list.getByText("新分析", { exact: true })).toBeVisible();

    // /journal 时间线含研究事件（T5：RESEARCH_EVENT 自动并入决策记录时间线，时间线为默认页签）
    await page.getByRole("link", { name: "决策" }).click();
    await expect(page).toHaveURL(/\/journal/, { timeout: 15_000 });
    const timeline = page.getByTestId("timeline");
    await expect(timeline.getByText("研究事件").first()).toBeVisible();
    await expect(timeline.getByText("立项：贵州茅台")).toBeVisible();
    await expect(timeline.getByText("策略定稿")).toBeVisible();
  });
});
