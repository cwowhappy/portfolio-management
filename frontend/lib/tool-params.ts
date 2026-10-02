import { z } from "zod";

// useRenderTool 具名重载的 parameters 必填（@copilotkit/react-core 1.70.1 .d.mts 核实；
// zod 3.25 实现 Standard Schema v1）。入参与后端 @ToolParam 定义对齐（InvestTools.java）。
export const KlineParamsSchema = z.object({
  code: z.string().describe("6位A股代码"),
  period: z.string().optional().describe("day/week/month，默认 day"),
  limit: z.number().optional().describe("返回根数，默认 120，最大 500"),
});
export const ValuationParamsSchema = z.object({});
export const OverviewParamsSchema = z.object({});
export const FinancialsParamsSchema = z.object({
  code: z.string().describe("6位A股代码"),
});
// ===== MS-12（F08~F12，与 InvestTools/UserInvestTools @ToolParam 对齐）=====
export const ScreeningParamsSchema = z.object({
  peTtmMax: z.number().optional(), pbMax: z.number().optional(),
  dividendYieldMin: z.number().optional(), roeMin: z.number().optional(),
  roaMin: z.number().optional(), grossMarginMin: z.number().optional(),
  debtToAssetsMax: z.number().optional(), currentRatioMin: z.number().optional(),
  revenueYoyMin: z.number().optional(), netprofitYoyMin: z.number().optional(),
  totalMvMin: z.number().optional(), turnoverRateMin: z.number().optional(),
  industryCode: z.string().optional(), indexCode: z.string().optional(),
  sortBy: z.string().optional(), sortDirection: z.string().optional(),
  limit: z.number().optional(),
});
export const FinancialsTrendParamsSchema = z.object({
  code: z.string().describe("6位A股代码"),
});
export const IndustryParamsSchema = z.object({
  industryCode: z.string().optional().describe("申万一级行业码，空=全行业板面"),
});
export const PortfolioParamsSchema = z.object({});
export const AllocationParamsSchema = z.object({});
// ===== MS-20（P1 财经新闻采集与AI结构化）：search_news 与后端 @ToolParam 对齐 =====
export const SearchNewsParamsSchema = z.object({
  q: z.string().optional().describe("关键词，按标题近似匹配"),
  stock: z.string().optional().describe("标的代码，如 600519"),
  industry: z.string().optional().describe("申万一级行业码，如 801140"),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().describe("起始日期 yyyy-MM-dd（含）"),
  to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().describe("结束日期 yyyy-MM-dd（含）"),
  minImportance: z.number().optional().describe("重要度下限 0..100"),
  limit: z.number().optional().describe("返回条数，默认 10，最大 20"),
});
// ===== MS-21（P2 公告与财报要点采集）：search_announcements 与后端 @ToolParam 对齐 =====
export const SearchAnnouncementsParamsSchema = z.object({
  stock: z.string().optional().describe("标的代码，如 600519"),
  type: z.string().optional().describe("公告类型枚举名，如 BUYBACK"),
  from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().describe("起始日期 yyyy-MM-dd（含）"),
  to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional().describe("结束日期 yyyy-MM-dd（含）"),
  q: z.string().optional().describe("关键词，按标题近似匹配"),
  scope: z.enum(["all", "subscription", "holdings"]).optional().describe("检索范围，缺省 all"),
  limit: z.number().optional().describe("返回条数，默认 10，最大 20"),
});
