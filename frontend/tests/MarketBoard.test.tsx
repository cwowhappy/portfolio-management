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
    { code: "sz399001", name: "深证成指", price: 11000.5, change: -20.3, changePct: -0.18 },
    { code: "sz399006", name: "创业板指", price: 2200.0, change: 0, changePct: 0 },
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

const kline: KlineBar[] = [1, 2, 3, 4, 5, 6].map((i) => ({
  date: "2026-08-" + String(i).padStart(2, "0"),
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
  indicators: [
    {
      reportDate: "2026-06-30",
      eps: 1.2,
      bps: 10,
      totalRevenue: 8.19e10,
      netProfit: 2.3e10,
      weightedRoe: 12.5,
      grossMargin: 50.2,
    },
    {
      reportDate: "2026-03-31",
      eps: 0.6,
      bps: 9.5,
      totalRevenue: 4e10,
      netProfit: null,
      weightedRoe: null,
      grossMargin: null,
    },
  ],
};

const news: NewsItem[] = [
  { title: "新闻一", summary: "摘要一", source: "证券时报", date: "2026-08-18", url: "https://x/1" },
];

beforeEach(() => {
  vi.resetAllMocks();
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
}

describe("MarketBoard", () => {
  it("渲染指数条（涨/跌/平三种颜色类）", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    expect(screen.getByText("深证成指")).toBeTruthy();
    expect(screen.getByText("创业板指")).toBeTruthy();
    expect(screen.getAllByText("3000.12").length).toBeGreaterThan(0);
    const cards = screen.getAllByText(/指数|成指/);
    expect(cards[0].parentElement?.parentElement?.querySelector(".up")).toBeTruthy();
  });

  it("指数接口失败时显示骨架屏", async () => {
    m.fetchOverview.mockRejectedValue(new Error("network"));
    const { container } = render(<MarketBoard />);
    await waitFor(() => expect(container.querySelectorAll(".skeleton").length).toBeGreaterThan(0));
  });

  it("未选择股票时显示引导文案", async () => {
    render(<MarketBoard />);
    await waitFor(() =>
      expect(screen.getByText("搜索一只股票，查看行情、走势、财务与新闻")).toBeTruthy(),
    );
  });

  it("空白搜索词不发起请求", async () => {
    render(<MarketBoard />);
    fireEvent.change(screen.getByPlaceholderText("输入股票名称或代码搜索，如 茅台 / 600519"), {
      target: { value: "   " },
    });
    await waitFor(() => expect(m.searchStocks).not.toHaveBeenCalled());
  });

  it("搜索失败时清空候选", async () => {
    m.searchStocks.mockRejectedValue(new Error("boom"));
    render(<MarketBoard />);
    fireEvent.change(screen.getByPlaceholderText("输入股票名称或代码搜索，如 茅台 / 600519"), {
      target: { value: "茅台" },
    });
    await waitFor(() => expect(m.searchStocks).toHaveBeenCalledWith("茅台"));
    expect(screen.queryByRole("button", { name: /贵州茅台/ })).toBeNull();
  });

  it("选中股票后加载报价、K线、财务与新闻", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("贵州茅台")).toBeTruthy());
    expect(screen.getByText("1680.50")).toBeTruthy();
    expect(screen.getByText("走势 · 前复权")).toBeTruthy();
    expect(screen.getByText("财务指标")).toBeTruthy();
    expect(screen.getByText("2026-06-30")).toBeTruthy();
    expect(screen.getByText("新闻一")).toBeTruthy();
    expect(screen.getByText("54.00 亿")).toBeTruthy();
    expect(m.fetchKline).toHaveBeenCalledWith("600519", "day", 120);
    expect(m.fetchNews).toHaveBeenCalledWith("600519", 8);
  });

  it("加载期间显示骨架屏", async () => {
    let resolveQuote!: (v: Quote) => void;
    m.fetchQuote.mockImplementation(
      () => new Promise<Quote>((r) => (resolveQuote = r)),
    );
    const { container } = render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(container.querySelectorAll(".skeleton").length).toBeGreaterThan(0));
    resolveQuote(quote);
    await waitFor(() => expect(screen.getByText("1680.50")).toBeTruthy());
  });

  it("加载失败显示错误信息", async () => {
    m.fetchQuote.mockRejectedValue(new Error("行情不可用"));
    const { container } = render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("行情不可用")).toBeTruthy());
    // 加载态结束
    expect(container.querySelectorAll(".skeleton").length).toBe(0);
  });

  it("非 Error 异常回退为默认文案", async () => {
    m.fetchQuote.mockRejectedValue("boom");
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("数据加载失败")).toBeTruthy());
  });

  it("切换 K线周期请求对应数据", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("走势 · 前复权")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "周K" }));
    await waitFor(() => expect(m.fetchKline).toHaveBeenCalledWith("600519", "week", 120));
  });

  it("切换周期失败时保留旧图不报错", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("走势 · 前复权")).toBeTruthy());
    m.fetchKline.mockRejectedValue(new Error("boom"));
    fireEvent.click(screen.getByRole("button", { name: "月K" }));
    await waitFor(() => expect(m.fetchKline).toHaveBeenCalledWith("600519", "month", 120));
    expect(screen.getByText("走势 · 前复权")).toBeTruthy();
  });

  it("PE 优先用行情值，缺失时回退财务值；PB 同理", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("贵州茅台")).toBeTruthy());
    // quote.pe 为 null → 回退 financials.pe = 19.95
    expect(screen.getByText("19.95")).toBeTruthy();
    expect(screen.getByText("8.50")).toBeTruthy();
  });

  it("金额超过万亿切换单位（fmtYi 分支）", async () => {
    m.fetchQuote.mockResolvedValue({ ...quote, pe: 20.1, amount: 2e12 });
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("2.00 万亿")).toBeTruthy());
  });

  it("财务指标缺失值显示 —", async () => {
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("贵州茅台")).toBeTruthy());
    // 第二行 indicators 的 netProfit/weightedRoe/grossMargin 均为 null
    expect(screen.getAllByText("—").length).toBeGreaterThan(0);
  });

  it("无新闻时显示占位文案", async () => {
    m.fetchNews.mockResolvedValue([]);
    render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    await selectStock();
    await waitFor(() => expect(screen.getByText("暂无新闻")).toBeTruthy());
  });

  it("卸载时清理定时器不报错", async () => {
    const { unmount } = render(<MarketBoard />);
    await waitFor(() => expect(screen.getByText("上证指数")).toBeTruthy());
    fireEvent.change(screen.getByPlaceholderText("输入股票名称或代码搜索，如 茅台 / 600519"), {
      target: { value: "茅台" },
    });
    unmount();
    expect(m.searchStocks).not.toHaveBeenCalled();
  });
});
