import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import hljs from "highlight.js/lib/common";
import { CodeBlock, InlineCode } from "@/components/chat/CodeHighlight";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("CodeBlock", () => {
  it("按语言标签高亮代码", () => {
    const { container } = render(
      <CodeBlock className="language-javascript">{"const a = 1;"}</CodeBlock>,
    );
    expect(screen.getByText("javascript")).toBeTruthy();
    const code = container.querySelector("code");
    expect(code?.innerHTML).toContain("hljs-keyword");
    expect(code?.innerHTML).toContain("hljs-number");
  });

  it("无语言标签时显示 text 且按纯文本高亮", () => {
    const { container } = render(<CodeBlock>{"plain <div>"}</CodeBlock>);
    expect(screen.getByText("text")).toBeTruthy();
    // highlight.js 对 plaintext 会转义 HTML 特殊字符
    expect(container.querySelector("code")?.innerHTML).toContain("&lt;div&gt;");
  });

  it("未知语言回退到 plaintext", () => {
    render(<CodeBlock className="language-not-a-real-lang">{"x = 1"}</CodeBlock>);
    expect(screen.getByText("not-a-real-lang")).toBeTruthy();
  });

  it("移除代码块末尾换行", () => {
    const { container } = render(<CodeBlock>{"hello\n"}</CodeBlock>);
    expect(container.querySelector("code")?.textContent).toBe("hello");
  });

  it("高亮异常时手动转义输出", () => {
    const spy = vi.spyOn(hljs, "highlight").mockImplementation(() => {
      throw new Error("boom");
    });
    try {
      const { container } = render(<CodeBlock className="language-javascript">{"a < b & c"}</CodeBlock>);
      expect(container.querySelector("code")?.innerHTML).toContain("a &lt; b &amp; c");
    } finally {
      spy.mockRestore();
    }
  });

  it("点击复制按钮写入剪贴板并切换文案", async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    render(<CodeBlock className="language-js">{"const a = 1;"}</CodeBlock>);
    fireEvent.click(screen.getByRole("button", { name: "复制" }));
    expect(writeText).toHaveBeenCalledWith("const a = 1;");
    // flush writeText().then() 微任务
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.getByText("已复制")).toBeTruthy();
    act(() => {
      vi.advanceTimersByTime(1500);
    });
    expect(screen.getByText("复制")).toBeTruthy();
  });

  it("剪贴板不可用时点击不抛错", () => {
    Object.defineProperty(navigator, "clipboard", {
      value: undefined,
      configurable: true,
    });
    render(<CodeBlock className="language-js">{"const a = 1;"}</CodeBlock>);
    expect(() =>
      fireEvent.click(screen.getByRole("button", { name: "复制" })),
    ).not.toThrow();
  });
});

describe("InlineCode", () => {
  it("渲染行内代码", () => {
    render(<InlineCode>{"npm install"}</InlineCode>);
    expect(screen.getByText("npm install").tagName).toBe("CODE");
  });
});
