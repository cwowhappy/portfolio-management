import { describe, it, expect, vi, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import IndustryBoard from "@/components/industry/IndustryBoard";

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/lib/valuationApi", () => ({
  fetchValuationIndustries: vi.fn().mockResolvedValue([
    { tradingDay: "2026-08-27", industryCode: "801780", industryName: "银行", pe: 5.9, pb: 0.65, roe: 11, dividendYield: 5.1 },
  ]),
}));

describe("IndustryBoard", () => {
  afterEach(() => cleanup());

  it("渲染行业对比表与热力图", async () => {
    render(<IndustryBoard />);
    expect(await screen.findByText("行业估值")).toBeTruthy();
    expect(screen.getByText("行业估值对比")).toBeTruthy();
    expect(screen.getByText("估值热力图")).toBeTruthy();
  });
});
