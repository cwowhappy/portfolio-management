import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import NotePanel from "@/components/wiki/NotePanel";
import * as wikiApi from "@/lib/wikiApi";
import type { WikiEntryView } from "@/lib/types";

afterEach(() => cleanup());

const entries: WikiEntryView[] = [
  { id: 5, type: "BOOK_NOTE", title: "《聪明的投资者》", content: "## 核心观点\n- 市场先生",
    category: null, industryCode: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
  { id: 6, type: "CONCEPT", title: "护城河", content: "结构性优势",
    category: "质量", industryCode: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
];

describe("NotePanel", () => {
  it("列表渲染条目标题与概念分类", () => {
    render(<NotePanel type="CONCEPT" entries={entries} onChanged={() => {}} />);
    expect(screen.getByTestId("wiki-note-list")).toBeTruthy();
    expect(screen.getByTestId("wiki-note-5").textContent).toContain("《聪明的投资者》");
    expect(screen.getByTestId("wiki-note-6").textContent).toContain("护城河");
    expect(screen.getByTestId("wiki-note-6").textContent).toContain("质量");
  });

  it("点标题展开 Markdown 渲染详情（h2）", () => {
    render(<NotePanel type="BOOK_NOTE" entries={entries} onChanged={() => {}} />);
    fireEvent.click(screen.getByTestId("wiki-note-5"));
    expect(screen.getByRole("heading", { name: "核心观点" })).toBeTruthy();
    fireEvent.click(screen.getByTestId("wiki-note-5")); // 再点收起
    expect(screen.queryByRole("heading", { name: "核心观点" })).toBeNull();
  });

  it("编辑器：预览切换渲染、保存校验与调用", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(entries[0]);
    render(<NotePanel type="BOOK_NOTE" entries={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-note-title"), { target: { value: "新笔记" } });
    fireEvent.change(screen.getByTestId("wiki-note-content"), { target: { value: "## 新内容" } });
    // 预览切换
    fireEvent.click(screen.getByTestId("wiki-note-preview-toggle"));
    expect(screen.getByRole("heading", { name: "新内容" })).toBeTruthy();
    fireEvent.click(screen.getByTestId("wiki-note-preview-toggle")); // 切回编辑
    fireEvent.click(screen.getByTestId("wiki-note-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ type: "BOOK_NOTE", title: "新笔记", content: "## 新内容" })));
  });

  it("空标题保存被前端拦截，不调 API", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(entries[0]);
    render(<NotePanel type="BOOK_NOTE" entries={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-note-content"), { target: { value: "只有内容" } });
    fireEvent.click(screen.getByTestId("wiki-note-save"));
    expect(createSpy).not.toHaveBeenCalled();
  });

  it("概念类型编辑器含分类输入", () => {
    render(<NotePanel type="CONCEPT" entries={[]} onChanged={() => {}} />);
    expect(screen.getByTestId("wiki-note-category")).toBeTruthy();
  });

  it("新建保存成功后清空表单（防重复创建）", async () => {
    const createSpy = vi.spyOn(wikiApi, "createWikiEntry").mockResolvedValue(entries[0]);
    render(<NotePanel type="BOOK_NOTE" entries={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-note-title"), { target: { value: "新笔记" } });
    fireEvent.change(screen.getByTestId("wiki-note-content"), { target: { value: "## 新内容" } });
    fireEvent.click(screen.getByTestId("wiki-note-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledTimes(1));
    await waitFor(() => {
      expect((screen.getByTestId("wiki-note-title") as HTMLInputElement).value).toBe("");
      expect((screen.getByTestId("wiki-note-content") as HTMLTextAreaElement).value).toBe("");
    });
  });
});
