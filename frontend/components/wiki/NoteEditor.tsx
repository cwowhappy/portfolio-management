"use client";

import { useState } from "react";
import MarkdownView from "@/components/shared/MarkdownView";
import { createWikiEntry, updateWikiEntry } from "@/lib/wikiApi";
import type { WikiEntryType, WikiEntryView } from "@/lib/types";

export default function NoteEditor({ type, editing, onSaved, onCancel }: {
  type: WikiEntryType;
  editing: WikiEntryView | null;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const [title, setTitle] = useState(editing?.title ?? "");
  const [category, setCategory] = useState(editing?.category ?? "");
  const [content, setContent] = useState(editing?.content ?? "");
  const [preview, setPreview] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setError(null);
    const trimmed = title.trim();
    if (!trimmed) { setError("标题不能为空"); return; }
    if (!content.trim()) { setError("内容不能为空"); return; }
    const cmd = {
      type, title: trimmed, content,
      category: type === "CONCEPT" ? (category.trim() || null) : (editing?.category ?? null),
      industryCode: editing?.industryCode ?? null, // 研究结论来源行业保留，手工创建为 null
    };
    try {
      if (editing) await updateWikiEntry(editing.id, cmd);
      else await createWikiEntry(cmd);
      if (!editing) {
        // 新建成功后清空表单：key 恒为 "new" 不 remount，残留会导致重复创建同内容条目
        setTitle(""); setCategory(""); setContent(""); setPreview(false); setError(null);
      }
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
      <div className="font-[family-name:var(--font-display)] text-[15px]">{editing ? "编辑条目" : "新建条目"}</div>
      <input data-testid="wiki-note-title" className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
        placeholder="标题" value={title} onChange={(e) => setTitle(e.target.value)} />
      {type === "CONCEPT" && (
        <input data-testid="wiki-note-category" className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
          placeholder="分类（如：估值/质量/行为）" value={category} onChange={(e) => setCategory(e.target.value)} />
      )}
      <div className="flex items-center justify-between">
        <span className="text-xs text-[color:var(--color-ink-faint)]">Markdown</span>
        <button data-testid="wiki-note-preview-toggle" type="button"
          className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-xs"
          onClick={() => setPreview(!preview)}>
          {preview ? "编辑" : "预览"}
        </button>
      </div>
      {preview ? (
        <div className="min-h-32 rounded-md border border-[color:var(--color-line-soft)] p-3">
          {content.trim() ? <MarkdownView content={content} /> : <span className="text-sm text-[color:var(--color-ink-faint)]">暂无内容</span>}
        </div>
      ) : (
        <textarea data-testid="wiki-note-content" rows={8}
          className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-2 text-sm min-h-32"
          placeholder="内容（Markdown）" value={content} onChange={(e) => setContent(e.target.value)} />
      )}
      {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
      <div className="flex gap-2">
        <button data-testid="wiki-note-save" type="button"
          className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
          onClick={save}>
          {editing ? "保存修改" : "保存"}
        </button>
        {editing && (
          <button type="button" className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]"
            onClick={onCancel}>
            取消
          </button>
        )}
      </div>
    </div>
  );
}
