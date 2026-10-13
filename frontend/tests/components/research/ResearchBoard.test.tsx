import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ResearchBoard from "@/components/research/ResearchBoard";
import * as researchApi from "@/lib/researchApi";
import type { ProjectView } from "@/lib/researchSchemas";

// 研究项目列表（F1 自 page 下沉组件层）：过滤/搜索/归档/过期响应守卫——
// 原 page 壳逻辑只能被 e2e 间接覆盖，此处按 vitest 组件层直测（照 ResearchPanels.test 先例，
// 仅 mock api 边界函数，ProjectList 等子组件真实渲染）。

vi.mock("@/lib/researchApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/researchApi")>("@/lib/researchApi");
  return {
    ...actual,
    listProjects: vi.fn(),
    archiveProject: vi.fn(),
  };
});

const api = vi.mocked(researchApi);

const project = (over: Partial<ProjectView> = {}): ProjectView => ({
  id: 7,
  stockCode: "600519",
  stockName: "贵州茅台",
  industryCode: "BK0477",
  title: "茅台重启研究",
  currentStage: "STRATEGY",
  status: "ACTIVE",
  intelligenceAlertEnabled: true,
  createdAt: "2026-09-20T08:00:00Z",
  updatedAt: "2026-09-28T08:00:00Z",
  ...over,
});

async function renderBoard() {
  render(<ResearchBoard />);
  await screen.findByTestId("research-board");
}

describe("ResearchBoard（研究项目列表，F1 下沉）", () => {
  beforeEach(() => {
    api.listProjects.mockReset().mockResolvedValue([project()]);
    api.archiveProject.mockReset().mockResolvedValue(project());
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it("初始加载按 ACTIVE 状态请求（默认不过滤阶段/搜索）", async () => {
    await renderBoard();
    expect(api.listProjects).toHaveBeenCalledWith({ stage: undefined, status: "ACTIVE", q: undefined });
    expect(screen.getByText("茅台重启研究")).toBeTruthy();
  });

  it("阶段过滤：点击阶段按钮后 listProjects 携带 stage 参数", async () => {
    await renderBoard();
    fireEvent.click(screen.getByRole("button", { name: "制定投资策略" }));
    await waitFor(() =>
      expect(api.listProjects).toHaveBeenCalledWith({ stage: "STRATEGY", status: "ACTIVE", q: undefined }),
    );
  });

  it("状态过滤：切到「已归档」后 listProjects 携带 status=ARCHIVED", async () => {
    await renderBoard();
    fireEvent.click(screen.getByRole("button", { name: "已归档" }));
    await waitFor(() =>
      expect(api.listProjects).toHaveBeenCalledWith({ stage: undefined, status: "ARCHIVED", q: undefined }),
    );
  });

  it("搜索：输入标题关键词后以 q 参数请求（前后空格不入参）", async () => {
    await renderBoard();
    fireEvent.change(screen.getByLabelText("搜索研究项目"), { target: { value: " 茅台 " } });
    await waitFor(() =>
      expect(api.listProjects).toHaveBeenCalledWith({ stage: undefined, status: "ACTIVE", q: "茅台" }),
    );
  });

  it("过滤后空结果渲染「没有符合条件」并提供清除过滤", async () => {
    api.listProjects.mockResolvedValue([]);
    await renderBoard();
    // 非过滤态空列表（首载即空）渲染立项引导
    expect(screen.getByTestId("research-empty")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "制定投资策略" }));
    await waitFor(() => expect(screen.getByTestId("research-empty-filtered")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "清除过滤" }));
    await waitFor(() =>
      expect(api.listProjects).toHaveBeenLastCalledWith({ stage: undefined, status: "ACTIVE", q: undefined }),
    );
  });

  it("归档：confirm 确认后调 archiveProject 并刷新列表；取消则不调用", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    await renderBoard();
    fireEvent.click(screen.getByRole("button", { name: "归档 茅台重启研究" }));
    expect(api.archiveProject).not.toHaveBeenCalled();
    confirmSpy.mockReturnValue(true);
    fireEvent.click(screen.getByRole("button", { name: "归档 茅台重启研究" }));
    await waitFor(() => expect(api.archiveProject).toHaveBeenCalledWith(7));
    // 归档成功后触发 reload（列表重新请求）
    await waitFor(() => expect(api.listProjects.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it("seq 守卫：过期响应到达时不覆盖最新列表", async () => {
    let resolveFirst!: (list: ProjectView[]) => void;
    let call = 0;
    api.listProjects.mockImplementation(() => {
      call += 1;
      if (call === 1) {
        return new Promise<ProjectView[]>((r) => {
          resolveFirst = r;
        });
      }
      return Promise.resolve([project({ id: 9, title: "最新研究" })]);
    });
    await renderBoard();
    // 触发第二次请求（过滤变更），其响应先到达
    fireEvent.click(screen.getByRole("button", { name: "复盘" }));
    await screen.findByText("最新研究");
    // 第一次（过期）响应后到达：不得覆盖新列表
    await act(async () => {
      resolveFirst([project({ id: 1, title: "过期研究" })]);
      await Promise.resolve();
    });
    expect(screen.queryByText("过期研究")).toBeNull();
    expect(screen.getByText("最新研究")).toBeTruthy();
  });
});
