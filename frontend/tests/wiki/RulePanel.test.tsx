import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import RulePanel from "@/components/wiki/RulePanel";
import * as wikiApi from "@/lib/wikiApi";
import type { PrincipleRuleView } from "@/lib/types";

afterEach(() => cleanup());

const rules: PrincipleRuleView[] = [
  { id: 9, metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true,
    description: "单票≤20%", createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
  { id: 10, metric: "STOCK_PE_MAX", threshold: 40, enabled: false,
    description: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z" },
];

describe("RulePanel", () => {
  it("列表渲染指标中文名、按单位格式化阈值与说明", () => {
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    const first = screen.getByTestId("wiki-rule-9").textContent ?? "";
    expect(first).toContain("单票仓位上限");
    expect(first).toContain("20%"); // 0.2 → 20%
    expect(first).toContain("单票≤20%");
    const second = screen.getByTestId("wiki-rule-10").textContent ?? "";
    expect(second).toContain("个股PE上限");
    expect(second).toContain("40"); // 倍数原样
  });

  it("比例指标输入 % 存 0~1（20 → 0.2）", async () => {
    const createSpy = vi.spyOn(wikiApi, "createRule").mockResolvedValue(rules[0]);
    render(<RulePanel rules={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-rule-metric"), { target: { value: "SINGLE_POSITION_RATIO" } });
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "20" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true })));
  });

  it("倍数指标直接数值（40 → 40）", async () => {
    const createSpy = vi.spyOn(wikiApi, "createRule").mockResolvedValue(rules[1]);
    render(<RulePanel rules={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-rule-metric"), { target: { value: "STOCK_PE_MAX" } });
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "40" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(createSpy).toHaveBeenCalledWith(
      expect.objectContaining({ metric: "STOCK_PE_MAX", threshold: 40 })));
  });

  it("已配置指标下拉置灰（其余可选）", () => {
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    const select = screen.getByTestId("wiki-rule-metric") as HTMLSelectElement;
    const option = [...select.options].find((o) => o.value === "SINGLE_POSITION_RATIO");
    expect(option?.disabled).toBe(true);
    const other = [...select.options].find((o) => o.value === "STOCK_PB_MAX");
    expect(other?.disabled).toBe(false);
  });

  it("启停切换调 updateRule", async () => {
    const updateSpy = vi.spyOn(wikiApi, "updateRule").mockResolvedValue(rules[0]);
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    fireEvent.click(screen.getByTestId("wiki-rule-toggle-9"));
    await waitFor(() => expect(updateSpy).toHaveBeenCalledWith(9,
      expect.objectContaining({ threshold: 0.2, enabled: false })));
  });

  it("DUPLICATE_METRIC 错误行内展示", async () => {
    const createSpy = vi.spyOn(wikiApi, "createRule")
      .mockRejectedValue(new Error("该指标已有规则，请直接编辑既有规则"));
    render(<RulePanel rules={[]} onChanged={() => {}} />);
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "20" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(screen.getByText("该指标已有规则，请直接编辑既有规则")).toBeTruthy());
    expect(createSpy).toHaveBeenCalled();
  });

  it("编辑既有规则：点编辑预填（0.2 → 显示 20）、指标锁定、保存调 updateRule", async () => {
    const updateSpy = vi.spyOn(wikiApi, "updateRule").mockResolvedValue(rules[0]);
    render(<RulePanel rules={rules} onChanged={() => {}} />);
    fireEvent.click(screen.getByTestId("wiki-rule-edit-9"));
    expect((screen.getByTestId("wiki-rule-metric") as HTMLSelectElement).value).toBe("SINGLE_POSITION_RATIO");
    expect((screen.getByTestId("wiki-rule-metric") as HTMLSelectElement).disabled).toBe(true);
    expect((screen.getByTestId("wiki-rule-threshold") as HTMLInputElement).value).toBe("20");
    fireEvent.change(screen.getByTestId("wiki-rule-threshold"), { target: { value: "25" } });
    fireEvent.click(screen.getByTestId("wiki-rule-save"));
    await waitFor(() => expect(updateSpy).toHaveBeenCalledWith(9,
      expect.objectContaining({ metric: "SINGLE_POSITION_RATIO", threshold: 0.25 })));
  });
});
