import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import WikiBoard from "@/components/wiki/WikiBoard";
import * as wikiApi from "@/lib/wikiApi";

afterEach(() => cleanup());

describe("WikiBoard", () => {
  beforeEach(() => {
    vi.spyOn(wikiApi, "fetchWikiEntries").mockResolvedValue([]);
    vi.spyOn(wikiApi, "fetchRules").mockResolvedValue([]);
  });

  it("渲染四 tab 与标题", async () => {
    render(<WikiBoard />);
    expect(screen.getByRole("heading", { name: "投资知识库" })).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-principle")).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-book")).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-concept")).toBeTruthy();
    expect(screen.getByTestId("wiki-tab-research")).toBeTruthy();
    await waitFor(() => expect(wikiApi.fetchRules).toHaveBeenCalled());
  });

  it("切到概念 tab 按 CONCEPT 拉取", async () => {
    render(<WikiBoard />);
    fireEvent.click(screen.getByTestId("wiki-tab-concept"));
    await waitFor(() => expect(wikiApi.fetchWikiEntries).toHaveBeenCalledWith("CONCEPT"));
  });

  it("切到读书笔记 tab 按 BOOK_NOTE 拉取", async () => {
    render(<WikiBoard />);
    fireEvent.click(screen.getByTestId("wiki-tab-book"));
    await waitFor(() => expect(wikiApi.fetchWikiEntries).toHaveBeenCalledWith("BOOK_NOTE"));
  });

  it("initialTab 生效（行业页深链 research）", async () => {
    render(<WikiBoard initialTab="research" />);
    await waitFor(() => expect(wikiApi.fetchWikiEntries).toHaveBeenCalledWith("RESEARCH_NOTE"));
  });
});
