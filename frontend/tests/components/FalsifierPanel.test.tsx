import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import FalsifierPanel from "@/components/research/FalsifierPanel";
import * as researchApi from "@/lib/researchApi";
import type { FalsifierHitView, FalsifierReviewView } from "@/lib/researchSchemas";

// 证伪命中合并视图（D21）：真实渲染断 DOM，仅 mock getHits/评审两端点。
// 两条语义裁定（T4 审查产出）在此验证：
// 1. EVENT 条目 hit 恒 false——展示按 basis（已确认事件/待人工勾选），不读 hit（Ruling-18）；
// 2. 历史行 hit=false 自述误导——按 realtime=false 标志给「历史命中」标签，不读 hit。
// P4 评审入口接通：发起评审卡 + 提交后命中行结论标签（P3-T7 deferred 留痕可见）。

vi.mock("@/lib/researchApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/researchApi")>("@/lib/researchApi");
  return { ...actual, getHits: vi.fn(), getFalsifierReviews: vi.fn(), postFalsifierReview: vi.fn() };
});

const api = vi.mocked(researchApi);

const predicateHit: FalsifierHitView = {
  id: null, falsifierId: 11, kind: "PREDICATE", predicate: "PRICE_BELOW", threshold: 13.5,
  note: "跌破估值下限", eventChecked: false, hit: true, pending: false, skipped: false,
  basis: "收盘价 12.34 < 下限 13.50（东财收盘及估值 2026-09-26）", realtime: true, hitAt: null,
};

const predicatePass: FalsifierHitView = {
  id: null, falsifierId: 13, kind: "PREDICATE", predicate: "PE_ABOVE", threshold: 30,
  note: null, eventChecked: false, hit: false, pending: false, skipped: false,
  basis: "PE 25.1 ≤ 上限 30（东财收盘及估值 2026-09-26）", realtime: true, hitAt: null,
};

const eventChecked: FalsifierHitView = {
  id: null, falsifierId: 12, kind: "EVENT", predicate: null, threshold: null,
  note: "食品安全事件", eventChecked: true, hit: false, pending: false, skipped: false,
  basis: "已确认事件", realtime: true, hitAt: null,
};

const eventPending: FalsifierHitView = {
  ...eventChecked, falsifierId: 14, note: "核心高管变动", eventChecked: false, pending: true, basis: "待人工勾选",
};

const historyRow: FalsifierHitView = {
  id: 99, falsifierId: 11, kind: "PREDICATE", predicate: "PRICE_BELOW", threshold: 13.5,
  note: "跌破估值下限", eventChecked: false, hit: false, pending: false, skipped: false,
  basis: "收盘价 12.10 < 下限 13.50（东财收盘及估值 2026-09-24）",
  realtime: false, hitAt: "2026-09-24T10:43:00Z",
};

/** 条件已整替删除的历史行：现态字段全 null，仅保 basis/时间（后端 historyView 契约）。 */
const historyDeleted: FalsifierHitView = {
  id: 100, falsifierId: 55, kind: null, predicate: null, threshold: null, note: null,
  eventChecked: false, hit: false, pending: false, skipped: false,
  basis: "收盘价 11.00 < 下限 12.00（东财收盘 2026-09-22）",
  realtime: false, hitAt: "2026-09-22T10:43:00Z",
};

beforeEach(() => {
  vi.resetAllMocks();
  api.getHits.mockResolvedValue([]);
  api.getFalsifierReviews.mockResolvedValue([]);
  api.postFalsifierReview.mockResolvedValue({
    id: 31, projectId: 7, hitId: 99, conclusion: "EXIT", reason: "逻辑破坏",
    suggestStrategyRevise: false, createdAt: "2026-09-28T11:00:00Z",
  } satisfies FalsifierReviewView);
});

afterEach(cleanup);

describe("FalsifierPanel 证伪命中合并视图", () => {
  it("实时 PREDICATE：命中/未命中徽标 + basis 可解释文案 + 谓词标签与说明", async () => {
    api.getHits.mockResolvedValue([predicateHit, predicatePass]);
    render(<FalsifierPanel projectId={7} />);
    expect(await screen.findByText("已命中")).toBeTruthy();
    expect(screen.getByText("未命中")).toBeTruthy();
    expect(screen.getByText("收盘价 12.34 < 下限 13.50（东财收盘及估值 2026-09-26）")).toBeTruthy();
    expect(screen.getByText("PE 25.1 ≤ 上限 30（东财收盘及估值 2026-09-26）")).toBeTruthy();
    expect(screen.getByText("价格跌破 13.5")).toBeTruthy();
    expect(screen.getByText("PE 高于 30")).toBeTruthy();
    expect(screen.getByText("跌破估值下限")).toBeTruthy();
  });

  it("EVENT 条目按 basis 分组（Ruling-18）：已确认事件/待人工勾选，不渲染命中语义", async () => {
    api.getHits.mockResolvedValue([eventChecked, eventPending]);
    render(<FalsifierPanel projectId={7} />);
    expect(await screen.findByText("已确认事件")).toBeTruthy();
    expect(screen.getByText("待人工勾选")).toBeTruthy();
    // hit 恒 false 的 EVENT 条目不得出现命中/未命中徽标
    expect(screen.queryByText("已命中")).toBeNull();
    expect(screen.queryByText("未命中")).toBeNull();
    expect(screen.getByText("食品安全事件")).toBeTruthy();
    expect(screen.getByText("核心高管变动")).toBeTruthy();
  });

  it("历史命中行（realtime=false）：给「历史命中」标签与日期，不读 hit 字段", async () => {
    api.getHits.mockResolvedValue([historyRow, historyDeleted]);
    render(<FalsifierPanel projectId={7} />);
    expect(await screen.findAllByText("历史命中")).toHaveLength(2);
    expect(screen.getByText("2026-09-24")).toBeTruthy();
    expect(screen.getByText("2026-09-22")).toBeTruthy();
    // 已删除条件的历史行仅保 basis（现态 null 不炸渲染）
    expect(screen.getByText("已删除条件")).toBeTruthy();
    expect(screen.getByText("收盘价 11.00 < 下限 12.00（东财收盘 2026-09-22）")).toBeTruthy();
  });

  it("「发起证伪评审」入口接通（P4）：点击展开评审卡（四结论单选），再点收起", async () => {
    api.getHits.mockResolvedValue([]);
    render(<FalsifierPanel projectId={7} />);
    const entry = await screen.findByRole("button", { name: "发起证伪评审" });
    expect((entry as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(entry);
    expect(screen.getByLabelText("维持")).toBeTruthy();
    expect(screen.getByLabelText("修订策略")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "收起评审" }));
    expect(screen.queryByLabelText("维持")).toBeNull();
  });

  it("提交评审：命中行旁显评审结论标签（P3-T7 留痕可见）+ 评审留痕列表前插", async () => {
    api.getHits.mockResolvedValue([historyRow]);
    render(<FalsifierPanel projectId={7} />);
    await screen.findByText("历史命中");
    fireEvent.click(screen.getByRole("button", { name: "发起证伪评审" }));
    fireEvent.click(screen.getByLabelText("退出"));
    fireEvent.change(screen.getByLabelText("评审理由"), { target: { value: "逻辑破坏" } });
    fireEvent.change(screen.getByLabelText("关联命中行"), { target: { value: "99" } });
    fireEvent.click(screen.getByRole("button", { name: "提交评审" }));
    await waitFor(() =>
      expect(api.postFalsifierReview).toHaveBeenCalledWith(7, {
        hitId: 99, conclusion: "EXIT", reason: "逻辑破坏",
      }),
    );
    // 「退出」出现于评审卡单选标签与命中行结论标签（title=证伪评审结论）
    expect((await screen.findAllByText("退出")).length).toBeGreaterThanOrEqual(2);
    expect(screen.getByTitle("证伪评审结论").textContent).toBe("退出");
    // 评审留痕列表（提交回包前插）
    expect(screen.getByText("逻辑破坏")).toBeTruthy();
    expect(screen.queryByText("暂无评审留痕。")).toBeNull();
  });

  it("评审留痕列表：GET 回显既有条目（createdAt 倒序由后端保证）", async () => {
    api.getHits.mockResolvedValue([]);
    api.getFalsifierReviews.mockResolvedValue([
      { id: 30, projectId: 7, hitId: null, conclusion: "HOLD", reason: "逻辑未破坏",
        suggestStrategyRevise: false, createdAt: "2026-09-27T09:00:00Z" },
    ]);
    render(<FalsifierPanel projectId={7} />);
    expect(await screen.findByText("逻辑未破坏")).toBeTruthy();
    expect(screen.getByText("维持")).toBeTruthy();
  });

  it("加载失败行内展示；刷新重拉", async () => {
    api.getHits.mockRejectedValueOnce(new Error("行情暂不可用"));
    render(<FalsifierPanel projectId={7} />);
    await waitFor(() => expect(screen.getByText("行情暂不可用")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "刷新" }));
    await waitFor(() => expect(api.getHits).toHaveBeenCalledTimes(2));
  });
});
