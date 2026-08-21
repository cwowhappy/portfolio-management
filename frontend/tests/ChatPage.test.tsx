import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchHealth } from "@/lib/api";
import { AuthProvider } from "@/lib/auth";
import ChatPage from "@/components/chat/ChatPage";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

vi.mock("@/lib/api", () => ({
  fetchHealth: vi.fn(),
}));

vi.mock("@/components/chat/ThreadArea", () => ({
  default: ({ llmReady }: { llmReady: boolean | null }) => (
    <div data-testid="thread-area" data-llm-ready={String(llmReady)} />
  ),
}));

const fetchHealthMock = vi.mocked(fetchHealth);

function okJson(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
});

beforeEach(() => {
  fetchHealthMock.mockReset();
  // RequireAuth 挂载时经 /api/auth/me 拉取当前用户，返回已审核用户以放行
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(
      okJson({ id: 1, username: "alice", role: "USER", status: "APPROVED", enabled: true }),
    ),
  );
});

function renderPage() {
  return render(
    <AuthProvider>
      <ChatPage />
    </AuthProvider>,
  );
}

describe("ChatPage", () => {
  it("健康检查成功：Key 已配置且行情源可用 → llmReady=true", async () => {
    fetchHealthMock.mockResolvedValue({
      status: "up",
      llm: { provider: "deepseek", model: "deepseek-chat", baseUrl: "https://x", keyConfigured: true },
      market: { ok: true },
    });
    renderPage();
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
    renderPage();
    await waitFor(() =>
      expect(screen.getByTestId("thread-area").getAttribute("data-llm-ready")).toBe("false"),
    );
  });

  it("健康检查失败 → 保持检测中（llmReady=null）", async () => {
    fetchHealthMock.mockRejectedValue(new Error("network"));
    renderPage();
    await waitFor(() => expect(screen.getByText("连接中…")).toBeTruthy());
    expect(screen.getByTestId("thread-area").getAttribute("data-llm-ready")).toBe("null");
  });
});
