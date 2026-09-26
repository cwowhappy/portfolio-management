import { afterEach, describe, expect, it, vi } from "vitest";
import { importCsv, templateHref } from "@/lib/portfolioImportApi";

afterEach(() => {
  vi.unstubAllGlobals();
});

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("portfolioImportApi", () => {
  it("templateHref 指向模板下载端点（同源代理透传 Content-Disposition）", () => {
    expect(templateHref()).toBe("/api/portfolio/import/template");
  });

  it("importCsv 以 multipart 字段 file 组装 FormData 并 POST 到导入端点", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(jsonResponse({ importedCount: 6, rowErrors: [] }));
    vi.stubGlobal("fetch", fetchSpy);
    const file = new File(["date,type\n"], "import.csv", { type: "text/csv" });

    const result = await importCsv(file);

    expect(result).toEqual({ importedCount: 6, rowErrors: [] });
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/import");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("file")).toBe(file);
  });

  it("非 2xx 抛响应体 message（文件级 400）", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(
      jsonResponse({ code: "INVALID_INPUT", message: "文件过大（上限 1MB）" }, 400),
    );
    vi.stubGlobal("fetch", fetchSpy);
    await expect(importCsv(new File(["x"], "a.csv"))).rejects.toThrow("文件过大（上限 1MB）");
  });

  it("非 2xx 且 body 无 message（非 JSON）时回退默认文案", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(
      new Response("Bad Gateway", { status: 502, headers: { "Content-Type": "text/plain" } }),
    );
    vi.stubGlobal("fetch", fetchSpy);
    await expect(importCsv(new File(["x"], "a.csv"))).rejects.toThrow("导入失败");
  });

  it("响应不符合 ImportResult 契约时抛数据格式异常", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(jsonResponse({ foo: "bar" }));
    vi.stubGlobal("fetch", fetchSpy);
    await expect(importCsv(new File(["x"], "a.csv"))).rejects.toThrow("数据格式异常");
  });
});
