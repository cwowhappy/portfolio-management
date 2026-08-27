import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import ValuationBoard from "@/components/valuation/ValuationBoard";

vi.mock("@/lib/valuationApi", () => ({
  fetchValuationOverview: vi.fn().mockResolvedValue({
    latestSnapshot: { tradingDay: "2026-08-27", peMedian: 19.14, pbMedian: 1.68, netBreakerCount: 220, netBreakerRatio: 0.041 },
    pePercentile: null, pbPercentile: null, netBreakerPercentile: null,
    erp: null, erpPercentile: null, thermometer: null, indices: [], dataAccumulating: true,
  }),
  fetchValuationHistory: vi.fn().mockResolvedValue({ snapshots: [], treasuryYields: [], indexValuations: [] }),
  fetchValuationIndustries: vi.fn().mockResolvedValue([]),
}));

describe("ValuationBoard", () => {
  it("渲染标题与积累中标注", async () => {
    render(<ValuationBoard />);
    expect(await screen.findByText("市场估值仪表盘")).toBeTruthy();
    expect(screen.getByText(/数据积累中/)).toBeTruthy();
  });
});
