// 产业链公开读 REST 客户端（经 /api/industry 反代，MS-10 P3）：zod 边界校验。
// 读侧无缓存（设计规格 §九#4），整包返回（链 2~3 条 × 成员十级量级）。

import { z } from "zod";
import { ChainViewSchema } from "./schemas";
import type { ChainView } from "./types";
import { get } from "./http";

export function fetchIndustryChains(industryCode: string): Promise<ChainView[]> {
  return get(
    `/api/industry/${encodeURIComponent(industryCode)}/chains`,
    z.array(ChainViewSchema),
  );
}
