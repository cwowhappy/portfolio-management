import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import Thermometer from "@/components/valuation/Thermometer";

describe("Thermometer", () => {
  it("null 显示积累中", () => {
    render(<Thermometer value={null} />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
  });
  it("30 以下显示绿色档", () => {
    render(<Thermometer value={20} />);
    expect(screen.getByText("20")).toBeTruthy();
    expect(screen.getByText(/低估/)).toBeTruthy();
  });
});
