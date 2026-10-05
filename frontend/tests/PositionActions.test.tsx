import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import PositionActions from "@/components/portfolio/PositionActions";
import { addCashDividend, addStockDividend, deletePosition, fetchDeleteImpact, editTrade, sell } from "@/lib/portfolioApi";
import { DeleteImpactSchema } from "@/lib/schemas";

vi.mock("@/lib/portfolioApi", () => ({
  sell: vi.fn().mockResolvedValue({}),
  deletePosition: vi.fn().mockResolvedValue({}),
  addCashDividend: vi.fn().mockResolvedValue({}),
  addStockDividend: vi.fn().mockResolvedValue({}),
  editTrade: vi.fn().mockResolvedValue({}),
  fetchTrades: vi.fn().mockResolvedValue([
    { id: 11, type: "BUY", tradeDate: "2026-08-27", price: 100, quantity: 100, fee: 0 },
  ]),
  fetchDeleteImpact: vi.fn().mockResolvedValue({ tradeCount: 3, dividendCount: 1, realizedPnl: 2000 }),
}));

const position = {
  id: 5, groupId: 1, stockCode: "600519", stockName: "贵州茅台",
  quantity: 100, avgCost: 100, price: 120, marketValue: 12000,
  floatingPnl: 2000, pnlRatio: 20, realizedPnl: 0, totalBuyCost: 10000, cumulativeCashDividend: 0,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.clearAllMocks();
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

  it("点删除先拉预检，弹窗渲染数字与警示，取消不删除", async () => {
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    await vi.waitFor(() => expect(vi.mocked(fetchDeleteImpact)).toHaveBeenCalledWith(5));
    expect(await screen.findByText(/将永久删除 3 笔交易、1 笔分红/)).toBeTruthy();
    expect(screen.getByText(/历史不可恢复/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(vi.mocked(deletePosition)).not.toHaveBeenCalled();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("弹窗确认后调用 deletePosition", async () => {
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    fireEvent.click(await screen.findByRole("button", { name: "确认删除" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(deletePosition)).toHaveBeenCalledWith(5);
  });

  it("预检失败降级为通用确认文案且不阻塞删除", async () => {
    vi.mocked(fetchDeleteImpact).mockRejectedValueOnce(new Error("网络错误"));
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    expect(await screen.findByText(/确定删除 贵州茅台 持仓及其交易\/分红记录/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "确认删除" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(deletePosition)).toHaveBeenCalledWith(5);
  });

  it("DeleteImpactSchema 解析合法契约数字", () => {
    expect(DeleteImpactSchema.parse({ tradeCount: 3, dividendCount: 1, realizedPnl: 2000 })).toEqual({
      tradeCount: 3, dividendCount: 1, realizedPnl: 2000,
    });
  });

  it("DeleteImpactSchema 缺字段拒绝", () => {
    expect(DeleteImpactSchema.safeParse({ tradeCount: 3, dividendCount: 1 }).success).toBe(false);
    expect(DeleteImpactSchema.safeParse({}).success).toBe(false);
    expect(DeleteImpactSchema.safeParse({ tradeCount: 3, dividendCount: 1, realizedPnl: "2000" }).success).toBe(false);
  });

  it("卖出失败时显示错误且不触发 onChanged", async () => {
    vi.mocked(sell).mockRejectedValueOnce(new Error("卖出数量超过持仓"));
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("卖价"), { target: { value: "120" } });
    fireEvent.change(screen.getByLabelText("卖量"), { target: { value: "40" } });
    fireEvent.click(screen.getByRole("button", { name: "卖出" }));
    expect(await screen.findByText("卖出数量超过持仓")).toBeTruthy();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("非 Error 卖出异常回退为默认错误文案", async () => {
    vi.mocked(sell).mockRejectedValueOnce("boom");
    render(<PositionActions position={position} onChanged={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("卖价"), { target: { value: "120" } });
    fireEvent.change(screen.getByLabelText("卖量"), { target: { value: "40" } });
    fireEvent.click(screen.getByRole("button", { name: "卖出" }));
    expect(await screen.findByText("卖出失败")).toBeTruthy();
  });

  it("删除失败时显示错误", async () => {
    vi.mocked(deletePosition).mockRejectedValueOnce(new Error("存在关联交易"));
    const onChanged = vi.fn();
    render(<PositionActions position={position} onChanged={onChanged} />);
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    fireEvent.click(await screen.findByRole("button", { name: "确认删除" }));
    expect(await screen.findByText("存在关联交易")).toBeTruthy();
    expect(onChanged).not.toHaveBeenCalled();
  });
});
