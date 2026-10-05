import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/mcpApi", () => ({
  fetchProviders: vi.fn(),
  fetchConfigs: vi.fn(),
  fetchTools: vi.fn(),
  testConnection: vi.fn(),
  saveConfig: vi.fn(),
  deleteConfig: vi.fn(),
}));

import McpSettingsPage from "@/components/mcp/McpSettingsPage";
import {
  fetchConfigs,
  fetchProviders,
  fetchTools,
  testConnection,
  type McpConfigItem,
  type McpProviderItem,
} from "@/lib/mcpApi";

const provider: McpProviderItem = {
  id: 1,
  code: "tushare",
  name: "Tushare",
  authType: "BEARER",
  authHeader: null,
  hasToken: false,
  domains: ["行情"],
};

const config: McpConfigItem = {
  providerId: 1,
  enabled: true,
  disabledTools: [],
  configVersion: 1,
};

function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

describe("McpSettingsPage", () => {
  beforeEach(() => {
    vi.mocked(fetchProviders).mockResolvedValue([provider]);
    vi.mocked(fetchConfigs).mockResolvedValue([config]);
    vi.mocked(fetchTools).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("连点「测试连接」仅触发一次请求（in-flight 防连点）", async () => {
    const d = deferred<{ success: boolean; tools: { name: string; description: string }[]; latencyMs: number; errorMessage: string | null }>();
    vi.mocked(testConnection).mockReturnValueOnce(d.promise);
    render(<McpSettingsPage />);

    const btn = await screen.findByRole("button", { name: "测试连接" });
    fireEvent.click(btn);
    fireEvent.click(btn);

    expect(testConnection).toHaveBeenCalledTimes(1);

    d.resolve({ success: true, tools: [], latencyMs: 12, errorMessage: null });
    await waitFor(() =>
      expect(vi.mocked(fetchTools)).toHaveBeenCalledWith(provider.id),
    );
  });

  it("testConnection 抛非 Error 值时错误条渲染兜底文案（非空、非 undefined）", async () => {
    vi.mocked(testConnection).mockRejectedValueOnce("boom");
    render(<McpSettingsPage />);

    fireEvent.click(await screen.findByRole("button", { name: "测试连接" }));

    const err = await screen.findByText("操作失败");
    expect(err.textContent).toBeTruthy();
    expect(err.textContent).not.toBe("undefined");
  });
});
