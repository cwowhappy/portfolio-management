import { z } from "zod";

// 与后端 Java record 双端对齐（05 §2.1/§2.2）。本期只含页面在用的三种类型；
// candlestick/table 随聊天流阶段扩展。null = 数据缺口（估值序列缺失日）。
const seriesData = z.array(z.union([z.number(), z.null()]));

export const ChartSpecSchema = z.discriminatedUnion("type", [
  z.object({
    specVersion: z.literal(1),
    type: z.literal("pie"),
    title: z.string(),
    subtitle: z.string().optional(),
    data: z.array(z.object({ name: z.string(), value: z.number() })),
    unit: z.string().optional(),
  }),
  z.object({
    specVersion: z.literal(1),
    type: z.literal("bar"),
    title: z.string(),
    subtitle: z.string().optional(),
    categories: z.array(z.string()),
    series: z.array(z.object({ name: z.string(), data: seriesData })),
    horizontal: z.boolean().optional(),
    unit: z.string().optional(),
  }),
  z.object({
    specVersion: z.literal(1),
    type: z.literal("line"),
    title: z.string(),
    subtitle: z.string().optional(),
    categories: z.array(z.string()),
    series: z.array(
      z.object({
        name: z.string(),
        data: seriesData,
        area: z.boolean().optional(),
      }),
    ),
    unit: z.string().optional(),
  }),
  z.object({
    specVersion: z.literal(1),
    type: z.literal("candlestick"),
    title: z.string(),
    subtitle: z.string().optional(),
    symbol: z.string(),                                  // "600519 贵州茅台"
    period: z.string(),                                  // "day"|"week"|"month"（与工具入参一致，展示用）
    dates: z.array(z.string()),                          // 交易日
    klines: z.array(z.tuple([z.number(), z.number(), z.number(), z.number()])),
    //                        ⚠ 顺序 = [开, 收, 低, 高]（ECharts 官方约定）
    volumes: z.array(z.number()).optional(),             // 成交量副图
    mas: z.array(z.object({
      name: z.string(),
      data: z.array(z.union([z.number(), z.null()])),    // null = 预热缺口，与 dates 对齐
    })).optional(),                                      // MA 线后端算好
  }),
  z.object({
    specVersion: z.literal(1),
    type: z.literal("table"),
    title: z.string(),
    subtitle: z.string().optional(),
    columns: z.array(z.object({
      key: z.string(), label: z.string(),
      align: z.enum(["left", "right", "center"]).optional(),
      sortable: z.boolean().optional(),                  // 默认 true
    })).min(1),                                          // 空列拒绝（后端契约保证 ≥1 列）
    rows: z.array(z.record(z.string(), z.union([z.string(), z.number(), z.null()]))),
    defaultPageSize: z.number().optional(),              // 预留：卡片内表格可视高度（本期固定 360px 未消费）
  }),
]);

export type ChartSpec = z.infer<typeof ChartSpecSchema>;
export type PieSpec = Extract<ChartSpec, { type: "pie" }>;
export type BarSpec = Extract<ChartSpec, { type: "bar" }>;
export type LineSpec = Extract<ChartSpec, { type: "line" }>;
export type CandlestickSpec = Extract<ChartSpec, { type: "candlestick" }>;
export type TableSpec = Extract<ChartSpec, { type: "table" }>;
