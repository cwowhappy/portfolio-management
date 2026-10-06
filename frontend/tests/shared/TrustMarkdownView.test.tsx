import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import TrustMarkdownView, { CorrectionNotes, TrustMessageContent } from "@/components/shared/TrustMarkdownView";
import {
  TRUST_ANCHORS_EVENT,
  TRUST_CORRECTION_EVENT,
  TrustPayloadSchema,
  handleTrustCustomEvent,
  trustStore,
  type TrustPayload,
} from "@/lib/trustMeta";
import type { Message } from "@ag-ui/client";

afterEach(() => cleanup());

/**
 * fixture 取服务层真实 payload 形态（memory 教训：展示层 fixture 失真）：
 * 后端 TrustTurnReport#toPayload → SSE trust.anchors 帧（B5 TrustHookIntegrationTest 锁定）
 * → 前端 TrustPayloadSchema 宽松解析的产物。verified 锚带全量来源字段，unverified 缺键。
 */
const WIRE_PAYLOAD = {
  v: 1,
  anchors: [
    {
      snippet: "1520.33元",
      occ: 1,
      state: "verified",
      tool: "get_quote",
      args: { symbol: "600519.SH" },
      asOf: "2026-10-05 14:59:32",
      asOfKind: "data",
      raw: "1520.33",
    },
    {
      snippet: "15.23元",
      occ: 1,
      state: "sourced",
      tool: "mcp_tushare_quote",
      args: { code: "600519.SH" },
      asOf: "2026-10-06 09:30:00",
      asOfKind: "call",
    },
    { snippet: "38倍", occ: 1, state: "unverified" },
  ],
  stats: { verified: 1, sourced: 1, unverified: 1 },
  correction: { notes: ["⚠ 校验修正：原文误述 15.20元 → 已按工具返回改写"] },
};

function parsePayload(wire: unknown = WIRE_PAYLOAD): TrustPayload {
  const parsed = TrustPayloadSchema.safeParse(wire);
  if (!parsed.success) throw new Error("fixture 不是合法 payload v1: " + JSON.stringify(parsed.error.issues));
  return parsed.data;
}

describe("TrustMarkdownView（markdown + anchors → 角标落位渲染）", () => {
  it("三态角标按 anchors 数组序落在对应数字上（真实 payload 形态）", () => {
    const payload = parsePayload();
    render(
      <TrustMarkdownView
        content={"贵州茅台现价1520.33元，同业口径15.23元，历史估值约38倍。"}
        anchors={payload.anchors}
      />,
    );
    const badges = screen.getAllByTestId("trust-anchor-badge");
    expect(badges).toHaveLength(3);
    // 序号按 anchors 数组序（任务裁定：独立角标，共用编号降级为后续打磨）
    expect(badges.map((b) => b.textContent)).toEqual(["1", "2", "3"]);
    expect(badges.map((b) => b.getAttribute("data-anchor-state"))).toEqual([
      "verified",
      "sourced",
      "unverified",
    ]);
    // 角标紧邻对应数字：包裹元素（span[data-anchor-state]）内含 snippet 原文
    const wrap = badges[0].parentElement!;
    expect(wrap.textContent).toContain("1520.33元");
    expect(badges[1].parentElement?.textContent).toContain("15.23元");
    expect(badges[2].parentElement?.textContent).toContain("38倍");
  });

  it("浮层内容随角标渲染：verified 措辞（决策 #13）与来源字段", () => {
    render(
      <TrustMarkdownView
        content={"贵州茅台现价1520.33元。"}
        anchors={parsePayload().anchors.slice(0, 1)}
      />,
    );
    expect(screen.getByText("数值与工具返回一致")).toBeTruthy();
    expect(screen.queryByText(/已核实/)).toBeNull();
    expect(screen.getByText("get_quote")).toBeTruthy();
    expect(screen.getByText("symbol：600519.SH")).toBeTruthy();
    expect(screen.getByText("数据时间戳：2026-10-05 14:59:32")).toBeTruthy();
    expect(screen.getByText("工具返回原值：1520.33")).toBeTruthy();
  });

  it("occ 语义：同 snippet 第 2 次出现才挂角标（与替换定位同口径）", () => {
    const content = "现价15.2元，压力位也在15.2元。";
    render(
      <TrustMarkdownView
        content={content}
        anchors={[{ snippet: "15.2元", occ: 2, state: "verified" }]}
      />,
    );
    expect(screen.getAllByTestId("trust-anchor-badge")).toHaveLength(1);
    // 角标缀在第二次出现之后：段落直接文本（首个出现未被包裹）+ 包裹元素的 snippet + 序号
    const p = screen.getByText(/压力位也在/);
    expect(p.textContent?.startsWith("现价15.2元，压力位也在15.2元1")).toBe(true);
    expect(screen.getByTestId("trust-anchor-badge").parentElement?.textContent).toContain("15.2元");
  });

  it("occ 越界安全跳过：该锚不渲染，正文与其余角标不受影响", () => {
    render(
      <TrustMarkdownView
        content={"现价1520.33元。"}
        anchors={[
          { snippet: "1520.33元", occ: 5, state: "verified" },
          { snippet: "1520.33元", occ: 1, state: "sourced" },
        ]}
      />,
    );
    const badges = screen.getAllByTestId("trust-anchor-badge");
    expect(badges).toHaveLength(1);
    expect(badges[0].getAttribute("data-anchor-state")).toBe("sourced");
    expect(badges[0].textContent).toBe("2"); // 序号仍按 anchors 数组序（idx+1）
    expect(screen.getByText("1520.33元")).toBeTruthy();
  });

  it("修正兼容：anchors 携带替换后形态 snippet，对当前（已改写）文本直接定位", () => {
    // correction 替换后的消息文本 + B5 终态锚定（snippet 即 replacement 形态）
    render(
      <TrustMarkdownView
        content={"贵州茅台现价1520.33元，历史估值约38倍。"}
        anchors={[
          { snippet: "1520.33元", occ: 1, state: "verified", tool: "get_quote", asOf: "2026-10-05 14:59:32", asOfKind: "data", raw: "1520.33" },
          { snippet: "38倍", occ: 1, state: "unverified" },
        ]}
      />,
    );
    expect(screen.getAllByTestId("trust-anchor-badge")).toHaveLength(2);
  });

  it("代码块/行内代码内不挂角标（occ 全文计次，命中代码区间跳过）", () => {
    const content = "现价15.2元，脚本 `15.2元` 与代码块：\n\n```\n15.2元\n```";
    // occ=3 → 围栏代码块内出现（第 1 次正文、第 2 次行内代码、第 3 次代码块）
    const { rerender } = render(
      <TrustMarkdownView content={content} anchors={[{ snippet: "15.2元", occ: 3, state: "verified" }]} />,
    );
    expect(screen.queryAllByTestId("trust-anchor-badge")).toHaveLength(0);
    // occ=2 → 行内代码内出现，同样不注入
    rerender(
      <TrustMarkdownView content={content} anchors={[{ snippet: "15.2元", occ: 2, state: "verified" }]} />,
    );
    expect(screen.queryAllByTestId("trust-anchor-badge")).toHaveLength(0);
    // occ=1 → 正文出现，正常注入；行内代码内容仍完整（InlineCode 直渲染）
    rerender(
      <TrustMarkdownView content={content} anchors={[{ snippet: "15.2元", occ: 1, state: "verified" }]} />,
    );
    expect(screen.getAllByTestId("trust-anchor-badge")).toHaveLength(1);
    // 角标包裹的正文 snippet + 行内代码的同一 snippet 均在
    expect(screen.getAllByText("15.2元").length).toBeGreaterThanOrEqual(2);
  });

  it("无 anchors 走原 MarkdownView 路径：零角标、markdown 正常渲染", () => {
    const { rerender } = render(
      <TrustMarkdownView content={"## 核心观点\n- 市场先生"} anchors={undefined} />,
    );
    expect(screen.getByRole("heading", { name: "核心观点" })).toBeTruthy();
    expect(screen.queryAllByTestId("trust-anchor-badge")).toHaveLength(0);
    // 空数组同样零开销降级
    rerender(<TrustMarkdownView content={"## 核心观点"} anchors={[]} />);
    expect(screen.queryAllByTestId("trust-anchor-badge")).toHaveLength(0);
  });

  it("其余 markdown 能力与 MarkdownView 同集：https 放行、http 拦截", () => {
    render(
      <TrustMarkdownView
        content={"[安全](https://example.com) [明文](http://example.com)"}
        anchors={[{ snippet: "1520.33元", occ: 1, state: "verified" }]}
      />,
    );
    expect(screen.getByText("安全").closest("a")?.getAttribute("href")).toBe("https://example.com");
    expect(screen.getByText("明文").closest("a")?.getAttribute("href")).toBe("");
  });

  it("图片防御同集：https 懒加载 + no-referrer + 尺寸约束", () => {
    render(
      <TrustMarkdownView
        content={"现价1520.33元。\n\n![走势](https://example.com/k.png)"}
        anchors={[{ snippet: "1520.33元", occ: 1, state: "verified" }]}
      />,
    );
    const img = screen.getByAltText("走势");
    expect(img.getAttribute("loading")).toBe("lazy");
    expect(img.getAttribute("referrerPolicy")).toBe("no-referrer");
    expect(img.getAttribute("src")).toBe("https://example.com/k.png");
  });
});

// ———— F6 修复轮：CorrectionNotes（拍板 #10 注记保留——改写痕迹引用块） ————

describe("CorrectionNotes（修正注记引用块）", () => {
  it("多条注记按序渲染，行形态「校验修正：原文误述 X」（后端 wire note = 原文误述 + snippet）", () => {
    render(<CorrectionNotes notes={["原文误述 15.20元", "原文误述 38倍"]} />);
    const block = screen.getByTestId("trust-correction-notes");
    expect(block.textContent).toContain("校验修正：原文误述 15.20元");
    expect(block.textContent).toContain("校验修正：原文误述 38倍");
    // 行序 = notes 数组序
    expect(block.textContent!.indexOf("15.20元")).toBeLessThan(block.textContent!.indexOf("38倍"));
  });

  it("缺键 / null / 空数组 → 零渲染", () => {
    const { rerender } = render(<CorrectionNotes notes={undefined} />);
    expect(screen.queryByTestId("trust-correction-notes")).toBeNull();
    rerender(<CorrectionNotes notes={null} />);
    expect(screen.queryByTestId("trust-correction-notes")).toBeNull();
    rerender(<CorrectionNotes notes={[]} />);
    expect(screen.queryByTestId("trust-correction-notes")).toBeNull();
  });

  it("降级注记（已自带「校验修正」字样）原样渲染，不叠双前缀", () => {
    render(
      <CorrectionNotes
        notes={["校验修正失败：原文误述 15.20元（重试上限已到，保留原文并转显式标注）"]}
      />,
    );
    const block = screen.getByTestId("trust-correction-notes");
    expect(block.textContent).toContain("校验修正失败：原文误述 15.20元");
    expect(block.textContent!.match(/校验修正/g)).toHaveLength(1);
  });
});

// ———— MS-29 后续④：瞬时高亮（拍板 #10 另一半：替换落位数字一次性视觉反馈） ————

describe("TrustMarkdownView flashSnippets（替换落位一次性高亮）", () => {
  it("命中替换串的锚定数字带高亮标记 span，其余锚定不带", () => {
    render(
      <TrustMarkdownView
        content={"现价1520.33元，目标38倍。"}
        anchors={[
          { snippet: "1520.33元", occ: 1, state: "verified" },
          { snippet: "38倍", occ: 1, state: "unverified" },
        ]}
        flashSnippets={new Set(["1520.33元"])}
      />,
    );
    const flashes = screen.getAllByTestId("trust-correction-flash");
    expect(flashes).toHaveLength(1);
    expect(flashes[0].textContent).toBe("1520.33元");
    // 高亮 span 带动画类（一次性 CSS 动画由 globals.css 定义，视觉本身不测）
    expect(flashes[0].className).toContain("trust-correction-flash");
    // 未替换锚定的包裹元素（38倍）内无高亮 span
    const badges = screen.getAllByTestId("trust-anchor-badge");
    expect(badges[1].parentElement?.querySelector('[data-testid="trust-correction-flash"]')).toBeNull();
  });

  it("缺省 flashSnippets（无新鲜修正）：零高亮标记，渲染不受影响", () => {
    render(
      <TrustMarkdownView
        content={"现价1520.33元。"}
        anchors={[{ snippet: "1520.33元", occ: 1, state: "verified" }]}
      />,
    );
    expect(screen.queryAllByTestId("trust-correction-flash")).toHaveLength(0);
    expect(screen.getAllByTestId("trust-anchor-badge")).toHaveLength(1);
  });
});

describe("TrustMessageContent 修正新鲜窗口（可控时钟）", () => {
  const MSG_ID = "flash-m1";

  function assistantMsg(id: string, content: string): Message {
    return { id, role: "assistant", content } as Message;
  }

  /** live 路径夹具：correction 替换 15.20元→1520.33元 + anchors 终态（替换后形态锚定）。 */
  function dispatchLiveCorrectionAndAnchors() {
    const messages = [assistantMsg(MSG_ID, "现价15.20元，目标38倍。")];
    const corrected = handleTrustCustomEvent(
      {
        name: TRUST_CORRECTION_EVENT,
        value: { messageId: MSG_ID, snippet: "15.20元", occ: 1, replacement: "1520.33元", note: "原文误述 15.20元" },
      },
      messages,
      trustStore,
    )!.messages!;
    handleTrustCustomEvent(
      {
        name: TRUST_ANCHORS_EVENT,
        value: {
          messageId: MSG_ID,
          payload: {
            v: 1,
            anchors: [
              { snippet: "1520.33元", occ: 1, state: "verified", tool: "get_quote", asOf: "2026-10-05 14:59:32", asOfKind: "data", raw: "1520.33" },
              { snippet: "38倍", occ: 1, state: "unverified" },
            ],
            stats: { verified: 1, sourced: 0, unverified: 1 },
          },
        },
      },
      corrected,
      trustStore,
    );
    return corrected;
  }

  beforeEach(() => {
    vi.useFakeTimers();
    trustStore.rebuild([]);
  });
  afterEach(() => {
    vi.useRealTimers();
    cleanup();
    trustStore.rebuild([]);
  });

  it("新鲜窗口内：替换落位锚定带高亮标记；窗口过期后重渲染不再带（不重放）", () => {
    const corrected = dispatchLiveCorrectionAndAnchors();
    const view = render(
      <TrustMessageContent messageId={MSG_ID} content={corrected[0].content as string} />,
    );
    const flashes = screen.getAllByTestId("trust-correction-flash");
    expect(flashes).toHaveLength(1);
    expect(flashes[0].textContent).toBe("1520.33元");

    // 窗口过期（>3s）后重渲染（如其他 store 事件）：新鲜判定失效，高亮不重放
    vi.advanceTimersByTime(3_500);
    view.rerender(<TrustMessageContent messageId={MSG_ID} content={corrected[0].content as string} />);
    expect(screen.queryAllByTestId("trust-correction-flash")).toHaveLength(0);
    // 角标渲染不受窗口过期影响
    expect(screen.getAllByTestId("trust-anchor-badge")).toHaveLength(2);
  });

  it("回灌路径（rebuild）无新鲜时间戳：不挂高亮", () => {
    const corrected = dispatchLiveCorrectionAndAnchors();
    // 历史回灌：整表重建（payload 来自持久化，correction 打点随 rebuild 清）
    trustStore.rebuild([
      [
        MSG_ID,
        TrustPayloadSchema.parse({
          v: 1,
          anchors: [{ snippet: "1520.33元", occ: 1, state: "verified" }],
          stats: { verified: 1, sourced: 0, unverified: 0 },
          correction: { notes: ["原文误述 15.20元"] },
        }),
      ],
    ]);
    render(<TrustMessageContent messageId={MSG_ID} content={corrected[0].content as string} />);
    expect(screen.queryAllByTestId("trust-correction-flash")).toHaveLength(0);
    // 回灌后角标与注记照常渲染（高亮是唯一差异面）
    expect(screen.getAllByTestId("trust-anchor-badge")).toHaveLength(1);
    expect(screen.getByTestId("trust-correction-notes").textContent).toContain("15.20元");
  });
});
