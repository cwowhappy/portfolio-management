// 未上市与融资公开读 REST 客户端（经 /api/industry 反代，MS-10 P2）：zod 边界校验。
// 读侧无缓存（设计规格 §九#4），直查后端。

import { z } from "zod";
import { FundingEventSchema, UnlistedCompanySchema, UnlistedOverviewSchema } from "./schemas";
import type { FundingEvent, UnlistedCompany, UnlistedOverview } from "./types";
import { get } from "./http";

export function fetchUnlistedCompanies(industryCode: string): Promise<UnlistedCompany[]> {
  return get(
    `/api/industry/${encodeURIComponent(industryCode)}/unlisted/companies`,
    z.array(UnlistedCompanySchema),
  );
}

/** months 缺省 24（后端默认），范围 [1,60]。 */
export function fetchFundingEvents(industryCode: string, months = 24): Promise<FundingEvent[]> {
  return get(
    `/api/industry/${encodeURIComponent(industryCode)}/unlisted/funding-events?months=${months}`,
    z.array(FundingEventSchema),
  );
}

export function fetchUnlistedOverview(industryCode: string): Promise<UnlistedOverview> {
  return get(
    `/api/industry/${encodeURIComponent(industryCode)}/unlisted/overview`,
    UnlistedOverviewSchema,
  );
}
