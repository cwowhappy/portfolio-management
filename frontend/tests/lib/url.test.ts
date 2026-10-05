import { describe, expect, it } from "vitest";
import { safeUrl } from "@/lib/url";

// 外链信任模型（与 MarkdownView.urlTransform 同款）：仅放行同源相对路径与 https，
// 其余（http 明文外域 / javascript: / data: 等伪协议 / 空值）返回 null 渲染纯文本。
describe("safeUrl", () => {
  it("同源相对路径放行", () => {
    expect(safeUrl("/path")).toBe("/path");
  });

  it("https 外链放行原样", () => {
    expect(safeUrl("https://x.com/a")).toBe("https://x.com/a");
  });

  it("http 明文外域拦截", () => {
    expect(safeUrl("http://x.com")).toBeNull();
  });

  it("javascript: 伪协议拦截", () => {
    expect(safeUrl("javascript:alert(1)")).toBeNull();
  });

  it("data: 伪协议拦截", () => {
    expect(safeUrl("data:text/html,<script>alert(1)</script>")).toBeNull();
  });

  it("空串/null/undefined → null", () => {
    expect(safeUrl("")).toBeNull();
    expect(safeUrl(null)).toBeNull();
    expect(safeUrl(undefined)).toBeNull();
  });
});
