// CSV 批量导入 REST 客户端（经 /api/portfolio 反代）。
// multipart 上传不能走 http.request（其 body 统一 JSON.stringify），这里单独 fetch：
// FormData 交给浏览器生成 boundary；非 2xx 抛 body.message；响应在边界用 zod 校验。

import { ImportResultSchema } from "./schemas";
import type { ImportResult } from "./types";

/** 模板下载直连同源代理（透传 Content-Disposition，<a download> 触发浏览器原生下载）。 */
export function templateHref() {
  return "/api/portfolio/import/template";
}

export async function importCsv(file: File): Promise<ImportResult> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch("/api/portfolio/import", { method: "POST", body: fd });
  if (!res.ok) {
    let message = "导入失败";
    try {
      const b = await res.json();
      if (b?.message) message = b.message;
    } catch {
      // ignore：body 非 JSON 或无 message 时回退默认文案
    }
    throw new Error(message);
  }
  const data: unknown = await res.json();
  const parsed = ImportResultSchema.safeParse(data);
  if (!parsed.success) {
    console.error("[portfolioImportApi] 响应 schema 校验失败", parsed.error);
    throw new Error("数据格式异常");
  }
  return parsed.data;
}
