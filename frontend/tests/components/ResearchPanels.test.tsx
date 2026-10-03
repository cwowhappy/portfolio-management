import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ProjectList from "@/components/research/ProjectList";
import StrategyPanel from "@/components/research/StrategyPanel";
import * as researchApi from "@/lib/researchApi";
import type { FalsifierView, ProjectView, StrategyView } from "@/lib/researchSchemas";

// 组件层真实渲染断 DOM；仅 mock api 边界函数（照 EntryEditor.test 先例）——
// 组件与 zod schema/状态机的组合行为不 mock，防「mock 对库内行为全盲」。

vi.mock("@/lib/researchApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/researchApi")>("@/lib/researchApi");
  return {
    ...actual,
    saveStrategyDraft: vi.fn(),
    finalizeStrategy: vi.fn(),
    reviseStrategy: vi.fn(),
    saveFalsifiers: vi.fn(),
  };
});

const api = vi.mocked(researchApi);

const activeProject: ProjectView = {
  id: 7,
  stockCode: "600519",
  stockName: "贵州茅台",
  industryCode: "BK0477",
  title: "茅台重启研究",
  currentStage: "STRATEGY",
  status: "ACTIVE",
  intelligenceAlertEnabled: true,
  createdAt: "2026-09-20T08:00:00Z",
  updatedAt: "2026-09-28T08:00:00Z",
};

const archivedProject: ProjectView = {
  ...activeProject,
  id: 8,
  stockCode: "000568",
  stockName: "泸州老窖",
  title: "老白干研究",
  currentStage: "REVIEW",
  status: "ARCHIVED",
};

const draftStrategy: StrategyView = {
  id: 3,
  state: "DRAFT",
  thesis: "需求刚性",
  valuationLow: 12.5,
  valuationHigh: 18,
  positionPlan: "首仓 10%",
  buyConditions: "PE 低于 20",
  riskNotes: null,
  finalizedAt: null,
  updatedAt: "2026-09-28T08:00:00Z",
};

const finalizedStrategy: StrategyView = {
  ...draftStrategy,
  state: "FINALIZED",
  finalizedAt: "2026-09-28T09:00:00Z",
};

const predicateFalsifier: FalsifierView = {
  id: 11,
  kind: "PREDICATE",
  predicate: "PRICE_BELOW",
  threshold: 12.5,
  eventChecked: false,
  note: "跌破估值下限",
  enabled: true,
};

const eventFalsifier: FalsifierView = {
  id: 12,
  kind: "EVENT",
  predicate: null,
  threshold: null,
  eventChecked: false,
  note: "食品安全事件",
  enabled: true,
};

beforeEach(() => {
  vi.resetAllMocks();
  api.saveStrategyDraft.mockResolvedValue(draftStrategy);
  api.finalizeStrategy.mockResolvedValue(finalizedStrategy);
  api.reviseStrategy.mockResolvedValue(draftStrategy);
  api.saveFalsifiers.mockResolvedValue([]);
});

afterEach(cleanup);

describe("ProjectList", () => {
  it("渲染行：标题链接/标的/阶段/状态，ACTIVE 行有归档按钮、ARCHIVED 行没有", () => {
    const onArchive = vi.fn();
    render(<ProjectList projects={[activeProject, archivedProject]} onArchive={onArchive} />);
    const link = screen.getByRole("link", { name: "茅台重启研究" });
    expect(link.getAttribute("href")).toBe("/research/7");
    expect(screen.getByText("贵州茅台 600519")).toBeTruthy();
    expect(screen.getByText("制定投资策略")).toBeTruthy();
    expect(screen.getByText("研究中")).toBeTruthy();
    expect(screen.getByText("已归档")).toBeTruthy();
    expect(screen.getByRole("button", { name: "归档 茅台重启研究" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "归档 老白干研究" })).toBeNull();
  });

  it("点击归档回调项目 id", () => {
    const onArchive = vi.fn();
    render(<ProjectList projects={[activeProject]} onArchive={onArchive} />);
    fireEvent.click(screen.getByRole("button", { name: "归档 茅台重启研究" }));
    expect(onArchive).toHaveBeenCalledWith(7);
  });
});

describe("StrategyPanel", () => {
  it("无策略文档：提示须先建草稿；「创建草稿」以空六字段暂存建稿", async () => {
    const onChanged = vi.fn();
    render(<StrategyPanel projectId={7} strategy={null} falsifiers={[]} onChanged={onChanged} />);
    expect(screen.getByText(/尚未创建策略草稿/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "创建草稿" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(api.saveStrategyDraft).toHaveBeenCalledWith(7, {
      thesis: null, valuationLow: null, valuationHigh: null,
      positionPlan: null, buyConditions: null, riskNotes: null,
    });
  });

  it("DRAFT：预填六字段可编辑，暂存以数值提交", async () => {
    const onChanged = vi.fn();
    render(<StrategyPanel projectId={7} strategy={draftStrategy} falsifiers={[]} onChanged={onChanged} />);
    const thesis = screen.getByLabelText("投资逻辑") as HTMLInputElement;
    expect(thesis.value).toBe("需求刚性");
    const low = screen.getByLabelText("估值下限") as HTMLInputElement;
    expect(low.value).toBe("12.5");
    fireEvent.change(thesis, { target: { value: "需求刚性，提价在途" } });
    fireEvent.change(screen.getByLabelText("估值上限"), { target: { value: "20" } });
    fireEvent.click(screen.getByRole("button", { name: "暂存草稿" }));
    await waitFor(() => expect(api.saveStrategyDraft).toHaveBeenCalled());
    expect(api.saveStrategyDraft).toHaveBeenCalledWith(7, {
      thesis: "需求刚性，提价在途",
      valuationLow: 12.5,
      valuationHigh: 20,
      positionPlan: "首仓 10%",
      buyConditions: "PE 低于 20",
      riskNotes: null,
    });
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it("定稿前端预校验：估值倒挂拦截，不调 finalize", () => {
    render(<StrategyPanel projectId={7} strategy={draftStrategy} falsifiers={[]} onChanged={vi.fn()} />);
    fireEvent.change(screen.getByLabelText("估值下限"), { target: { value: "20" } });
    fireEvent.change(screen.getByLabelText("估值上限"), { target: { value: "12.5" } });
    fireEvent.click(screen.getByRole("button", { name: "定稿" }));
    expect(screen.getByText("估值下限必须小于上限，且均不能为空")).toBeTruthy();
    expect(api.finalizeStrategy).not.toHaveBeenCalled();
  });

  it("FINALIZED：只读展示（无输入框）+ 修订回草稿", async () => {
    const onChanged = vi.fn();
    render(
      <StrategyPanel projectId={7} strategy={finalizedStrategy} falsifiers={[predicateFalsifier]} onChanged={onChanged} />,
    );
    expect(screen.queryByLabelText("投资逻辑")).toBeNull();
    expect(screen.getByText(/已定稿/)).toBeTruthy();
    expect(screen.getByText("需求刚性")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "修订（回草稿）" }));
    await waitFor(() => expect(api.reviseStrategy).toHaveBeenCalledWith(7));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
  });

  it("证伪条件：已有条目渲染；事件类缺说明被前端拦截；合法整替提交映射项", async () => {
    render(<StrategyPanel projectId={7} strategy={draftStrategy} falsifiers={[predicateFalsifier]} onChanged={vi.fn()} />);
    // 已有条目：谓词选项 + 阈值/说明回填输入框
    expect(screen.getByText(/价格跌破/)).toBeTruthy();
    expect((screen.getByLabelText("阈值") as HTMLInputElement).value).toBe("12.5");
    expect((screen.getByLabelText("说明") as HTMLInputElement).value).toBe("跌破估值下限");

    // 新增事件类条件但不填说明 → 前端拦截
    fireEvent.click(screen.getByRole("button", { name: "添加事件条件" }));
    fireEvent.click(screen.getByRole("button", { name: "保存证伪条件" }));
    expect(screen.getByText("事件类证伪条件必须填写说明")).toBeTruthy();
    expect(api.saveFalsifiers).not.toHaveBeenCalled();

    // 补说明后整替提交：已有谓词项 + 新事件项（新事件未勾选 → eventChecked: false）
    const noteInput = screen.getByLabelText("事件说明") as HTMLInputElement;
    fireEvent.change(noteInput, { target: { value: "食品安全事件" } });
    fireEvent.click(screen.getByRole("button", { name: "保存证伪条件" }));
    await waitFor(() => expect(api.saveFalsifiers).toHaveBeenCalledWith(7, [
      { kind: "PREDICATE", predicate: "PRICE_BELOW", threshold: 12.5, note: "跌破估值下限" },
      { kind: "EVENT", note: "食品安全事件", eventChecked: false },
    ]));
  });

  it("EVENT 勾选：checkbox 随读模型种子化，勾选后 PUT 载荷携 eventChecked=true（PREDICATE 行不渲染勾选）", async () => {
    render(
      <StrategyPanel
        projectId={7}
        strategy={draftStrategy}
        falsifiers={[predicateFalsifier, eventFalsifier]}
        onChanged={vi.fn()}
      />,
    );
    // 勾选框仅 EVENT 行渲染（1 个），随读模型 eventChecked=false 种子化
    const checkbox = screen.getByLabelText("已确认") as HTMLInputElement;
    expect(checkbox.checked).toBe(false);

    fireEvent.click(checkbox);
    expect(checkbox.checked).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "保存证伪条件" }));
    await waitFor(() =>
      expect(api.saveFalsifiers).toHaveBeenCalledWith(7, [
        { kind: "PREDICATE", predicate: "PRICE_BELOW", threshold: 12.5, note: "跌破估值下限" },
        { kind: "EVENT", note: "食品安全事件", eventChecked: true },
      ]));
  });
});
