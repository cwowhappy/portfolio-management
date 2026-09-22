"use client";

import { useState } from "react";
import Link from "next/link";
import MarkdownView from "@/components/shared/MarkdownView";
import { createWikiEntry } from "@/lib/wikiApi";

/** 行业下钻页「保存研究结论」入口：预填行业上下文，存为 RESEARCH_NOTE，成功深链 /wiki。 */
export default function ResearchNoteDialog({ industryCode, industryName }: {
  industryCode: string;
  industryName: string;
}) {
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [preview, setPreview] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const openDialog = () => {
    setTitle(`${industryName || industryCode} 研究结论`);
    setContent("");
    setPreview(false);
    setSaved(false);
    setError(null);
    setOpen(true);
  };

  const save = async () => {
    setError(null);
    if (!title.trim()) { setError("标题不能为空"); return; }
    if (!content.trim()) { setError("内容不能为空"); return; }
    try {
      await createWikiEntry({ type: "RESEARCH_NOTE", title: title.trim(), content, industryCode });
      setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  return (
    <>
      <button data-testid="research-note-open" type="button"
        className="rounded-md px-3 py-1.5 text-xs border border-[color:var(--color-line)] hover:bg-[color:var(--color-panel)]"
        onClick={openDialog}>
        保存研究结论
      </button>
      {open && (
        <div data-testid="research-note-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          onClick={(e) => { if (e.target === e.currentTarget) setOpen(false); }}>
          <div className="w-full max-w-2xl rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-bg)] p-5 space-y-3">
            {saved ? (
              <div className="space-y-3 text-center py-6">
                <div className="text-sm">已保存至投资知识库</div>
                <Link data-testid="research-note-view-link" href="/wiki?tab=research"
                  className="inline-block rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]">
                  在知识库查看
                </Link>
              </div>
            ) : (
              <>
                <div className="font-[family-name:var(--font-display)] text-[15px]">保存研究结论到知识库</div>
                <input data-testid="research-note-title" className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
                  placeholder="标题" value={title} onChange={(e) => setTitle(e.target.value)} />
                <div className="flex items-center justify-between">
                  <span className="text-xs text-[color:var(--color-ink-faint)]">Markdown</span>
                  <button type="button" className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-xs"
                    onClick={() => setPreview(!preview)}>
                    {preview ? "编辑" : "预览"}
                  </button>
                </div>
                {preview ? (
                  <div className="min-h-24 rounded-md border border-[color:var(--color-line-soft)] p-3">
                    {content.trim() ? <MarkdownView content={content} /> : <span className="text-sm text-[color:var(--color-ink-faint)]">暂无内容</span>}
                  </div>
                ) : (
                  <textarea data-testid="research-note-content" rows={6}
                    className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-2 text-sm"
                    placeholder="研究结论（Markdown）" value={content} onChange={(e) => setContent(e.target.value)} />
                )}
                {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
                <div className="flex gap-2">
                  <button data-testid="research-note-save" type="button"
                    className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
                    onClick={save}>
                    保存
                  </button>
                  <button type="button" className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]"
                    onClick={() => setOpen(false)}>
                    取消
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}
