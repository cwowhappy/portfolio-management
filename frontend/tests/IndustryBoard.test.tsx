import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { cleanup, render, screen, fireEvent, waitFor, within } from "@testing-library/react";
import IndustryBoard from "@/components/industry/IndustryBoard";
import { useAuth } from "@/lib/auth";

// vi.hoisted：vi.mock 工厂被提升到静态 import 前，普通 const 撞 TDZ（仓库既有教训）。
const pushMock = vi.hoisted(() => vi.fn());
const { fetchIndustryWatchMock, watchIndustryMock, unwatchIndustryMock } = vi.hoisted(() => ({
  fetchIndustryWatchMock: vi.fn(),
  watchIndustryMock: vi.fn(),
  unwatchIndustryMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock }) }));
vi.mock("@/lib/industryApi", () => ({
  fetchIndustryBoard: vi.fn().mockResolvedValue([
    { industryCode: "801780", industryName: "银行", pe: 5.5, pb: 0.8, roe: 12, dividendYield: 4,
      pePercentile: 40, pbPercentile: 30, prosperity: "UP",
      prosperityInputs: { roeDeltaMedian: 1, revenueYoyMedian: 10, sampleSize: 42 } },
  ]),
}));
vi.mock("@/lib/industryWatchApi", () => ({
  fetchIndustryWatch: fetchIndustryWatchMock,
  watchIndustry: watchIndustryMock,
  unwatchIndustry: unwatchIndustryMock,
}));
// 组件新增 useAuth()（关注需登录态），本测试无 AuthProvider，照 ScreenerBoard 惯例 mock。
vi.mock("@/lib/auth", () => ({ useAuth: vi.fn() }));

const userStub = { id: 1, username: "u", role: "USER", status: "APPROVED", enabled: true } as NonNullable<ReturnType<typeof useAuth>["user"]>;

describe("IndustryBoard", () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
    fetchIndustryWatchMock.mockReset().mockResolvedValue([]);
    watchIndustryMock.mockReset().mockResolvedValue(undefined);
    unwatchIndustryMock.mockReset().mockResolvedValue(undefined);
  });
  afterEach(() => {
    cleanup();
    pushMock.mockClear();
  });

  it("渲染 board 与热力图，loading 态先出骨架", async () => {
    render(<IndustryBoard />);
    expect(screen.getByText("行业估值")).toBeTruthy();
    expect(screen.getByLabelText("加载中")).toBeTruthy();
    // 同一份数据喂 board 表与热力图：银行在两处各出现一次
    expect((await screen.findAllByText("银行")).length).toBe(2);
    expect(screen.getByText("行业估值对比")).toBeTruthy();
    expect(screen.getByText("估值热力图")).toBeTruthy();
  });

  it("点击行业名跳转 /industry/{code}", async () => {
    render(<IndustryBoard />);
    const board = await screen.findByTestId("industry-board-table");
    fireEvent.click(within(board).getByText("银行"));
    expect(pushMock).toHaveBeenCalledWith("/industry/801780");
  });

  it("登录后 mount 调 fetchIndustryWatch 填充关注集合（⭐ 实心）", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    fetchIndustryWatchMock.mockResolvedValue([{ industryCode: "801780", addedAt: "2026-09-25T00:00:00Z" }]);

    render(<IndustryBoard />);
    const board = await screen.findByTestId("industry-board-table");
    expect(await within(board).findByRole("button", { name: "取消关注 801780" })).toBeTruthy();
    expect(fetchIndustryWatchMock).toHaveBeenCalledTimes(1);
  });

  it("未登录点 ⭐ 跳登录且不调关注接口", async () => {
    render(<IndustryBoard />);
    const board = await screen.findByTestId("industry-board-table");
    fireEvent.click(within(board).getByRole("button", { name: "关注 801780" }));
    expect(pushMock).toHaveBeenCalledWith("/login?redirect=/industry");
    expect(watchIndustryMock).not.toHaveBeenCalled();
  });

  it("登录点击 ☆ 调 watchIndustry 并置实心；再点 ★ 调 unwatchIndustry 还原", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);

    render(<IndustryBoard />);
    const board = await screen.findByTestId("industry-board-table");
    fireEvent.click(within(board).getByRole("button", { name: "关注 801780" }));
    await waitFor(() => expect(watchIndustryMock).toHaveBeenCalledWith("801780"));
    await waitFor(() =>
      expect(within(board).getByRole("button", { name: "取消关注 801780" })).toBeTruthy());

    fireEvent.click(within(board).getByRole("button", { name: "取消关注 801780" }));
    await waitFor(() => expect(unwatchIndustryMock).toHaveBeenCalledWith("801780"));
    await waitFor(() =>
      expect(within(board).getByRole("button", { name: "关注 801780" })).toBeTruthy());
  });
});
