// 观测看板展示层格式化（纯函数，jsdom 可测；与 IntelligenceFilters.formatPublished 同口径）。

/** Instant（UTC ISO）→ 上海时区 YYYY/MM/DD HH:mm（后端观测列均 UTC 存储）；null → —。 */
export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

/** 长文本预览截断（args JSONB 原文/resultText 摘要）；null/空 → —。 */
export function preview(text: string | null | undefined, max: number): string {
  if (!text) return "—";
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

/** contentHash 短展示（完整值经 title 悬停可见）。 */
export function hashShort(hash: string): string {
  return hash.length > 10 ? `${hash.slice(0, 10)}…` : hash;
}

/** 运行时长：分钟级展示（评测跑通常小时级）；null → —。 */
export function formatDurationMs(ms: number | null | undefined): string {
  if (ms == null) return "—";
  const minutes = Math.round(ms / 60_000);
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分`;
}
