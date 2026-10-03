import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import IntelligenceBoard from "@/components/intelligence/IntelligenceBoard";
import PaginationBar from "@/components/intelligence/PaginationBar";
import * as intelligenceApi from "@/lib/intelligenceApi";
import type {
  AnnouncementItem,
  AnnouncementPage,
  BriefDetail,
  BriefItem,
  BriefPage,
  MacroCalendar,
  NewsItem,
  NewsPage,
  PolicyItem,
  PolicyPage,
} from "@/lib/intelligenceApi";

// 深链参数可变 mock：vi.mock 工厂提升到文件顶部，闭包懒读模块级 queryString
//（useSearchParams 实际调用发生在渲染期，届时变量已初始化）。
let queryString = "";
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(queryString),
}));

vi.mock("@/lib/intelligenceApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/intelligenceApi")>(
    "@/lib/intelligenceApi",
  );
  return {
    ...actual,
    fetchNews: vi.fn(),
    fetchAnnouncements: vi.fn(),
    fetchPolicies: vi.fn(),
    fetchBriefs: vi.fn(),
    fetchBriefDetail: vi.fn(),
    fetchMacroCalendar: vi.fn(),
  };
});

const api = vi.mocked(intelligenceApi);

const PAGE_SIZE = 20;

const news = (over: Partial<NewsItem> = {}): NewsItem => ({
  title: "新闻标题",
  summary: "新闻摘要",
  direction: null,
  importance: null,
  keyNumbers: [],
  stockCodes: [],
  url: "https://example.com/news",
  publishedAt: "2026-09-30T08:00:00Z",
  ...over,
});
const newsPage = (items: NewsItem[], total = items.length, page = 1): NewsPage => ({
  items,
  total,
  page,
  pageSize: PAGE_SIZE,
});

const ann = (over: Partial<AnnouncementItem> = {}): AnnouncementItem => ({
  title: "公告标题",
  stockCode: "600519",
  stockName: "贵州茅台",
  annTypes: [],
  annTypeSource: null,
  metrics: null,
  pdfUrl: "https://example.com/ann.pdf",
  publishedAt: "2026-09-30T08:00:00Z",
  ...over,
});
const annPage = (items: AnnouncementItem[]): AnnouncementPage => ({
  items,
  total: items.length,
  page: 1,
  pageSize: PAGE_SIZE,
});

const policy = (over: Partial<PolicyItem> = {}): PolicyItem => ({
  title: "政策标题",
  direction: "NEUTRAL",
  strength: "MEDIUM",
  areas: [],
  summary: "政策摘要",
  confidence: "HIGH",
  isPolicy: true,
  url: "https://example.com/policy",
  publishedAt: "2026-09-29T08:00:00Z",
  ...over,
});
const policyPage = (items: PolicyItem[]): PolicyPage => ({
  items,
  total: items.length,
  page: 1,
  pageSize: PAGE_SIZE,
});

const brief = (over: Partial<BriefItem> = {}): BriefItem => ({
  tradeDate: "2026-09-30",
  status: "GENERATED",
  topStocks: ["600519"],
  model: "deepseek-chat",
  generatedAt: "2026-09-30T16:00:00Z",
  ...over,
});
const briefPage = (items: BriefItem[]): BriefPage => ({
  items,
  total: items.length,
  page: 1,
  pageSize: PAGE_SIZE,
});
const briefDetail = (over: Partial<BriefDetail> = {}): BriefDetail => ({
  tradeDate: "2026-09-30",
  contentMd: "# 每日简报\n\n今日要点：市场平稳。",
  topStocks: ["600519"],
  status: "GENERATED",
  failReason: null,
  model: "deepseek-chat",
  generatedAt: "2026-09-30T16:00:00Z",
  ...over,
});

const calendar: MacroCalendar = [
  {
    indicator: "CPI",
    expectedDate: "2026-10-09",
    frequency: "月度",
    sourceSite: "国家统计局",
    updatedAt: "2026-10-01T02:00:00Z",
  },
];

beforeEach(() => {
  vi.resetAllMocks();
  api.fetchNews.mockResolvedValue(newsPage([]));
  api.fetchAnnouncements.mockResolvedValue(annPage([]));
  api.fetchPolicies.mockResolvedValue(policyPage([]));
  api.fetchBriefs.mockResolvedValue(briefPage([]));
  api.fetchBriefDetail.mockResolvedValue(briefDetail());
  api.fetchMacroCalendar.mockResolvedValue([]);
  queryString = "";
});

afterEach(() => {
  cleanup();
});

describe("IntelligenceBoard", () => {
  it("默认新闻 tab：渲染列表行（标题链接/方向徽标/重要度/摘要/标的码/时间）并按 page=1、pageSize=20 请求", async () => {
    api.fetchNews.mockResolvedValue(
      newsPage([
        news({
          title: "茅台三季报超预期",
          direction: "BULLISH",
          importance: 80,
          stockCodes: ["600519"],
        }),
      ]),
    );
    render(<IntelligenceBoard />);

    expect(await screen.findByText("茅台三季报超预期")).toBeTruthy();
    expect(screen.getByText("利好")).toBeTruthy();
    expect(screen.getByText("重要度 80")).toBeTruthy();
    expect(screen.getByText("新闻摘要")).toBeTruthy();
    expect(screen.getByText("600519")).toBeTruthy();
    // Asia/Shanghai 折算：2026-09-30T08:00:00Z → 09/30 16:00（zh-CN ICU 实际输出，与 chat 卡同口径）
    expect(screen.getByText("09/30 16:00")).toBeTruthy();
    expect(api.fetchNews).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ page: 1, pageSize: 20 }),
    );
    expect(api.fetchNews).toHaveBeenCalledTimes(1);
  });

  it("顶部统一关键词：回车触发当前 tab 的 q 过滤", async () => {
    render(<IntelligenceBoard />);
    const input = await screen.findByLabelText("关键词检索");
    fireEvent.change(input, { target: { value: "增持" } });
    fireEvent.keyDown(input, { key: "Enter" });
    await waitFor(() =>
      expect(api.fetchNews).toHaveBeenLastCalledWith(expect.objectContaining({ q: "增持" })),
    );
  });

  it("四 tab 切换：各 tab 挂载拉取并渲染各自区块", async () => {
    api.fetchNews.mockResolvedValue(newsPage([news({ title: "新闻A" })]));
    api.fetchAnnouncements.mockResolvedValue(annPage([ann({ title: "公告B" })]));
    api.fetchPolicies.mockResolvedValue(policyPage([policy({ title: "政策C" })]));
    api.fetchBriefs.mockResolvedValue(briefPage([brief({ tradeDate: "2026-09-30" })]));

    render(<IntelligenceBoard />);
    expect(await screen.findByText("新闻A")).toBeTruthy();

    fireEvent.click(screen.getByTestId("intel-tab-announcements"));
    expect(await screen.findByText("公告B")).toBeTruthy();

    fireEvent.click(screen.getByTestId("intel-tab-policies"));
    expect(await screen.findByText("政策C")).toBeTruthy();

    fireEvent.click(screen.getByTestId("intel-tab-briefs"));
    expect(await screen.findByText("2026-09-30")).toBeTruthy();
  });

  it("公告行：类型徽标中文/业绩要点非空字段行/仅重大 checkbox 传 major=true", async () => {
    api.fetchAnnouncements.mockResolvedValue(
      annPage([
        ann({
          title: "贵州茅台三期报告",
          annTypes: ["INCREASE_HOLD"],
          metrics: {
            revenueYi: 1000.5,
            netProfitYi: 500.2,
            netProfitYoyPct: 15.3,
            deductedProfitYi: null,
            grossMarginPct: null,
            dividendDesc: null,
            undisclosed: [],
          },
        }),
      ]),
    );
    render(<IntelligenceBoard />);
    fireEvent.click(screen.getByTestId("intel-tab-announcements"));

    expect(await screen.findByText("贵州茅台三期报告")).toBeTruthy();
    // selector 限定 span：排除「公告类型」下拉 option 的同名文本
    expect(screen.getByText("股东增持", { selector: "span" })).toBeTruthy();
    expect(screen.getByText(/营收 1000\.5 亿/)).toBeTruthy();
    expect(screen.getByText(/净利同比 \+15\.3%/)).toBeTruthy();

    fireEvent.click(screen.getByLabelText("仅重大公告"));
    await waitFor(() =>
      expect(api.fetchAnnouncements).toHaveBeenLastCalledWith(
        expect.objectContaining({ major: true }),
      ),
    );
  });

  it("政策库：方向/力度徽标、领域标签、非政策灰标 + 日历卡（未来 7 天 + 最近维护时间）+ 方向过滤传参", async () => {
    api.fetchPolicies.mockResolvedValue(
      policyPage([
        policy({
          title: "降准落地",
          direction: "EASING",
          strength: "HIGH",
          areas: ["货币"],
          isPolicy: false,
        }),
      ]),
    );
    api.fetchMacroCalendar.mockResolvedValue(calendar);
    render(<IntelligenceBoard />);
    fireEvent.click(screen.getByTestId("intel-tab-policies"));

    expect(await screen.findByText("降准落地")).toBeTruthy();
    // selector 限定 span：排除「政策方向」下拉 option 的同名文本
    expect(screen.getByText("宽松", { selector: "span" })).toBeTruthy();
    expect(screen.getByText("强")).toBeTruthy();
    expect(screen.getByText("货币")).toBeTruthy();
    expect(screen.getByText("非政策类")).toBeTruthy();

    expect(api.fetchMacroCalendar).toHaveBeenCalledWith(7);
    expect(await screen.findByText("CPI")).toBeTruthy();
    expect(screen.getByText(/2026-10-09/)).toBeTruthy();
    expect(screen.getByText(/维护至 10\/01/)).toBeTruthy();

    fireEvent.change(screen.getByLabelText("政策方向"), { target: { value: "TIGHTENING" } });
    await waitFor(() =>
      expect(api.fetchPolicies).toHaveBeenLastCalledWith(
        expect.objectContaining({ direction: "TIGHTENING" }),
      ),
    );
  });

  it("可空字段 null 形态：政策 direction/strength null 不渲染方向/力度徽标，新闻 url null 不出链接（终审 I-2）", async () => {
    api.fetchNews.mockResolvedValue(newsPage([news({ title: "无链接新闻", url: null })]));
    api.fetchPolicies.mockResolvedValue(
      policyPage([
        policy({ title: "取向未判政策", direction: null, strength: null, confidence: null }),
      ]),
    );
    render(<IntelligenceBoard />);

    // 新闻 url null：标题仍在但 anchor 无 href（React 省略属性）
    const el = await screen.findByText("无链接新闻");
    expect(el.closest("a")?.getAttribute("href")).toBeNull();

    fireEvent.click(screen.getByTestId("intel-tab-policies"));
    expect(await screen.findByText("取向未判政策")).toBeTruthy();
    // direction null：三个方向徽标（span 形态，排除下拉 option）均不渲染
    expect(screen.queryByText("宽松", { selector: "span" })).toBeNull();
    expect(screen.queryByText("收紧", { selector: "span" })).toBeNull();
    expect(screen.queryByText("中性", { selector: "span" })).toBeNull();
    // strength null：力度文案不渲染
    expect(screen.queryByText("强")).toBeNull();
    expect(screen.queryByText("中")).toBeNull();
  });

  it("双空态：无过滤 intel-empty（去行情台 CTA）；有过滤 intel-empty-filtered；清除过滤恢复", async () => {
    render(<IntelligenceBoard />);
    expect(await screen.findByTestId("intel-empty")).toBeTruthy();
    const cta = screen.getByRole("link", { name: "去行情台" });
    expect(cta.getAttribute("href")).toBe("/market");

    fireEvent.change(screen.getByLabelText("新闻标的"), { target: { value: "600519" } });
    expect(await screen.findByTestId("intel-empty-filtered")).toBeTruthy();

    fireEvent.click(screen.getByText("清除过滤"));
    await waitFor(() =>
      expect(api.fetchNews).toHaveBeenLastCalledWith(
        expect.objectContaining({ stock: undefined }),
      ),
    );
    expect(await screen.findByTestId("intel-empty")).toBeTruthy();
  });

  it("服务端分页：PaginationBar 总数文案 + 翻页触发 fetch page=2", async () => {
    api.fetchNews.mockResolvedValue(newsPage([news({ title: "第1页新闻" })], 45, 1));
    render(<IntelligenceBoard />);

    expect(await screen.findByText(/共 45 条 · 第 1\/3 页/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "第 2 页" }));
    await waitFor(() =>
      expect(api.fetchNews).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 })),
    );
  });

  it("seq 守卫：两次并发 fetch 只渲染后到的最新响应，过期响应被丢弃", async () => {
    let resolveStale!: (v: NewsPage) => void;
    const staleGate = new Promise<NewsPage>((res) => {
      resolveStale = res;
    });
    api.fetchNews.mockResolvedValueOnce(newsPage([])); // 挂载
    api.fetchNews.mockReturnValueOnce(staleGate); // reload #2（旧，挂起）
    api.fetchNews.mockResolvedValueOnce(newsPage([news({ title: "FRESH" })])); // reload #3（新）

    render(<IntelligenceBoard />);
    await screen.findByTestId("intel-empty");
    expect(api.fetchNews).toHaveBeenCalledTimes(1);

    fireEvent.change(screen.getByLabelText("新闻标的"), { target: { value: "600519" } });
    await waitFor(() => expect(api.fetchNews).toHaveBeenCalledTimes(2));
    fireEvent.change(screen.getByLabelText("新闻标的"), { target: { value: "000001" } });
    await waitFor(() => expect(api.fetchNews).toHaveBeenCalledTimes(3));
    expect(await screen.findByText("FRESH")).toBeTruthy();

    // 放行过期 reload #2：seq 守卫应丢弃，不被覆盖成 STALE
    await act(async () => {
      resolveStale(newsPage([news({ title: "STALE" })]));
    });
    expect(screen.queryByText("STALE")).toBeNull();
    expect(screen.getByText("FRESH")).toBeTruthy();
  });

  it("深链 ?stock=600519&industry=BK0477：进新闻过滤初值并作为请求参数", async () => {
    queryString = "stock=600519&industry=BK0477";
    render(<IntelligenceBoard />);

    await waitFor(() => expect(api.fetchNews).toHaveBeenCalled());
    expect(api.fetchNews).toHaveBeenNthCalledWith(
      1,
      expect.objectContaining({ stock: "600519", industry: "BK0477" }),
    );
    expect((screen.getByLabelText("新闻标的") as HTMLInputElement).value).toBe("600519");
    expect((screen.getByLabelText("新闻行业") as HTMLInputElement).value).toBe("BK0477");
  });

  it("公告未抽取行：annTypes 空回退 annTypeSource，再空回退「其他」；metrics 空不渲染要点行", async () => {
    api.fetchAnnouncements.mockResolvedValue(
      annPage([
        ann({ title: "临时公告行", annTypes: [], annTypeSource: "临时公告" }),
        ann({
          title: "无要点行",
          annTypes: ["OTHER"],
          annTypeSource: null,
          pdfUrl: "https://example.com/other.pdf",
        }),
      ]),
    );
    render(<IntelligenceBoard />);
    fireEvent.click(screen.getByTestId("intel-tab-announcements"));

    expect(await screen.findByText("临时公告行")).toBeTruthy();
    expect(screen.getByText("临时公告", { selector: "span" })).toBeTruthy();
    expect(await screen.findByText("无要点行")).toBeTruthy();
    expect(screen.getByText("其他", { selector: "span" })).toBeTruthy();
    // 两行均无 metrics：不渲染「营收 …」要点片段
    expect(screen.queryByText(/营收/)).toBeNull();
  });

  it("错误态：整块替换 + 重试恢复", async () => {
    api.fetchNews
      .mockRejectedValueOnce(new Error("网络异常"))
      .mockResolvedValue(newsPage([news({ title: "重试后新闻" })]));
    render(<IntelligenceBoard />);

    expect(await screen.findByText(/加载失败：网络异常/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(await screen.findByText("重试后新闻")).toBeTruthy();
  });
});

describe("BriefArchivePanel（经工作台简报 tab）", () => {
  it("点击日期加载详情：markdown 正文渲染", async () => {
    api.fetchBriefs.mockResolvedValue(briefPage([brief()]));
    render(<IntelligenceBoard />);
    fireEvent.click(screen.getByTestId("intel-tab-briefs"));

    fireEvent.click(await screen.findByText("2026-09-30"));
    expect(await screen.findByText("每日简报")).toBeTruthy();
    expect(screen.getByText(/今日要点：市场平稳。/)).toBeTruthy();
  });

  it("详情 404：显示「该交易日无简报档」空态", async () => {
    api.fetchBriefs.mockResolvedValue(briefPage([brief()]));
    api.fetchBriefDetail.mockRejectedValue(new Error("该交易日无简报档"));
    render(<IntelligenceBoard />);
    fireEvent.click(screen.getByTestId("intel-tab-briefs"));

    fireEvent.click(await screen.findByText("2026-09-30"));
    expect(await screen.findByTestId("brief-detail-empty")).toBeTruthy();
    expect(screen.getByText("该交易日无简报档")).toBeTruthy();
  });

  it("日期范围过滤：from/to 传给 fetchBriefs 并归位 page=1", async () => {
    render(<IntelligenceBoard />);
    fireEvent.click(screen.getByTestId("intel-tab-briefs"));
    await screen.findByTestId("intel-empty");

    fireEvent.change(screen.getByLabelText("开始日期"), { target: { value: "2026-09-01" } });
    fireEvent.change(screen.getByLabelText("结束日期"), { target: { value: "2026-09-30" } });
    await waitFor(() =>
      expect(api.fetchBriefs).toHaveBeenLastCalledWith(
        expect.objectContaining({ from: "2026-09-01", to: "2026-09-30", page: 1 }),
      ),
    );
  });

  it("FAILED 简报详情：透出失败原因", async () => {
    api.fetchBriefs.mockResolvedValue(
      briefPage([brief({ status: "FAILED", topStocks: [] })]),
    );
    api.fetchBriefDetail.mockResolvedValue(
      briefDetail({ status: "FAILED", failReason: "抽取失败", contentMd: "" }),
    );
    render(<IntelligenceBoard />);
    fireEvent.click(screen.getByTestId("intel-tab-briefs"));

    fireEvent.click(await screen.findByText("2026-09-30"));
    expect(await screen.findByText(/抽取失败/)).toBeTruthy();
  });
});

describe("PaginationBar（通用分页控件）", () => {
  it("页码窗口 ±2 + 首末页 + 省略号 + 总数文案，点击回调页码", () => {
    const onPageChange = vi.fn();
    render(<PaginationBar page={5} pageSize={20} total={120} onPageChange={onPageChange} />);

    expect(screen.getByText("共 120 条 · 第 5/6 页")).toBeTruthy();
    [1, 3, 4, 5, 6].forEach((p) => {
      expect(screen.getByRole("button", { name: `第 ${p} 页` })).toBeTruthy();
    });
    expect(screen.queryByRole("button", { name: "第 2 页" })).toBeNull();
    expect(screen.getAllByTestId("pagination-ellipsis").length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole("button", { name: "第 6 页" }));
    expect(onPageChange).toHaveBeenCalledWith(6);
  });

  it("单页无省略号；零数据不渲染", () => {
    const { container, rerender } = render(
      <PaginationBar page={1} pageSize={20} total={5} onPageChange={() => {}} />,
    );
    expect(screen.getByRole("button", { name: "第 1 页" })).toBeTruthy();
    expect(screen.queryByTestId("pagination-ellipsis")).toBeNull();

    rerender(<PaginationBar page={1} pageSize={20} total={0} onPageChange={() => {}} />);
    expect(container.querySelector("[data-testid='pagination-bar']")).toBeNull();
  });
});
