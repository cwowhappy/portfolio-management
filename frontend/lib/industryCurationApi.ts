// 策展写侧 REST 客户端（经 /api/industry-curation 反代，需登录，MS-10 P2）。
// multipart 上传不能走 http.request（其 body 统一 JSON.stringify），这里单独 fetch：
// FormData 交给浏览器生成 boundary；非 2xx 抛 body.message；响应在边界用 zod 校验
// （CurationImportResult 双计数契约——与 MS-14 ImportResult 不同源，勿混用）。

import { CurationImportResultSchema, UnlistedCompanySchema } from "./schemas";
import type { CurationImportResult, SaveUnlistedCompanyInput, UnlistedCompany } from "./types";
import { request } from "./http";

/** 模板下载直连同源代理（透传 Content-Disposition，<a download> 触发浏览器原生下载）。 */
export function companiesTemplateHref() {
  return "/api/industry-curation/companies/import/template";
}

export function fundingEventsTemplateHref() {
  return "/api/industry-curation/funding-events/import/template";
}

/** 新增/编辑复用：id 有值走 PUT /companies/{id}，否则 POST /companies。 */
export function saveUnlistedCompany(cmd: SaveUnlistedCompanyInput): Promise<UnlistedCompany> {
  const path = cmd.id == null
    ? "/api/industry-curation/companies"
    : `/api/industry-curation/companies/${cmd.id}`;
  return request<UnlistedCompany>(path, cmd.id == null ? "POST" : "PUT", cmd, UnlistedCompanySchema);
}

export function deleteUnlistedCompany(id: number): Promise<void> {
  return request<void>(`/api/industry-curation/companies/${id}`, "DELETE");
}

export function deleteFundingEvent(id: number): Promise<void> {
  return request<void>(`/api/industry-curation/funding-events/${id}`, "DELETE");
}

export async function importUnlistedCompanies(file: File): Promise<CurationImportResult> {
  return importCsv("/api/industry-curation/companies/import", file);
}

export async function importFundingEvents(file: File): Promise<CurationImportResult> {
  return importCsv("/api/industry-curation/funding-events/import", file);
}

async function importCsv(path: string, file: File): Promise<CurationImportResult> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch(path, { method: "POST", body: fd });
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
  const parsed = CurationImportResultSchema.safeParse(data);
  if (!parsed.success) {
    console.error("[industryCurationApi] 响应 schema 校验失败", path, parsed.error);
    throw new Error("数据格式异常");
  }
  return parsed.data;
}
