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
