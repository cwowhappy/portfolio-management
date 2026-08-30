import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import PositionActions from "@/components/portfolio/PositionActions";
import { addCashDividend, addStockDividend, deletePosition, editTrade, sell } from "@/lib/portfolioApi";

vi.mock("@/lib/portfolioApi", () => ({
  sell: vi.fn().mockResolvedValue({}),
  deletePosition: vi.fn().mockResolvedValue({}),
  addCashDividend: vi.fn().mockResolvedValue({}),
  addStockDividend: vi.fn().mockResolvedValue({}),
  editTrade: vi.fn().mockResolvedValue({}),
  fetchTrades: vi.fn().mockResolvedValue([
    { id: 11, type: "BUY", tradeDate: "2026-08-27", price: 100, quantity: 100, fee: 0 },
  ]),
}));

const position = {
  id: 5, groupId: 1, stockCode: "600519", stockName: "贵州茅台",
  quantity: 100, avgCost: 100, price: 120, marketValue: 12000,
  floatingPnl: 2000, pnlRatio: 20, realizedPnl: 0, totalBuyCost: 10000, cumulativeCashDividend: 0,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("PositionActions", () => {
  it("卖出调用 sell", async () => {
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("卖价"), { target: { value: "120" } });
    fireEvent.change(screen.getByLabelText("卖量"), { target: { value: "40" } });
    fireEvent.click(screen.getByRole("button", { name: "卖出" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(sell)).toHaveBeenCalledWith(
      expect.objectContaining({ positionId: 5, price: 120, quantity: 40 }),
    );
  });

  it("现金分红调用 addCashDividend", async () => {
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("每股金额"), { target: { value: "1.5" } });
    fireEvent.click(screen.getByRole("button", { name: "现金分红" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(addCashDividend)).toHaveBeenCalledWith(
      expect.objectContaining({ positionId: 5, cashPerShare: 1.5 }),
    );
  });

  it("送股调用 addStockDividend", async () => {
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("送股比例"), { target: { value: "0.5" } });
    fireEvent.click(screen.getByRole("button", { name: "送股" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(addStockDividend)).toHaveBeenCalledWith(
      expect.objectContaining({ positionId: 5, stockRatio: 0.5 }),
    );
  });

  it("编辑买入交易调用 editTrade", async () => {
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.click(screen.getByRole("button", { name: "编辑" }));
    await screen.findByLabelText("编辑价格");
    fireEvent.change(screen.getByLabelText("编辑价格"), { target: { value: "110" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(editTrade)).toHaveBeenCalledWith(5, 11, expect.objectContaining({ price: 110 }));
  });

  it("删除持仓调用 deletePosition", async () => {
    vi.stubGlobal("confirm", vi.fn(() => true));
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(deletePosition)).toHaveBeenCalledWith(5);
  });
});
