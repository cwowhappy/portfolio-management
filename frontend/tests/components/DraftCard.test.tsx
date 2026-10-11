import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import DraftCard from "@/components/chat/DraftCard";
import { listProjects, saveStrategyDraft } from "@/lib/researchApi";
import type { ProjectView, StrategyView } from "@/lib/researchSchemas";

// DraftCard 的纯展示半边无外部依赖（真实渲染断 DOM 文本）；MS-32 F4 保存流半边经
// ProjectPickerDialog 消费 researchApi——按仓内先例（UnlistedCompanyDialog）mock api 层，
// fixture 取服务层真实产出形态（ProjectView / StrategyView 全字段，见 tests/lib/researchApi.test.ts）。
vi.mock("@/lib/researchApi", () => ({
  listProjects: vi.fn(),
  saveStrategyDraft: vi.fn(),
}));

const mockedListProjects = vi.mocked(listProjects);
const mockedSave = vi.mocked(saveStrategyDraft);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

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

// 服务层真实产出形态的 ACTIVE 项目对（industryCode 可空 / 阶段各异）；显式标 view 类型
// 防 TS 把 status/currentStage 枚举字面量拓宽成 string
const activeProjects: ProjectView[] = [
  { id: 7, stockCode: "600519", stockName: "贵州茅台", industryCode: "BK0477", title: "茅台重启研究",
    currentStage: "NEW_ANALYSIS", status: "ACTIVE", intelligenceAlertEnabled: true,
    createdAt: "2026-09-28T08:00:00Z", updatedAt: "2026-09-28T08:00:00Z" },
  { id: 9, stockCode: "000858", stockName: "五粮液", industryCode: null, title: "五粮液估值修复",
    currentStage: "STRATEGY", status: "ACTIVE", intelligenceAlertEnabled: true,
    createdAt: "2026-10-01T08:00:00Z", updatedAt: "2026-10-01T08:00:00Z" },
];

const strategyViewJson: StrategyView = {
  id: 3, state: "DRAFT",
  thesis: "高端白酒需求刚性", valuationLow: 12.5, valuationHigh: 18,
  positionPlan: "首仓 10%，跌破估值下限不加仓", buyConditions: "PE 低于 20 且放量",
  riskNotes: null, finalizedAt: null, updatedAt: "2026-10-11T08:00:00Z",
};

/** 打开 picker 的公共前置：聚焦触发按钮（浏览器点击即聚焦，jsdom 需显式补齐）→ 点保存 */
const openPicker = () => {
  const trigger = screen.getByRole("button", { name: "保存到项目" });
  trigger.focus();
  fireEvent.click(trigger);
  return trigger;
};

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

// ===== MS-32 F4（SOP v2 轻量）：策略草稿「保存到项目」直存——picker 选择 → PUT strategy =====
describe("DraftCard 保存到项目（MS-32 F4）", () => {
  it("strategy：点保存 → 打开项目选择浮层，拉 ACTIVE 项目并渲染 代码/名称/标题", async () => {
    mockedListProjects.mockResolvedValue(activeProjects);
    render(<DraftCard raw={strategyRaw} />);
    openPicker();

    const dialog = await waitFor(() => screen.getByTestId("project-picker-dialog"));
    expect(dialog.getAttribute("role")).toBe("dialog");
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    expect(mockedListProjects).toHaveBeenCalledWith({ status: "ACTIVE" });
    expect(mockedListProjects).toHaveBeenCalledTimes(1);
    // 项目行：code/名称 + 标题
    expect(screen.getByText("600519 贵州茅台")).toBeTruthy();
    expect(screen.getByText("茅台重启研究")).toBeTruthy();
    expect(screen.getByText("000858 五粮液")).toBeTruthy();
    expect(screen.getByText("五粮液估值修复")).toBeTruthy();
    expect(screen.getByRole("button", { name: "取消" })).toBeTruthy();
  });

  it("关键词过滤：命中代码/名称/标题收窄列表，无命中出「无匹配项目」", async () => {
    mockedListProjects.mockResolvedValue(activeProjects);
    render(<DraftCard raw={strategyRaw} />);
    openPicker();
    await waitFor(() => screen.getByText("600519 贵州茅台"));

    fireEvent.change(screen.getByLabelText("搜索项目"), { target: { value: "五粮液" } });
    expect(screen.queryByText("600519 贵州茅台")).toBeNull();
    expect(screen.getByText("000858 五粮液")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("搜索项目"), { target: { value: "不存在的标的" } });
    expect(screen.getByText("无匹配项目")).toBeTruthy();
  });

  it("选中项目 → saveStrategyDraft(id, 六字段整组 body) → 成功态 + 「查看项目」深链", async () => {
    mockedListProjects.mockResolvedValue(activeProjects);
    mockedSave.mockResolvedValue(strategyViewJson);
    render(<DraftCard raw={strategyRaw} />);
    openPicker();
    await waitFor(() => screen.getByText("600519 贵州茅台"));

    fireEvent.click(screen.getByTestId("project-picker-row-7"));
    await waitFor(() => expect(screen.getByText("已保存到研究项目")).toBeTruthy());
    // body 转换：直存六字段（缺省 null），证伪条件项按卡片同款「·」串联换行拼接为 riskNotes
    expect(mockedSave).toHaveBeenCalledWith(7, {
      thesis: "高端白酒需求刚性",
      valuationLow: 12.5,
      valuationHigh: 18,
      positionPlan: "首仓 10%，跌破估值下限不加仓",
      buyConditions: "PE 低于 20 且放量",
      riskNotes: "PRICE_BREAK_BELOW · 跌破价格 · 阈值 12.5 · 跌破估值下限\nEVENT · 食品安全事件",
    });
    const link = screen.getByRole("link", { name: "查看项目" });
    expect(link.getAttribute("href")).toBe("/research/7");
  });

  it("保存失败：行内错误条可见、列表保留，再选可重试成功", async () => {
    mockedListProjects.mockResolvedValue(activeProjects);
    mockedSave.mockRejectedValueOnce(new Error("策略已定稿，请先修订"));
    mockedSave.mockResolvedValueOnce(strategyViewJson);
    render(<DraftCard raw={strategyRaw} />);
    openPicker();
    await waitFor(() => screen.getByText("600519 贵州茅台"));

    fireEvent.click(screen.getByTestId("project-picker-row-7"));
    await waitFor(() => expect(screen.getByText("策略已定稿，请先修订")).toBeTruthy());
    // 失败后浮层与列表保留 → 重试路径可用
    expect(screen.getByTestId("project-picker-dialog")).toBeTruthy();
    fireEvent.click(screen.getByTestId("project-picker-row-7"));
    await waitFor(() => expect(screen.getByText("已保存到研究项目")).toBeTruthy());
    expect(mockedSave).toHaveBeenCalledTimes(2);
  });

  it("保存在途 Escape 不关浮层；失败后浮层仍在（与取消按钮同口径门禁）", async () => {
    mockedListProjects.mockResolvedValue(activeProjects);
    let rejectSave!: (e: Error) => void;
    mockedSave.mockImplementationOnce(
      () => new Promise((_resolve, reject) => { rejectSave = reject; }),
    );
    render(<DraftCard raw={strategyRaw} />);
    openPicker();
    await waitFor(() => screen.getByText("600519 贵州茅台"));

    fireEvent.click(screen.getByTestId("project-picker-row-7"));
    await waitFor(() => expect(screen.getByText("保存中…")).toBeTruthy());
    const dialog = screen.getByTestId("project-picker-dialog");
    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.getByTestId("project-picker-dialog")).toBeTruthy(); // 在途不关

    rejectSave(new Error("请求失败"));
    await waitFor(() => expect(screen.getByText("请求失败")).toBeTruthy());
    expect(screen.getByTestId("project-picker-dialog")).toBeTruthy();
  });

  it("Escape 关闭浮层并返还焦点给「保存到项目」按钮；取消按钮同效（useDialogA11y 契约）", async () => {
    mockedListProjects.mockResolvedValue(activeProjects);
    render(<DraftCard raw={strategyRaw} />);
    const trigger = openPicker();
    const dialog = await waitFor(() => screen.getByTestId("project-picker-dialog"));
    // 初始焦点入浮层：首个可聚焦元素（搜索输入框）
    expect(document.activeElement).toBe(screen.getByLabelText("搜索项目"));

    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByTestId("project-picker-dialog")).toBeNull();
    expect(document.activeElement).toBe(trigger);

    // 取消按钮路径：重开 → 点取消 → 关闭 + 焦点返还
    trigger.focus();
    fireEvent.click(trigger);
    await waitFor(() => screen.getByTestId("project-picker-dialog"));
    fireEvent.click(screen.getByRole("button", { name: "取消" }));
    expect(screen.queryByTestId("project-picker-dialog")).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it("项目列表加载失败：错误条 + 「重试加载」恢复列表", async () => {
    mockedListProjects.mockRejectedValueOnce(new Error("请求失败"));
    mockedListProjects.mockResolvedValueOnce(activeProjects);
    render(<DraftCard raw={strategyRaw} />);
    openPicker();

    await waitFor(() => expect(screen.getByText("请求失败")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "重试加载" }));
    await waitFor(() => expect(screen.getByText("600519 贵州茅台")).toBeTruthy());
    expect(mockedListProjects).toHaveBeenCalledTimes(2);
  });

  it("无 ACTIVE 项目：空态文案 + 「前往研究页立项」链接", async () => {
    mockedListProjects.mockResolvedValue([]);
    render(<DraftCard raw={strategyRaw} />);
    openPicker();

    await waitFor(() => expect(screen.getByText("暂无进行中的研究项目")).toBeTruthy());
    const link = screen.getByRole("link", { name: "前往研究页立项" });
    expect(link.getAttribute("href")).toBe("/research");
  });

  it("非 strategy（分析/建仓/复盘）：不出现保存按钮，出引导文案", () => {
    render(<DraftCard raw={JSON.stringify({
      specVersion: 1, stage: "POSITION",
      batches: [{ priceLow: 12.5, quantity: 100 }],
    })} />);
    expect(
      screen.getByText("分析/建仓/复盘类草稿请到研究项目页对应面板操作，或对项目发起 AI 研报"),
    ).toBeTruthy();
    expect(screen.queryByRole("button", { name: "保存到项目" })).toBeNull();

    cleanup();
    render(<DraftCard raw={JSON.stringify({ specVersion: 1, stage: "NEW_ANALYSIS", symbol: "600519" })} />);
    expect(
      screen.getByText("分析/建仓/复盘类草稿请到研究项目页对应面板操作，或对项目发起 AI 研报"),
    ).toBeTruthy();
  });
});
