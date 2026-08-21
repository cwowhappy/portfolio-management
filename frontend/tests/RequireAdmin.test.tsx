import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const routerReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: routerReplace }),
}));

import { RequireAdmin } from "@/components/auth/RequireAdmin";
import { AuthProvider } from "@/lib/auth";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

const adminUser = {
  id: 1,
  username: "admin",
  role: "ADMIN",
  status: "APPROVED",
  enabled: true,
};

const normalUser = {
  id: 2,
  username: "alice",
  role: "USER",
  status: "APPROVED",
  enabled: true,
};

describe("RequireAdmin", () => {
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
        <RequireAdmin>
          <div>管理内容</div>
        </RequireAdmin>
      </AuthProvider>,
    );
  }

  it("未登录时重定向到 / 且不渲染子内容", async () => {
    renderGuard({ code: "UNAUTHENTICATED", message: "未登录" }, 401);
    await waitFor(() => expect(routerReplace).toHaveBeenCalledWith("/"));
    expect(screen.queryByText("管理内容")).toBeNull();
  });

  it("已登录但非管理员（USER）时重定向到 / 且不渲染子内容", async () => {
    renderGuard(normalUser, 200);
    await waitFor(() => expect(routerReplace).toHaveBeenCalledWith("/"));
    expect(screen.queryByText("管理内容")).toBeNull();
  });

  it("管理员时渲染子内容且不重定向", async () => {
    renderGuard(adminUser, 200);
    await waitFor(() => expect(screen.getByText("管理内容")).toBeTruthy());
    expect(routerReplace).not.toHaveBeenCalled();
  });
});
