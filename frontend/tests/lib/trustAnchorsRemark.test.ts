import { describe, expect, it } from "vitest";
import {
  applyTrustAnchors,
  remarkTrustAnchors,
  TRUST_ANCHOR_IDX_PROP,
  TRUST_ANCHOR_MARK,
  type MdastNode,
} from "@/lib/trustAnchorsRemark";

// ———— 手工 mdast 夹具（position.offset 对应 content 的源文区间）————

function textNode(value: string, start: number, end: number): MdastNode {
  return { type: "text", value, position: { start: { offset: start }, end: { offset: end } } };
}

function para(children: MdastNode[]): MdastNode {
  return { type: "paragraph", children };
}

function root(children: MdastNode[]): MdastNode {
  return { type: "root", children };
}

/** 断言 node 是盖 sup 的信任标记节点（data.hName/hProperties → mdast-util-to-hast applyData 通道）。 */
function expectAnchorMark(node: MdastNode | undefined, idx: number, snippet: string) {
  expect(node).toBeDefined();
  expect(node?.type).toBe(TRUST_ANCHOR_MARK);
  expect(node?.data?.hName).toBe("sup");
  expect(node?.data?.hProperties?.[TRUST_ANCHOR_IDX_PROP]).toBe(String(idx));
  expect(node?.children).toEqual([{ type: "text", value: snippet }]);
}

describe("applyTrustAnchors（remark 插件核心：按 anchors 切分 text 节点）", () => {
  it("命中 snippet+occ → text 节点三段切分，中段盖 sup 标记节点带 anchor idx", () => {
    const content = "现价15.2元，涨幅2.3%";
    const tree = root([para([textNode(content, 0, 14)])]);
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 1, state: "verified" }]);
    const children = tree.children![0].children!;
    expect(children).toHaveLength(3);
    expect(children[0]).toMatchObject({ type: "text", value: "现价" });
    expectAnchorMark(children[1], 0, "15.2元");
    expect(children[2]).toMatchObject({ type: "text", value: "，涨幅2.3%" });
  });

  it("occ 定位第 2 次出现（与 F1 替换同口径：全文计次）", () => {
    const content = "现价15.2元，压力位也在15.2元";
    const tree = root([para([textNode(content, 0, 18)])]);
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 2, state: "sourced" }]);
    const children = tree.children![0].children!;
    expect(children).toHaveLength(2);
    expect(children[0]).toMatchObject({ type: "text", value: "现价15.2元，压力位也在" });
    expectAnchorMark(children[1], 0, "15.2元");
  });

  it("嵌套数字不计次（复用 nthIndexOf 合法边界）：5% ∈ 15% 被跳过，命中独立出现", () => {
    const content = "涨幅15%，占比5%";
    const tree = root([para([textNode(content, 0, 10)])]);
    applyTrustAnchors(tree, content, [{ snippet: "5%", occ: 1, state: "unverified" }]);
    const children = tree.children![0].children!;
    expect(children).toHaveLength(2);
    expect(children[0]).toMatchObject({ type: "text", value: "涨幅15%，占比" });
    expectAnchorMark(children[1], 0, "5%");
  });

  it("occ 越界（出现次数不足）→ 安全跳过，树不变", () => {
    const content = "现价15.2元";
    const tree = root([para([textNode(content, 0, 7)])]);
    const before = tree.children![0].children;
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 3, state: "verified" }]);
    expect(tree.children![0].children).toBe(before);
  });

  it("anchors 为空数组 → 纯透传，children 引用不变", () => {
    const content = "现价15.2元";
    const tree = root([para([textNode(content, 0, 7)])]);
    const before = tree.children![0].children;
    applyTrustAnchors(tree, content, []);
    expect(tree.children![0].children).toBe(before);
  });

  it("行内代码区间内的命中不注入（代码块数字不标）", () => {
    // 源文：`15.2元` 现价15.2元 —— inlineCode 节点占 [0,7)，text 节点占 [7,15)
    const content = "`15.2元` 现价15.2元";
    const inlineCode: MdastNode = {
      type: "inlineCode",
      value: "15.2元",
      position: { start: { offset: 0 }, end: { offset: 7 } },
    };
    const tree = root([para([inlineCode, textNode(" 现价15.2元", 7, 15)])]);
    // occ=1 命中 inlineCode 区间 → 跳过
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 1, state: "verified" }]);
    expect(tree.children![0].children).toHaveLength(2);
    expect(tree.children![0].children![0]).toBe(inlineCode);
  });

  it("行内代码后正文出现可正常命中（occ 全文计次跨节点）", () => {
    const content = "`15.2元` 现价15.2元";
    const inlineCode: MdastNode = {
      type: "inlineCode",
      value: "15.2元",
      position: { start: { offset: 0 }, end: { offset: 7 } },
    };
    const tree = root([para([inlineCode, textNode(" 现价15.2元", 7, 15)])]);
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 2, state: "verified" }]);
    const children = tree.children![0].children!;
    expect(children).toHaveLength(3);
    expect(children[0]).toBe(inlineCode);
    expect(children[1]).toMatchObject({ type: "text", value: " 现价" });
    expectAnchorMark(children[2], 0, "15.2元");
  });

  it("围栏代码块内的出现不注入（code 子树整跳）", () => {
    // 源文：看15.2元\n```\n15.2元\n``` —— code 节点占 [7,20)
    const content = "看15.2元\n```\n15.2元\n```";
    const codeNode: MdastNode = {
      type: "code",
      value: "15.2元\n",
      position: { start: { offset: 7 }, end: { offset: 20 } },
    };
    const tree = root([para([textNode("看15.2元", 0, 6)]), codeNode]);
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 2, state: "verified" }]);
    expect(tree.children![0].children).toHaveLength(1);
    expect(tree.children![1]).toBe(codeNode);
  });

  it("snippet 跨 text 节点（强调边界切开）→ 无单一节点完整包含 → 安全跳过", () => {
    // 源文：现价**15.2元** —— text("现价") [0,2)，emphasis 内 text("15.2元") [4,9)
    const content = "现价**15.2元**";
    const inner = textNode("15.2元", 4, 9);
    const tree = root([
      para([textNode("现价", 0, 2), { type: "emphasis", children: [inner] }, textNode("**", 9, 11)]),
    ]);
    // 命中区间 [0,7) 横跨「现价」与强调内节点，无单一 text 节点完整包含 → 跳过
    applyTrustAnchors(tree, content, [{ snippet: "现价15.2元", occ: 1, state: "verified" }]);
    expect(tree.children![0].children![1]).toMatchObject({ type: "emphasis" });
    expect(tree.children![0].children![1].children![0]).toBe(inner);
  });

  it("text 节点 value 与源文区间漂移（转义/实体改写）→ 命中校验失败安全跳过", () => {
    const content = "现价15.2元";
    // value 比 slice 多一个 *（模拟 `现价\*` 转义改写后的 text 节点）
    const tree = root([para([textNode("现价*15.2元", 0, 7)])]);
    const before = tree.children![0].children;
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 1, state: "verified" }]);
    expect(tree.children![0].children).toBe(before);
  });

  it("无 position 的 text 节点 → 无法映射区间 → 安全跳过", () => {
    const content = "现价15.2元";
    const tree = root([para([{ type: "text", value: content }])]);
    const before = tree.children![0].children;
    applyTrustAnchors(tree, content, [{ snippet: "15.2元", occ: 1, state: "verified" }]);
    expect(tree.children![0].children).toBe(before);
  });

  it("同一 text 节点多锚依文档序切分（idx 各自回传 anchors 数组序）", () => {
    const content = "现价15.2元，涨幅2.3%";
    const tree = root([para([textNode(content, 0, 14)])]);
    applyTrustAnchors(tree, content, [
      { snippet: "2.3%", occ: 1, state: "unverified" },
      { snippet: "15.2元", occ: 1, state: "verified" },
    ]);
    const children = tree.children![0].children!;
    expect(children).toHaveLength(4);
    expect(children[0]).toMatchObject({ type: "text", value: "现价" });
    expectAnchorMark(children[1], 1, "15.2元"); // anchors[1]
    expect(children[2]).toMatchObject({ type: "text", value: "，涨幅" });
    expectAnchorMark(children[3], 0, "2.3%"); // anchors[0]
  });

  it("重叠区间先到先得（长 snippet 与其前缀 snippet 各一锚）", () => {
    const content = "现价15.2元";
    const tree = root([para([textNode(content, 0, 7)])]);
    applyTrustAnchors(tree, content, [
      { snippet: "15.2", occ: 1, state: "verified" },
      { snippet: "15.2元", occ: 1, state: "sourced" },
    ]);
    const children = tree.children![0].children!;
    expect(children).toHaveLength(3);
    expectAnchorMark(children[1], 0, "15.2"); // 先到（文档序在前）者胜
    expect(children[2]).toMatchObject({ type: "text", value: "元" });
  });

  it("remarkTrustAnchors 工厂返回 transformer（react-markdown 插件形态）", () => {
    const content = "现价15.2元";
    const tree = root([para([textNode(content, 0, 7)])]);
    const transformer = remarkTrustAnchors({
      content,
      anchors: [{ snippet: "15.2元", occ: 1, state: "verified" }],
    });
    expect(typeof transformer).toBe("function");
    transformer(tree);
    expectAnchorMark(tree.children![0].children![1], 0, "15.2元");
  });
});
