import { describe, it, expect, vi, afterEach } from "vitest";
import { cleanup, render, screen, fireEvent, within } from "@testing-library/react";
import IndustryBoard from "@/components/industry/IndustryBoard";

// vi.hoisted：vi.mock 工厂被提升到静态 import 前，普通 const 撞 TDZ（仓库既有教训）。
const pushMock = vi.hoisted(() => vi.fn());

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock }) }));
vi.mock("@/lib/industryApi", () => ({
  fetchIndustryBoard: vi.fn().mockResolvedValue([
    { industryCode: "801780", industryName: "银行", pe: 5.5, pb: 0.8, roe: 12, dividendYield: 4,
      pePercentile: 40, pbPercentile: 30, prosperity: "UP",
      prosperityInputs: { roeDeltaMedian: 1, revenueYoyMedian: 10, sampleSize: 42 } },
  ]),
}));

describe("IndustryBoard", () => {
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
});
