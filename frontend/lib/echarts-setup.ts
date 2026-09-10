// ★ 全仓库唯一允许 import echarts 的文件（eslint.config.mjs 禁裸 'echarts' 入口）。
// 只注册页面当前用到的图表；聊天流阶段再补 Candlestick/DataZoom/Dataset（见 05 §4.4）。
import * as echarts from "echarts/core";
import { PieChart, BarChart, LineChart } from "echarts/charts";
import {
  TooltipComponent,
  GridComponent,
  LegendComponent,
} from "echarts/components";
import { LabelLayout, UniversalTransition } from "echarts/features";
import { CanvasRenderer } from "echarts/renderers";
import type { ComposeOption } from "echarts/core";
import type {
  PieSeriesOption,
  BarSeriesOption,
  LineSeriesOption,
} from "echarts/charts";
import type {
  TooltipComponentOption,
  GridComponentOption,
  LegendComponentOption,
} from "echarts/components";

echarts.use([
  PieChart,
  BarChart,
  LineChart,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  LabelLayout,
  UniversalTransition,
  CanvasRenderer,
]);

/** 本项目用到的 option 类型（按需组合，随注册集同步扩展） */
export type ECOption = ComposeOption<
  | PieSeriesOption
  | BarSeriesOption
  | LineSeriesOption
  | TooltipComponentOption
  | GridComponentOption
  | LegendComponentOption
>;

export { echarts };
