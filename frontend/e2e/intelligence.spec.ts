import { test, expect, type APIRequestContext } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

// 情报工作台 e2e（P4 Task 9）：/intelligence 四 tab 双态渲染（数据卡或空态卡 .or() 兜底，
// 口径照 analytics.spec / industry.spec MS-10 先例）+ 公告过滤器 + 深链过滤初值 +
// /settings/intelligence 订阅主链路（生成绑定码 S1 实打）+ 研究项目情报提醒开关（M16-F11 回收）。
// 真浏览器兜底动机：mock 单测对库内行为盲区（记忆教训），四 Panel 的分页信封/双空态/
// 深链过滤链路须真实后端验证。

// 页面 RequireAuth + /api/intelligence/** 需登录 → 全组 registerAndApprove，
// 依赖种子管理员（照 analytics.spec / portfolio.spec 范式）
const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);
const SKIP_NO_ADMIN = "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过情报工作台用例";

/**
 * 数据态门控探测（照 industry.spec boardHasData 模式）：/api/intelligence/news 非空。
 * 注意：/api/intelligence/** 不在公开白名单（需登录），裸 request 固定 401 会让门控
 * 恒开成死代码——调用方必须传 page.request（与浏览器共享会话 Cookie，照
 * conversation.spec / hitl.spec 先例），在 registerAndApprove 之后探测。
 * 接口非 200 时不门控（返回 true），让用例以可见失败暴露真实回归，避免空转绿灯。
 * CI 空库恒跳过：V3 迁移仅 seed 宏观日历，intelligence_news_raw 为空（collector 才写入）。
 */
async function intelligenceHasData(request: APIRequestContext): Promise<boolean> {
  const res = await request.get("/api/intelligence/news?pageSize=1");
  if (!res.ok()) return true;
  const body = (await res.json()) as { items?: unknown[] };
  return !(Array.isArray(body.items) && body.items.length === 0);
}

const SKIP_NO_DATA = "库中无情报数据（CI 空库，collector 未写入），跳过数据态用例";

test.describe("/intelligence 情报工作台", () => {
  test.skip(!hasAdminSeed, SKIP_NO_ADMIN);

  test("登录后访问：四 tab 双态渲染（数据卡或空态卡）", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("intel"), TEST_PASSWORD);

    await page.goto("/intelligence");
    await expect(page.getByRole("heading", { name: "情报工作台" })).toBeVisible();
    await expect(page.getByLabel("关键词检索")).toBeVisible();
    // 四 tab 按钮齐备（结构锚点，不依赖数据）
    for (const key of ["news", "announcements", "policies", "briefs"] as const) {
      await expect(page.getByTestId(`intel-tab-${key}`)).toBeVisible();
    }
    // 双态兜底（.or() 口径照 analytics.spec）：列表容器 / 全空引导至少其一可见——
    // 本地真库 collector 有数 → 列表；CI 空库（V3 仅 seed 宏观日历）→ intel-empty
    await expect(
      page.getByTestId("intel-news-list").or(page.getByTestId("intel-empty")),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByTestId("intel-tab-announcements").click();
    await expect(
      page.getByTestId("intel-announcements-list").or(page.getByTestId("intel-empty")),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByTestId("intel-tab-policies").click();
    // 政策 tab 特有：宏观日历卡恒渲染（V3 seed 行 → 有日程；无行 → 「暂无」文案）
    await expect(page.getByTestId("macro-calendar-card")).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByTestId("intel-policies-list").or(page.getByTestId("intel-empty")),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByTestId("intel-tab-briefs").click();
    await expect(
      page.getByTestId("intel-briefs-list").or(page.getByTestId("intel-empty")),
    ).toBeVisible({ timeout: 15_000 });
    // 简报 tab 特有：未选交易日时右侧恒出引导占位（不依赖数据）
    await expect(page.getByTestId("brief-detail-hint").or(page.getByTestId("brief-detail"))).toBeVisible();
  });

  test("新闻流有数据态：列表渲染条目", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("inteln"), TEST_PASSWORD);
    test.skip(!(await intelligenceHasData(page.request)), SKIP_NO_DATA);

    await page.goto("/intelligence");
    const list = page.getByTestId("intel-news-list");
    await expect(list).toBeVisible({ timeout: 15_000 });
    // 至少一条条目：标题锚链接（target=_blank 外链）真实渲染
    await expect(list.getByRole("listitem").first()).toBeVisible();
    await expect(list.locator("a").first()).toHaveAttribute("target", "_blank");
  });

  test("公告 tab 过滤器渲染：类型下拉含中文 label 选项", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("intela"), TEST_PASSWORD);

    await page.goto("/intelligence");
    await page.getByTestId("intel-tab-announcements").click();
    await expect(page.getByTestId("intel-announcements-panel")).toBeVisible({ timeout: 15_000 });
    // 过滤器三件套：标的输入 / 类型下拉（枚举→中文 Record 投影，11 类 + 全部）/ 仅重大
    await expect(page.getByLabel("公告标的")).toBeVisible();
    await expect(page.getByLabel("仅重大公告")).toBeVisible();
    const type = page.getByLabel("公告类型");
    await expect(type).toBeVisible();
    const labels = await type.locator("option").allTextContents();
    expect(labels).toContain("全部类型");
    expect(labels).toContain("股东增持");
    expect(labels).toContain("定期报告");
    expect(labels).toContain("退市风险");
    // 列表双态兜底（同首用例口径）
    await expect(
      page.getByTestId("intel-announcements-list").or(page.getByTestId("intel-empty")),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("深链 ?stock=600519：新闻 tab 过滤初值带入且请求携带", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("inteld"), TEST_PASSWORD);

    // 深链不仅回填输入框，还应把 stock 过滤带进首次检索（F17 互跳入参真链路）
    const newsReq = page.waitForResponse(
      (r) =>
        r.request().method() === "GET" &&
        r.url().includes("/api/intelligence/news") &&
        r.url().includes("stock=600519"),
    );
    await page.goto("/intelligence?stock=600519");
    expect((await newsReq).ok()).toBeTruthy();
    // 默认落新闻 tab，标的过滤初值 = 深链参数（value 断言）
    await expect(page.getByTestId("intel-news-panel")).toBeVisible();
    await expect(page.getByLabel("新闻标的")).toHaveValue("600519");
  });

  test("订阅设置页：总开关渲染 + 生成绑定码出码与倒计时", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("intels"), TEST_PASSWORD);

    await page.goto("/settings/intelligence");
    await expect(page.getByTestId("intel-settings")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "情报订阅" })).toBeVisible();
    // 总开关 checkbox 可见且缺省物化为开（S1：无行后端返回 pushEnabled=true）
    const master = page.getByLabel("推送总开关");
    await expect(master).toBeVisible();
    await expect(master).toBeChecked();

    // 未绑定分支：点击生成绑定码 → 6 位码 + mm:ss 倒计时可见（T3 审查建议的 S1 实打——
    // B1 契约 201 回执 code 为 6 位数字、TTL 10 分钟）
    await page.getByRole("button", { name: "生成绑定码" }).click();
    const code = page.getByTestId("binding-code");
    await expect(code).toBeVisible({ timeout: 15_000 });
    await expect(code).toHaveText(/^\d{6}$/);
    const countdown = page.getByTestId("binding-countdown");
    await expect(countdown).toBeVisible();
    // mm:ss 且非过期态 00:00（新码 10 分钟有效期，负向前瞻排除）
    await expect(countdown).toHaveText(/^(?!00:00$)\d{2}:\d{2}$/);
    // 已持码态：按钮翻转为「生成新码」
    await expect(page.getByRole("button", { name: "生成新码" })).toBeVisible();
  });

  test("项目情报开关：研究详情页开关默认开，关闭后保存持久化", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("intelp"), TEST_PASSWORD);

    // 项目 fixture 现造（照 research.spec 立项先例——项目按用户隔离，共享库 fixture
    // 对新注册用户不可见（非本人 404），唯有本用户自建）
    const params = new URLSearchParams({ code: "600519", name: "贵州茅台", industry: "BK0477" });
    await page.goto(`/research/new?${params.toString()}`);
    await page.getByLabel("项目标题").fill("e2e 情报开关回接验证");
    await page.getByRole("button", { name: "立项" }).click();
    await expect(page).toHaveURL(/\/research\/\d+$/, { timeout: 15_000 });

    // 开关渲染 + D13 默认开
    const toggle = page.getByLabel("情报提醒");
    await expect(toggle).toBeVisible({ timeout: 15_000 });
    await expect(toggle).toBeChecked();

    // 关闭 → 保存开关（M16-F11 回收端点 PUT /intelligence-alert）→ 保存按钮回禁用
    //（alertDraft === project.intelligenceAlertEnabled，服务端回执原地更新后相等）
    await toggle.uncheck();
    await page.getByRole("button", { name: "保存开关" }).click();
    await expect(page.getByRole("button", { name: "保存开关" })).toBeDisabled({ timeout: 15_000 });
    // 刷新验证持久化：读模型仍为关
    await page.reload();
    await expect(page.getByLabel("情报提醒")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByLabel("情报提醒")).not.toBeChecked();
  });
});
