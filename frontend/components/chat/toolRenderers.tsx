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
import { z } from "zod";
import { ChartCard, type ChartCardBuilder } from "@/components/chat/charts/ChartCard";
import DraftCard from "@/components/chat/DraftCard";
import { buildCandlestickOption, buildLineOption, buildBarOption } from "@/components/charts/optionBuilders";
import { KlineParamsSchema, ValuationParamsSchema, OverviewParamsSchema, FinancialsParamsSchema, ScreeningParamsSchema, FinancialsTrendParamsSchema, IndustryParamsSchema, PortfolioParamsSchema, AllocationParamsSchema } from "@/lib/tool-params";
import { extractResearchDraftJson } from "@/lib/research-draft";
import { buildPieOption } from "@/components/charts/optionBuilders";

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
  // ===== MS-12：4 个 table 工具不传 builder（ChartCard 内接 DataTable），pie 传 buildPieOption =====
  useRenderTool({
    name: "screen_stocks",
    parameters: ScreeningParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "analyze_financials",
    parameters: FinancialsTrendParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "analyze_industry",
    parameters: IndustryParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "suggest_allocation",
    parameters: AllocationParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "analyze_portfolio",
    parameters: PortfolioParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildPieOption as ChartCardBuilder} />,
  });
  // ===== invest-sop P1（Ruling-1）：research_draft 草稿卡——按工具名注册（工具名在 tool call
  // 块确定可得，不扫描助手消息），渲染器从工具结果文本提取 ```research-draft 围栏内 JSON 交
  // DraftCard；无围栏（参数错误文本）原样透传 → DraftCard 降级卡。参数 schema 为具名重载
  // 必填的类型载体（运行时不校验，ToolCallRenderer 直接透传 partialJSONParse 产物），渲染
  // 不消费 parameters，就地定义而不入 tool-params.ts（本任务限定只动四文件）。 =====
  useRenderTool({
    name: "research_draft",
    parameters: z.object({
      stage: z.string(),
      draftJson: z.string(),
    }),
    render: (p) => {
      if (p.status !== "complete" || typeof p.result !== "string") {
        return (
          <div className="tool-card running my-2 w-full max-w-[560px] px-3 py-2 text-xs text-[color:var(--color-ink-faint)]">
            {p.name} 执行中…
          </div>
        );
      }
      return <DraftCard raw={extractResearchDraftJson(p.result) ?? p.result} />;
    },
  });
  return null;
}
