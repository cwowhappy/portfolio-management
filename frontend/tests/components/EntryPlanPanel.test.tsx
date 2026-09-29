import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import EntryPlanPanel from "@/components/research/EntryPlanPanel";
import * as researchApi from "@/lib/researchApi";
import type { CheckItemResult, EntryPlanView } from "@/lib/researchSchemas";

// 组件层真实渲染断 DOM；仅 mock api 边界函数（照 ResearchPanels.test 先例）——
// Σratio 拦截、检查流状态机（F01 勾选 → 预检 → 确认/越过）不 mock。

vi.mock("@/lib/researchApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/researchApi")>("@/lib/researchApi");
  return {
    ...actual,
    getEntryPlan: vi.fn(),
    saveEntryPlan: vi.fn(),
    previewCheck: vi.fn(),
    submitCheck: vi.fn(),
  };
});

const api = vi.mocked(researchApi);

const savedPlan: EntryPlanView = {
  id: 5,
  winRate: 0.6,
  payoffRatio: 2,
  kellyRatio: 0.4,
  batches: [
    { seq: 1, priceLow: 12, priceHigh: 13, quantity: 1000, amount: 12500, ratio: 0.6 },
    { seq: 2, priceLow: 10, priceHigh: 11, quantity: 500, amount: null, ratio: 0.4 },
  ],
  createdAt: "2026-09-27T08:00:00Z",
  updatedAt: "2026-09-28T08:00:00Z",
};

const buyPreviewItems: CheckItemResult[] = [
  { metric: "SINGLE_POSITION_RATIO", threshold: 0.3, currentValue: 0.25, outcome: "PASS" },
  { metric: "能力圈", threshold: null, currentValue: null, outcome: "HIT" },
];

const sellPreviewItems: CheckItemResult[] = [
  { metric: "能力圈", threshold: null, currentValue: null, outcome: "PASS" },
  { metric: "PRICE_BELOW 13.5000", threshold: 13.5, currentValue: null, outcome: "UNSET" },
];

beforeEach(() => {
  vi.resetAllMocks();
  api.getEntryPlan.mockRejectedValue(new Error("建仓计划不存在"));
  api.saveEntryPlan.mockResolvedValue(savedPlan);
  api.previewCheck.mockResolvedValue([]);
  api.submitCheck.mockResolvedValue({
    id: 9, checkType: "BUY", items: [], result: "CONFIRMED", overrideReason: null,
    createdAt: "2026-09-28T10:00:00Z",
  });
});

afterEach(cleanup);

describe("EntryPlanPanel 建仓计划编辑", () => {
  it("未保存（GET 404）：空表单可填，保存整替提交数值载荷", async () => {
    render(<EntryPlanPanel projectId={7} onChanged={vi.fn()} />);
    expect(await screen.findByText(/尚未保存建仓计划/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("批次 1 价格下限"), { target: { value: "12" } });
    fireEvent.change(screen.getByLabelText("批次 1 价格上限"), { target: { value: "13" } });
    fireEvent.change(screen.getByLabelText("批次 1 数量"), { target: { value: "100" } });
    fireEvent.change(screen.getByLabelText("批次 1 占比"), { target: { value: "0.6" } });
    fireEvent.click(screen.getByRole("button", { name: "保存建仓计划" }));
    await waitFor(() => expect(api.saveEntryPlan).toHaveBeenCalledWith(7, {
      winRate: null,
      payoffRatio: null,
      batches: [{ seq: 1, priceLow: 12, priceHigh: 13, quantity: 100, amount: null, ratio: 0.6 }],
    }));
  });

  it("已保存：批次回填 + kellyRatio 只读展示（后端算得）+ 修改后整替保存", async () => {
    api.getEntryPlan.mockResolvedValue(savedPlan);
    render(<EntryPlanPanel projectId={7} onChanged={vi.fn()} />);
    await waitFor(() =>
      expect((screen.getByLabelText("批次 1 占比") as HTMLInputElement).value).toBe("0.6"),
    );
    // kellyRatio 只读（Ruling-15 锚点：0.6/2.0 → 0.4），无对应输入框
    expect(screen.getByText("凯利比例 0.4")).toBeTruthy();
    expect((screen.getByLabelText("胜率") as HTMLInputElement).value).toBe("0.6");
    expect((screen.getByLabelText("赔率") as HTMLInputElement).value).toBe("2");
    // 改批次 1 占比 → Σ实时更新 → 保存携带完整两批
    fireEvent.change(screen.getByLabelText("批次 1 占比"), { target: { value: "0.5" } });
    expect(screen.getByText("Σ占比 0.9")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "保存建仓计划" }));
    await waitFor(() => expect(api.saveEntryPlan).toHaveBeenCalledWith(7, {
      winRate: 0.6,
      payoffRatio: 2,
      batches: [
        { seq: 1, priceLow: 12, priceHigh: 13, quantity: 1000, amount: 12500, ratio: 0.5 },
        { seq: 2, priceLow: 10, priceHigh: 11, quantity: 500, amount: null, ratio: 0.4 },
      ],
    }));
  });

  it("Σratio>1 前端拦截：提示且不发请求", async () => {
    api.getEntryPlan.mockResolvedValue(savedPlan);
    render(<EntryPlanPanel projectId={7} onChanged={vi.fn()} />);
    await waitFor(() =>
      expect((screen.getByLabelText("批次 2 占比") as HTMLInputElement).value).toBe("0.4"),
    );
    fireEvent.change(screen.getByLabelText("批次 2 占比"), { target: { value: "0.5" } });
    expect(screen.getByText("Σ占比 1.1")).toBeTruthy();
    expect(screen.getByText("批次占比合计超过 100%，请调整后再保存")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "保存建仓计划" }));
    expect(api.saveEntryPlan).not.toHaveBeenCalled();
  });

  it("加载异常（非 404）：行内展示错误不吞", async () => {
    api.getEntryPlan.mockRejectedValue(new Error("网络中断"));
    render(<EntryPlanPanel projectId={7} onChanged={vi.fn()} />);
    await waitFor(() => expect(screen.getByText("网络中断")).toBeTruthy());
  });
});

describe("EntryPlanPanel 纪律检查流（D18 页面级）", () => {
  it("批次行发起买入检查：F01 勾选 → 预检 → 逐项确认留痕 → onChanged", async () => {
    const onChanged = vi.fn();
    api.previewCheck.mockResolvedValue(buyPreviewItems);
    render(<EntryPlanPanel projectId={7} onChanged={onChanged} />);
    fireEvent.click(screen.getByRole("button", { name: "批次 1 发起买入检查" }));
    fireEvent.click(screen.getByLabelText("能力圈"));
    fireEvent.click(screen.getByRole("button", { name: "预检" }));
    await waitFor(() => expect(api.previewCheck).toHaveBeenCalledWith(7, {
      checkType: "BUY",
      f01MustItems: { "能力圈": true, "安全边际": false, "估值核对": false, "买入条件": false },
    }));
    // 确认卡渲染三态 + 确认提交（items 为 preview 快照原样回传）
    expect(screen.getByText("命中 1 项")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "逐项确认" }));
    await waitFor(() => expect(api.submitCheck).toHaveBeenCalledWith(7, {
      checkType: "BUY",
      result: "CONFIRMED",
      items: buyPreviewItems,
    }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    // 提交成功后确认卡收起
    await waitFor(() => expect(screen.queryByText("命中 1 项")).toBeNull());
  });

  it("持仓操作区发起卖出检查：证伪核对项「待核对」→ 越过须填写理由", async () => {
    api.previewCheck.mockResolvedValue(sellPreviewItems);
    render(<EntryPlanPanel projectId={7} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "卖出检查" }));
    fireEvent.click(screen.getByRole("button", { name: "预检" }));
    await waitFor(() => expect(api.previewCheck).toHaveBeenCalledWith(7, {
      checkType: "SELL",
      f01MustItems: { "能力圈": false, "安全边际": false, "估值核对": false, "买入条件": false },
    }));
    expect(screen.getByText("待核对")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "越过命中项继续" }));
    const submit = screen.getByRole("button", { name: "提交（越过）" }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("越过理由"), { target: { value: "证伪条件已人工核对" } });
    fireEvent.click(screen.getByRole("button", { name: "提交（越过）" }));
    await waitFor(() => expect(api.submitCheck).toHaveBeenCalledWith(7, {
      checkType: "SELL",
      result: "OVERRIDDEN",
      overrideReason: "证伪条件已人工核对",
      items: sellPreviewItems,
    }));
  });

  it("预检失败：错误行内展示", async () => {
    api.previewCheck.mockRejectedValue(new Error("行情暂不可用"));
    render(<EntryPlanPanel projectId={7} onChanged={vi.fn()} />);
    fireEvent.click(screen.getByRole("button", { name: "卖出检查" }));
    fireEvent.click(screen.getByRole("button", { name: "预检" }));
    await waitFor(() => expect(screen.getByText("行情暂不可用")).toBeTruthy());
  });
});
