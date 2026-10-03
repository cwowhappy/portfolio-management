import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import McpTokenSection from "@/components/admin/McpTokenSection";
import { fetchProviders, type McpProviderItem } from "@/lib/mcpApi";
import { adminApi } from "@/lib/adminApi";

vi.mock("@/lib/mcpApi", () => ({ fetchProviders: vi.fn() }));
vi.mock("@/lib/adminApi", () => ({ adminApi: { setMcpProviderToken: vi.fn() } }));

const providers = vi.mocked(fetchProviders);
const setTok = vi.mocked(adminApi.setMcpProviderToken);

const rows: McpProviderItem[] = [
  { id: 1, code: "mx-ds", name: "妙想数据", authType: "HEADER", authHeader: "X-Api-Key", hasToken: true, domains: [] },
  { id: 2, code: "tushare", name: "Tushare 数据", authType: "BEARER", authHeader: null, hasToken: false, domains: [] },
  { id: 3, code: "free", name: "免鉴权源", authType: "NONE", authHeader: null, hasToken: false, domains: [] },
];

beforeEach(() => {
  vi.clearAllMocks();
  providers.mockResolvedValue(rows);
});

afterEach(() => {
  cleanup();
});

function row(code: string) {
  return within(screen.getByTestId(`mcp-provider-${code}`));
}

describe("MCP Token 管理块（P1-10）", () => {
  it("渲染 provider 行与已设置状态；NONE 鉴权源不需要 token，不显示输入", async () => {
    render(<McpTokenSection />);

    expect(await screen.findByText("妙想数据")).toBeTruthy();
    expect(within(screen.getByTestId("mcp-provider-mx-ds")).getByText(/已设置/)).toBeTruthy();
    expect(within(screen.getByTestId("mcp-provider-tushare")).getByText(/未设置/)).toBeTruthy();
    expect(screen.queryByTestId("mcp-provider-free")).toBeNull();
    expect(screen.getAllByPlaceholderText("输入新 token")).toHaveLength(2);
  });

  it("填写并保存：PUT 明文经请求体，成功后清空输入（不回显）并刷新状态点", async () => {
    setTok.mockResolvedValue(undefined);
    providers
      .mockResolvedValueOnce(rows)
      .mockResolvedValueOnce(rows.map((r) => (r.code === "tushare" ? { ...r, hasToken: true } : r)));

    render(<McpTokenSection />);
    const input = await waitFor(() => row("tushare").getByPlaceholderText("输入新 token"));
    fireEvent.change(input, { target: { value: "new-token" } });
    fireEvent.click(row("tushare").getByRole("button", { name: "保存" }));

    await waitFor(() => expect(setTok).toHaveBeenCalledWith("tushare", "new-token"));
    await waitFor(() => expect((input as HTMLInputElement).value).toBe(""));
    await waitFor(() => expect(row("tushare").getByText(/已设置/)).toBeTruthy());
  });

  it("保存失败：显示服务端错误信息（如密钥未配置）", async () => {
    setTok.mockRejectedValue(new Error("MCP_SECRET_KEY 未配置：请设置后重启"));

    render(<McpTokenSection />);
    const input = await waitFor(() => row("tushare").getByPlaceholderText("输入新 token"));
    fireEvent.change(input, { target: { value: "tok" } });
    fireEvent.click(row("tushare").getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(screen.getByText(/MCP_SECRET_KEY 未配置/)).toBeTruthy(),
    );
  });

  it("输入为空时保存按钮禁用", async () => {
    render(<McpTokenSection />);
    const btn = await waitFor(() => row("tushare").getByRole("button", { name: "保存" }));
    expect((btn as HTMLButtonElement).disabled).toBe(true);
  });
});
