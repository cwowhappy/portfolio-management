import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import StatCard from "@/components/valuation/StatCard";

describe("StatCard", () => {
  it("展示标题/数值/分位，分位为 null 显示积累中", () => {
    render(<StatCard title="全A PE 中位数" value={19.14} unit="" percentile={null} />);
    expect(screen.getByText("全A PE 中位数")).toBeTruthy();
    expect(screen.getByText("19.14")).toBeTruthy();
    expect(screen.getByText(/积累中/)).toBeTruthy();
  });
});
