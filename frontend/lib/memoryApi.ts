// 记忆管理客户端（经 /api/memory 反代），响应用 zod 在边界校验（B3/B4 契约）。
//
// 不复用 lib/http.ts 的 request：其非 2xx 抛 Error 丢 HTTP status，而记忆管理需要
// 409（并发冲突）/410（版本漂移）/400（只读标题/非法请求）分流刷新提示（B3 评审 Minor-2
// 承接）。沿 lib/conversations.ts 带 status 的 request 变体先例在本模块内薄封装，
// 抛类型化 MemoryApiError（status + 后端错误码 code），不改 lib/http.ts 本体。

import { z } from "zod";

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
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });
  if (!res.ok) {
    let message = "请求失败";
    let code: string | null = null;
    try {
      const b = await res.json();
      if (typeof b?.message === "string") message = b.message;
      if (typeof b?.code === "string") code = b.code;
    } catch {
      // ignore：body 非 JSON 或无 message 时回退默认文案
    }
    throw new MemoryApiError(message, res.status, code);
  }
  if (res.status === 204) return undefined as T;
  const data: unknown = await res.json();
  if (!schema) return data as T;
  const parsed = schema.safeParse(data);
  if (!parsed.success) {
    console.error("[memoryApi] 响应 schema 校验失败", path, parsed.error);
    throw new Error("数据格式异常");
  }
  return parsed.data;
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
