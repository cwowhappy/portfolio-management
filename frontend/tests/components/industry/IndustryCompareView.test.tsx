import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { cleanup, render, screen, fireEvent, within } from "@testing-library/react";
import IndustryCompareView from "@/components/industry/IndustryCompareView";
import ResearchNoteDialog from "@/components/wiki/ResearchNoteDialog";
import type { IndustryBoardItem } from "@/lib/types";
import type { AuthUser } from "@/lib/auth";

// vitest globals 关闭时 RTL 自动清理不生效，须显式 cleanup（照 IndustryBoardTable.test.tsx 惯例）
afterEach(cleanup);

// EChart mock：序列化 option 落到 data-option，纯 DOM 断言图表数据源（照 IndustryBar/AnalyticsBoard 先例）
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; height?: number; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

// ResearchNoteDialog mock：记录 props（industryCode/industryName 预填断言）
vi.mock("@/components/wiki/ResearchNoteDialog", () => ({
  default: vi.fn((p: { industryCode: string; industryName: string }) => (
    <div data-testid="research-note-dialog-mock" data-code={p.industryCode} />
  )),
}));

// 首两个为手造全字段/全 null 样本（覆盖对比表取值分支），补 29 个填充项凑 31 行业（对齐真实榜单规模）
const NAMED: IndustryBoardItem[] = [
  { industryCode: "801780", industryName: "银行", pe: 5.5, pb: 0.8, roe: 12, dividendYield: 4,
    pePercentile: 40, pbPercentile: 30, prosperity: "UP",
    prosperityInputs: { roeDeltaMedian: 1, revenueYoyMedian: 10, sampleSize: 42 } },
  { industryCode: "801010", industryName: "农林牧渔", pe: 20, pb: 2, roe: null, dividendYield: null,
    pePercentile: null, pbPercentile: null, prosperity: null, prosperityInputs: null },
];
const FILLER: IndustryBoardItem[] = Array.from({ length: 29 }, (_, i) => ({
  industryCode: `8011${String(i).padStart(2, "0")}`,
  industryName: `行业${String(i).padStart(2, "0")}`,
  pe: 10 + i, pb: 1 + i * 0.1, roe: 8 + i, dividendYield: 1 + i * 0.1,
  pePercentile: 10 + i * 2, pbPercentile: 20 + i * 2, prosperity: "FLAT",
  prosperityInputs: { roeDeltaMedian: 0, revenueYoyMedian: 5, sampleSize: 10 + i },
}));
const ITEMS: IndustryBoardItem[] = [...NAMED, ...FILLER];

const userStub = { id: 1, username: "u", role: "USER", status: "APPROVED", enabled: true } as AuthUser;

function renderView(overrides: Partial<Parameters<typeof IndustryCompareView>[0]> = {}) {
  const props = {
    items: ITEMS,
    watchedCodes: new Set<string>(),
    onToggleWatch: vi.fn(),
    user: null,
    ...overrides,
  };
  render(<IndustryCompareView {...props} />);
  return props;
}

const chartOption = () =>
  JSON.parse(screen.getByTestId("industry-compare-chart").getAttribute("data-option")!) as {
    xAxis: { data: string[] };
    series: { type: string; name: string; data: (number | null)[] }[];
  };

describe("IndustryCompareView 多选列表", () => {
  it("渲染全部 31 项 checkbox + ⭐（点击调 onToggleWatch），搜索框按行业名过滤", () => {
    const onToggleWatch = vi.fn();
    renderView({ onToggleWatch });

    expect(screen.getAllByRole("checkbox").length).toBe(31);
    const search = screen.getByPlaceholderText("搜索行业");
    expect(search).toBeTruthy();

    // ⭐ 复用关注回调（未关注 → ☆，照 IndustryBoardTable aria-label 口径）
    fireEvent.click(screen.getByRole("button", { name: "关注 801780" }));
    expect(onToggleWatch).toHaveBeenCalledWith("801780");

    fireEvent.change(search, { target: { value: "银" } });
    expect(screen.getAllByRole("checkbox").length).toBe(1);
    expect(screen.getByRole("checkbox", { name: "银行" })).toBeTruthy();
    expect(screen.queryByRole("checkbox", { name: "农林牧渔" })).toBeNull();

    // 清空搜索恢复全量
    fireEvent.change(search, { target: { value: "" } });
    expect(screen.getAllByRole("checkbox").length).toBe(31);
  });

  it("关注集默认勾选：watchedCodes 交集项 checked，其余不勾", () => {
    renderView({ watchedCodes: new Set(["801780"]) });
    expect((screen.getByRole("checkbox", { name: "银行" }) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByRole("checkbox", { name: "农林牧渔" }) as HTMLInputElement).checked).toBe(false);
    expect((screen.getByRole("checkbox", { name: "行业00" }) as HTMLInputElement).checked).toBe(false);
  });
});

describe("IndustryCompareView 对比表", () => {
  it("勾选 ≥2 行渲染并排对比表：行=选中行业、列=七指标 + 景气，null 显示「—」", () => {
    renderView();
    fireEvent.click(screen.getByRole("checkbox", { name: "银行" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "农林牧渔" }));

    const table = screen.getByTestId("industry-compare-table");
    const rows = within(table).getAllByRole("row");
    expect(rows.length).toBe(3); // 表头 + 2 个选中行业

    const header = rows[0];
    for (const label of ["行业", "PE", "PB", "ROE", "股息率", "PE 5y分位", "PB 5y分位", "景气"]) {
      expect(header.textContent).toContain(label);
    }

    const bankRow = within(table).getByText("银行").closest("tr")!;
    expect(within(bankRow).getByText("5.5")).toBeTruthy();   // PE
    expect(within(bankRow).getByText("0.8")).toBeTruthy();   // PB
    expect(within(bankRow).getByText("12")).toBeTruthy();    // ROE
    expect(within(bankRow).getByText("4")).toBeTruthy();     // 股息率
    expect(within(bankRow).getByText("40.0%")).toBeTruthy(); // PE 分位
    expect(within(bankRow).getByText("30.0%")).toBeTruthy(); // PB 分位
    expect(within(bankRow).getByText("↑")).toBeTruthy();     // 景气 上行

    const agriRow = within(table).getByText("农林牧渔").closest("tr")!;
    expect(within(agriRow).getByText("20")).toBeTruthy();
    expect(within(agriRow).getAllByText("—").length).toBe(5); // ROE/股息率/两分位/景气 全 null

    // 引导文案在 ≥2 时不再出现
    expect(screen.queryByText("勾选至少两个行业开始对比")).toBeNull();
  });

  it("分位列/景气列口径提示照 IndustryBoardTable 先例", () => {
    renderView();
    fireEvent.click(screen.getByRole("checkbox", { name: "银行" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "农林牧渔" }));

    const table = screen.getByTestId("industry-compare-table");
    expect(within(table).getAllByTitle(/当前申万 2021 分类成分回溯重算/).length).toBe(2);
    expect(within(table).getByTitle(/ROEΔ中位数 1.*样本 42/)).toBeTruthy();
  });

  it("勾选 <2 显示引导文案且不渲染对比表/图；全部取消不崩", () => {
    renderView();
    // 初始 0 选：引导文案在，表/图不在
    expect(screen.getByText("勾选至少两个行业开始对比")).toBeTruthy();
    expect(screen.queryByTestId("industry-compare-table")).toBeNull();
    expect(screen.queryByTestId("industry-compare-chart")).toBeNull();

    // 勾 1 个仍引导
    fireEvent.click(screen.getByRole("checkbox", { name: "银行" }));
    expect(screen.getByText("勾选至少两个行业开始对比")).toBeTruthy();
    expect(screen.queryByTestId("industry-compare-table")).toBeNull();

    // 全部取消回到 0 选不崩
    fireEvent.click(screen.getByRole("checkbox", { name: "银行" }));
    expect(screen.getByText("勾选至少两个行业开始对比")).toBeTruthy();
    expect(screen.queryByTestId("industry-compare-table")).toBeNull();
  });
});

describe("IndustryCompareView 对比图", () => {
  it("默认 PE 柱状图：x 轴=选中行业名、单系列=指标值；指标按钮组切换换数据源", () => {
    renderView();
    fireEvent.click(screen.getByRole("checkbox", { name: "银行" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "农林牧渔" }));

    let opt = chartOption();
    expect(opt.xAxis.data).toEqual(["银行", "农林牧渔"]);
    expect(opt.series.length).toBe(1);
    expect(opt.series[0].type).toBe("bar");
    expect(opt.series[0].name).toBe("PE");
    expect(opt.series[0].data).toEqual([5.5, 20]);

    const pbBtn = screen.getByRole("button", { name: "PB" });
    expect(pbBtn.getAttribute("aria-pressed")).toBe("false");
    fireEvent.click(pbBtn);
    expect(pbBtn.getAttribute("aria-pressed")).toBe("true");

    opt = chartOption();
    expect(opt.series[0].name).toBe("PB");
    expect(opt.series[0].data).toEqual([0.8, 2]);

    fireEvent.click(screen.getByRole("button", { name: "股息率" }));
    expect(chartOption().series[0].data).toEqual([4, null]);

    fireEvent.click(screen.getByRole("button", { name: "PE分位" }));
    expect(chartOption().series[0].data).toEqual([40, null]);

    fireEvent.click(screen.getByRole("button", { name: "PB分位" }));
    expect(chartOption().series[0].data).toEqual([30, null]);
  });
});

describe("IndustryCompareView 研究笔记入口", () => {
  beforeEach(() => {
    vi.mocked(ResearchNoteDialog).mockClear();
  });

  it("登录 + 有勾选：ResearchNoteDialog 收首个勾选行业（items 序），旁注归属", () => {
    renderView({ user: userStub, watchedCodes: new Set(["801780"]) }); // 银行默认勾选
    fireEvent.click(screen.getByRole("checkbox", { name: "农林牧渔" })); // 第二勾选

    expect(screen.getByTestId("research-note-dialog-mock")).toBeTruthy();
    // React 函数组件调用带第二 context 实参，断言落在首参 props 上
    const lastProps = vi.mocked(ResearchNoteDialog).mock.calls.at(-1)![0];
    expect(lastProps).toMatchObject({ industryCode: "801780", industryName: "银行" });
    expect(screen.getByText("笔记归属：银行")).toBeTruthy();
  });

  it("登录 + 无勾选：不挂 Dialog，显示引导小字", () => {
    renderView({ user: userStub });
    expect(screen.queryByTestId("research-note-dialog-mock")).toBeNull();
    expect(vi.mocked(ResearchNoteDialog)).not.toHaveBeenCalled();
    expect(screen.getByText("勾选行业后可保存研究结论")).toBeTruthy();
  });

  it("未登录不渲染笔记入口（照 IndustryDrilldown {user && …} 先例）", () => {
    renderView({ user: null, watchedCodes: new Set(["801780"]) });
    fireEvent.click(screen.getByRole("checkbox", { name: "农林牧渔" }));
    expect(screen.queryByTestId("research-note-dialog-mock")).toBeNull();
    expect(vi.mocked(ResearchNoteDialog)).not.toHaveBeenCalled();
    expect(screen.queryByText(/笔记归属：/)).toBeNull();
  });
});
