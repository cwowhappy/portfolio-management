// 统一 REST 客户端：经同源反代调后端，出参用 zod 在边界校验。
// 收敛各 lib 层重复的 request/get 帮助函数（portfolioApi/allocationApi/journalApi 逐字相同、
// screeningApi/valuationApi 相同、api.ts 变体），统一语义：
// - 非 2xx：优先取响应体 message，无则回退「请求失败」（body 非 JSON 同样回退）；
// - 204：返回 undefined（不解析 body）；
// - 响应不符合 schema：记日志并抛「数据格式异常」。

import { z } from "zod";

export async function request<T>(
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
    try {
      const b = await res.json();
      if (b?.message) message = b.message;
    } catch {
      // ignore：body 非 JSON 或无 message 时回退默认文案
    }
    throw new Error(message);
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
