import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import IntelligenceSettingsPage from "@/components/intelligence/IntelligenceSettingsPage";
import * as intelligenceApi from "@/lib/intelligenceApi";
import * as marketApi from "@/lib/api";
import * as portfolioApi from "@/lib/portfolioApi";
import * as valuationApi from "@/lib/valuationApi";
import type { SubscriptionView } from "@/lib/intelligenceApi";

// 情报订阅设置页（P4 Task 6）：三区块——推送总开关（PUT 全量三字段）、关注标的/行业
// （搜索添加 / 从持仓导入去重 / 申万行业下拉）、飞书绑定（未绑定生成码 + mm:ss 倒计时、
// 已绑定绑定时间 + 解绑）。照 intelligence-board.test 的 api mock 模式（importActual 保留
// 类型，函数逐个打桩）；倒计时用 fake timers 驱动（ThreadArea.test 先例）。

vi.mock("@/lib/intelligenceApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/intelligenceApi")>(
    "@/lib/intelligenceApi",
  );
  return {
    ...actual,
    getSubscription: vi.fn(),
    updateSubscription: vi.fn(),
    createBindingCode: vi.fn(),
    unbind: vi.fn(),
    getBindingStatus: vi.fn(),
  };
});

vi.mock("@/lib/api", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api")>("@/lib/api");
  return { ...actual, searchStocks: vi.fn() };
});

vi.mock("@/lib/portfolioApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/portfolioApi")>(
    "@/lib/portfolioApi",
  );
  return { ...actual, fetchPositions: vi.fn() };
});

vi.mock("@/lib/valuationApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/valuationApi")>(
    "@/lib/valuationApi",
  );
  return { ...actual, fetchValuationIndustries: vi.fn() };
});

const api = vi.mocked(intelligenceApi);
const market = vi.mocked(marketApi);
const portfolio = vi.mocked(portfolioApi);
const valuation = vi.mocked(valuationApi);

const sub = (over: Partial<SubscriptionView> = {}): SubscriptionView => ({
  pushEnabled: true,
  industries: ["801010"],
  stocks: [{ code: "600519", name: "贵州茅台" }],
  updatedAt: "2026-10-01T12:00:00Z",
  ...over,
});

const industryOption = (code: string, name: string) => ({
  industryCode: code,
  industryName: name,
  pe: null,
  pb: null,
  roe: null,
  dividendYield: null,
});

beforeEach(() => {
  vi.resetAllMocks();
  api.getSubscription.mockResolvedValue(sub());
  api.updateSubscription.mockResolvedValue(sub());
  api.getBindingStatus.mockResolvedValue({ bound: false, boundAt: null });
  api.createBindingCode.mockResolvedValue({ code: "482913", expiresAt: "2099-01-01T00:00:00Z" });
  api.unbind.mockResolvedValue(undefined);
  market.searchStocks.mockResolvedValue([]);
  portfolio.fetchPositions.mockResolvedValue([]);
  valuation.fetchValuationIndustries.mockResolvedValue([
    industryOption("801010", "农林牧渔"),
    industryOption("801080", "电子"),
  ]);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("IntelligenceSettingsPage", () => {
  it("初始加载：GET 订阅与绑定态渲染开关/标的行业标签，未绑定区展示生成绑定码入口", async () => {
    render(<IntelligenceSettingsPage />);

    // 标签渲染即初始 GET 已落地（checkbox/标签均来自同一批 setState）
    expect(await screen.findByText("600519 贵州茅台")).toBeTruthy();
    expect((screen.getByLabelText("推送总开关") as HTMLInputElement).checked).toBe(true);
    expect(screen.getByText("801010 农林牧渔")).toBeTruthy();
    expect(screen.getByRole("button", { name: "生成绑定码" })).toBeTruthy();
    expect(api.getSubscription).toHaveBeenCalledTimes(1);
    expect(api.getBindingStatus).toHaveBeenCalledTimes(1);
  });

  it("开关保存：PUT 全量三字段（带当前 stocks/industries），成功后绿色提示", async () => {
    api.updateSubscription.mockResolvedValue(sub({ pushEnabled: false }));
    render(<IntelligenceSettingsPage />);
    // 先等初始 GET 落地（标签渲染即 state 已 set，loaded=true，保存按钮可点）
    await screen.findByText("600519 贵州茅台");
    const toggle = screen.getByLabelText("推送总开关");
    fireEvent.click(toggle);
    expect((toggle as HTMLInputElement).checked).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "保存推送设置" }));

    expect(await screen.findByTestId("save-ok")).toBeTruthy();
    expect(api.updateSubscription).toHaveBeenCalledWith({
      pushEnabled: false,
      industries: ["801010"],
      stocks: [{ code: "600519", name: "贵州茅台" }],
    });
    expect((screen.getByLabelText("推送总开关") as HTMLInputElement).checked).toBe(false);
  });

  it("保存失败：顶部红字透出错误消息", async () => {
    api.updateSubscription.mockRejectedValue(new Error("保存失败：会话过期"));
    render(<IntelligenceSettingsPage />);
    await screen.findByText("600519 贵州茅台");

    fireEvent.click(screen.getByRole("button", { name: "保存推送设置" }));

    expect(await screen.findByText(/保存失败：会话过期/)).toBeTruthy();
    expect(screen.queryByTestId("save-ok")).toBeNull();
  });

  it("标的搜索：回车调行情搜索、点选结果加入标签、删除既有标签", async () => {
    market.searchStocks.mockResolvedValue([
      { code: "300750", name: "宁德时代", market: "SZ", marketName: "深圳A股" },
      { code: "600900", name: "长江电力", market: "SH", marketName: "上海A股" },
    ]);
    render(<IntelligenceSettingsPage />);
    const input = await screen.findByLabelText("标的搜索");

    fireEvent.change(input, { target: { value: "宁德" } });
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() => expect(market.searchStocks).toHaveBeenCalledWith("宁德"));

    fireEvent.click(await screen.findByRole("button", { name: "300750 宁德时代" }));
    expect(screen.getByText("300750 宁德时代")).toBeTruthy();
    expect((screen.getByLabelText("标的搜索") as HTMLInputElement).value).toBe("");
    expect(screen.queryByRole("button", { name: "300750 宁德时代" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "删除标的 600519" }));
    expect(screen.queryByText("600519 贵州茅台")).toBeNull();
  });

  it("IME 组合中 Enter 不触发标的搜索（isComposing 守卫）", async () => {
    render(<IntelligenceSettingsPage />);
    const input = await screen.findByLabelText("标的搜索");
    fireEvent.change(input, { target: { value: "宁德" } });
    // IME 组合态 Enter 只应选定候选词：不发起搜索
    const notPrevented = fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    expect(notPrevented).toBe(true);
    expect(market.searchStocks).not.toHaveBeenCalled();
    // 组合结束后正常 Enter 恢复搜索
    fireEvent.keyDown(input, { key: "Enter", isComposing: false });
    await waitFor(() => expect(market.searchStocks).toHaveBeenCalledWith("宁德"));
  });

  it("从持仓导入：批量加入标签并按 code 去重（已有标的不重复）", async () => {
    portfolio.fetchPositions.mockResolvedValue([
      { stockCode: "000001", stockName: "平安银行" },
      { stockCode: "600519", stockName: "贵州茅台" },
    ] as never);
    render(<IntelligenceSettingsPage />);
    await screen.findByText("600519 贵州茅台");

    fireEvent.click(screen.getByRole("button", { name: "从持仓导入" }));

    expect(await screen.findByText("000001 平安银行")).toBeTruthy();
    expect(screen.getAllByText(/600519/).length).toBe(1);
    expect(portfolio.fetchPositions).toHaveBeenCalledTimes(1);
  });

  it("行业下拉：选申万行业加标签，保存关注设置全量提交 industries", async () => {
    render(<IntelligenceSettingsPage />);
    await screen.findByText("801010 农林牧渔");

    fireEvent.change(screen.getByLabelText("关注行业"), { target: { value: "801080" } });
    expect(screen.getByText("801080 电子")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "保存关注设置" }));
    await screen.findByTestId("save-ok");
    expect(api.updateSubscription).toHaveBeenLastCalledWith({
      pushEnabled: true,
      industries: ["801010", "801080"],
      stocks: [{ code: "600519", name: "贵州茅台" }],
    });
  });

  it("行业枚举加载失败降级：标签显示裸码、下拉只剩占位项；name 为 null 的标的只显示码", async () => {
    valuation.fetchValuationIndustries.mockRejectedValue(new Error("行业枚举不可用"));
    api.getSubscription.mockResolvedValue(
      sub({ industries: ["801970"], stocks: [{ code: "000001", name: null }] }),
    );
    render(<IntelligenceSettingsPage />);

    expect(await screen.findByText("801970")).toBeTruthy();
    expect(screen.queryByText(/农林牧渔/)).toBeNull();
    expect(screen.getByText("000001")).toBeTruthy();
    const select = screen.getByLabelText("关注行业") as HTMLSelectElement;
    expect(select.options.length).toBe(1); // 仅「选择行业」占位
  });

  it("绑定码：生成展示 6 位码 + 发码指引 + mm:ss 倒计时，生成新码换码，过期提示", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-10-03T09:00:00Z"));
    api.createBindingCode
      .mockResolvedValueOnce({ code: "482913", expiresAt: "2026-10-03T09:10:00Z" })
      .mockResolvedValueOnce({ code: "999999", expiresAt: "2026-10-03T09:05:00Z" });
    render(<IntelligenceSettingsPage />);
    await act(async () => {});

    fireEvent.click(screen.getByRole("button", { name: "生成绑定码" }));
    await act(async () => {});
    expect(screen.getByTestId("binding-code").textContent).toBe("482913");
    expect(screen.getByText(/在飞书中对机器人发送此码完成绑定/)).toBeTruthy();
    expect(screen.getByTestId("binding-countdown").textContent).toBe("10:00");

    act(() => {
      vi.advanceTimersByTime(5_000);
    });
    expect(screen.getByTestId("binding-countdown").textContent).toBe("09:55");

    fireEvent.click(screen.getByRole("button", { name: "生成新码" }));
    await act(async () => {});
    expect(screen.getByTestId("binding-code").textContent).toBe("999999");
    // 换码时刻 fake 时钟已在 09:00:05：09:05:00 - 09:00:05 = 4 分 55 秒
    expect(screen.getByTestId("binding-countdown").textContent).toBe("04:55");

    act(() => {
      vi.advanceTimersByTime(5 * 60_000);
    });
    expect(screen.getByTestId("binding-countdown").textContent).toBe("00:00");
    expect(screen.getByText(/绑定码已过期/)).toBeTruthy();
  });

  it("已绑定：展示绑定时间 + 解绑确认后回未绑定态", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(true);
    api.getBindingStatus.mockResolvedValue({ bound: true, boundAt: "2026-10-03T08:00:00Z" });
    render(<IntelligenceSettingsPage />);

    expect(await screen.findByText(/绑定时间/)).toBeTruthy();
    // Asia/Shanghai 折算：2026-10-03T08:00:00Z → 2026/10/03 16:00（zh-CN ICU 输出）
    expect(screen.getByText(/2026\/10\/03 16:00/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "生成绑定码" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "解绑" }));
    await waitFor(() => expect(api.unbind).toHaveBeenCalledTimes(1));
    expect(await screen.findByRole("button", { name: "生成绑定码" })).toBeTruthy();
  });

  it("解绑前取消确认：不调 unbind，保持已绑定态", async () => {
    vi.spyOn(window, "confirm").mockReturnValue(false);
    api.getBindingStatus.mockResolvedValue({ bound: true, boundAt: "2026-10-03T08:00:00Z" });
    render(<IntelligenceSettingsPage />);
    await screen.findByText(/绑定时间/);

    fireEvent.click(screen.getByRole("button", { name: "解绑" }));

    expect(api.unbind).not.toHaveBeenCalled();
    expect(screen.getByText(/绑定时间/)).toBeTruthy();
  });

  it("绑定码生成失败：红字透出，不展示码", async () => {
    api.createBindingCode.mockRejectedValue(new Error("生成失败：稍后再试"));
    render(<IntelligenceSettingsPage />);
    await screen.findByRole("button", { name: "生成绑定码" });

    fireEvent.click(screen.getByRole("button", { name: "生成绑定码" }));

    expect(await screen.findByText(/生成失败：稍后再试/)).toBeTruthy();
    expect(screen.queryByTestId("binding-code")).toBeNull();
  });
});
