"use client";
// 聊天工具结果卡的共享时间格式化（2026-10 P3 审查 F4 自 toolRenderers.tsx 抽出，
// 供 news/announcements/macro 三张列表卡共用；与 intelligence 域 IntelligenceFilters
// 的 formatPublished 同口径不同格式（彼为 yyyy/MM/dd），勿合并）。

/** Instant（UTC ISO）→ 上海时区 MM-DD HH:mm（与后端日界折算口径一致）；非法值原样返回。 */
export function formatPublished(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}
