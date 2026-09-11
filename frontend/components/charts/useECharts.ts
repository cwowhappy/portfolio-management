"use client";
// ~60 行自写封装：echarts-for-react 有 Next.js transpile 负担 + 双 init 反模式 + 3.x 撤包风险（05 §4.4 对比）。
import { useEffect, useRef } from "react";
import type { EChartsType } from "echarts/core";
// 值导入（非 import type）：echarts-setup 是全仓库唯一 echarts.use() 注册点，init 必须
// 经由它调用，否则图表/渲染器未注册——生产构建无任何模块值导入 setup 时，zrender init
// 抛「nC[a] is not a constructor」（空 painter 注册表），整页炸 error boundary（P5 e2e 实证）。
import { echarts, type ECOption } from "@/lib/echarts-setup";
import { ensureAppThemes, currentThemeName } from "@/lib/chart-theme";

export function useECharts(option: ECOption) {
  const ref = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<EChartsType | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    ensureAppThemes();
    const chart = echarts.init(el, currentThemeName());
    chartRef.current = chart;
    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(el);
    return () => {
      ro.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    chartRef.current?.setOption(option, { notMerge: true });
  }, [option]);

  return ref;
}
