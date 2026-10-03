import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Suspense } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ProjectDetailPage from "@/app/research/[id]/page";
import type { ProjectDetailView } from "@/lib/researchSchemas";

// 项目详情页（M16-F11 回收）：项目信息操作区「情报提醒」开关——受控勾选 + 保存调用
// PUT /intelligence-alert + 保存后回读。子面板/鉴权壳打桩，聚焦本页自有行为。

vi.mock("@/components/auth/RequireAuth", () => ({
  RequireAuth: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

// 四个子面板各有独立数据流（strategy/entry-plan/hits/reviews），与本用例无关，整体打桩
vi.mock("@/components/research/StrategyPanel", () => ({ default: () => null }));
vi.mock("@/components/research/EntryPlanPanel", () => ({ default: () => null }));
vi.mock("@/components/research/FalsifierPanel", () => ({ default: () => null }));
vi.mock("@/components/research/ReviewPanel", () => ({ default: () => null }));

const { getProjectMock, getNotesMock, getWikiMock, setAlertMock } = vi.hoisted(() => ({
  getProjectMock: vi.fn(),
  getNotesMock: vi.fn(),
  getWikiMock: vi.fn(),
  setAlertMock: vi.fn(),
}));

vi.mock("@/lib/researchApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/researchApi")>();
  return {
    ...actual,
    getProject: getProjectMock,
    getLinkedNotes: getNotesMock,
    getLinkedWiki: getWikiMock,
    setIntelligenceAlert: setAlertMock,
    archiveProject: vi.fn(),
    patchProject: vi.fn(),
  };
});

const detail = (alertEnabled: boolean): ProjectDetailView => ({
  project: {
    id: 5,
    stockCode: "600519",
    stockName: "贵州茅台",
    industryCode: "801120",
    title: "茅台扩产研究",
    currentStage: "NEW_ANALYSIS",
    status: "ACTIVE",
    intelligenceAlertEnabled: alertEnabled,
    createdAt: "2026-09-28T08:00:00Z",
    updatedAt: "2026-09-28T08:00:00Z",
  },
  completions: {},
  strategy: null,
  falsifiers: [],
});

// 页面组件 use(params) 需 Suspense 边界兜住（jsdom 无 RSC 预解析），且首帧挂起须在
// act 内 await（「suspended inside an act scope」警告否则树保持空）
async function renderPage(id = "5") {
  let view: ReturnType<typeof render> | undefined;
  await act(async () => {
    view = render(
      <Suspense fallback={null}>
        <ProjectDetailPage params={Promise.resolve({ id })} />
      </Suspense>,
    );
  });
  return view!;
}

describe("ProjectDetail 情报提醒开关", () => {
  beforeEach(() => {
    getProjectMock.mockReset().mockResolvedValue(detail(true));
    getNotesMock.mockReset().mockResolvedValue([]);
    getWikiMock.mockReset().mockResolvedValue([]);
    setAlertMock.mockReset();
  });

  afterEach(cleanup);

  it("渲染开关且初值取项目当前开关位（默认开）；未改动时保存按钮禁用", async () => {
    await renderPage();
    const toggle = await screen.findByLabelText("情报提醒");
    expect((toggle as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole("button", { name: "保存开关" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("取消勾选并保存：调 setIntelligenceAlert(5, false) 且保存后回读新值", async () => {
    setAlertMock.mockResolvedValue(detail(false).project);
    await renderPage();
    fireEvent.click(await screen.findByLabelText("情报提醒"));
    expect((screen.getByLabelText("情报提醒") as HTMLInputElement).checked).toBe(false);
    const save = screen.getByRole("button", { name: "保存开关" }) as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    fireEvent.click(save);
    await waitFor(() => expect(setAlertMock).toHaveBeenCalledWith(5, false));
    // 保存产物回填 detail.project → 勾选保持新值、按钮回禁用（无脏改动）
    await waitFor(() => {
      expect((screen.getByLabelText("情报提醒") as HTMLInputElement).checked).toBe(false);
      expect((screen.getByRole("button", { name: "保存开关" }) as HTMLButtonElement).disabled).toBe(true);
    });
    // 开关保存不整页重载（区别于归档的 load() 路径）：仅一次详情请求
    expect(getProjectMock).toHaveBeenCalledTimes(1);
  });

  it("保存失败显示红字错误且勾选保持用户改动", async () => {
    setAlertMock.mockRejectedValue(new Error("网络中断"));
    await renderPage();
    fireEvent.click(await screen.findByLabelText("情报提醒"));
    fireEvent.click(screen.getByRole("button", { name: "保存开关" }));
    await waitFor(() => expect(screen.getByText("网络中断")).toBeTruthy());
    expect((screen.getByLabelText("情报提醒") as HTMLInputElement).checked).toBe(false);
  });
});
