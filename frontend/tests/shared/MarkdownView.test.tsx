import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import MarkdownView from "@/components/shared/MarkdownView";

afterEach(() => cleanup());

describe("MarkdownView", () => {
  it("渲染标题/列表 Markdown", () => {
    render(<MarkdownView content={"## 核心观点\n- 市场先生"} />);
    expect(screen.getByRole("heading", { name: "核心观点" })).toBeTruthy();
    expect(screen.getByText("市场先生")).toBeTruthy();
  });

  it("https 链接放行，http 明文外域拦截（FR-6 同款）", () => {
    render(<MarkdownView content={"[安全](https://example.com) [明文](http://example.com)"} />);
    expect(screen.getByText("安全").closest("a")?.getAttribute("href")).toBe("https://example.com");
    expect(screen.getByText("明文").closest("a")?.getAttribute("href")).toBe("");
  });

  it("行内代码渲染 InlineCode", () => {
    render(<MarkdownView content={"`PE` 口径"} />);
    expect(screen.getByText("PE")).toBeTruthy();
  });
});
