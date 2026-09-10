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
]);

export type ChartSpec = z.infer<typeof ChartSpecSchema>;
export type PieSpec = Extract<ChartSpec, { type: "pie" }>;
export type BarSpec = Extract<ChartSpec, { type: "bar" }>;
export type LineSpec = Extract<ChartSpec, { type: "line" }>;
