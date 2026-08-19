import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchHealth } from "@/lib/api";
import ChatPage from "@/components/chat/ChatPage";

vi.mock("@/lib/api", () => ({
  fetchHealth: vi.fn(),
}));

vi.mock("@/components/chat/ThreadArea", () => ({
  default: ({ llmReady }: { llmReady: boolean | null }) => (
    <div data-testid="thread-area" data-llm-ready={String(llmReady)} />
  ),
}));

const fetchHealthMock = vi.mocked(fetchHealth);

afterEach(() => {
  cleanup();
  localStorage.clear();
});

beforeEach(() => {
  fetchHealthMock.mockReset();
});

describe("ChatPage", () => {
  it("健康检查成功：Key 已配置且行情源可用 → llmReady=true", async () => {
    fetchHealthMock.mockResolvedValue({
      status: "up",
      llm: { provider: "deepseek", model: "deepseek-chat", baseUrl: "https://x", keyConfigured: true },
      market: { ok: true },
    });
    render(<ChatPage />);
    await waitFor(() =>
      expect(screen.getByTestId("thread-area").getAttribute("data-llm-ready")).toBe("true"),
    );
    await waitFor(() => expect(screen.getByText("系统就绪")).toBeTruthy());
  });

  it("Key 未配置 → llmReady=false", async () => {
    fetchHealthMock.mockResolvedValue({
      status: "degraded",
      llm: { provider: "deepseek", model: "deepseek-chat", baseUrl: "https://x", keyConfigured: false },
      market: { ok: true },
    });
    render(<ChatPage />);
    await waitFor(() =>
      expect(screen.getByTestId("thread-area").getAttribute("data-llm-ready")).toBe("false"),
    );
  });

  it("健康检查失败 → 保持检测中（llmReady=null）", async () => {
    fetchHealthMock.mockRejectedValue(new Error("network"));
    render(<ChatPage />);
    await waitFor(() => expect(screen.getByText("连接中…")).toBeTruthy());
    expect(screen.getByTestId("thread-area").getAttribute("data-llm-ready")).toBe("null");
  });
});
