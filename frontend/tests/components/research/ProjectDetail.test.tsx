import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ProjectDetail from "@/components/research/ProjectDetail";
import * as researchApi from "@/lib/researchApi";
import type { ProjectDetailView } from "@/lib/researchSchemas";

// 研究项目详情（F1 自 page 下沉组件层）：草稿同步/标题校验/归档/非法 id/关联记录——
// 原 page 壳逻辑只能被 e2e 间接覆盖，此处按 vitest 组件层直测（照 ResearchDetailPage.test
// 先例：子面板打桩，仅 api 边界 mock）。

vi.mock("@/components/research/StrategyPanel", () => ({ default: () => null }));
vi.mock("@/components/research/EntryPlanPanel", () => ({ default: () => null }));
vi.mock("@/components/research/FalsifierPanel", () => ({ default: () => null }));
vi.mock("@/components/research/ReviewPanel", () => ({ default: () => null }));
vi.mock("@/components/research/ReportsPanel", () => ({ default: () => null }));

vi.mock("@/lib/researchApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/researchApi")>("@/lib/researchApi");
  return {
    ...actual,
    getProject: vi.fn(),
    getLinkedNotes: vi.fn(),
    getLinkedWiki: vi.fn(),
    patchProject: vi.fn(),
    archiveProject: vi.fn(),
    setIntelligenceAlert: vi.fn(),
  };
});

const api = vi.mocked(researchApi);

const detail = (over: Partial<ProjectDetailView["project"]> = {}): ProjectDetailView => ({
  project: {
    id: 5,
    stockCode: "600519",
    stockName: "贵州茅台",
    industryCode: "801120",
    title: "茅台扩产研究",
    currentStage: "NEW_ANALYSIS",
    status: "ACTIVE",
    intelligenceAlertEnabled: true,
    createdAt: "2026-09-28T08:00:00Z",
    updatedAt: "2026-09-28T08:00:00Z",
    ...over,
  },
  completions: {},
  strategy: null,
  falsifiers: [],
});

async function renderDetail(projectId = 5) {
  render(<ProjectDetail projectId={projectId} />);
  await screen.findByTestId("research-detail");
}

describe("ProjectDetail（研究项目详情，F1 下沉）", () => {
  beforeEach(() => {
    api.getProject.mockReset().mockResolvedValue(detail());
    api.getLinkedNotes.mockReset().mockResolvedValue([]);
    api.getLinkedWiki.mockReset().mockResolvedValue([]);
    api.patchProject.mockReset();
    api.archiveProject.mockReset().mockResolvedValue(detail().project);
    api.setIntelligenceAlert.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("加载完成后表单草稿初始化为读模型值（标题/阶段/情报提醒）", async () => {
    await renderDetail();
    expect((screen.getByLabelText("项目标题") as HTMLInputElement).value).toBe("茅台扩产研究");
    expect((screen.getByLabelText("当前阶段") as HTMLSelectElement).value).toBe("NEW_ANALYSIS");
    expect((screen.getByLabelText("情报提醒") as HTMLInputElement).checked).toBe(true);
  });

  it("保存标题：PATCH 返回的新 detail 经 useDraftSync 回填草稿（输入框同步新值）", async () => {
    await renderDetail();
    api.patchProject.mockResolvedValue(detail({ title: "茅台扩产研究（2026Q4）" }));
    const input = screen.getByLabelText("项目标题");
    fireEvent.change(input, { target: { value: "  茅台扩产研究（2026Q4）  " } });
    fireEvent.click(screen.getByRole("button", { name: "保存标题" }));
    await waitFor(() =>
      expect(api.patchProject).toHaveBeenCalledWith(5, { title: "茅台扩产研究（2026Q4）" }),
    );
    // 草稿镜像读模型新值（去空白后的已存值），而非用户输入的原始串
    await waitFor(() =>
      expect((screen.getByLabelText("项目标题") as HTMLInputElement).value).toBe("茅台扩产研究（2026Q4）"),
    );
  });

  it("空标题保存被前端拦截：行内报错且不发起 PATCH", async () => {
    await renderDetail();
    fireEvent.change(screen.getByLabelText("项目标题"), { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "保存标题" }));
    await screen.findByText("项目标题不能为空");
    expect(api.patchProject).not.toHaveBeenCalled();
  });

  it("归档：confirm 确认后调 archiveProject 并整页重载（详情重新请求）", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    await renderDetail();
    fireEvent.click(screen.getByRole("button", { name: "归档项目" }));
    await waitFor(() => expect(api.archiveProject).toHaveBeenCalledWith(5));
    await waitFor(() => expect(api.getProject.mock.calls.length).toBe(2));
  });

  it("关联记录区渲染 journal 决策记录与 wiki 条目（F08 反查）", async () => {
    api.getLinkedNotes.mockResolvedValue([
      {
        id: 11,
        type: "RESEARCH_EVENT",
        stockCode: "600519",
        stockName: "贵州茅台",
        tradeId: null,
        title: "立项：茅台扩产研究",
        content: "",
        targetPrice: null,
        stopLoss: null,
        periodType: null,
        periodStart: null,
        periodEnd: null,
        eventDate: "2026-09-28",
        createdAt: "2026-09-28T08:00:00Z",
        updatedAt: "2026-09-28T08:00:00Z",
      },
    ]);
    api.getLinkedWiki.mockResolvedValue([
      {
        id: 3,
        type: "RESEARCH_NOTE",
        title: "白酒行业笔记",
        content: "",
        category: null,
        industryCode: null,
        createdAt: "2026-09-28T08:00:00Z",
        updatedAt: "2026-09-29T08:00:00Z",
      },
    ]);
    await renderDetail();
    expect(screen.getByTestId("linked-notes").textContent).toContain("立项：茅台扩产研究");
    expect(screen.getByTestId("linked-wiki").textContent).toContain("白酒行业笔记");
  });

  it("非法 id 渲染错误视图且不发请求", async () => {
    render(<ProjectDetail projectId={0} />);
    await screen.findByTestId("research-detail-error");
    expect(api.getProject).not.toHaveBeenCalled();
  });
});
