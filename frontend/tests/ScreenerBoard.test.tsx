import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ScreenerBoard from "@/components/screening/ScreenerBoard";
import { fetchScreenedStocks } from "@/lib/screeningApi";
import { fetchValuationIndustries } from "@/lib/valuationApi";

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams("industryCode=801780"),
}));

vi.mock("@/lib/screeningApi", () => ({
  fetchScreenedStocks: vi.fn(),
}));

vi.mock("@/lib/valuationApi", () => ({
  fetchValuationIndustries: vi.fn(),
}));

const mockedFetchScreenedStocks = vi.mocked(fetchScreenedStocks);
const mockedFetchIndustries = vi.mocked(fetchValuationIndustries);

const industries = [
  { industryCode: "801780", industryName: "银行", pe: 5.9, pb: 0.65, roe: 11, dividendYield: 5.1 },
];

beforeEach(() => {
  vi.clearAllMocks();
  mockedFetchIndustries.mockResolvedValue(industries as never);
  mockedFetchScreenedStocks.mockResolvedValue([] as never);
});

afterEach(() => {
  cleanup();
});

describe("ScreenerBoard · 行业条件从 URL 带入", () => {
  it("带 ?industryCode= 挂载时行业下拉预填该行业", async () => {
    render(<ScreenerBoard />);
    // 等异步行业列表加载，出现「银行」选项
    expect(await screen.findByText("银行")).toBeTruthy();
    const select = screen.getByLabelText("行业") as HTMLSelectElement;
    expect(select.value).toBe("801780");
  });

  it("提交筛选时保留 URL 带入的行业条件（不再报「请至少填写一个筛选条件」）", async () => {
    const user = userEvent.setup();
    render(<ScreenerBoard />);
    expect(await screen.findByText("银行")).toBeTruthy();
    await user.click(screen.getByRole("button", { name: "筛选" }));
    await vi.waitFor(() => expect(mockedFetchScreenedStocks).toHaveBeenCalled());
    expect(mockedFetchScreenedStocks).toHaveBeenCalledWith(
      expect.objectContaining({ industryCode: "801780" }),
    );
    expect(screen.queryByText("请至少填写一个筛选条件")).toBeNull();
  });
});
