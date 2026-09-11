"use client";
// 状态机壳（05 §4.6，2026-09-11 澄清版）：单一 ChartSpecSchema，按 type 分发。
// P4：table 变体接 DataTable（不再降级）；option 在单一 useMemo 内构建（不 conditional hook）。
import { useMemo } from "react";
import { ChartSpecSchema, type ChartSpec } from "@/lib/chart-spec";
import { getPalette } from "@/lib/chart-theme";
import { EChart } from "@/components/charts/EChart";
import { DataTable } from "@/components/chat/charts/DataTable";
import type { ECOption } from "@/lib/echarts-setup";
import type { BuilderStyle } from "@/components/charts/optionBuilders";

export type ChartCardBuilder = (spec: ChartSpec, style?: BuilderStyle) => ECOption;

/** 后端错误内容嗅探（05 §3.5：AguiEvent$ToolCallResult 无 state/error 字段，只能嗅探内容）。
 *  只认两种形态：错误文案 / 顶层 error 键（^{"error"）——table 行内合法 error 键不误伤。 */
function isToolError(result: string): boolean {
  return /tool execution failed|工具执行失败/i.test(result) || /^\s*{\s*"error"/.test(result);
}

export function ChartCard({ status, result, name, builder }: {
  status: "inProgress" | "executing" | "complete";
  result?: string;
  name: string;
  builder?: ChartCardBuilder;
}) {
  const parsed = useMemo(() => {
    if (status !== "complete" || typeof result !== "string") return null;
    if (isToolError(result)) return { degrade: true as const };
    try {
      const check = ChartSpecSchema.safeParse(JSON.parse(result));
      if (!check.success) return { degrade: true as const };
      const spec = check.data;
      if (spec.type === "table") return { spec, table: true as const }; // P4：接 DataTable，不再降级
      if (!builder) return { degrade: true as const };
      const p = getPalette();
      // 空串规约为 undefined（jsdom/SSR 取不到 CSS 变量时），让 builder 的 ?? FALLBACK 兜底生效——
      // ?? 不回退空串，直接透传 "" 会把 K 线实体色置空。生产路径解析值为非空 hex，语义不变。
      return {
        spec,
        option: builder(spec, { up: p.up || undefined, down: p.down || undefined }),
      };
    } catch {
      return { degrade: true as const };
    }
  }, [status, result, builder]);

  if (parsed == null)
    return (
      <div className="tool-card running my-2 w-full max-w-[560px] px-3 py-2 text-xs text-[color:var(--color-ink-faint)]">
        {name} 执行中…
      </div>
    );
  // 判别式真值收窄（同下 parsed.degrade 模式）：不用 "table" in parsed——in 无法剔除
  // 未声明该键的联合成员，parsed.spec 会带上 undefined；.table 为 true|undefined 可正确收窄。
  if (parsed.table)
    return (
      <div className="tool-card my-2 w-full max-w-[560px] px-3 py-2">
        <div className="mb-1 text-xs font-medium text-[color:var(--color-ink-dim)]">{parsed.spec.title}</div>
        <DataTable spec={parsed.spec} />
      </div>
    );
  if (parsed.degrade)
    return (
      <details className="tool-card my-2 w-full max-w-[560px] px-3 py-2 text-xs">
        <summary className="cursor-pointer text-[color:var(--color-ink-dim)]">数据异常（原始结果折叠）</summary>
        <pre className="mt-2 max-h-[320px] overflow-auto whitespace-pre-wrap break-all text-[color:var(--color-ink-faint)]">
          {result!.slice(0, 2000)}
        </pre>
      </details>
    );
  const height = parsed.spec.type === "candlestick" ? 360 : 240;
  return <EChart option={parsed.option} height={height} testid="chart-card-chart" />;
}
