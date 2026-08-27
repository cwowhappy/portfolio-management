import { describe, it, expect, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import Thermometer from "@/components/valuation/Thermometer";

function barWidth(container: HTMLElement): string {
  const bar = container.querySelector('div[style*="width"]') as HTMLElement;
  return bar.style.width;
}

describe("Thermometer", () => {
  afterEach(() => {
    cleanup();
  });

  it("null 显示积累中", () => {
    render(<Thermometer value={null} />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
  });
  it("30 以下显示绿色档", () => {
    render(<Thermometer value={20} />);
    expect(screen.getByText("20")).toBeTruthy();
    expect(screen.getByText(/低估/)).toBeTruthy();
  });
  it("30 边界为中性档（amber）", () => {
    const { container } = render(<Thermometer value={30} />);
    expect(screen.getByText("30")).toBeTruthy();
    expect(screen.getByText(/中性/)).toBeTruthy();
    expect(barWidth(container)).toBe("30%");
  });
  it("70 边界为中性档（amber）", () => {
    const { container } = render(<Thermometer value={70} />);
    expect(screen.getByText("70")).toBeTruthy();
    expect(screen.getByText(/中性/)).toBeTruthy();
    expect(barWidth(container)).toBe("70%");
  });
  it("71 进入高估档（red）", () => {
    const { container } = render(<Thermometer value={71} />);
    expect(screen.getByText("71")).toBeTruthy();
    expect(screen.getByText(/高估/)).toBeTruthy();
    expect(barWidth(container)).toBe("71%");
  });
});
