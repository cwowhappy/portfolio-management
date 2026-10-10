import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import ResearchNoteDialog from "@/components/wiki/ResearchNoteDialog";
import * as wikiApi from "@/lib/wikiApi";

afterEach(() => cleanup());

const saved: wikiReturn = {
  id: 99, type: "RESEARCH_NOTE", title: "白酒行业研究结论", content: "结论",
  category: null, industryCode: "801120", createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z",
};
type wikiReturn = Awaited<ReturnType<typeof wikiApi.createWikiEntry>>;

describe("ResearchNoteDialog", () => {
  it("标题预填行业名 + 行业代码自动带入", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(saved);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    expect(screen.queryByTestId("research-note-dialog")).toBeNull(); // 初始关闭
    fireEvent.click(screen.getByTestId("research-note-open"));
    expect((screen.getByTestId("research-note-title") as HTMLInputElement).value).toBe("白酒 研究结论");
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "景气上行" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(expect.objectContaining({
      type: "RESEARCH_NOTE", industryCode: "801120",
    })));
  });

  it("行业名未加载时用代码兜底预填", () => {
    render(<ResearchNoteDialog industryCode="801120" industryName="" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    expect((screen.getByTestId("research-note-title") as HTMLInputElement).value).toBe("801120 研究结论");
  });

  it("保存成功展示「在知识库查看」深链", async () => {
    vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(saved);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "结论" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    const link = await screen.findByTestId("research-note-view-link");
    expect(link.getAttribute("href")).toBe("/wiki?tab=research");
  });

  it("空内容保存被拦截不调 API", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(saved);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.click(screen.getByTestId("research-note-save"));
    expect(createSpy).not.toHaveBeenCalled();
  });

  it("保存请求在途时按钮禁用，连点仅发一次请求", async () => {
    let resolveSave!: (v: wikiReturn) => void;
    const pending = new Promise<wikiReturn>((res) => { resolveSave = res; });
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockReturnValue(pending);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "结论" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    await waitFor(() => expect((screen.getByTestId("research-note-save") as HTMLButtonElement).disabled).toBe(true));
    fireEvent.click(screen.getByTestId("research-note-save")); // 在途再点不触发
    resolveSave(saved);
    await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(1));
    // 保存成功切换到「已保存」视图（保存按钮随之卸载）即完成信号
    await waitFor(() => expect(screen.getByTestId("research-note-view-link")).toBeTruthy());
  });

  it("保存中取消禁用（防迟到响应翻转重开后的视图）", async () => {
    let resolveSave!: (v: wikiReturn) => void;
    const pending = new Promise<wikiReturn>((res) => { resolveSave = res; });
    vi.spyOn(wikiApi, "createWikiEntry").mockReturnValue(pending);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "结论" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    await waitFor(() => expect((screen.getByTestId("research-note-save") as HTMLButtonElement).disabled).toBe(true));

    const cancel = screen.getByRole("button", { name: "取消" }) as HTMLButtonElement;
    expect(cancel.disabled).toBe(true);

    resolveSave(saved);
    await waitFor(() => expect(screen.getByTestId("research-note-view-link")).toBeTruthy());
  });

  it("关闭后重开：saving 已复位，可再次发起保存（reset 生效）", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(saved);
    render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
    // 第一次：保存成功
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "第一条" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    await screen.findByTestId("research-note-view-link");
    // 已保存视图下再点外部「保存研究结论」入口 → openDialog 重置（含 hook reset）→ 表单可再次保存
    fireEvent.click(screen.getByTestId("research-note-open"));
    fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "第二条" } });
    fireEvent.click(screen.getByTestId("research-note-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(2));
  });

  // P3-⑤：模态可达性——初始焦点入浮层、Escape 关闭 + 焦点返还触发按钮、Tab 首尾圈定
  describe("可达性（P3-⑤）", () => {
    it("打开即聚焦标题输入；Tab/Shift+Tab 首尾循环；Escape 关闭并返还焦点", () => {
      render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
      const trigger = screen.getByTestId("research-note-open");
      trigger.focus(); // 浏览器点击即聚焦，jsdom 需显式补齐
      fireEvent.click(trigger);
      const dialog = screen.getByTestId("research-note-dialog");
      expect(dialog.getAttribute("role")).toBe("dialog");
      expect(dialog.getAttribute("aria-modal")).toBe("true");

      // 初始焦点入浮层：首个可聚焦元素（标题输入）
      expect(document.activeElement).toBe(screen.getByTestId("research-note-title"));

      // Tab 圈定：末位（取消）Tab 回首位；首位 Shift+Tab 到末位
      const cancel = screen.getByRole("button", { name: "取消" });
      cancel.focus();
      fireEvent.keyDown(dialog, { key: "Tab" });
      expect(document.activeElement).toBe(screen.getByTestId("research-note-title"));
      fireEvent.keyDown(dialog, { key: "Tab", shiftKey: true });
      expect(document.activeElement).toBe(cancel);

      // Escape 关闭 + 焦点返还触发按钮
      fireEvent.keyDown(dialog, { key: "Escape" });
      expect(screen.queryByTestId("research-note-dialog")).toBeNull();
      expect(document.activeElement).toBe(trigger);
    });

    it("保存中（saving）Escape 不关闭（防误触丢失输入，与取消按钮禁用同口径）", async () => {
      let resolveSave!: (v: wikiReturn) => void;
      const pending = new Promise<wikiReturn>((res) => {
        resolveSave = res;
      });
      vi.spyOn(wikiApi, "createWikiEntry").mockReturnValue(pending);
      render(<ResearchNoteDialog industryCode="801120" industryName="白酒" />);
      fireEvent.click(screen.getByTestId("research-note-open"));
      fireEvent.change(screen.getByTestId("research-note-content"), { target: { value: "结论" } });
      fireEvent.click(screen.getByTestId("research-note-save"));
      await waitFor(() =>
        expect((screen.getByTestId("research-note-save") as HTMLButtonElement).disabled).toBe(true),
      );
      const dialog = screen.getByTestId("research-note-dialog");
      fireEvent.keyDown(dialog, { key: "Escape" });
      expect(screen.getByTestId("research-note-dialog")).toBeTruthy();
      resolveSave(saved);
    });
  });
});
