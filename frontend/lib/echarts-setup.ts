// ★ 全仓库唯一 echarts.use() 注册点（子路径 import 见 ESLint 规则，eslint.config.mjs 禁裸 'echarts' 入口）。
// 页面（Pie/Bar/Line）与聊天流阶段（candlestick + dataZoom，见 05 §4.4）均已注册。
// 偏差记录：不加 DatasetComponent——builders 不用 dataset transform，YAGNI。
import * as echarts from "echarts/core";
import { PieChart, BarChart, LineChart, CandlestickChart } from "echarts/charts";
import {
  TooltipComponent,
  GridComponent,
  LegendComponent,
  DataZoomComponent,
} from "echarts/components";
import { LabelLayout, UniversalTransition } from "echarts/features";
import { CanvasRenderer } from "echarts/renderers";
import type { ComposeOption } from "echarts/core";
import type {
  PieSeriesOption,
  BarSeriesOption,
  LineSeriesOption,
  CandlestickSeriesOption,
} from "echarts/charts";
import type {
  TooltipComponentOption,
  GridComponentOption,
  LegendComponentOption,
  DataZoomComponentOption,
} from "echarts/components";

echarts.use([
  PieChart,
  BarChart,
  LineChart,
  CandlestickChart,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  DataZoomComponent,
  LabelLayout,
  UniversalTransition,
  CanvasRenderer,
]);

/** 本项目用到的 option 类型（按需组合，随注册集同步扩展） */
export type ECOption = ComposeOption<
  | PieSeriesOption
  | BarSeriesOption
  | LineSeriesOption
  | CandlestickSeriesOption
  | TooltipComponentOption
  | GridComponentOption
  | LegendComponentOption
  | DataZoomComponentOption
>;

export { echarts };
