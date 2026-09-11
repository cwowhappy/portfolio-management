"use client";
// 图表渲染器注册集合（05 §4.3，2026-09-11 澄清 + P4 补 get_financials：四个有真实数据源的工具）。
// 不传 agentId：headless 无 ChatConfigurationProvider，useRenderTool 匹配链中「无 agentId 的具名渲染器」
// 仍压过通配（05 §4.1 源码核实）。注意与 useInterrupt 不同——后者内部经 useAgent 解析 agentId，
// 缺省会抛「Agent 'default' not found」（ThreadArea.tsx:391 注释），渲染 hook 无此问题。
// builder 单点 cast：buildCandlestickOption 等只收 CandlestickSpec 等窄类型，直传撞
// ChartCardBuilder(spec: ChartSpec) 的严格函数参数逆变（TS2322）；ChartCard 内部经
// ChartSpecSchema 判别联合分发，spec.type 必与 builder 匹配，cast 安全。
// get_financials 走 table 分支，不传 builder。
import { useRenderTool } from "@copilotkit/react-core/v2";
import { ChartCard, type ChartCardBuilder } from "@/components/chat/charts/ChartCard";
import { buildCandlestickOption, buildLineOption, buildBarOption } from "@/components/charts/optionBuilders";
import { KlineParamsSchema, ValuationParamsSchema, OverviewParamsSchema, FinancialsParamsSchema } from "@/lib/tool-params";

export function ChartToolRenderers() {
  useRenderTool({
    name: "get_kline",
    parameters: KlineParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildCandlestickOption as ChartCardBuilder} />,
  });
  useRenderTool({
    name: "get_valuation",
    parameters: ValuationParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildLineOption as ChartCardBuilder} />,
  });
  useRenderTool({
    name: "get_market_overview",
    parameters: OverviewParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildBarOption as ChartCardBuilder} />,
  });
  useRenderTool({
    name: "get_financials",
    parameters: FinancialsParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  return null;
}
