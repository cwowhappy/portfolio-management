import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const routerReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: routerReplace }),
}));

import { RequireAuth } from "@/components/auth/RequireAuth";
import { AuthProvider } from "@/lib/auth";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

const approvedUser = {
  id: 1,
  username: "alice",
  role: "USER",
  status: "APPROVED",
  enabled: true,
};

describe("RequireAuth", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    routerReplace.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderGuard(user: unknown, status: number) {
    fetchMock.mockResolvedValue(jsonResponse(user, status));
    return render(
      <AuthProvider>
        <RequireAuth>
          <div>受保护内容</div>
        </RequireAuth>
      </AuthProvider>,
    );
  }

  it("未登录时重定向到 /login 且不渲染子内容", async () => {
    renderGuard({ code: "UNAUTHENTICATED", message: "未登录" }, 401);
    await waitFor(() => expect(routerReplace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("受保护内容")).toBeNull();
  });

  it("已登录时渲染子内容且不重定向", async () => {
    renderGuard(approvedUser, 200);
    await waitFor(() => expect(screen.getByText("受保护内容")).toBeTruthy());
    expect(routerReplace).not.toHaveBeenCalled();
  });
});
