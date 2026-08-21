import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Sidebar from "@/components/chat/Sidebar";
import { RuntimeProvider } from "@/components/chat/RuntimeProvider";
import { installConversationsApi } from "@/tests/mockConversationsApi";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

beforeEach(() => {
  localStorage.clear();
});

function session(id: string, title: string, updatedAt: number) {
  return { id, title, updatedAt };
}

const now = Date.now();

function renderSidebar(health: { llmKey: boolean; marketOk: boolean } | null) {
  return render(
    <RuntimeProvider>
      <Sidebar health={health} />
    </RuntimeProvider>,
  );
}

describe("Sidebar", () => {
  it("后端不可达时显示空状态", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("network down");
      }),
    );
    renderSidebar(null);
    await waitFor(() => expect(screen.getByText(/还没有会话/)).toBeTruthy());
  });

  it("时间标签覆盖 刚刚/分钟/小时/天 四个分支", async () => {
    installConversationsApi({
      list: [
        session("t1", "会话一", now - 1_000),
        session("t2", "会话二", now - 5 * 60_000),
        session("t3", "会话三", now - 3 * 3_600_000),
        session("t4", "会话四", now - 2 * 86_400_000),
      ],
    });
    renderSidebar(null);
    await waitFor(() => expect(screen.getByText("会话一")).toBeTruthy());
    expect(screen.getByText("刚刚")).toBeTruthy();
    expect(screen.getByText("5 分钟前")).toBeTruthy();
    expect(screen.getByText("3 小时前")).toBeTruthy();
    expect(screen.getByText("2 天前")).toBeTruthy();
  });

  it("当前会话高亮，点击其他会话切换", async () => {
    installConversationsApi({
      list: [
        session("t1", "会话一", now - 1_000),
        session("t2", "会话二", now - 2_000),
      ],
    });
    renderSidebar(null);
    await waitFor(() => expect(screen.getByText("会话一")).toBeTruthy());

    const first = screen.getByRole("button", { name: /会话一/ });
    const second = screen.getByRole("button", { name: /会话二/ });
    expect(first.getAttribute("aria-current")).toBe("true");
    expect(second.getAttribute("aria-current")).toBeNull();

    fireEvent.click(second);
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /会话二/ }).getAttribute("aria-current")).toBe(
        "true",
      ),
    );
  });

  it("点击新对话按钮生成新线程", async () => {
    installConversationsApi({
      list: [session("t1", "会话一", now - 1_000)],
    });
    renderSidebar(null);
    await waitFor(() => expect(screen.getByText("会话一")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "＋ 新对话" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /会话一/ }).getAttribute("aria-current")).toBeNull(),
    );
  });

  it("点击删除按钮移除会话", async () => {
    installConversationsApi({
      list: [
        session("t1", "会话一", now - 1_000),
        session("t2", "会话二", now - 2_000),
      ],
    });
    renderSidebar(null);
    await waitFor(() => expect(screen.getByText("会话一")).toBeTruthy());
    const del = screen.getAllByRole("button", { name: "删除会话" })[0];
    fireEvent.click(del);
    await waitFor(() => expect(screen.queryByText("会话一")).toBeNull());
    expect(screen.getByText("会话二")).toBeTruthy();
  });

  it("health=null 显示连接中", async () => {
    renderSidebar(null);
    await waitFor(() => expect(screen.getByText("连接中…")).toBeTruthy());
  });

  it("未配置 Key 显示提示", async () => {
    renderSidebar({ llmKey: false, marketOk: true });
    await waitFor(() => expect(screen.getByText("未配置模型 Key")).toBeTruthy());
  });

  it("行情源异常显示提示", async () => {
    renderSidebar({ llmKey: true, marketOk: false });
    await waitFor(() => expect(screen.getByText("行情源异常")).toBeTruthy());
  });

  it("全部就绪显示系统就绪", async () => {
    renderSidebar({ llmKey: true, marketOk: true });
    await waitFor(() => expect(screen.getByText("系统就绪")).toBeTruthy());
  });
});
