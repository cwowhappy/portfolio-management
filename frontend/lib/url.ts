/** 外链信任模型（与 MarkdownView.urlTransform 一致）：仅放行同源相对路径与 https，其余渲染为纯文本。 */
export function safeUrl(u: string | null | undefined): string | null {
  if (!u) return null;
  if (u.startsWith("/") || u.startsWith("https://")) return u;
  return null;
}
