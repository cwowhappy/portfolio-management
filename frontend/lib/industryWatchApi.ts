// 行业关注 REST 客户端（经 /api/industry-watch 反代，需登录；幂等 watch/unwatch 均 204 无体）。

import { z } from "zod";
import { IndustryWatchItemSchema } from "./schemas";
import type { IndustryWatchItem } from "./types";
import { get, request } from "./http";

export const fetchIndustryWatch = () =>
  get<IndustryWatchItem[]>("/api/industry-watch", z.array(IndustryWatchItemSchema));
export const watchIndustry = (industryCode: string) =>
  request<void>("/api/industry-watch", "POST", { industryCode });
export const unwatchIndustry = (industryCode: string) =>
  request<void>(`/api/industry-watch/${industryCode}`, "DELETE");
