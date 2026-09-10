"use client";
// ~60 行自写封装：echarts-for-react 有 Next.js transpile 负担 + 双 init 反模式 + 3.x 撤包风险（05 §4.4 对比）。
import { useEffect, useRef } from "react";
import { init } from "echarts/core";
import type { EChartsType } from "echarts/core";
import type { ECOption } from "@/lib/echarts-setup";
import { ensureAppThemes, currentThemeName } from "@/lib/chart-theme";

export function useECharts(option: ECOption) {
  const ref = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<EChartsType | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    ensureAppThemes();
    const chart = init(el, currentThemeName());
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
