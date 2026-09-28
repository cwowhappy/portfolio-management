import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import FalsifierReviewCard from "@/components/research/FalsifierReviewCard";
import ReviewPanel from "@/components/research/ReviewPanel";
import * as researchApi from "@/lib/researchApi";
import type {
  FalsifierHitView,
  FalsifierReviewView,
  ReviewView,
} from "@/lib/researchSchemas";

// 复盘面板（P4-T4）：真实渲染断 DOM，仅 mock api 边界函数（照 FalsifierPanel.test 先例）。
// Review Focus 落点：auto/override 双列渲染、无数据标注原样展示、REVISE 提示出现、
// reflux 确认弹层与失败重试；常量表驱动（三档字段清单切换）。

vi.mock("@/lib/researchApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/researchApi")>("@/lib/researchApi");
  return {
    ...actual,
    getReviews: vi.fn(),
    createReview: vi.fn(),
    updateReview: vi.fn(),
    refluxReview: vi.fn(),
    getChecks: vi.fn(),
    postFalsifierReview: vi.fn(),
  };
});

const api = vi.mocked(researchApi);

/** 快照（Composer 定格 JSON 契约）：periodReturn=「无数据」字符串（Review Focus 1）。 */
const snapshotNoData = {
  periodStart: "2026-09-01",
  periodEnd: "2026-09-30",
  asOf: "2026-09-28",
  priceBasis: "东财收盘",
  navBasis: "组合 totalValue 日序列（analytics 重放）；periodReturn=区间末/首−1",
  navSeries: "无数据",
  periodReturn: "无数据",
  trades: "无数据",
  tradeIds: [],
  attributionWindow: [{ start: "2026-09-01", end: "2026-09-30" }],
};

const snapshotWithReturn = {
  ...snapshotNoData,
  navSeries: [
    { date: "2026-09-01", value: 100000 },
    { date: "2026-09-30", value: 105000 },
  ],
  periodReturn: 0.05,
  tradeIds: [103],
};

const monthlyReview: ReviewView = {
  id: 21,
  projectId: 7,
  tier: "MONTHLY",
  periodStart: "2026-09-01",
  periodEnd: "2026-09-30",
  snapshot: snapshotWithReturn,
  answers: { "4.1": "检查单 4/4 执行", "4.4": "决策对/结果对" },
  narrative: "样本叙述",
  overrides: { "4.2": "手算 +5.1%" },
  tradeIds: [103, 205],
  refluxState: "PENDING",
  wikiEntryId: null,
  createdAt: "2026-09-30T12:00:00Z",
  updatedAt: "2026-09-30T12:00:00Z",
};

const historyHit: FalsifierHitView = {
  id: 99,
  falsifierId: 11,
  kind: "PREDICATE",
  predicate: "PRICE_BELOW",
  threshold: 13.5,
  note: "跌破估值下限",
  eventChecked: false,
  hit: false,
  pending: false,
  skipped: false,
  basis: "收盘价 12.10 < 下限 13.50（东财收盘 2026-09-24）",
  realtime: false,
  hitAt: "2026-09-24T10:43:00Z",
};

const reviewExit: FalsifierReviewView = {
  id: 31,
  projectId: 7,
  hitId: 99,
  conclusion: "EXIT",
  reason: "跌破下限且逻辑破坏",
  suggestStrategyRevise: false,
  createdAt: "2026-09-28T11:00:00Z",
};

/** 打开某条复盘的修正表单（列表行点击）。 */
async function openForm(fixture: ReviewView) {
  api.getReviews.mockResolvedValue([fixture]);
  render(<ReviewPanel projectId={7} />);
  fireEvent.click(await screen.findByRole("button", { name: new RegExp(fixture.periodStart) }));
  // 表单就绪锚点：auto 列文案出现（快照解析完成；正则锚定列文案，不撞字段标签）
  await screen.findByText(/^区间收益 |^无数据$/);
}

beforeEach(() => {
  vi.resetAllMocks();
  api.getReviews.mockResolvedValue([]);
  api.getChecks.mockResolvedValue([]);
  api.createReview.mockResolvedValue(monthlyReview);
  api.updateReview.mockResolvedValue(monthlyReview);
  api.refluxReview.mockResolvedValue(monthlyReview);
  api.postFalsifierReview.mockResolvedValue(reviewExit);
});

afterEach(cleanup);

describe("ReviewPanel 复盘列表与三档新建", () => {
  it("历史列表行（档位+区间+回流徽标）+ 三档新建 POST 载荷后展开季档表单（4.6 追加字段）", async () => {
    api.getReviews.mockResolvedValue([monthlyReview]);
    api.createReview.mockResolvedValue({
      ...monthlyReview,
      id: 22,
      tier: "QUARTERLY",
      periodStart: "2026-07-01",
      periodEnd: "2026-09-30",
    });
    render(<ReviewPanel projectId={7} />);
    expect(
      await screen.findByRole("button", { name: /月度复盘 2026-09-01 ~ 2026-09-30/ }),
    ).toBeTruthy();
    expect(screen.getByText("待回流")).toBeTruthy();

    fireEvent.change(screen.getByLabelText("复盘档位"), { target: { value: "QUARTERLY" } });
    fireEvent.change(screen.getByLabelText("复盘起始日"), { target: { value: "2026-07-01" } });
    fireEvent.change(screen.getByLabelText("复盘截止日"), { target: { value: "2026-09-30" } });
    fireEvent.click(screen.getByRole("button", { name: "新建复盘" }));
    await waitFor(() =>
      expect(api.createReview).toHaveBeenCalledWith(7, {
        tier: "QUARTERLY",
        periodStart: "2026-07-01",
        periodEnd: "2026-09-30",
      }),
    );
    // 季档 = 月档 4.1-4.5 + 追加 4.6-4.9（常量表驱动）
    expect(await screen.findByLabelText("论点全量重检（论据链逐条重验）")).toBeTruthy();
  });

  it("起止缺失/区间倒置：行内拦截不发请求", async () => {
    render(<ReviewPanel projectId={7} />);
    fireEvent.click(screen.getByRole("button", { name: "新建复盘" }));
    expect(screen.getByText("复盘起止日期不能为空")).toBeTruthy();
    expect(api.createReview).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("复盘起始日"), { target: { value: "2026-09-30" } });
    fireEvent.change(screen.getByLabelText("复盘截止日"), { target: { value: "2026-09-01" } });
    fireEvent.click(screen.getByRole("button", { name: "新建复盘" }));
    expect(screen.getByText("复盘起点不能晚于终点")).toBeTruthy();
    expect(api.createReview).not.toHaveBeenCalled();
  });

  it("加载失败行内展示；刷新重拉", async () => {
    api.getReviews.mockRejectedValueOnce(new Error("网络中断"));
    render(<ReviewPanel projectId={7} />);
    await waitFor(() => expect(screen.getByText("网络中断")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "刷新" }));
    await waitFor(() => expect(api.getReviews).toHaveBeenCalledTimes(2));
  });
});

describe("ReviewForm 快照对照（auto/override 双列）", () => {
  it("auto 灰显只读（数值转百分比 + 口径 tooltip）+ override 预填可编辑 + 普通字段预填 answers", async () => {
    await openForm(monthlyReview);
    // auto 列：0.05 → 百分比文案；口径 tooltip 含快照定格标注
    expect(screen.getByText("区间收益 5.00%")).toBeTruthy();
    const caliber = screen.getByTitle(/净值口径/);
    expect(caliber.title).toContain("东财收盘");
    expect(caliber.title).toContain("2026-09-28");
    // override 列预填自 overrides JSONB，可编辑
    const overrideInput = screen.getByLabelText(
      "结果质量：区间收益（自动带入）",
    ) as HTMLInputElement;
    expect(overrideInput.value).toBe("手算 +5.1%");
    fireEvent.change(overrideInput, { target: { value: "手算 +6.0%" } });
    expect(overrideInput.value).toBe("手算 +6.0%");
    // 普通字段预填自 answers JSONB（textarea / select 两形态）
    expect(
      (screen.getByLabelText("决策质量：检查单执行与论点新证据") as HTMLTextAreaElement).value,
    ).toBe("检查单 4/4 执行");
    expect(
      (screen.getByLabelText("归因：决策/结果四象限") as HTMLSelectElement).value,
    ).toBe("决策对/结果对");
  });

  it("无数据标注原样展示：快照 periodReturn=「无数据」不以空白/0 兜底（Review Focus 1）", async () => {
    await openForm({ ...monthlyReview, snapshot: snapshotNoData });
    expect(screen.getByText("无数据")).toBeTruthy();
  });

  it("4.3 纪律遵守度旁显「本期越过 N 次」（GET checks 按复盘区间过滤统计）", async () => {
    api.getChecks.mockResolvedValue([
      {
        id: 1, checkType: "BUY", items: [], result: "OVERRIDDEN",
        overrideReason: "估值偏离可接受", createdAt: "2026-09-10T02:00:00Z",
      },
      {
        id: 2, checkType: "SELL", items: [], result: "CONFIRMED",
        overrideReason: null, createdAt: "2026-09-15T02:00:00Z",
      },
      {
        id: 3, checkType: "ADD", items: [], result: "OVERRIDDEN",
        overrideReason: "窗外", createdAt: "2026-08-30T02:00:00Z",
      },
    ]);
    await openForm(monthlyReview);
    expect(await screen.findByText("本期越过 1 次")).toBeTruthy();
  });

  it("修正保存整替：answers/overrides 分桶 + narrative + tradeIds（chip 增删）", async () => {
    api.updateReview.mockResolvedValue({ ...monthlyReview, updatedAt: "2026-09-30T13:00:00Z" });
    await openForm(monthlyReview);
    // chip 删 205、加 301
    fireEvent.click(screen.getByRole("button", { name: "移除交易 205" }));
    fireEvent.change(screen.getByLabelText("添加交易 ID"), { target: { value: "301" } });
    fireEvent.click(screen.getByRole("button", { name: "添加" }));
    expect(screen.getByText("#301")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("复盘叙述"), { target: { value: "重写叙述" } });
    fireEvent.click(screen.getByRole("button", { name: "保存修正" }));
    await waitFor(() =>
      expect(api.updateReview).toHaveBeenCalledWith(7, 21, {
        answers: { "4.1": "检查单 4/4 执行", "4.4": "决策对/结果对" },
        overrides: { "4.2": "手算 +5.1%" },
        narrative: "重写叙述",
        tradeIds: [103, 301],
      }),
    );
  });

  it("非法交易 ID：行内拦截不落 chip", async () => {
    await openForm(monthlyReview);
    fireEvent.change(screen.getByLabelText("添加交易 ID"), { target: { value: "abc" } });
    fireEvent.click(screen.getByRole("button", { name: "添加" }));
    expect(screen.getByText("交易 ID 须为正整数")).toBeTruthy();
    expect(screen.queryByText("#abc")).toBeNull();
  });
});

describe("RefluxButton 回流确认（F16 确认后入库）", () => {
  it("确认弹层「将写入知识库 RESEARCH_NOTE」：未确认不发请求；成功显示 wikiEntryId", async () => {
    api.refluxReview.mockResolvedValue({
      ...monthlyReview,
      refluxState: "REFLOWN",
      wikiEntryId: 66,
      updatedAt: "2026-09-30T14:00:00Z",
    });
    await openForm(monthlyReview);
    fireEvent.click(screen.getByRole("button", { name: "回流知识库" }));
    expect(screen.getByText(/将写入知识库 RESEARCH_NOTE/)).toBeTruthy();
    expect(api.refluxReview).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "确认回流" }));
    await waitFor(() => expect(api.refluxReview).toHaveBeenCalledWith(7, 21));
    expect(await screen.findByText("已回流 · wiki #66")).toBeTruthy();
  });

  it("回流失败（502 降级文案）：错误展示且确认层保留可重试", async () => {
    api.refluxReview
      .mockRejectedValueOnce(new Error("知识库写入失败，复盘已保留，请稍后重试回流"))
      .mockResolvedValueOnce({
        ...monthlyReview,
        refluxState: "REFLOWN",
        wikiEntryId: 67,
        updatedAt: "2026-09-30T14:30:00Z",
      });
    await openForm(monthlyReview);
    fireEvent.click(screen.getByRole("button", { name: "回流知识库" }));
    fireEvent.click(screen.getByRole("button", { name: "确认回流" }));
    expect(await screen.findByText("知识库写入失败，复盘已保留，请稍后重试回流")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "确认回流" }));
    await waitFor(() => expect(api.refluxReview).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("已回流 · wiki #67")).toBeTruthy();
  });

  it("已回流（REFLOWN 幂等终态）：直接显示 wikiEntryId，无回流按钮", async () => {
    await openForm({ ...monthlyReview, refluxState: "REFLOWN", wikiEntryId: 88 });
    expect(await screen.findByText("已回流 · wiki #88")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "回流知识库" })).toBeNull();
  });
});

describe("FalsifierReviewCard 证伪评审（F15）", () => {
  it("四结论单选 + 理由必填 + 关联命中行：提交载荷逐字对齐（hitId/conclusion/reason）", async () => {
    render(<FalsifierReviewCard projectId={7} hits={[historyHit]} onSubmitted={vi.fn()} />);
    fireEvent.click(screen.getByLabelText("退出"));
    fireEvent.change(screen.getByLabelText("评审理由"), { target: { value: "跌破下限且逻辑破坏" } });
    fireEvent.change(screen.getByLabelText("关联命中行"), { target: { value: "99" } });
    fireEvent.click(screen.getByRole("button", { name: "提交评审" }));
    await waitFor(() =>
      expect(api.postFalsifierReview).toHaveBeenCalledWith(7, {
        hitId: 99,
        conclusion: "EXIT",
        reason: "跌破下限且逻辑破坏",
      }),
    );
  });

  it("理由未填：提交禁用不发请求（域 422 前端前置拦截）", () => {
    render(<FalsifierReviewCard projectId={7} hits={[historyHit]} onSubmitted={vi.fn()} />);
    fireEvent.click(screen.getByLabelText("维持"));
    const submit = screen.getByRole("button", { name: "提交评审" }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(api.postFalsifierReview).not.toHaveBeenCalled();
  });

  it("不关联命中行：body 不带 hitId 键", async () => {
    render(<FalsifierReviewCard projectId={7} hits={[historyHit]} onSubmitted={vi.fn()} />);
    fireEvent.click(screen.getByLabelText("维持"));
    fireEvent.change(screen.getByLabelText("评审理由"), { target: { value: "逻辑未破坏" } });
    fireEvent.click(screen.getByRole("button", { name: "提交评审" }));
    await waitFor(() =>
      expect(api.postFalsifierReview).toHaveBeenCalledWith(7, {
        conclusion: "HOLD",
        reason: "逻辑未破坏",
      }),
    );
  });

  it("REVISE 响应 suggestStrategyRevise：出现「去修订策略」链接，锚到策略修订按钮（Review Focus 3）", async () => {
    api.postFalsifierReview.mockResolvedValue({
      ...reviewExit,
      id: 32,
      hitId: null,
      conclusion: "REVISE",
      reason: "持有逻辑已变化",
      suggestStrategyRevise: true,
    });
    const onSubmitted = vi.fn();
    render(<FalsifierReviewCard projectId={7} hits={[]} onSubmitted={onSubmitted} />);
    fireEvent.click(screen.getByLabelText("修订策略"));
    fireEvent.change(screen.getByLabelText("评审理由"), { target: { value: "持有逻辑已变化" } });
    fireEvent.click(screen.getByRole("button", { name: "提交评审" }));
    const link = await screen.findByRole("link", { name: "去修订策略" });
    expect(link.getAttribute("href")).toBe("#strategy-revise");
    await waitFor(() => expect(onSubmitted).toHaveBeenCalled());
  });

  it("提交失败：错误文案展示（不吞）", async () => {
    api.postFalsifierReview.mockRejectedValueOnce(new Error("评审理由不能为空"));
    render(<FalsifierReviewCard projectId={7} hits={[]} onSubmitted={vi.fn()} />);
    fireEvent.click(screen.getByLabelText("减仓"));
    fireEvent.change(screen.getByLabelText("评审理由"), { target: { value: "仓位过重" } });
    fireEvent.click(screen.getByRole("button", { name: "提交评审" }));
    await waitFor(() => expect(screen.getByText("评审理由不能为空")).toBeTruthy());
  });
});
