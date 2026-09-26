import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAuth } from "@/lib/auth";
import type { ChainView, UnlistedCompany } from "@/lib/types";

// echarts/core mock（图卡内 EChart，照 LandscapeChart.test 先例；本文件不断言图表内部调用）
const { initSpy } = vi.hoisted(() => {
  const chart = {
    setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(),
    on: vi.fn(), off: vi.fn(),
  };
  return { initSpy: vi.fn(() => chart) };
});
vi.mock("echarts/core", () => ({
  init: initSpy, registerTheme: vi.fn(), use: vi.fn(),
}));

const { chainsMock, companiesMock, saveChainMock, deleteChainMock, pushMock } = vi.hoisted(() => ({
  chainsMock: vi.fn(), companiesMock: vi.fn(), saveChainMock: vi.fn(),
  deleteChainMock: vi.fn(), pushMock: vi.fn(),
}));
vi.mock("@/lib/industryChainApi", () => ({ fetchIndustryChains: chainsMock }));
vi.mock("@/lib/industryUnlistedApi", () => ({ fetchUnlistedCompanies: companiesMock }));
vi.mock("@/lib/industryCurationApi", () => ({
  saveChain: saveChainMock, deleteChain: deleteChainMock,
}));
vi.mock("@/lib/auth", () => ({ useAuth: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock }) }));

import ChainPanel from "@/components/industry/chain/ChainPanel";

class ResizeObserverStub {
  observe = vi.fn();
  disconnect = vi.fn();
}

const company = (id: number, name: string): UnlistedCompany => ({
  id, industryCode: "801730", companyName: name, segment: null,
  latestRound: "B", latestRoundLabel: "B轮", lastFundingDate: null,
  totalFundingYi: null, summary: null, sourceNote: null, updatedAt: "2026-09-26T00:00:00Z",
});

const chain = (id: number, name: string): ChainView => ({
  id, name, description: null,
  stages: [{ id: id * 10 + 1, tier: "UPSTREAM", tierLabel: "上游", name: "锂矿", sortOrder: 1, members: [
    { id: id * 100 + 1, memberType: "LISTED", stockCode: "300750", unlistedCompanyId: null, displayName: "宁德时代" },
  ] }],
});

const userStub = { id: 1, username: "u", role: "USER", status: "APPROVED", enabled: true } as NonNullable<ReturnType<typeof useAuth>["user"]>;

describe("ChainPanel", () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
    chainsMock.mockResolvedValue([]);
    companiesMock.mockResolvedValue([company(9, "示例康源生物")]);
    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  });
  afterEach(() => {
    cleanup();
    chainsMock.mockReset();
    companiesMock.mockReset();
    saveChainMock.mockReset();
    deleteChainMock.mockReset();
    vi.unstubAllGlobals();
    vi.mocked(useAuth).mockReset();
  });

  it("未策展行业空态：chain-empty 引导文案", async () => {
    render(<ChainPanel industryCode="801730" industryName="电力设备" />);
    expect(await screen.findByTestId("chain-empty")).toBeTruthy();
    expect(screen.getByTestId("chain-empty").textContent).toContain("该行业暂无产业链映射");
  });

  it("多链渲染：每链一张图卡，未登录无编辑入口", async () => {
    chainsMock.mockResolvedValue([chain(1, "锂电池"), chain(2, "创新药")]);
    render(<ChainPanel industryCode="801730" industryName="电力设备" />);

    expect(await screen.findByTestId("chain-card-1")).toBeTruthy();
    expect(screen.getByTestId("chain-card-2")).toBeTruthy();
    expect(screen.getByTestId("chain-graph-1")).toBeTruthy();
    expect(screen.getByTestId("chain-graph-2")).toBeTruthy();
    // 未登录：新增与行内编辑均不渲染（登录门控）
    expect(screen.queryByTestId("chain-add")).toBeNull();
    expect(screen.queryByTestId("chain-edit-1")).toBeNull();
  });

  it("登录后新建链：全文档表单提交 POST 命令（两环节各一成员）并刷新", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    saveChainMock.mockResolvedValue(chain(3, "测试链"));
    render(<ChainPanel industryCode="801730" industryName="电力设备" />);

    fireEvent.click(await screen.findByTestId("chain-add"));
    expect(screen.getByTestId("chain-editor-dialog")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("链名（必填）"), { target: { value: "测试链" } });
    // 第一环节（默认一成员）：环节名 + 上市成员代码/展示名
    fireEvent.change(screen.getByTestId("stage-name-0"), { target: { value: "锂矿" } });
    fireEvent.change(screen.getByTestId("member-code-0-0"), { target: { value: "300750" } });
    fireEvent.change(screen.getByTestId("member-name-0-0"), { target: { value: "宁德时代" } });
    // 添加第二环节 + 切未上市成员（选策展企业，展示名自动带出；先等策展名单选项加载）
    fireEvent.click(screen.getByTestId("chain-add-stage"));
    fireEvent.change(screen.getByTestId("stage-name-1"), { target: { value: "整车" } });
    fireEvent.change(screen.getByTestId("member-type-1-0"), { target: { value: "UNLISTED" } });
    expect(await screen.findByRole("option", { name: "示例康源生物" })).toBeTruthy();
    fireEvent.change(screen.getByTestId("member-company-1-0"), { target: { value: "9" } });
    expect((screen.getByTestId("member-name-1-0") as HTMLInputElement).value).toBe("示例康源生物");

    fireEvent.click(screen.getByRole("button", { name: "保存", exact: true }));
    await waitFor(() => expect(saveChainMock).toHaveBeenCalledTimes(1));
    const cmd = saveChainMock.mock.calls[0][0];
    expect(cmd.id).toBeUndefined();
    expect(cmd.name).toBe("测试链");
    expect(cmd.stages).toHaveLength(2);
    expect(cmd.stages[0]).toMatchObject({ tier: "UPSTREAM", name: "锂矿", sortOrder: 1 });
    expect(cmd.stages[0].members[0]).toMatchObject({
      memberType: "LISTED", stockCode: "300750", unlistedCompanyId: null, displayName: "宁德时代",
    });
    expect(cmd.stages[1].members[0]).toMatchObject({
      memberType: "UNLISTED", stockCode: null, unlistedCompanyId: 9, displayName: "示例康源生物",
    });
    // 保存成功回调刷新（fetchIndustryChains 至少两次：首载 + onChanged）
    await waitFor(() => expect(chainsMock).toHaveBeenCalledTimes(2));
    expect(screen.queryByTestId("chain-editor-dialog")).toBeNull(); // 对话框关闭
  });

  it("编辑器校验：链名缺失保存被拦，行内错误可见且不触保存", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    render(<ChainPanel industryCode="801730" industryName="电力设备" />);

    fireEvent.click(await screen.findByTestId("chain-add"));
    fireEvent.click(screen.getByRole("button", { name: "保存", exact: true }));
    expect(await screen.findByText("链名必填")).toBeTruthy();
    expect(saveChainMock).not.toHaveBeenCalled();
  });

  it("行内编辑打开既有链：删除链（confirm 确认）后委托删除并刷新", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    chainsMock.mockResolvedValue([chain(1, "锂电池")]);
    deleteChainMock.mockResolvedValue(undefined);
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(true);
    render(<ChainPanel industryCode="801730" industryName="电力设备" />);

    fireEvent.click(await screen.findByTestId("chain-edit-1"));
    expect(screen.getByTestId("chain-editor-dialog")).toBeTruthy();
    expect((screen.getByLabelText("链名（必填）") as HTMLInputElement).value).toBe("锂电池"); // 回显
    fireEvent.click(screen.getByTestId("chain-delete"));

    await waitFor(() => expect(deleteChainMock).toHaveBeenCalledWith(1));
    await waitFor(() => expect(chainsMock).toHaveBeenCalledTimes(2));
    expect(confirmSpy).toHaveBeenCalled();
    confirmSpy.mockRestore();
  });
});
