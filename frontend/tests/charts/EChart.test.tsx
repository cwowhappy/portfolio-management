import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

// hook 已在 useECharts.test.tsx 单独覆盖；此处 mock hook 验证壳组件透传
vi.mock("@/components/charts/useECharts", () => ({
  useECharts: vi.fn(() => ({ current: null })),
}));

import { EChart } from "@/components/charts/EChart";

describe("EChart", () => {
  it("透传 testid 与显式高度", () => {
    render(<EChart option={{ series: [] }} height={240} testid="trend-chart" />);
    const el = screen.getByTestId("trend-chart");
    expect(el.style.height).toBe("240px");
    expect(el.style.width).toBe("100%");
  });

  it("默认高度 200", () => {
    render(<EChart option={{ series: [] }} testid="x" />);
    expect(screen.getByTestId("x").style.height).toBe("200px");
  });
});
