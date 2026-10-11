import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import MarketBoard from "@/components/market/MarketBoard";
import {
  fetchFinancials,
  fetchKline,
  fetchNews,
  fetchOverview,
  fetchQuote,
  searchStocks,
} from "@/lib/api";
import type { Financials, KlineBar, MarketOverview, NewsItem, Quote, StockHit } from "@/lib/types";

const { searchParamsMock } = vi.hoisted(() => ({ searchParamsMock: vi.fn() }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => searchParamsMock(),
}));

vi.mock("@/lib/api", () => ({
  fetchOverview: vi.fn(),
  fetchQuote: vi.fn(),
  fetchKline: vi.fn(),
  fetchFinancials: vi.fn(),
  fetchNews: vi.fn(),
  searchStocks: vi.fn(),
}));

const m = {
  fetchOverview: vi.mocked(fetchOverview),
  fetchQuote: vi.mocked(fetchQuote),
  fetchKline: vi.mocked(fetchKline),
  fetchFinancials: vi.mocked(fetchFinancials),
  fetchNews: vi.mocked(fetchNews),
  searchStocks: vi.mocked(searchStocks),
};

const overview: MarketOverview = {
  time: "2026-08-18 15:00",
  indices: [
    { code: "sh000001", name: "上证指数", price: 3000.12, change: 10.2, changePct: 0.34 },
  ],
};

const hit: StockHit = { code: "600519", name: "贵州茅台", market: "1", marketName: "沪A" };

const quote: Quote = {
  code: "600519",
  name: "贵州茅台",
  price: 1680.5,
  change: 5.2,
  changePct: 0.31,
  open: 1670,
  high: 1690,
  low: 1665,
  prevClose: 1675.3,
  volume: 3_200_000,
  amount: 5.4e9,
  pe: null,
  pb: 8.5,
  time: "2026-08-18 15:00",
};

const kline: KlineBar[] = [1, 2, 3].map((i) => ({
  date: "2026-08-0" + i,
  open: 10 + i,
  close: 10.5 + i,
  high: 11 + i,
  low: 9.5 + i,
  volume: 1000 * i,
  amount: 10000 * i,
  amplitudePct: 1,
}));

const financials: Financials = {
  code: "600519",
  name: "贵州茅台",
  pe: 19.95,
  pb: 8.5,
  indicators: [],
};

const news: NewsItem[] = [];

beforeEach(() => {
  vi.resetAllMocks();
  searchParamsMock.mockReturnValue(new URLSearchParams());
  m.fetchOverview.mockResolvedValue(overview);
  m.fetchQuote.mockResolvedValue(quote);
  m.fetchKline.mockResolvedValue(kline);
  m.fetchFinancials.mockResolvedValue(financials);
  m.fetchNews.mockResolvedValue(news);
  m.searchStocks.mockResolvedValue([hit]);
});

afterEach(cleanup);

async function selectStock() {
  fireEvent.change(screen.getByPlaceholderText("输入股票名称或代码搜索，如 茅台 / 600519"), {
    target: { value: "茅台" },
  });
  await waitFor(() => expect(screen.getByRole("button", { name: /贵州茅台/ })).toBeTruthy());
  fireEvent.click(screen.getByRole("button", { name: /贵州茅台/ }));
  await waitFor(() => expect(screen.getByText("走势 · 前复权")).toBeTruthy());
}

/** 周期按钮激活态：激活样式含 panel-2 背景（MarketBoard 高亮口径）。 */
function isActive(name: string): boolean {
  return screen.getByRole("button", { name }).className.includes("panel-2");
}

// P3-③：切换周期失败不再「标签已翻数据未换 + 静默」——周期标签回滚到旧值（与保留的旧图一致），
// 并给出可见错误；后续成功切换清除错误；过期失败（已被更新的切换取代）不回滚不报错。
describe("MarketBoard 切换周期失败（P3-③）", () => {
  it("周K 请求失败：周期标签回滚日K + 可见错误提示（已还原语义），旧图保留", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();

    m.fetchKline.mockRejectedValue(new Error("boom"));
    fireEvent.click(screen.getByRole("button", { name: "周K" }));
    await waitFor(() => expect(m.fetchKline).toHaveBeenCalledWith("600519", "week", 120));

    // 标签回滚：日K 重新激活（不再是翻开后数据未换的错位态）
    await waitFor(() => expect(isActive("日K")).toBe(true));
    expect(isActive("周K")).toBe(false);
    // 可见错误（不再静默）：含失败与还原语义
    await waitFor(() => expect(screen.getByText(/K线获取失败/)).toBeTruthy());
    expect(screen.getByText(/已还原/)).toBeTruthy();
    // 旧图（走势区）仍在
    expect(screen.getByText("走势 · 前复权")).toBeTruthy();
  });

  it("失败后的下一次成功切换：错误提示清除、新周期生效", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();

    m.fetchKline.mockRejectedValueOnce(new Error("boom"));
    fireEvent.click(screen.getByRole("button", { name: "周K" }));
    await waitFor(() => expect(screen.getByText(/K线获取失败/)).toBeTruthy());

    m.fetchKline.mockResolvedValueOnce(kline);
    fireEvent.click(screen.getByRole("button", { name: "月K" }));
    await waitFor(() => expect(isActive("月K")).toBe(true));
    await waitFor(() => expect(screen.queryByText(/K线获取失败/)).toBeNull());
  });

  it("过期失败不回滚：周K 失败晚于月K 成功到达时，保持月K 且无错误", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();

    let rejectWeek!: (e: Error) => void;
    m.fetchKline.mockImplementation((_code, p) => {
      if (p === "week") return new Promise<KlineBar[]>((_res, rej) => (rejectWeek = rej));
      return Promise.resolve(kline);
    });
    fireEvent.click(screen.getByRole("button", { name: "周K" }));
    fireEvent.click(screen.getByRole("button", { name: "月K" }));
    await waitFor(() => expect(isActive("月K")).toBe(true));

    // 周K 的失败晚到：seq 守卫应丢弃（不回滚到 day、不报错）
    rejectWeek(new Error("late failure"));
    await waitFor(() => expect(m.fetchKline).toHaveBeenCalledWith("600519", "week", 120));
    await new Promise((r) => setTimeout(r, 0));
    expect(isActive("月K")).toBe(true);
    expect(screen.queryByText(/K线获取失败/)).toBeNull();
  });
});
