// MS-29 F2：信任角标 remark 插件（设计规格 §5.2）。
//
// 定位口径与 F1 原位替换完全同源：对 **原始 markdown 全文** 用 trustMeta.nthIndexOf（合法
// 边界语义，后端 ConsistencyValidator 同构）定位 snippet 第 occ 次出现，再经 text 节点的
// position offset 映射进 mdast 切分。**禁止对单个 text 节点独立计次**——嵌套数字（「15%」
// 里的「5%」）口径会与替换错位。
//
// 安全跳过（宁可少标不可崩）：occ 越界 / 命中区间不在任何 text 节点内（跨节点、code /
// inlineCode 区间）/ 节点 value 与源文片段漂移（转义/实体改写）→ 该锚不渲染，树其余部分
// 不受影响。
//
// 注：不复用 unist-util-visit 等传递依赖（pnpm 隔离下不可直接 import），用原生遍历。

import { nthIndexOf, type TrustAnchor } from "@/lib/trustMeta";

/** 结构化 mdast 子集（仅本模块消费的字段；不依赖 @types/mdast）。 */
export interface MdastNode {
  type: string;
  value?: string;
  children?: MdastNode[];
  position?: { start?: { offset?: number }; end?: { offset?: number } };
  data?: { hName?: string; hProperties?: Record<string, string> };
}

/** 命中区间不进入的节点子树（代码块/行内代码/原始 HTML——代码块数字不标）。 */
const SKIPPED_SUBTREES = new Set(["code", "inlineCode", "math", "inlineMath", "html"]);

/** 切分产生的标记节点类型（经 data.hName 盖 sup，mdast-util-to-hast applyData 通道）。 */
export const TRUST_ANCHOR_MARK = "trustAnchorMark";
/** sup 元素上回传 anchors 数组序的属性名（components.sup 映射据此取 anchor）。 */
export const TRUST_ANCHOR_IDX_PROP = "data-anchor-idx";

function anchorMarkNode(idx: number, snippet: string): MdastNode {
  return {
    type: TRUST_ANCHOR_MARK,
    data: { hName: "sup", hProperties: { [TRUST_ANCHOR_IDX_PROP]: String(idx) } },
    children: [{ type: "text", value: snippet }],
  };
}

function plainText(value: string): MdastNode {
  return { type: "text", value };
}

interface TextNodeRef {
  node: MdastNode;
  parent: MdastNode;
}

/** 深度优先、文档序收集可注入的 text 节点（跳过 code 类子树）。 */
function collectTextNodes(node: MdastNode, parent: MdastNode | null, acc: TextNodeRef[]): void {
  if (SKIPPED_SUBTREES.has(node.type)) return;
  if (node.type === "text" && parent && typeof node.value === "string" && node.value !== "") {
    acc.push({ node, parent });
  }
  if (Array.isArray(node.children)) {
    for (const child of node.children) collectTextNodes(child, node, acc);
  }
}

/** node 的源文区间是否完整包含 [start, end)。 */
function coversRange(node: MdastNode, start: number, end: number): boolean {
  const s = node.position?.start?.offset;
  const e = node.position?.end?.offset;
  return typeof s === "number" && typeof e === "number" && s <= start && end <= e;
}

export interface TrustAnchorsPluginOptions {
  /** ReactMarkdown 渲染的原始 markdown 全文（occ 定位基线，与 correction 替换同一字符串）。 */
  content: string;
  anchors: readonly TrustAnchor[];
}

/**
 * 按 anchors 切分 text 节点、注入盖 sup 的 trustAnchorMark 标记节点。
 * 直接导出便于单测（remark 插件形态见 remarkTrustAnchors）。原地修改 tree。
 */
export function applyTrustAnchors(
  tree: MdastNode,
  content: string,
  anchors: readonly TrustAnchor[],
): void {
  if (anchors.length === 0) return;
  const textNodes: TextNodeRef[] = [];
  collectTextNodes(tree, null, textNodes);

  // 1) 定位：全文 nthIndexOf（与替换同口径）→ 找完整包含命中区间的 text 节点
  interface Located {
    idx: number;
    anchor: TrustAnchor;
    start: number;
    end: number;
    ref: TextNodeRef;
  }
  const located: Located[] = [];
  for (let idx = 0; idx < anchors.length; idx++) {
    const anchor = anchors[idx];
    if (typeof anchor.snippet !== "string" || anchor.snippet === "") continue;
    const start = nthIndexOf(content, anchor.snippet, anchor.occ);
    if (start === -1) continue; // occ 越界 / 文本已变 → 安全跳过
    const end = start + anchor.snippet.length;
    const ref = textNodes.find(({ node }) => coversRange(node, start, end));
    if (!ref) continue; // 跨节点 / code 类区间 → 跳过
    located.push({ idx, anchor, start, end, ref });
  }
  if (located.length === 0) return;

  // 2) 按 text 节点分组、文档序一次重建（同节点多锚不重复 splice；重叠区间先到先得）
  const byNode = new Map<MdastNode, Located[]>();
  for (const loc of located) {
    const list = byNode.get(loc.ref.node);
    if (list) list.push(loc);
    else byNode.set(loc.ref.node, [loc]);
  }
  for (const [original, hits] of byNode) {
    hits.sort((a, b) => a.start - b.start);
    const parent = hits[0].ref.parent;
    const base = original.position?.start?.offset ?? 0;
    const value = original.value ?? "";
    const parts: MdastNode[] = [];
    let cursor = 0;
    let landed = false;
    for (const hit of hits) {
      const relStart = hit.start - base;
      const relEnd = hit.end - base;
      if (relStart < cursor) continue; // 与前一命中区间重叠：先到先得
      // value 与源文漂移（同节点内含转义/实体改写）：区间对不上即跳过该锚
      if (value.slice(relStart, relEnd) !== hit.anchor.snippet) continue;
      if (relStart > cursor) parts.push(plainText(value.slice(cursor, relStart)));
      parts.push(anchorMarkNode(hit.idx, hit.anchor.snippet));
      cursor = relEnd;
      landed = true;
    }
    if (!landed) continue; // 该节点全部命中被守卫拦下：原节点保留
    if (cursor < value.length) parts.push(plainText(value.slice(cursor)));
    const siblings = parent.children ?? [];
    const at = siblings.indexOf(original);
    if (at === -1) continue; // 理论不可达（同一原文节点只切分一次）
    siblings.splice(at, 1, ...parts);
  }
}

/** remark 插件形态：`remarkPlugins={[[remarkTrustAnchors, { content, anchors }]]}`。 */
export function remarkTrustAnchors(options: TrustAnchorsPluginOptions) {
  const { content, anchors } = options;
  return (tree: MdastNode) => applyTrustAnchors(tree, content, anchors);
}
