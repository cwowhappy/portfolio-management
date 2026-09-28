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

  // P3-T7 收口场景（F09/F10，D18）+ P4-T5 复盘闭环追加段（F13/F14/F16）：立项 → 策略定稿
  // → 建仓计划保存 → 批次行发起 BUY 检查 → 预检（F01 全不勾 → 4 条 HIT）→ 越过（理由必填：
  // 空理由禁用提交）→ 留痕可见（响应回发 overrideReason + 关联记录 journal 事件「纪律检查：买入/越过」）
  // → 创建月度复盘（创建即定格快照，auto 列「无数据」标注 + 口径徽标）→ 修正作答（4.3 预填
  // 本期越过次数）→ 回流确认弹层 → 已回流记 wiki 条目号 → /wiki 研究笔记检索到「复盘·」条目。
  test("立项→定稿→建仓→检查越过→复盘创建→回流知识库", async ({ page }) => {
    // 预检走真实行情取数（东财），叠加注册审核流转与复盘回流，放宽时限
    test.setTimeout(180_000);
    await registerAndApprove(page, uniqueUsername("rs"), TEST_PASSWORD);

    // 立项（F05 预填）
    const params = new URLSearchParams({ code: "600519", name: "贵州茅台", industry: "BK0477" });
    await page.goto(`/research/new?${params.toString()}`);
    await page.getByLabel("项目标题").fill("买入检查越过留痕验证");
    await page.getByRole("button", { name: "立项" }).click();
    await expect(page).toHaveURL(/\/research\/\d+$/, { timeout: 15_000 });

    // 策略：创建草稿（服务端建 DRAFT 文档）→ 填估值区间暂存 → 定稿（定稿只卡估值下限<上限）
    const draftCreated = page.waitForResponse(
      (r) => r.request().method() === "PUT" && /\/api\/research\/projects\/\d+\/strategy$/.test(r.url()),
    );
    await page.getByRole("button", { name: "创建草稿" }).click();
    expect((await draftCreated).ok()).toBeTruthy();
    await page.getByLabel("估值下限").fill("1500");
    await page.getByLabel("估值上限").fill("1800");
    const draftSaved = page.waitForResponse(
      (r) => r.request().method() === "PUT" && /\/api\/research\/projects\/\d+\/strategy$/.test(r.url()),
    );
    await page.getByRole("button", { name: "暂存草稿" }).click();
    expect((await draftSaved).ok()).toBeTruthy();
    await page.getByRole("button", { name: "定稿" }).click();
    await expect(page.getByText("已定稿")).toBeVisible({ timeout: 15_000 });

    // 建仓计划（F09）：单批次 Σ占比 0.4，保存后「尚未保存」提示消失
    await page.getByLabel("批次 1 价格下限").fill("1400");
    await page.getByLabel("批次 1 价格上限").fill("1500");
    await page.getByLabel("批次 1 数量").fill("100");
    await page.getByLabel("批次 1 占比").fill("0.4");
    const planSaved = page.waitForResponse(
      (r) => r.request().method() === "PUT" && /\/api\/research\/projects\/\d+\/entry-plan$/.test(r.url()),
    );
    await page.getByRole("button", { name: "保存建仓计划" }).click();
    expect((await planSaved).ok()).toBeTruthy();
    await expect(page.getByText("尚未保存建仓计划")).toBeHidden();

    // 发起买入检查（D18 批次行入口）：F01 四项全不勾 → 预检记 4 条 HIT
    await page.getByRole("button", { name: "批次 1 发起买入检查" }).click();
    await expect(page.getByText("发起买入纪律检查")).toBeVisible();
    const previewed = page.waitForResponse(
      (r) => r.request().method() === "POST" && /\/api\/research\/projects\/\d+\/checks\/preview$/.test(r.url()),
    );
    await page.getByRole("button", { name: "预检" }).click();
    expect((await previewed).ok()).toBeTruthy();
    await expect(page.getByText("检查确认")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("命中 4 项")).toBeVisible();

    // 越过（OVERRIDDEN 必填理由）：空理由提交禁用，填理由后提交留痕（append-only + journal 事件）
    await page.getByRole("button", { name: "越过命中项继续" }).click();
    await expect(page.getByText("越过命中项须填写理由")).toBeVisible();
    const overrideSubmit = page.getByRole("button", { name: "提交（越过）" });
    await expect(overrideSubmit).toBeDisabled();
    const reason = "估值已回落至计划区间，命中项为未勾选自检项而非规则越线，按计划建仓";
    await page.getByLabel("越过理由").fill(reason);
    const checkSubmitted = page.waitForResponse(
      (r) => r.request().method() === "POST" && /\/api\/research\/projects\/\d+\/checks$/.test(r.url()),
    );
    await overrideSubmit.click();
    const record = (await (await checkSubmitted).json()) as { overrideReason?: string };
    expect(record.overrideReason).toBe(reason);

    // 留痕可见：确认卡随提交收起；关联记录并入 journal 事件「纪律检查：买入/越过」
    await expect(page.getByText("检查确认")).toBeHidden({ timeout: 15_000 });
    const notes = page.getByTestId("linked-notes");
    await expect(notes.getByText("纪律检查：买入/越过")).toBeVisible({ timeout: 15_000 });

    // —— 复盘闭环追加段（F13/F14/F16，MS-27 验收链路）——
    // 创建月度复盘（三档选月主，D7）：区间 = 本月 1 日 ~ 今日（UTC 日期，与留痕预填同口径）
    const todayIso = new Date().toISOString().slice(0, 10);
    const monthStartIso = `${todayIso.slice(0, 7)}-01`;
    await page.getByLabel("复盘档位").selectOption("MONTHLY");
    await page.getByLabel("复盘起始日").fill(monthStartIso);
    await page.getByLabel("复盘截止日").fill(todayIso);
    const reviewCreated = page.waitForResponse(
      (r) => r.request().method() === "POST" && /\/api\/research\/projects\/\d+\/reviews$/.test(r.url()),
    );
    await page.getByRole("button", { name: "新建复盘" }).click();
    const review = (await (await reviewCreated).json()) as { id: number };
    expect(review.id).toBeGreaterThan(0);

    // 修正表单展开：快照已定格说明 + auto 列「无数据」标注（无持仓，Review Focus 1）+ 口径徽标
    await expect(page.getByText("快照已定格（创建时写入，修正不复算）")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("无数据", { exact: true }).first()).toBeVisible();
    await expect(page.locator('span[title*="东财收盘"]').first()).toBeVisible();
    // 列表行：月度复盘 + 待回流徽标
    await expect(page.getByTestId("review-list").getByText("月度复盘")).toBeVisible();
    await expect(page.getByTestId("review-list").getByText("待回流")).toBeVisible();

    // 填 answers（4.1 决策质量 / 4.4 归因单选）+ 4.3 预填「本期越过 1 次」（GET checks 统计）+ 复盘叙述
    await expect(page.getByText("本期越过 1 次")).toBeVisible({ timeout: 15_000 });
    await page
      .getByLabel("决策质量：检查单执行与论点新证据")
      .fill("检查单逐项执行，越过项已留理由，决策流程符合 SOP");
    await page.getByLabel("归因：决策/结果四象限").selectOption("决策对/结果对");
    await page
      .getByLabel("复盘叙述")
      .fill("e2e 复盘回流验证：本期按计划建仓，纪律检查一次越过已留痕，结论沉淀为知识条目。");
    const reviewSaved = page.waitForResponse(
      (r) =>
        r.request().method() === "PUT" &&
        /\/api\/research\/projects\/\d+\/reviews\/\d+$/.test(r.url()),
    );
    await page.getByRole("button", { name: "保存修正" }).click();
    expect((await reviewSaved).ok()).toBeTruthy();

    // 回流确认弹层（F16 用户确认后入库，不自动）→ 确认 → 已回流记 wiki 条目号（幂等终态展示）
    await page.getByRole("button", { name: "回流知识库" }).click();
    const confirmLayer = page.getByTestId("reflux-confirm");
    await expect(confirmLayer).toBeVisible();
    await expect(confirmLayer.getByText("将写入知识库 RESEARCH_NOTE")).toBeVisible();
    const refluxed = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" &&
        /\/api\/research\/projects\/\d+\/reviews\/\d+\/reflux$/.test(r.url()),
    );
    await confirmLayer.getByRole("button", { name: "确认回流" }).click();
    const refluxBody = (await (await refluxed).json()) as { refluxState: string; wikiEntryId: number };
    expect(refluxBody.refluxState).toBe("REFLOWN");
    expect(refluxBody.wikiEntryId).toBeGreaterThan(0);
    await expect(page.getByText(new RegExp(`已回流 · wiki #${refluxBody.wikiEntryId}`))).toBeVisible({
      timeout: 15_000,
    });

    // /wiki 研究笔记检索到回流条目（复盘· 标题 + SOP_REVIEW 徽标，MS-27 验收：复盘结论可在 wiki 检索）
    await page.goto("/wiki?tab=research");
    const noteList = page.getByTestId("wiki-note-list");
    await expect(noteList.getByText("复盘·买入检查越过留痕验证", { exact: false })).toBeVisible({
      timeout: 15_000,
    });
    await expect(noteList.getByText("SOP_REVIEW")).toBeVisible();
  });
});
