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
