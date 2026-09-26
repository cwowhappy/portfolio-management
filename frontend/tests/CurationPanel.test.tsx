import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CurationPanel from "@/components/industry/unlisted/CurationPanel";
import UnlistedPanel from "@/components/industry/unlisted/UnlistedPanel";
import { useAuth } from "@/lib/auth";
import type { FundingEvent, UnlistedCompany, UnlistedOverview } from "@/lib/types";

const { saveMock, deleteCompanyMock, importCompaniesMock, importEventsMock, pushMock,
  companiesTemplateMock, eventsTemplateMock } = vi.hoisted(() => ({
  saveMock: vi.fn(), deleteCompanyMock: vi.fn(),
  importCompaniesMock: vi.fn(), importEventsMock: vi.fn(), pushMock: vi.fn(),
  companiesTemplateMock: vi.fn(() => "/api/industry-curation/companies/import/template"),
  eventsTemplateMock: vi.fn(() => "/api/industry-curation/funding-events/import/template"),
}));
vi.mock("@/lib/industryCurationApi", () => ({
  saveUnlistedCompany: saveMock,
  deleteUnlistedCompany: deleteCompanyMock,
  deleteFundingEvent: vi.fn(),
  importUnlistedCompanies: importCompaniesMock,
  importFundingEvents: importEventsMock,
  companiesTemplateHref: companiesTemplateMock,
  fundingEventsTemplateHref: eventsTemplateMock,
}));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock }) }));

const { overviewMock, fetchCompaniesMock, fetchEventsMock } = vi.hoisted(() => ({
  overviewMock: vi.fn(), fetchCompaniesMock: vi.fn(), fetchEventsMock: vi.fn(),
}));
vi.mock("@/lib/industryUnlistedApi", () => ({
  fetchUnlistedOverview: overviewMock,
  fetchUnlistedCompanies: fetchCompaniesMock,
  fetchFundingEvents: fetchEventsMock,
}));
vi.mock("@/lib/industryApi", () => ({ fetchIndustryStocks: vi.fn().mockResolvedValue([]) }));

// LandscapeChart 真实挂载会触 echarts.init（jsdom 无 canvas/ResizeObserver）——置壳隔离
// （LandscapeChart 自身行为在 LandscapeChart.test.tsx 覆盖）
vi.mock("@/components/industry/unlisted/LandscapeChart", () => ({
  default: () => <div data-testid="landscape-chart" />,
}));

vi.mock("@/lib/auth", () => ({ useAuth: vi.fn() }));

const userStub = { id: 1, username: "u", role: "USER", status: "APPROVED", enabled: true } as NonNullable<ReturnType<typeof useAuth>["user"]>;

afterEach(() => {
  cleanup();
  saveMock.mockReset(); deleteCompanyMock.mockReset();
  importCompaniesMock.mockReset(); importEventsMock.mockReset(); pushMock.mockReset();
  overviewMock.mockReset(); fetchCompaniesMock.mockReset(); fetchEventsMock.mockReset();
  vi.clearAllMocks();
});

describe("CurationPanel（登录门控）", () => {
  it("未登录点编辑/导入 → /login 带 redirect，不触发入口回调", () => {
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
    const onAdd = vi.fn();
    const onImport = vi.fn();

    render(<CurationPanel industryCode="801080" onAdd={onAdd} onImport={onImport} />);

    fireEvent.click(screen.getByTestId("curation-edit-open"));
    expect(pushMock).toHaveBeenCalledWith("/login?redirect=/industry/801080");
    fireEvent.click(screen.getByTestId("curation-import-open"));
    expect(pushMock).toHaveBeenLastCalledWith("/login?redirect=/industry/801080");
    expect(onAdd).not.toHaveBeenCalled();
    expect(onImport).not.toHaveBeenCalled();
  });
});

describe("UnlistedPanel 登录态策展（集成）", () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    overviewMock.mockResolvedValue({
      listedCount: 1, listedMarketCapYi: 100, curatedCount: 1, fundingEvents12m: 0,
      roundDistribution: [], coverageNote: "x",
    } satisfies UnlistedOverview);
    fetchCompaniesMock.mockResolvedValue([
      { id: 9, industryCode: "801080", companyName: "既有策展行", segment: null,
        latestRound: "C", latestRoundLabel: "C轮", lastFundingDate: null, totalFundingYi: null,
        summary: null, sourceNote: null, updatedAt: "2026-09-26T00:00:00Z" },
    ] satisfies UnlistedCompany[]);
    fetchEventsMock.mockResolvedValue([] satisfies FundingEvent[]);
  });

  it("未登录不渲染 CurationPanel；登录渲染", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
    const { rerender } = render(<UnlistedPanel industryCode="801080" industryName="电子" />);
    await screen.findByTestId("unlisted-companies-table"); // 数据就位后再断言登录态挂载
    expect(screen.queryByTestId("curation-panel")).toBeNull();

    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    rerender(<UnlistedPanel industryCode="801080" industryName="电子" />);
    expect(screen.getByTestId("curation-panel")).toBeTruthy();
  });

  it("新增策展企业：编辑入口开对话框 → 保存 POST → 刷新重拉", async () => {
    saveMock.mockResolvedValue(null);
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);
    await screen.findByTestId("unlisted-companies-table");

    fireEvent.click(screen.getByTestId("curation-edit-open"));
    fireEvent.change(screen.getByLabelText("企业名称（必填）"), { target: { value: "新策展企业" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(saveMock).toHaveBeenCalled());
    const cmd = saveMock.mock.calls[0][0];
    expect(cmd.id).toBeUndefined();
    expect(cmd.companyName).toBe("新策展企业");
    expect(cmd.industryCode).toBe("801080");
    // onChanged：三读端点重拉
    await waitFor(() => expect(fetchCompaniesMock.mock.calls.length).toBeGreaterThanOrEqual(2));
    expect(screen.queryByTestId("unlisted-company-dialog")).toBeNull();
  });

  it("表单校验：空企业名行内报错且不调保存", async () => {
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);
    await screen.findByTestId("curation-panel");

    fireEvent.click(screen.getByTestId("curation-edit-open"));
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(await screen.findByText("企业名称必填")).toBeTruthy();
    expect(saveMock).not.toHaveBeenCalled();
  });

  it("行编辑复用对话框（预填 + 带 id PUT）与行删除（删除后重拉）", async () => {
    saveMock.mockResolvedValue(null);
    deleteCompanyMock.mockResolvedValue(undefined);
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);
    await screen.findByText("既有策展行");
    // 「编辑」入口按钮与行内编辑按钮同名——行操作限定在名单表内定位
    const table = screen.getByTestId("unlisted-companies-table");

    fireEvent.click(within(table).getByRole("button", { name: "编辑" }));
    expect((screen.getByLabelText("企业名称（必填）") as HTMLInputElement).value).toBe("既有策展行");
    expect((screen.getByLabelText("最新轮次") as HTMLSelectElement).value).toBe("C");
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(saveMock.mock.calls[0][0].id).toBe(9));

    fireEvent.click(within(screen.getByTestId("unlisted-companies-table")).getByRole("button", { name: "删除" }));
    await waitFor(() => expect(deleteCompanyMock).toHaveBeenCalledWith(9));
    await waitFor(() => expect(fetchCompaniesMock.mock.calls.length).toBeGreaterThanOrEqual(2));
  });

  it("批量导入三态：成功双计数文案 + 刷新；行级错误表含行号；目标切换换模板", async () => {
    importCompaniesMock.mockResolvedValue({ insertedCount: 3, updatedCount: 2, rowErrors: [] });
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);
    await screen.findByTestId("curation-panel");

    fireEvent.click(screen.getByTestId("curation-import-open"));
    const dialog = screen.getByTestId("curation-import-dialog");
    // 默认策展企业模板
    expect(dialog.textContent).toContain("unlisted-companies-template.csv");
    const file = new File(["industry_code\n"], "companies.csv", { type: "text/csv" });
    fireEvent.change(screen.getByLabelText("CSV 文件"), { target: { files: [file] } });
    fireEvent.click(screen.getByRole("button", { name: "导入" }));

    expect(await screen.findByText("新增 3 条，更新 2 条")).toBeTruthy();
    await waitFor(() => expect(fetchCompaniesMock.mock.calls.length).toBeGreaterThanOrEqual(2));

    // 行级错误态：切融资事件目标 + 错误 CSV
    importEventsMock.mockResolvedValue({
      insertedCount: 0, updatedCount: 0,
      rowErrors: [{ row: 2, reason: "轮次无效: X轮" }],
    });
    fireEvent.click(screen.getByRole("button", { name: "再导一次" }));
    fireEvent.click(screen.getByLabelText("融资事件"));
    expect(screen.getByTestId("curation-import-dialog").textContent).toContain("industry-funding-events-template.csv");
    fireEvent.change(screen.getByLabelText("CSV 文件"), {
      target: { files: [new File(["event_date\n"], "events.csv")] },
    });
    fireEvent.click(screen.getByRole("button", { name: "导入" }));

    const errors = await screen.findByTestId("curation-import-row-errors");
    expect(errors.textContent).toContain("2");
    expect(errors.textContent).toContain("轮次无效: X轮");
  });

  it("文件级错误（非 2xx）行内文案", async () => {
    importCompaniesMock.mockRejectedValue(new Error("文件为空"));
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);
    await screen.findByTestId("curation-panel");

    fireEvent.click(screen.getByTestId("curation-import-open"));
    fireEvent.change(screen.getByLabelText("CSV 文件"), {
      target: { files: [new File(["x"], "a.csv")] },
    });
    fireEvent.click(screen.getByRole("button", { name: "导入" }));

    expect(await screen.findByText("文件为空")).toBeTruthy();
  });
});
