import { describe, it, expect } from "vitest";
// 冒烟：真实加载注册模块不抛错（echarts.use 幂等；jsdom 无 canvas 但 use 只注册不渲染）
import { echarts, type ECOption } from "@/lib/echarts-setup";

describe("echarts-setup（冒烟）", () => {
  it("模块加载完成 use 注册不抛错，且导出 echarts 实例与 ECOption 类型", () => {
    expect(echarts).toBeTruthy();
    expect(typeof echarts.use).toBe("function");
    const opt: ECOption = { series: [{ type: "candlestick", data: [[1, 2, 0.5, 3]] }] };
    expect(opt).toBeTruthy();
  });
});
