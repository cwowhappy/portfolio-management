import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import {
  ConfidenceBanner,
  DisclaimerNote,
  TrustMessageAdvisories,
} from "@/components/shared/TrustAdvisories";
import { TrustPayloadSchema, trustStore, type TrustPayload } from "@/lib/trustMeta";

afterEach(() => cleanup());

/**
 * fixture 取服务层真实 payload 形态（memory 教训：展示层 fixture 失真）：
 * B6/B7 契约——advice/confidence 仅命中时携带；signals 为「线名:值」串
 * （unverified_ratio 比例两位小数去一位尾零如 0.4/0.75，其余计数整数）。
 */
function parsePayload(wire: unknown): TrustPayload {
  const parsed = TrustPayloadSchema.safeParse(wire);
  if (!parsed.success) {
    throw new Error("fixture 不是合法 payload v1: " + JSON.stringify(parsed.error.issues));
  }
  return parsed.data;
}

describe("ConfidenceBanner（命中信号人话映射 + 核实路径静态映射）", () => {
  it("七类信号名全量映射为多信号列表（线序渲染，data-signal 保留原名）", () => {
    render(
      <ConfidenceBanner
        signals={[
          "unverified_ratio:0.4",
          "stale_quotes:1",
          "stale_financials:2",
          "stale_macro:1",
          "tool_failures:1",
          "corrections:2",
          "correction_failed:1",
        ]}
      />,
    );
    const items = screen.getAllByTestId("confidence-signal");
    expect(items.map((li) => li.textContent)).toEqual([
      "40% 数字未溯源",
      "数据时间戳陈旧",
      "数据时间戳陈旧",
      "数据时间戳陈旧",
      "工具调用失败",
      "校验修正已介入",
      "校验修正已介入",
    ]);
    expect(items[5].getAttribute("data-signal")).toBe("corrections");
    expect(items[6].getAttribute("data-signal")).toBe("correction_failed");
  });

  it("unverified_ratio value 解析为整百分比（线格式鲁棒：0.75 / 0.40 / 1.0），解析失败降级无数字文案", () => {
    const { rerender } = render(<ConfidenceBanner signals={["unverified_ratio:0.75"]} />);
    expect(screen.getByTestId("confidence-signal").textContent).toBe("75% 数字未溯源");
    rerender(<ConfidenceBanner signals={["unverified_ratio:0.40"]} />);
    expect(screen.getByTestId("confidence-signal").textContent).toBe("40% 数字未溯源");
    rerender(<ConfidenceBanner signals={["unverified_ratio:1.0"]} />);
    expect(screen.getByTestId("confidence-signal").textContent).toBe("100% 数字未溯源");
    // 值缺失/不可解析：不猜数字，降级为不带数值的文案（勿对 value 做字符串匹配）
    rerender(<ConfidenceBanner signals={["unverified_ratio"]} />);
    expect(screen.getByTestId("confidence-signal").textContent).toBe("数字未溯源");
  });

  it("核实路径静态映射：财报族→巨潮资讯网、宏观族→统计局/央行官网、其余→通用建议核实", () => {
    const { rerender } = render(<ConfidenceBanner signals={["stale_financials:2"]} />);
    expect(screen.getByTestId("confidence-suggestion").textContent).toBe("巨潮资讯网");
    rerender(<ConfidenceBanner signals={["stale_macro:1"]} />);
    expect(screen.getByTestId("confidence-suggestion").textContent).toBe("统计局/央行官网");
    rerender(<ConfidenceBanner signals={["tool_failures:1"]} />);
    expect(screen.getByTestId("confidence-suggestion").textContent).toBe("建议核实数据");
  });

  it("多信号取首个映射（按信号线序）：unverified_ratio 压过 stale_macro → 行情建议", () => {
    render(<ConfidenceBanner signals={["unverified_ratio:0.4", "stale_macro:1"]} />);
    expect(screen.getByTestId("confidence-suggestion").textContent).toBe(
      "建议重问最新价或查看东方财富行情页",
    );
  });

  it("首个映射按线序而非固定优先级：stale_macro 在前取宏观建议", () => {
    render(<ConfidenceBanner signals={["stale_macro:1", "stale_quotes:2"]} />);
    expect(screen.getByTestId("confidence-suggestion").textContent).toBe("统计局/央行官网");
  });

  it("空/缺 signals 不渲染；未知信号名整体不渲染横幅（宁可少标）", () => {
    const { rerender } = render(<ConfidenceBanner signals={[]} />);
    expect(screen.queryByTestId("confidence-banner")).toBeNull();
    rerender(<ConfidenceBanner signals={undefined} />);
    expect(screen.queryByTestId("confidence-banner")).toBeNull();
    rerender(<ConfidenceBanner signals={["future_signal:1"]} />);
    expect(screen.queryByTestId("confidence-banner")).toBeNull();
  });
});

describe("DisclaimerNote（advice.flag → 后端透传文案 + by 小标签）", () => {
  it("渲染后端透传 text（前端不内置常量）与 by 三态小标签（自声明/词表/两者）", () => {
    const { rerender } = render(
      <DisclaimerNote
        advice={{ flag: true, by: "self", text: "以上内容由 AI 生成，仅供参考。" }}
      />,
    );
    expect(screen.getByTestId("disclaimer-note").textContent).toContain(
      "以上内容由 AI 生成，仅供参考。",
    );
    expect(screen.getByTestId("disclaimer-by").textContent).toBe("自声明");
    rerender(<DisclaimerNote advice={{ flag: true, by: "lexicon", text: "以上内容由 AI 生成。" }} />);
    expect(screen.getByTestId("disclaimer-by").textContent).toBe("词表");
    rerender(<DisclaimerNote advice={{ flag: true, by: "both", text: "以上内容由 AI 生成。" }} />);
    expect(screen.getByTestId("disclaimer-by").textContent).toBe("两者");
  });

  it("flag 缺失/false、text 缺失不渲染", () => {
    const { rerender } = render(<DisclaimerNote advice={{ flag: false, by: "self" }} />);
    expect(screen.queryByTestId("disclaimer-note")).toBeNull();
    // flag=true 但 text 缺（后端 disclaimer-text 配置为空白）：无内容可示，同样不渲染
    rerender(<DisclaimerNote advice={{ flag: true, by: "self" }} />);
    expect(screen.queryByTestId("disclaimer-note")).toBeNull();
    rerender(<DisclaimerNote advice={null} />);
    expect(screen.queryByTestId("disclaimer-note")).toBeNull();
  });
});

describe("TrustMessageAdvisories（同消息级订阅：横幅与 disclaimer 并存不互斥）", () => {
  it("payload 同时携带 confidence 与 advice → 两组件同时渲染", () => {
    trustStore.applyAnchors(
      "tadv-both",
      parsePayload({
        v: 1,
        anchors: [],
        stats: { verified: 0, sourced: 0, unverified: 3 },
        advice: {
          flag: true,
          by: "lexicon",
          text: "以上内容由 AI 生成，仅供参考，不构成任何投资建议。",
        },
        confidence: { signals: ["unverified_ratio:0.4", "tool_failures:1"] },
      }),
    );
    render(<TrustMessageAdvisories messageId="tadv-both" />);
    expect(screen.getByTestId("confidence-banner")).toBeTruthy();
    expect(screen.getAllByTestId("confidence-signal").map((li) => li.textContent)).toEqual([
      "40% 数字未溯源",
      "工具调用失败",
    ]);
    expect(screen.getByTestId("confidence-suggestion").textContent).toBe(
      "建议重问最新价或查看东方财富行情页",
    );
    expect(screen.getByTestId("disclaimer-note").textContent).toContain(
      "以上内容由 AI 生成，仅供参考，不构成任何投资建议。",
    );
    expect(screen.getByTestId("disclaimer-by").textContent).toBe("词表");
  });

  it("payload 无 confidence/advice 键 → 零渲染；store 无记录同（旧消息自然降级）", () => {
    trustStore.applyAnchors(
      "tadv-plain",
      parsePayload({
        v: 1,
        anchors: [{ snippet: "1520.33元", occ: 1, state: "verified" }],
        stats: { verified: 1, sourced: 0, unverified: 0 },
      }),
    );
    const { rerender } = render(<TrustMessageAdvisories messageId="tadv-plain" />);
    expect(screen.queryByTestId("confidence-banner")).toBeNull();
    expect(screen.queryByTestId("disclaimer-note")).toBeNull();
    rerender(<TrustMessageAdvisories messageId="tadv-none" />);
    expect(screen.queryByTestId("confidence-banner")).toBeNull();
    expect(screen.queryByTestId("disclaimer-note")).toBeNull();
  });
});
