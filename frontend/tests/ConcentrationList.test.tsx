import { afterEach, describe, it, expect } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import ConcentrationList from "@/components/portfolio/ConcentrationList";
import type { Concentration } from "@/lib/types";

const concentration: Concentration = {
  holdings: [
    { stockCode: "600519", stockName: "贵州茅台", marketValue: 160000, ratio: 25.5 },
    { stockCode: "600036", stockName: "招商银行", marketValue: 40000, ratio: 6.37 },
  ],
  top5Ratio: 31.87,
};

afterEach(() => {
  cleanup();
});

describe("ConcentrationList", () => {
  it("concentration 为 null 时渲染空态", () => {
    render(<ConcentrationList concentration={null} />);
    expect(screen.getByText("集中度分析")).toBeTruthy();
    expect(screen.getByText("暂无数据")).toBeTruthy();
  });

  it("holdings 为空时渲染空态", () => {
    render(<ConcentrationList concentration={{ holdings: [], top5Ratio: 0 }} />);
    expect(screen.getByText("暂无数据")).toBeTruthy();
  });

  it("渲染持仓列表与占比", () => {
    render(<ConcentrationList concentration={concentration} />);
    expect(screen.getByText("贵州茅台")).toBeTruthy();
    expect(screen.getByText("600519")).toBeTruthy();
    expect(screen.getByText("25.50%")).toBeTruthy();
    expect(screen.getByText("招商银行")).toBeTruthy();
    expect(screen.getByText("6.37%")).toBeTruthy();
  });

  it("top5Ratio 超过阈值时显示警示", () => {
    render(<ConcentrationList concentration={concentration} />);
    const warning = screen.getByText("前5大重仓占比超 20%");
    expect(warning.className).toContain("accent");
  });

  it("top5Ratio 恰好等于阈值时不显示警示", () => {
    render(<ConcentrationList concentration={{ ...concentration, top5Ratio: 20 }} />);
    expect(screen.queryByText(/前5大重仓占比超/)).toBeNull();
  });

  it("top5Ratio 低于阈值时不显示警示", () => {
    render(<ConcentrationList concentration={{ ...concentration, top5Ratio: 10 }} />);
    expect(screen.queryByText(/前5大重仓占比超/)).toBeNull();
  });
});
