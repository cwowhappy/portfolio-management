import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import DraftCard from "@/components/chat/DraftCard";

// DraftCard 为纯展示组件（无 echarts/fetch），真实渲染断 DOM 文本——
// 不 mock 任何依赖（e2e 记忆教训：mock 单测对库内行为全盲）。
afterEach(cleanup);

const strategyRaw = JSON.stringify({
  specVersion: 1, stage: "STRATEGY",
  thesis: "高端白酒需求刚性",
  valuationLow: 12.5, valuationHigh: 18,
  positionPlan: "首仓 10%，跌破估值下限不加仓",
  buyConditions: "PE 低于 20 且放量",
  riskItems: [
    { kind: "PRICE_BREAK_BELOW", predicate: "跌破价格", threshold: 12.5, note: "跌破估值下限" },
    { kind: "EVENT", note: "食品安全事件" },
  ],
});

describe("DraftCard（草稿只读卡）", () => {
  it("合法 STRATEGY：stage 中文标题 + 表单化字段 + 保存按钮", () => {
    render(<DraftCard raw={strategyRaw} />);
    expect(screen.getByText("投研草稿 · 策略")).toBeTruthy();
    expect(screen.getByText("投资逻辑")).toBeTruthy();
    expect(screen.getByText("高端白酒需求刚性")).toBeTruthy();
    expect(screen.getByText("12.5~18")).toBeTruthy();
    expect(screen.getByText("首仓 10%，跌破估值下限不加仓")).toBeTruthy();
    expect(screen.getByText("PE 低于 20 且放量")).toBeTruthy();
    // 证伪条件逐条渲染（缺省字段静默跳过）
    expect(screen.getByText("PRICE_BREAK_BELOW · 跌破价格 · 阈值 12.5 · 跌破估值下限")).toBeTruthy();
    expect(screen.getByText("EVENT · 食品安全事件")).toBeTruthy();
    expect(screen.getByRole("button", { name: "保存到项目" })).toBeTruthy();
  });

  it("点击保存 → 提示「研究项目功能即将上线」（P1 no-op，不阻塞、按钮仍在）", () => {
    render(<DraftCard raw={strategyRaw} />);
    fireEvent.click(screen.getByRole("button", { name: "保存到项目" }));
    expect(screen.getByText("研究项目功能即将上线")).toBeTruthy();
    expect(screen.getByRole("button", { name: "保存到项目" })).toBeTruthy();
  });

  it("onSave 接线（P2）：点击保存回调解析后的草稿对象并出成功提示", async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<DraftCard raw={strategyRaw} onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: "保存到项目" }));
    await waitFor(() => expect(screen.getByText("已保存到研究项目")).toBeTruthy());
    expect(onSave).toHaveBeenCalledTimes(1);
    const draft = onSave.mock.calls[0][0];
    expect(draft.stage).toBe("STRATEGY");
    expect(draft.thesis).toBe("高端白酒需求刚性");
  });

  it("onSave 抛错：行内展示错误文案 + 「前往研究页」跳转链接", async () => {
    const onSave = vi.fn().mockRejectedValue(new Error("请先在研究页立项"));
    render(<DraftCard raw={strategyRaw} onSave={onSave} />);
    fireEvent.click(screen.getByRole("button", { name: "保存到项目" }));
    await waitFor(() => expect(screen.getByText("请先在研究页立项")).toBeTruthy());
    const link = screen.getByRole("link", { name: "前往研究页" });
    expect(link.getAttribute("href")).toBe("/research");
    // 失败后按钮保留，可重试
    expect(screen.getByRole("button", { name: "保存到项目" })).toBeTruthy();
  });

  it("合法 NEW_ANALYSIS：标的合并展示 + 清单顿号串联", () => {
    render(<DraftCard raw={JSON.stringify({
      specVersion: 1, stage: "NEW_ANALYSIS",
      symbol: "600519", companyName: "贵州茅台", industry: "白酒",
      checklistDone: ["商业模式已核", "ROE 趋势已看"],
      summary: "白酒龙头，格局稳定",
    })} />);
    expect(screen.getByText("投研草稿 · 新分析")).toBeTruthy();
    expect(screen.getByText("600519 贵州茅台")).toBeTruthy();
    expect(screen.getByText("商业模式已核、ROE 趋势已看")).toBeTruthy();
    expect(screen.getByText("白酒龙头，格局稳定")).toBeTruthy();
  });

  it("合法 POSITION：批次逐条 + 胜率/盈亏比", () => {
    render(<DraftCard raw={JSON.stringify({
      specVersion: 1, stage: "POSITION",
      batches: [
        { priceLow: 12.5, priceHigh: 13, quantity: 100, ratio: 0.1 },
        { priceLow: 11.8, quantity: 200, ratio: 0.2 },
      ],
      winRate: 0.55, payoffRatio: 2.5, note: "分两批建仓",
    })} />);
    expect(screen.getByText("投研草稿 · 建仓计划")).toBeTruthy();
    expect(screen.getByText("第1批 · 12.5~13 · 100 股 · 占比 0.1")).toBeTruthy();
    expect(screen.getByText("第2批 · 11.8 · 200 股 · 占比 0.2")).toBeTruthy();
    expect(screen.getByText("0.55")).toBeTruthy();
    expect(screen.getByText("2.5")).toBeTruthy();
    expect(screen.getByText("分两批建仓")).toBeTruthy();
  });

  it("合法 REVIEW：档期/区间/叙述；全空草稿出兜底文案不白屏", () => {
    render(<DraftCard raw={JSON.stringify({
      specVersion: 1, stage: "REVIEW",
      tier: "MONTHLY", periodStart: "2026-08-01", periodEnd: "2026-08-31",
      narrative: "逻辑未破坏，继续持有",
    })} />);
    expect(screen.getByText("投研草稿 · 复盘")).toBeTruthy();
    expect(screen.getByText("MONTHLY")).toBeTruthy();
    expect(screen.getByText("2026-08-01~2026-08-31")).toBeTruthy();
    expect(screen.getByText("逻辑未破坏，继续持有")).toBeTruthy();

    cleanup();
    render(<DraftCard raw={JSON.stringify({ specVersion: 1, stage: "REVIEW" })} />);
    expect(screen.getByText("草稿暂无内容")).toBeTruthy();
  });

  it("非 JSON raw（参数错误文本透传）→ 降级卡「草稿格式不兼容」，原文折叠可见", () => {
    render(<DraftCard raw="[research_draft] 参数错误：未知 stage: STAGE_X" />);
    expect(screen.getByText(/草稿格式不兼容/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "保存到项目" })).toBeNull();
    fireEvent.click(screen.getByText(/草稿格式不兼容/));
    expect(screen.getByText(/未知 stage: STAGE_X/)).toBeTruthy();
  });

  it("未知 specVersion → 降级卡（literal(1) 锁版本）", () => {
    render(<DraftCard raw={JSON.stringify({ specVersion: 2, stage: "STRATEGY", thesis: "x" })} />);
    expect(screen.getByText(/草稿格式不兼容/)).toBeTruthy();
  });

  it("null 字段（wire 契约破坏）→ 降级卡不白屏", () => {
    render(<DraftCard raw={JSON.stringify({ specVersion: 1, stage: "STRATEGY", thesis: null })} />);
    expect(screen.getByText(/草稿格式不兼容/)).toBeTruthy();
  });
});
