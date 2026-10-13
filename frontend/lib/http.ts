// 统一 REST 客户端：经同源反代调后端，出参用 zod 在边界校验。
// 收敛各 lib 层重复的 request/get 帮助函数（portfolioApi/allocationApi/journalApi 逐字相同、
// screeningApi/valuationApi 相同、api.ts 变体；adminApi/memoryApi/conversations 私有 request
// 已迁此，P3 第三批 F3），统一语义：
// - 非 2xx：优先取响应体 message，无则回退「请求失败」（body 非 JSON 同样回退）；
// - 204：返回 undefined（不解析 body）；
// - 响应不符合 schema：记日志并抛「数据格式异常」。
//
// 可选行为（RequestOptions，缺省与上述基础语义完全一致，既有调用方零变更）：
// - withStatus：错误承载状态码——非 2xx 错误挂 HTTP status（错误体带 code 时一并挂载，
//   供 memoryApi 按 409/410/400 + 后端错误码分流）；传输层失败（fetch reject）包成
//   status=0 的错误（conversations keepalive/断网分类口径，读取走 errorStatus）；
// - init：追加 fetch init（keepalive / 自定义头等），headers 与默认 Content-Type 浅合并
//   （adminApi 全员 credentials:"same-origin"、GET 也带 Content-Type 的钉死形态经此注入）。

import { z } from "zod";

export interface RequestOptions {
  /** 错误承载状态码：非 2xx 挂 HTTP status（错误体 code 一并挂）；传输层失败包成 status=0。 */
  withStatus?: boolean;
  /** 追加 fetch init（keepalive / 自定义头等）；headers 与默认 Content-Type 浅合并。 */
  init?: RequestInit;
}

export async function request<T>(
  path: string,
  method: string,
  body?: unknown,
  schema?: z.ZodType<T>,
  opts?: RequestOptions,
): Promise<T> {
  const { headers: extraHeaders, ...restInit } = opts?.init ?? {};
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      cache: "no-store",
      ...restInit,
      // fetch init 键形态与历史实现逐字一致（headers/body 恒为显式键，无 body 时值为
      // undefined）：tests/reportApi、researchApi 以 objectContaining({ body: undefined })
      // 钉住 GET 请求的显式空 body 键，缺键即破契约
      headers: extraHeaders
        ? { ...(body !== undefined ? { "Content-Type": "application/json" } : {}), ...extraHeaders }
        : body !== undefined
          ? { "Content-Type": "application/json" }
          : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (e) {
    if (!opts?.withStatus) throw e;
    // P3-⑦ 口径：传输层失败（断网 / keepalive PUT 体积超限被浏览器拒绝等）此前裸抛 TypeError，
    // 无法按状态分类；与 HTTP 错误同口径包成带 status 的错误（0 表传输层），保留原始 message
    throw Object.assign(new Error(e instanceof Error ? e.message : "网络请求失败"), { status: 0 });
  }
  if (!res.ok) {
    let message = "请求失败";
    let code: string | null = null;
    try {
      const b = await res.json();
      if (b?.message) message = b.message;
      if (typeof b?.code === "string") code = b.code;
    } catch {
      // ignore：body 非 JSON 或无 message 时回退默认文案
    }
    const err = new Error(message) as Error & { status?: number; code?: string };
    if (opts?.withStatus) {
      err.status = res.status;
      if (code !== null) err.code = code;
    }
    throw err;
  }
  if (res.status === 204) return undefined as T;
  const data: unknown = await res.json();
  if (!schema) return data as T;
  const parsed = schema.safeParse(data);
  if (!parsed.success) {
    console.error("[http] 响应 schema 校验失败", path, parsed.error);
    throw new Error("数据格式异常");
  }
  return parsed.data;
}

/** GET 便捷封装：不带 body、无 Content-Type。 */
export function get<T>(path: string, schema: z.ZodType<T>): Promise<T> {
  return request<T>(path, "GET", undefined, schema);
}
