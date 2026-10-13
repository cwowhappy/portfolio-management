// 记忆管理客户端（经 /api/memory 反代），响应用 zod 在边界校验（B3/B4 契约）。
//
// P3 收敛：传输/错误处理统一走 lib/http.ts 的 request()（withStatus 承载状态码，错误体
// code 一并挂载）；本模块仅保留错误类型适配——HTTP 层错误翻译为 MemoryApiError
// （status + 后端错误码 code），供 F2/F3 按 409/410/400 分流；传输层（status=0）与
// schema 校验错误保持 http.ts 语义原样上抛（与旧私有 request 行为等价）。

import { z } from "zod";
import { request as httpRequest } from "./http";

/** 日账本节类型（后端 SectionKind：节头前缀判定） */
export const MemorySectionKindSchema = z.enum(["FLUSH", "EXTERNAL_EVENT"]);
export type MemorySectionKind = z.infer<typeof MemorySectionKindSchema>;

/** 策展层文件视图：hash 文件缺失为显式 null（键在），lines 行序即 lineIndex 寻址序 */
const CuratedFileViewSchema = z.object({
  hash: z.string().nullable(),
  lines: z.array(z.string()),
});

/** 日账本节：整节为管理操作单元（header 为完整节头行，作删除寻址键） */
const LedgerSectionSchema = z.object({
  header: z.string(),
  kind: MemorySectionKindSchema,
  lines: z.array(z.string()),
});

/** 日账本文件视图：date 为 ISO yyyy-MM-dd 字符串（LocalDate 序列化形态） */
const LedgerFileViewSchema = z.object({
  date: z.string(),
  hash: z.string().nullable(),
  sections: z.array(LedgerSectionSchema),
});

export const MemoryViewSchema = z.object({
  curated: CuratedFileViewSchema,
  ledgers: z.array(LedgerFileViewSchema),
});
export type MemoryView = z.infer<typeof MemoryViewSchema>;

/** 画像授权段：preview 与注入同一渲染路径直出，失败/空段为 null（键在） */
const ProfileSectionSchema = z.object({
  enabled: z.boolean(),
  preview: z.string().nullable(),
});

export const ProfileConfigViewSchema = z.object({
  style: ProfileSectionSchema,
  industry: ProfileSectionSchema,
  watchlist: ProfileSectionSchema,
  holdings: ProfileSectionSchema,
});
export type ProfileConfigView = z.infer<typeof ProfileConfigViewSchema>;

const CuratedMutationResultSchema = z.object({ purgedLedgerLines: z.number() });
type CuratedMutationResult = z.infer<typeof CuratedMutationResultSchema>;

/**
 * 记忆 API 类型化错误：携带 HTTP status 与后端错误码（MEMORY_CONFLICT/MEMORY_STALE/
 * MEMORY_READ_ONLY/INVALID_REQUEST），供 F2/F3 按 409/410/400 分流。
 */
export class MemoryApiError extends Error {
  readonly status: number;
  readonly code: string | null;

  constructor(message: string, status: number, code: string | null) {
    super(message);
    this.name = "MemoryApiError";
    this.status = status;
    this.code = code;
  }
}

async function request<T>(
  path: string,
  method: string,
  body?: unknown,
  schema?: z.ZodType<T>,
): Promise<T> {
  try {
    return await httpRequest<T>(path, method, body, schema, { withStatus: true });
  } catch (e) {
    // 仅 HTTP 层错误（status>0）翻译为 MemoryApiError；status=0 传输层与「数据格式异常」
    // 等非 HTTP 错误保持 http.ts 语义原样上抛
    const status = (e as { status?: unknown }).status;
    if (typeof status === "number" && status > 0) {
      const code = (e as { code?: unknown }).code;
      throw new MemoryApiError(
        e instanceof Error ? e.message : "请求失败",
        status,
        typeof code === "string" ? code : null,
      );
    }
    throw e;
  }
}

/** GET /api/memory：策展层 + 当期日账本全视图 */
export const fetchMemory = () => request<MemoryView>("/api/memory", "GET", undefined, MemoryViewSchema);

/** DELETE /api/memory/curated：删策展层行，返回日账本同步清除行数（尽力清除，如实计数） */
export function deleteCuratedLine(p: {
  lineIndex: number;
  expectLine: string;
  baseHash: string;
}): Promise<CuratedMutationResult> {
  return request("/api/memory/curated", "DELETE", p, CuratedMutationResultSchema);
}

/** PUT /api/memory/curated：编辑策展层行（204 无响应体） */
export function editCuratedLine(p: {
  lineIndex: number;
  expectLine: string;
  newLine: string;
  baseHash: string;
}): Promise<void> {
  return request("/api/memory/curated", "PUT", p);
}

/** DELETE /api/memory/ledger/{date}：删整节（204 无响应体；date 为 ISO yyyy-MM-dd） */
export function deleteLedgerSection(
  date: string,
  p: { sectionHeader: string; baseHash: string },
): Promise<void> {
  return request(`/api/memory/ledger/${encodeURIComponent(date)}`, "DELETE", p);
}

/** GET /api/memory/profile-config：四开关 + 四段预览同刻一次拉取 */
export const fetchProfileConfig = () =>
  request<ProfileConfigView>("/api/memory/profile-config", "GET", undefined, ProfileConfigViewSchema);

/** PUT /api/memory/profile-config：四开关全量保存，200 回保存后最新配置视图 */
export function saveProfileConfig(p: {
  style: boolean;
  industry: boolean;
  watchlist: boolean;
  holdings: boolean;
}): Promise<ProfileConfigView> {
  return request("/api/memory/profile-config", "PUT", p, ProfileConfigViewSchema);
}
