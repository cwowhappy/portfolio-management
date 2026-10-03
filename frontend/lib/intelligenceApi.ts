import { z } from "zod";
import { request } from "./http";

// 情报工作台 api 层（P4 Task 3）：端点/参数逐字对齐后端 IntelligenceController +
// IntelligenceSubscriptionController（T2 契约速查表 R1~R8 / S1~S2 / B1~B2）。
// 契约口径（CS01）：可空字段后端序列化为 JSON null（非缺省键），一律 .nullable()；
// Instant 为 ISO-8601 UTC 字符串（2026-09-30T08:00:00Z）、LocalDate 为 yyyy-MM-dd；
// 分页由后端夹紧回显（page≥1、pageSize 1..100），客户端只校验不预夹。
// 同源反代自动带 Cookie，CSRF 生产已关（ADR-0007），写方法无需任何 token。

// —— 枚举（后端 domain/intelligence 枚举名，drift 在边界报错而非深渲染崩溃）——

export const NewsDirectionSchema = z.enum(["BULLISH", "BEARISH", "NEUTRAL"]);
export const AnnouncementTypeSchema = z.enum([
  "INCREASE_HOLD",
  "DECREASE_HOLD",
  "BUYBACK",
  "PLACEMENT",
  "RELATED_TRANSACTION",
  "EARNINGS_FORECAST",
  "EARNINGS_FLASH",
  "PERIODIC_REPORT",
  "EQUITY_INCENTIVE",
  "DELISTING_RISK",
  "OTHER",
]);
export const PolicyDirectionSchema = z.enum(["EASING", "TIGHTENING", "NEUTRAL"]);
export const PolicyStrengthSchema = z.enum(["HIGH", "MEDIUM", "LOW"]);
export const PolicyConfidenceSchema = z.enum(["HIGH", "LOW"]);
export const BriefStatusSchema = z.enum(["GENERATED", "EMPTY_SIMPLE", "FAILED"]);

export type NewsDirection = z.infer<typeof NewsDirectionSchema>;
export type AnnouncementType = z.infer<typeof AnnouncementTypeSchema>;
export type PolicyDirection = z.infer<typeof PolicyDirectionSchema>;
export type BriefStatus = z.infer<typeof BriefStatusSchema>;

// —— 条目视图（R1~R3/R5/R8 条目形状）——

/** 新闻条目：direction/importance 未抽取为 null；keyNumbers/stockCodes 恒数组（空集归一）。 */
export const NewsItemSchema = z.object({
  title: z.string(),
  summary: z.string(),
  direction: NewsDirectionSchema.nullable(),
  importance: z.number().int().nullable(),
  keyNumbers: z.array(z.string()),
  stockCodes: z.array(z.string()),
  url: z.string(),
  publishedAt: z.string(),
});
export type NewsItem = z.infer<typeof NewsItemSchema>;

/** 业绩要点六字段（F07 契约）：五个数值字段未披露为 null 且字段名进 undisclosed，严禁编造。 */
export const AnnouncementMetricsSchema = z.object({
  revenueYi: z.number().nullable(),
  netProfitYi: z.number().nullable(),
  netProfitYoyPct: z.number().nullable(),
  deductedProfitYi: z.number().nullable(),
  grossMarginPct: z.number().nullable(),
  dividendDesc: z.string().nullable(),
  undisclosed: z.array(z.string()),
});
export type AnnouncementMetrics = z.infer<typeof AnnouncementMetricsSchema>;

/** 公告条目：未抽取行 metrics/annTypeSource 为 null、annTypes 归一空数组。 */
export const AnnouncementItemSchema = z.object({
  title: z.string(),
  stockCode: z.string(),
  stockName: z.string(),
  annTypes: z.array(AnnouncementTypeSchema),
  annTypeSource: z.string().nullable(),
  metrics: AnnouncementMetricsSchema.nullable(),
  pdfUrl: z.string(),
  publishedAt: z.string(),
});
export type AnnouncementItem = z.infer<typeof AnnouncementItemSchema>;

/** 政策条目：isPolicy=false 为非政策兜底行（展示层据此降权标注）。 */
export const PolicyItemSchema = z.object({
  title: z.string(),
  direction: PolicyDirectionSchema,
  strength: PolicyStrengthSchema,
  areas: z.array(z.string()),
  summary: z.string(),
  confidence: PolicyConfidenceSchema,
  isPolicy: z.boolean(),
  url: z.string(),
  publishedAt: z.string(),
});
export type PolicyItem = z.infer<typeof PolicyItemSchema>;

/** 简报档列表条目（R4）：正文不进列表，点开走 fetchBriefDetail。 */
export const BriefItemSchema = z.object({
  tradeDate: z.string(),
  status: BriefStatusSchema,
  topStocks: z.array(z.string()),
  model: z.string(),
  generatedAt: z.string(),
});
export type BriefItem = z.infer<typeof BriefItemSchema>;

/** 简报档详情（R5）：failReason 仅 FAILED 非 null。 */
export const BriefDetailSchema = z.object({
  tradeDate: z.string(),
  contentMd: z.string(),
  topStocks: z.array(z.string()),
  status: BriefStatusSchema,
  failReason: z.string().nullable(),
  model: z.string(),
  generatedAt: z.string(),
});
export type BriefDetail = z.infer<typeof BriefDetailSchema>;

/** 宏观指标节（R6）：value/yoy 源未给出时为 null（缺席而非编造）；series 单期值非空。 */
export const MacroIndicatorSchema = z.object({
  indicator: z.string(),
  value: z.number().nullable(),
  yoy: z.number().nullable(),
  period: z.string(),
  periodType: z.string(),
  series: z.array(z.object({ period: z.string(), value: z.number() })),
});
export type MacroIndicator = z.infer<typeof MacroIndicatorSchema>;

/** 宏观总览（R6）：missing 显式列出缺失指标（F13 不编造不省略）。 */
export const MacroOverviewSchema = z.object({
  indicators: z.array(MacroIndicatorSchema),
  missing: z.array(z.string()),
  generatedAt: z.string(),
});
export type MacroOverview = z.infer<typeof MacroOverviewSchema>;

/** 宏观日历条目（R7）：expectedDate 是预期发布日（预期非承诺）。 */
export const MacroCalendarEntrySchema = z.object({
  indicator: z.string(),
  expectedDate: z.string(),
  frequency: z.string(),
  sourceSite: z.string(),
  updatedAt: z.string(),
});
export type MacroCalendarEntry = z.infer<typeof MacroCalendarEntrySchema>;
export const MacroCalendarSchema = z.array(MacroCalendarEntrySchema);
export type MacroCalendar = z.infer<typeof MacroCalendarSchema>;

/** 标的聚合（R8，F17）：政策路恒零计数 + 引导语；三路全零 empty=true（空态引导）。 */
export const StockIntelSchema = z.object({
  stockCode: z.string(),
  newsTotal: z.number().int(),
  news: z.array(NewsItemSchema),
  announcementTotal: z.number().int(),
  announcements: z.array(AnnouncementItemSchema),
  policyTotal: z.number().int(),
  policyNote: z.string(),
  empty: z.boolean(),
});
export type StockIntel = z.infer<typeof StockIntelSchema>;

// —— 分页信封（D20）：{items,total,page,pageSize}，page/pageSize 为后端夹紧后回显值 ——

const pageOf = <T extends z.ZodTypeAny>(item: T) =>
  z.object({
    items: z.array(item),
    total: z.number().int().min(0),
    page: z.number().int().min(1),
    pageSize: z.number().int().min(1),
  });

export const NewsPageSchema = pageOf(NewsItemSchema);
export const AnnouncementPageSchema = pageOf(AnnouncementItemSchema);
export const PolicyPageSchema = pageOf(PolicyItemSchema);
export const BriefPageSchema = pageOf(BriefItemSchema);
export type NewsPage = z.infer<typeof NewsPageSchema>;
export type AnnouncementPage = z.infer<typeof AnnouncementPageSchema>;
export type PolicyPage = z.infer<typeof PolicyPageSchema>;
export type BriefPage = z.infer<typeof BriefPageSchema>;

// —— 订阅与绑定（S1/S2/B1/B2）——

/** 订阅标的对：name 可空（后端 StockItem name 可空 ≤32 字符）。 */
export const SubscriptionStockSchema = z.object({
  code: z.string(),
  name: z.string().nullable(),
});
export type SubscriptionStock = z.infer<typeof SubscriptionStockSchema>;

/** 订阅视图：缺省实例（从未落库）updatedAt 为 null。 */
export const SubscriptionViewSchema = z.object({
  pushEnabled: z.boolean(),
  industries: z.array(z.string()),
  stocks: z.array(SubscriptionStockSchema),
  updatedAt: z.string().nullable(),
});
export type SubscriptionView = z.infer<typeof SubscriptionViewSchema>;

/** 绑定码（B1 201 回执）：code 为 6 位数字，expiresAt 供前端倒计时。 */
export const BindingCodeViewSchema = z.object({
  code: z.string(),
  expiresAt: z.string(),
});
export type BindingCodeView = z.infer<typeof BindingCodeViewSchema>;

// —— 查询入参（全部可缺省；from/to 为 yyyy-MM-dd，日期/枚举非法由后端 400）——

/** querystring 拼接（照 journalApi 先例）：falsy 片段剔除，全空则不带 ?。 */
function toQueryString(parts: Array<string | false | undefined>): string {
  const qs = parts.filter(Boolean).join("&");
  return qs ? `?${qs}` : "";
}

export interface NewsQuery {
  q?: string;
  stock?: string;
  industry?: string;
  from?: string;
  to?: string;
  minImportance?: number;
  page?: number;
  pageSize?: number;
}

/** R1 新闻库检索。 */
export const fetchNews = (query: NewsQuery = {}) =>
  request<NewsPage>(
    `/api/intelligence/news${toQueryString([
      query.q && `q=${encodeURIComponent(query.q)}`,
      query.stock && `stock=${query.stock}`,
      query.industry && `industry=${query.industry}`,
      query.from && `from=${query.from}`,
      query.to && `to=${query.to}`,
      query.minImportance != null && `minImportance=${query.minImportance}`,
      query.page != null && `page=${query.page}`,
      query.pageSize != null && `pageSize=${query.pageSize}`,
    ])}`,
    "GET",
    undefined,
    NewsPageSchema,
  );

export interface AnnouncementsQuery {
  stock?: string;
  type?: AnnouncementType;
  q?: string;
  from?: string;
  to?: string;
  major?: boolean;
  page?: number;
  pageSize?: number;
}

/** R2 公告库检索：type 为后端枚举名，major=true 只看重大（六字段业绩要点）公告。 */
export const fetchAnnouncements = (query: AnnouncementsQuery = {}) =>
  request<AnnouncementPage>(
    `/api/intelligence/announcements${toQueryString([
      query.stock && `stock=${query.stock}`,
      query.type && `type=${query.type}`,
      query.q && `q=${encodeURIComponent(query.q)}`,
      query.from && `from=${query.from}`,
      query.to && `to=${query.to}`,
      query.major != null && `major=${query.major}`,
      query.page != null && `page=${query.page}`,
      query.pageSize != null && `pageSize=${query.pageSize}`,
    ])}`,
    "GET",
    undefined,
    AnnouncementPageSchema,
  );

export interface PoliciesQuery {
  q?: string;
  from?: string;
  to?: string;
  direction?: PolicyDirection;
  page?: number;
  pageSize?: number;
}

/** R3 政策库检索：direction 取向过滤（EASING 宽松 / TIGHTENING 收紧 / NEUTRAL 中性）。 */
export const fetchPolicies = (query: PoliciesQuery = {}) =>
  request<PolicyPage>(
    `/api/intelligence/policies${toQueryString([
      query.q && `q=${encodeURIComponent(query.q)}`,
      query.from && `from=${query.from}`,
      query.to && `to=${query.to}`,
      query.direction && `direction=${query.direction}`,
      query.page != null && `page=${query.page}`,
      query.pageSize != null && `pageSize=${query.pageSize}`,
    ])}`,
    "GET",
    undefined,
    PolicyPageSchema,
  );

export interface BriefsQuery {
  from?: string;
  to?: string;
  stock?: string;
  q?: string;
  page?: number;
  pageSize?: number;
}

/** R4 简报归档列表（元信息，无正文）。 */
export const fetchBriefs = (query: BriefsQuery = {}) =>
  request<BriefPage>(
    `/api/intelligence/briefs${toQueryString([
      query.from && `from=${query.from}`,
      query.to && `to=${query.to}`,
      query.stock && `stock=${query.stock}`,
      query.q && `q=${encodeURIComponent(query.q)}`,
      query.page != null && `page=${query.page}`,
      query.pageSize != null && `pageSize=${query.pageSize}`,
    ])}`,
    "GET",
    undefined,
    BriefPageSchema,
  );

/** R5 简报档详情：缺档后端 404（message「该交易日无简报档」语义），调用方按未存档处理。 */
export const fetchBriefDetail = (tradeDate: string) =>
  request<BriefDetail>(
    `/api/intelligence/briefs/${tradeDate}`,
    "GET",
    undefined,
    BriefDetailSchema,
  );

export interface MacroQuery {
  /** 指标码（CPI/PPI/PMI/LPR/AFMI/TY1Y/TY10Y）；空/缺省 = 全七指标。 */
  indicators?: string[];
  /** 每指标序列期数（后端夹 1..60，缺省 5）。 */
  limit?: number;
}

/** R6 宏观总览：指标节（latest + 近 N 期序列）+ missing 显式缺失列表。 */
export const fetchMacro = (query: MacroQuery = {}) =>
  request<MacroOverview>(
    `/api/intelligence/macro${toQueryString([
      query.indicators?.length ? `indicators=${query.indicators.join(",")}` : undefined,
      query.limit != null && `limit=${query.limit}`,
    ])}`,
    "GET",
    undefined,
    MacroOverviewSchema,
  );

/** R7 宏观日历：未来 days 天（后端夹 1..30，缺省 7）的指标预期发布日程。 */
export const fetchMacroCalendar = (days?: number) =>
  request<MacroCalendar>(
    `/api/intelligence/macro/calendar${toQueryString([days != null && `days=${days}`])}`,
    "GET",
    undefined,
    MacroCalendarSchema,
  );

/** R8 标的情报聚合：新闻/公告两路最新条目 + 计数；空白码后端 422。 */
export const fetchStockIntel = (code: string) =>
  request<StockIntel>(
    `/api/intelligence/stocks/${encodeURIComponent(code)}`,
    "GET",
    undefined,
    StockIntelSchema,
  );

/** S1 取订阅视图：无行后端物化缺省（pushEnabled=true 空集，updatedAt=null）。 */
export const getSubscription = () =>
  request<SubscriptionView>(
    "/api/intelligence/subscription",
    "GET",
    undefined,
    SubscriptionViewSchema,
  );

/** S2 全量替换保存：pushEnabled 必填；industries/stocks 显式整组提交（缺省即清空该组）。 */
export interface UpdateSubscriptionInput {
  pushEnabled: boolean;
  industries: string[];
  stocks: SubscriptionStock[];
}

export const updateSubscription = (input: UpdateSubscriptionInput) =>
  request<SubscriptionView>(
    "/api/intelligence/subscription",
    "PUT",
    input,
    SubscriptionViewSchema,
  );

/** B1 生成绑定码（201）：6 位数字 + expiresAt（TTL 缺省 10 分钟，前端倒计时消费）。 */
export const createBindingCode = () =>
  request<BindingCodeView>(
    "/api/intelligence/subscription/binding-code",
    "POST",
    undefined,
    BindingCodeViewSchema,
  );

/** B2 解绑（幂等，未绑定同 204）。 */
export const unbind = () => request<void>("/api/intelligence/subscription/binding", "DELETE");
