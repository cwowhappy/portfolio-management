# P3 · 行业联动与 e2e 收尾实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 行业下钻页「保存研究结论」入口（MS-11 验收第二条）+ Playwright e2e 全链路 + 交付文档同步，MS-11 收口。

**Architecture:** `IndustryDrilldown` 页头挂 `ResearchNoteDialog`（独立弹窗组件：预填行业上下文 → `POST type=RESEARCH_NOTE` → 成功后深链 `/wiki?tab=research`）；未登录 `useAuth()` 判空隐藏（行业页公开）。e2e 沿用 `registerAndApprove` + industry 空库门控范式。

**Tech Stack:** React 19 / Next 15 Link / Playwright（已有配置与 helpers）

**Spec:** `features/wiki-knowledge-base/01-需求规格/需求规格说明.md` §三.D、§五；`features/wiki-knowledge-base/02-设计规格/设计规格说明.md` §4.3、§6、§7

## Global Constraints

- 行业页保持公开（不加 RequireAuth）；按钮未登录隐藏而非登录墙
- e2e 用例对行业数据依赖沿用 `boardHasData` 门控（空库 skip，接口非 200 不门控）
- `make smoke` 无新外部数据源不重跑（口径同 MS-07/08/09，同步进交付文档说明）
- 文档同步清单以 `features/README.md`「交付回填 checklist」与设计规格 §七为准

---

### Task 1: ResearchNoteDialog 与行业下钻页挂载

**Files:**
- Create: `frontend/components/wiki/ResearchNoteDialog.tsx`
- Modify: `frontend/components/industry/IndustryDrilldown.tsx`（页头挂按钮）
- Test: `frontend/tests/wiki/ResearchNoteDialog.test.tsx`

**Interfaces:**
- Consumes: P2 的 `createWikiEntry`、`MarkdownView`；`lib/auth.tsx` 的 `useAuth()`（返回 `{ user, ... }`，`user` 为 `AuthUser | null`）
- Produces: `ResearchNoteDialog({ industryCode, industryName })`；`data-testid`：`research-note-open`（触发按钮）、`research-note-dialog`、`research-note-title`、`research-note-content`、`research-note-save`、`research-note-view-link`。e2e（Task 2）依赖。

- [ ] **Step 1: 写失败测试**

```tsx
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import ResearchNoteDialog from "@/components/wiki/ResearchNoteDialog";
import * as wikiApi from "@/lib/wikiApi";

afterEach(() => cleanup());

const saved: wikiReturn = {
  id: 99, type: "RESEARCH_NOTE", title: "白酒行业研究结论", content: "结论",
  category: null, industryCode: "801120", createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z",
};
type wikiReturn = Awaited<ReturnType<typeof wikiApi.createWikiEntry>>;

describe("ResearchNoteDialog", () => {
  it("标题预填行业名 + 行业代码自动带入", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(saved);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    expect(screen.queryByTestId("research-note-dialog")).toBeNull(); // 初始关闭
    fireEvent.click(screen.getByTestId("research-note-open"));
    expect((screen.getByTestId("research-note-title") as HTMLInputElement).value).toBe("白酒 研究结论");
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "景气上行" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(expect.objectContaining({
      type: "RESEARCH_NOTE", industryCode: "801120",
    })));
  });

  it("行业名未加载时用代码兜底预填", () => {
    render(<ResearchNoteDialog industryCode="801120" industryName="" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    expect((screen.getByTestId("research-note-title") as HTMLInputElement).value).toBe("801120 研究结论");
  });

  it("保存成功展示「在知识库查看」深链", async () => {
    vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(saved);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "结论" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    const link = await screen.findByTestId("research-note-view-link");
    expect(link.getAttribute("href")).toBe("/wiki?tab=research");
  });

  it("空内容保存被拦截不调 API", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(saved);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.click(screen.getByTestId("research-note-save"));
    expect(createSpy).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd frontend && pnpm test -- tests/wiki/ResearchNoteDialog.test.tsx`
Expected: FAIL（组件不存在）

- [ ] **Step 3: 最小实现**

`frontend/components/wiki/ResearchNoteDialog.tsx`：

```tsx
"use client";

import { useState } from "react";
import Link from "next/link";
import MarkdownView from "@/components/shared/MarkdownView";
import { createWikiEntry } from "@/lib/wikiApi";

/** 行业下钻页「保存研究结论」入口：预填行业上下文，存为 RESEARCH_NOTE，成功深链 /wiki。 */
export default function ResearchNoteDialog({ industryCode, industryName }: {
  industryCode: string;
  industryName: string;
}) {
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [preview, setPreview] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const openDialog = () => {
    setTitle(`${industryName || industryCode} 研究结论`);
    setContent("");
    setPreview(false);
    setSaved(false);
    setError(null);
    setOpen(true);
  };

  const save = async () => {
    setError(null);
    if (!title.trim()) { setError("标题不能为空"); return; }
    if (!content.trim()) { setError("内容不能为空"); return; }
    try {
      await createWikiEntry({ type: "RESEARCH_NOTE", title: title.trim(), content, industryCode });
      setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  return (
    <>
      <button data-testid="research-note-open" type="button"
        className="rounded-md px-3 py-1.5 text-xs border border-[color:var(--color-line)] hover:bg-[color:var(--color-panel)]"
        onClick={openDialog}>
        保存研究结论
      </button>
      {open && (
        <div data-testid="research-note-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          onClick={(e) => { if (e.target === e.currentTarget) setOpen(false); }}>
          <div className="w-full max-w-2xl rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-bg)] p-5 space-y-3">
            {saved ? (
              <div className="space-y-3 text-center py-6">
                <div className="text-sm">已保存至投资知识库</div>
                <Link data-testid="research-note-view-link" href="/wiki?tab=research"
                  className="inline-block rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]">
                  在知识库查看
                </Link>
              </div>
            ) : (
              <>
                <div className="font-[family-name:var(--font-display)] text-[15px]">保存研究结论到知识库</div>
                <input data-testid="research-note-title" className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
                  placeholder="标题" value={title} onChange={(e) => setTitle(e.target.value)} />
                <div className="flex items-center justify-between">
                  <span className="text-xs text-[color:var(--color-ink-faint)]">Markdown</span>
                  <button type="button" className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-xs"
                    onClick={() => setPreview(!preview)}>
                    {preview ? "编辑" : "预览"}
                  </button>
                </div>
                {preview ? (
                  <div className="min-h-24 rounded-md border border-[color:var(--color-line-soft)] p-3">
                    {content.trim() ? <MarkdownView content={content} /> : <span className="text-sm text-[color:var(--color-ink-faint)]">暂无内容</span>}
                  </div>
                ) : (
                  <textarea data-testid="research-note-content" rows={6}
                    className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-2 text-sm"
                    placeholder="研究结论（Markdown）" value={content} onChange={(e) => setContent(e.target.value)} />
                )}
                {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
                <div className="flex gap-2">
                  <button data-testid="research-note-save" type="button"
                    className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
                    onClick={save}>
                    保存
                  </button>
                  <button type="button" className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]"
                    onClick={() => setOpen(false)}>
                    取消
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}
```

`IndustryDrilldown.tsx` 修改（两处）：

1. import 区加：

```tsx
import { useAuth } from "@/lib/auth";
import ResearchNoteDialog from "@/components/wiki/ResearchNoteDialog";
```

2. 组件内（`const [sortBy, setSortBy] = ...` 附近）加 `const { user } = useAuth();`，页头 `<span className="text-sm ...">成员 {stocks.length}</span>` 之后追加：

```tsx
        {user && (
          <span className="ml-auto">
            <ResearchNoteDialog industryCode={industryCode} industryName={industryName} />
          </span>
        )}
```

> `ml-auto` 把按钮推到页头右端（页头是 `flex items-baseline`）。

- [ ] **Step 4: 跑测试确认通过 + 行业组件回归**

Run: `cd frontend && pnpm test -- tests/wiki/ tests/industry 2>/dev/null || cd frontend && pnpm test -- tests/wiki/`
Expected: PASS（新 4 tests；若 tests/industry 存在则一并无回归）

- [ ] **Step 5: 提交**

```bash
git add frontend/components/wiki/ResearchNoteDialog.tsx frontend/components/industry/IndustryDrilldown.tsx frontend/tests/wiki/ResearchNoteDialog.test.tsx
git commit -m "feat(wiki): 行业下钻页保存研究结论入口（未登录隐藏）"
```

---

### Task 2: e2e wiki.spec.ts

**Files:**
- Create: `frontend/e2e/wiki.spec.ts`

**Interfaces:**
- Consumes: `frontend/e2e/helpers.ts` 的 `registerAndApprove` / `uniqueUsername` / `TEST_PASSWORD`；P2/P3 的 testid 与文案；`industry.spec.ts` 的 `boardHasData` 门控范式
- Produces: MS-11 验收的浏览器级证据（预置/笔记/规则/行业联动四链路）

- [ ] **Step 1: 写 e2e 用例**

```ts
import { test, expect, type APIRequestContext } from "@playwright/test";
import { registerAndApprove, TEST_PASSWORD, uniqueUsername } from "./helpers";

const hasAdminSeed = !!(process.env.ADMIN_USERNAME && process.env.ADMIN_PASSWORD);

/** 与 industry.spec.ts 同范式：CI 空库时行业联动用例无从发生，探测板面为空则跳过。 */
async function boardHasData(request: APIRequestContext): Promise<boolean> {
  const res = await request.get("/api/industry/board");
  if (!res.ok()) return true;
  const body: unknown = await res.json();
  return !(Array.isArray(body) && body.length === 0);
}

test.describe("/wiki 投资知识库", () => {
  test.skip(!hasAdminSeed, "未配置 ADMIN_USERNAME/ADMIN_PASSWORD（无种子管理员），跳过配置用例");

  test("概念预置 + 读书笔记 Markdown 渲染 + 原则规则", async ({ page }) => {
    await registerAndApprove(page, uniqueUsername("wk"), TEST_PASSWORD);

    await page.getByRole("link", { name: "知识库" }).click();
    await expect(page).toHaveURL(/\/wiki/, { timeout: 15_000 });
    await expect(page.getByRole("heading", { name: "投资知识库" })).toBeVisible();

    // 概念 tab：首次访问自动出现预置词条（seeding）
    await page.getByTestId("wiki-tab-concept").click();
    await expect(page.getByTestId("wiki-note-list").getByText("安全边际")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("wiki-note-list").getByText("护城河")).toBeVisible();

    // 读书笔记：创建 + 详情 Markdown 渲染
    await page.getByTestId("wiki-tab-book").click();
    await page.getByTestId("wiki-note-title").fill("《聪明的投资者》");
    await page.getByTestId("wiki-note-content").fill("## 核心观点\n- 市场先生是仆非主");
    await page.getByTestId("wiki-note-save").click();
    await expect(page.getByTestId("wiki-note-list").getByText("《聪明的投资者》")).toBeVisible({ timeout: 15_000 });
    await page.getByTestId("wiki-note-list").getByText("《聪明的投资者》").click();
    await expect(page.getByRole("heading", { name: "核心观点" })).toBeVisible();

    // 原则纪律：建规则（% 输入 → 0.2 落库 → 20% 展示）
    await page.getByTestId("wiki-tab-principle").click();
    await page.getByTestId("wiki-rule-threshold").fill("20");
    await page.getByTestId("wiki-rule-save").click();
    await expect(page.getByTestId("wiki-rule-list").getByText("单票仓位上限")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("wiki-rule-list").getByText("20%")).toBeVisible();
  });

  test("行业下钻页保存研究结论 → 知识库可见（MS-11 验收第二条）", async ({ page, request }) => {
    test.skip(!(await boardHasData(request)), "库中无行业板面数据（CI 空库），跳过行业联动用例");
    await registerAndApprove(page, uniqueUsername("wkj"), TEST_PASSWORD);

    await page.goto("/industry");
    await page.getByRole("button", { name: /银行/ }).first().click();
    await expect(page).toHaveURL(/\/industry\/\d+/, { timeout: 15_000 });

    await page.getByTestId("research-note-open").click();
    await expect(page.getByTestId("research-note-title")).toHaveValue(/研究结论$/);
    await page.getByTestId("research-note-content").fill("低估值区间，关注息差改善");
    await page.getByTestId("research-note-save").click();
    await page.getByTestId("research-note-view-link").click();

    await expect(page).toHaveURL(/\/wiki\?tab=research/, { timeout: 15_000 });
    await expect(page.getByTestId("wiki-note-list").getByText("研究结论")).toBeVisible({ timeout: 15_000 });
  });
});
```

> 注意：第二个用例研究笔记断言用 `getByText("研究结论")`（标题前缀匹配行业名，如「银行 研究结论」）；若 flaky 可改为 `getByText(/研究结论$/)`。

- [ ] **Step 2: 本地跑新 spec**

Run: `cd frontend && CI=true pnpm test:e2e -- wiki.spec.ts`
Expected: 两用例 PASS（本机 dev 库有行业数据时；CI 空库第二用例按门控 skip）。若失败按 trace 修复（常见：webServer 端口占用先 `pkill` 或改用既有 `.next` 构建产物须先清理——见 memory e2e 陈旧构建教训）。

- [ ] **Step 3: 提交**

```bash
git add frontend/e2e/wiki.spec.ts
git commit -m "test(wiki): e2e 四链路（预置/笔记渲染/规则/行业联动）"
```

---

### Task 3: 交付文档同步

**Files:**
- Modify: `docs/function/modules/13-投资知识库.md`
- Modify: `docs/function/00-功能模块概览.md`
- Modify: `docs/plans/2026-08-27-产品落地计划.md`
- Modify: `features/README.md`

**Interfaces:**
- Consumes: Task 1/2 交付结果；`features/README.md` 交付回填 checklist
- Produces: MS-11 状态 ✅ 的文档口径（PR 合并后回填 PR 号与复盘总结）

- [ ] **Step 1: 模块文档 13-投资知识库.md**

- 头部状态行：`⏳ 未开始 | 0/3` → `✅ 已完成 | 3/3`；页面 `/wiki`（已上线）+ 行业下钻页联动入口；
- F01/F02/F03 状态列 ⏳ → ✅，各附一行交付说明：
  - F01：参数化规则（4 指标枚举 + 阈值 + 启停，`principle_rule` V16，`UNIQUE(user_id, metric)`；三期 MS-15 预警消费契约 `PrincipleMetric`）
  - F02：Markdown 笔记 + 预览切换 + 详情渲染（react-markdown 共享 MarkdownView）
  - F03：应用层首次访问 seeding 10 条预置概念（`wiki_seed_state` 幂等，删光不复活）+ 用户可增删改

- [ ] **Step 2: 功能模块概览 00-功能模块概览.md**

- M13 行状态 `⏳ | 0/3` → `✅ | 3/3`，页面列补 `/industry/[industryCode]`（联动入口）；
- 基线统计：已完成 93→**96**、待开发 22→**19**、进度 ≈81%→**≈83%**（口径：二期 23/33）。

- [ ] **Step 3: 产品落地计划 2026-08-27-产品落地计划.md**

- 「三、里程碑总览」MS-11 行：`⏳ | 0/3` → `✅ | 3/3`；
- 「二、当前基线」表：已完成 96、待开发 19、整体进度 ≈83%（截至日期改为交付日）；
- 「四、里程碑详情」MS-11 节改写为交付说明（照 MS-09 节格式：交付范围勾选 + 状态行含 PR 号占位、技术要点——`domain/wiki` 两聚合 + V16 三表 + seeding + 行业入口、`make test` 三端全绿 + e2e 全绿、`make smoke` 无新增段不重跑口径）；
- 「七、里程碑变更记录」追加一行：`| 2026-09-XX | MS-11 交付 | 投资知识库三点收齐（M13-F01/F02/F03，PR #NN）：domain/wiki 两聚合 + Flyway V16 三表 + 概念预置 seeding + 行业下钻页研究结论入口；原则规则参数化结构化存储（三期 MS-15 预警消费契约）|`（XX/NN 合并后回填实际日期与 PR 号）。

- [ ] **Step 4: features/README.md 索引行**

`| [wiki-knowledge-base](wiki-knowledge-base/) | MS-11（进行中） | ...` → `| [wiki-knowledge-base](wiki-knowledge-base/) | MS-11（已交付 2026-09-XX，PR #NN） | M13 投资知识库 | /wiki（新增）/industry/[industryCode]（扩展）|`。

- [ ] **Step 5: 提交**

```bash
git add docs/function/modules/13-投资知识库.md docs/function/00-功能模块概览.md docs/plans/2026-08-27-产品落地计划.md features/README.md
git commit -m "docs(wiki): MS-11 交付文档同步（模块/概览/落地计划/索引）"
```

> PR 合并后回填：上述 XX/NN 实际值 + `features/wiki-knowledge-base/08-复盘总结/复盘总结.md`（交付结果/经验/不足/优化建议/计划偏差清单，README checklist 第 6 项）。

---

### Task 4: 最终验证与收尾

**Files:** 无新文件（验证任务）

- [ ] **Step 1: 三端全量测试**

Run: `make test`
Expected: `gradlew check`（单测 + ArchUnit + JaCoCo ≥80% + integrationTest + BDD）+ 前端 lint/vitest（560+ 既有无回归）+ collector pytest（无变更，应全绿）全绿。

- [ ] **Step 2: e2e 全量**

Run: `make test-e2e`
Expected: 全部 spec 通过（既有 45 用例 + wiki 新 2 用例；CI 空库时行业依赖用例按门控 skip 属正常）。若复用 `.next` 陈旧构建出现怪异失败，先清理构建产物重跑（memory 既有教训）。

- [ ] **Step 3: 汇总交付状态**

向用户汇报：功能点 3/3、测试计数（后端新增用例数/前端新增用例数/e2e 2）、`make smoke` 不重跑口径说明、待合并后回填项（PR 号、复盘总结）。按用户工作流偏好（破坏性操作先确认），推送与开 PR 前征求确认。
