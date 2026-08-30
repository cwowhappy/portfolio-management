import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import BuyForm from "@/components/portfolio/BuyForm";
import { buy } from "@/lib/portfolioApi";
import { searchStocks } from "@/lib/api";
import type { GroupView, PositionView } from "@/lib/types";

vi.mock("@/lib/portfolioApi", () => ({ buy: vi.fn().mockResolvedValue({}) }));
vi.mock("@/lib/api", () => ({
  searchStocks: vi.fn().mockResolvedValue([{ code: "600519", name: "贵州茅台", market: "1", marketName: "沪" }]),
}));

const accountGroup: GroupView = { id: 1, name: "华泰", type: "ACCOUNT", positionCount: 0, cashBalance: 0 };

const boughtPosition: PositionView = {
  id: 1, groupId: 1, stockCode: "600519", stockName: "贵州茅台",
  quantity: 100, avgCost: 1500, price: 1500, marketValue: 150000,
  floatingPnl: 0, pnlRatio: 0, realizedPnl: 0, totalBuyCost: 150000, cumulativeCashDividend: 0,
};

function fillRequiredFields() {
  fireEvent.change(screen.getByLabelText("代码"), { target: { value: "600519" } });
  fireEvent.change(screen.getByLabelText("名称"), { target: { value: "贵州茅台" } });
  fireEvent.change(screen.getByLabelText("价格"), { target: { value: "1500" } });
  fireEvent.change(screen.getByLabelText("数量"), { target: { value: "100" } });
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(buy).mockResolvedValue(boughtPosition);
  vi.mocked(searchStocks).mockResolvedValue([{ code: "600519", name: "贵州茅台", market: "1", marketName: "沪" }]);
});

afterEach(() => {
  cleanup();
});

describe("BuyForm", () => {
  it("提交买入调用 buy", async () => {
    const onChanged = vi.fn();
    render(<BuyForm groups={[accountGroup]} onChanged={onChanged} />);
    fireEvent.change(screen.getByLabelText("代码"), { target: { value: "600519" } });
    fireEvent.blur(screen.getByLabelText("代码"));
    fireEvent.change(screen.getByLabelText("价格"), { target: { value: "1500" } });
    fireEvent.change(screen.getByLabelText("数量"), { target: { value: "100" } });
    await screen.findByDisplayValue("贵州茅台");
    fireEvent.click(screen.getByRole("button", { name: "买入" }));
    await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(vi.mocked(buy)).toHaveBeenCalled();
  });

  it("必填项未填全时买入按钮禁用", () => {
    render(<BuyForm groups={[accountGroup]} onChanged={vi.fn()} />);
    const button = screen.getByRole("button", { name: "买入" });
    expect(button).toHaveProperty("disabled", true);
    // 只填部分字段仍然禁用
    fireEvent.change(screen.getByLabelText("代码"), { target: { value: "600519" } });
    fireEvent.change(screen.getByLabelText("名称"), { target: { value: "贵州茅台" } });
    expect(button).toHaveProperty("disabled", true);
    expect(vi.mocked(buy)).not.toHaveBeenCalled();
  });

  it("分组为空时无可选分组选项", () => {
    render(<BuyForm groups={[]} onChanged={vi.fn()} />);
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it("买入失败时显示错误且不触发 onChanged", async () => {
    const onChanged = vi.fn();
    vi.mocked(buy).mockRejectedValueOnce(new Error("可用现金不足"));
    render(<BuyForm groups={[accountGroup]} onChanged={onChanged} />);
    fillRequiredFields();
    fireEvent.click(screen.getByRole("button", { name: "买入" }));
    expect(await screen.findByText("可用现金不足")).toBeTruthy();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("非 Error 异常回退为默认错误文案", async () => {
    vi.mocked(buy).mockRejectedValueOnce("boom");
    render(<BuyForm groups={[accountGroup]} onChanged={vi.fn()} />);
    fillRequiredFields();
    fireEvent.click(screen.getByRole("button", { name: "买入" }));
    expect(await screen.findByText("买入失败")).toBeTruthy();
  });

  it("提交中按钮显示提交中且禁用", async () => {
    vi.mocked(buy).mockImplementationOnce(() => new Promise(() => {}));
    render(<BuyForm groups={[accountGroup]} onChanged={vi.fn()} />);
    fillRequiredFields();
    const button = screen.getByRole("button", { name: "买入" });
    fireEvent.click(button);
    expect(await screen.findByRole("button", { name: "提交中…" })).toHaveProperty("disabled", true);
  });

  it("搜索失败时忽略且不崩溃", async () => {
    vi.mocked(searchStocks).mockRejectedValueOnce(new Error("行情源超时"));
    render(<BuyForm groups={[accountGroup]} onChanged={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("代码"), { target: { value: "600519" } });
    fireEvent.blur(screen.getByLabelText("代码"));
    // 名称未被回填，页面不报错
    await vi.waitFor(() => expect(vi.mocked(searchStocks)).toHaveBeenCalled());
    expect(screen.getByLabelText("名称")).toHaveProperty("value", "");
  });
});
