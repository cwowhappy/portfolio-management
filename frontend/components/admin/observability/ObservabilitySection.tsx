"use client";

import TraceTable from "./TraceTable";
import CostCharts from "./CostCharts";
import LatencyCharts from "./LatencyCharts";
import PromptAssetPanel from "./PromptAssetPanel";
import EvalRunsPanel from "./EvalRunsPanel";

/**
 * 可观测性与评测看板（MS-30 F1，设计规格 §7.2）：与 McpTokenSection 平级挂入
 * AdminBoard 的四区块——①工具调用明细 ②成本看板 ③时延看板 ④提示词版本 + 评测运行历史。
 * 子区块 props 无（自取数），各自带 requestSeqRef 竞态守卫；观测无数据时块级空态引导。
 */
export default function ObservabilitySection() {
  return (
    <section aria-label="可观测性与评测" className="mt-8" data-testid="observability-section">
      <h2 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">
        可观测性与评测
        <span className="ml-2 text-[12px] text-[color:var(--color-ink-faint)]">
          （对话产生后自动采集）
        </span>
      </h2>
      <TraceTable />
      <CostCharts />
      <LatencyCharts />
      <section aria-label="提示词版本与评测运行" className="mt-8">
        <h3 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">
          提示词版本与评测运行
        </h3>
        <div className="mt-3 grid gap-6 xl:grid-cols-2">
          <PromptAssetPanel />
          <EvalRunsPanel />
        </div>
      </section>
    </section>
  );
}
