import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const routerReplace = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: routerReplace }),
}));

// 子组件打标：只验证页面壳是否套上鉴权门，不关心子组件自身数据行为
vi.mock("@/components/mcp/McpSettingsPage", () => ({
  default: () => <div>MCP 设置内容</div>,
}));
vi.mock("@/components/skill/SkillSettingsPage", () => ({
  default: () => <div>Skill 设置内容</div>,
}));
vi.mock("@/components/intelligence/IntelligenceSettingsPage", () => ({
  default: () => <div>Intelligence 设置内容</div>,
}));

import IntelligenceSettingsPage from "@/app/settings/intelligence/page";
import McpSettingsPage from "@/app/settings/mcp/page";
import SkillSettingsPage from "@/app/settings/skills/page";
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

const settingsPages = [
  { name: "/settings/mcp", Page: McpSettingsPage, marker: "MCP 设置内容" },
  { name: "/settings/skills", Page: SkillSettingsPage, marker: "Skill 设置内容" },
  { name: "/settings/intelligence", Page: IntelligenceSettingsPage, marker: "Intelligence 设置内容" },
];

describe("设置三页鉴权（RequireAuth 门）", () => {
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

  it.each(settingsPages)("未登录时 $name 不渲染子组件并跳登录", async ({ Page, marker }) => {
    fetchMock.mockResolvedValue(jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401));
    render(
      <AuthProvider>
        <Page />
      </AuthProvider>,
    );
    await waitFor(() => expect(routerReplace).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText(marker)).toBeNull();
  });

  it.each(settingsPages)("已登录时 $name 渲染子组件", async ({ Page, marker }) => {
    fetchMock.mockResolvedValue(jsonResponse(approvedUser, 200));
    render(
      <AuthProvider>
        <Page />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByText(marker)).toBeTruthy());
    expect(routerReplace).not.toHaveBeenCalled();
  });
});
