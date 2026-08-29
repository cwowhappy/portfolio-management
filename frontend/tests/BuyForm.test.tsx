import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import BuyForm from "@/components/portfolio/BuyForm";
import { buy } from "@/lib/portfolioApi";

vi.mock("@/lib/portfolioApi", () => ({ buy: vi.fn().mockResolvedValue({}) }));
vi.mock("@/lib/api", () => ({
  searchStocks: vi.fn().mockResolvedValue([{ code: "600519", name: "贵州茅台", market: "1", marketName: "沪" }]),
}));

describe("BuyForm", () => {
  it("提交买入调用 buy", async () => {
    const onChanged = vi.fn();
    render(<BuyForm groups={[{ id: 1, name: "华泰", type: "ACCOUNT", positionCount: 0, cashBalance: 0 }]} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("代码"), { target: { value: "600519" } });
    fireEvent.blur(screen.getByLabelText("代码"));
    fireEvent.change(screen.getByLabelText("价格"), { target: { value: "1500" } });
    fireEvent.change(screen.getByLabelText("数量"), { target: { value: "100" } });
    await screen.findByDisplayValue("贵州茅台");
    fireEvent.click(screen.getByRole("button", { name: "买入" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(buy)).toHaveBeenCalled();
  });
});
