"use client";
// 立项表单页（F05）：筛选器结果行/行业中心标的行的「发起研究」带 code/name/industry
// 预填跳转到这里；withTemplate=true 时后端按新分析 SOP 模板初始化（模板内容 F01 定稿回填）。

import { Suspense, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { RequireAuth } from "@/components/auth/RequireAuth";
import { createProject } from "@/lib/researchApi";
import { useSaveAction } from "@/lib/useSaveAction";

export default function NewProjectPage() {
  return (
    <RequireAuth>
      <Suspense fallback={<div className="mx-auto max-w-3xl px-6 py-8"><div className="h-40 rounded-2xl skeleton" /></div>}>
        <NewProjectForm />
      </Suspense>
    </RequireAuth>
  );
}

function NewProjectForm() {
  const sp = useSearchParams();
  const router = useRouter();
  const { saving, error, setError, run } = useSaveAction("立项失败");
  const [stockCode, setStockCode] = useState(sp.get("code") ?? "");
  const [stockName, setStockName] = useState(sp.get("name") ?? "");
  const [industryCode, setIndustryCode] = useState(sp.get("industry") ?? "");
  const [title, setTitle] = useState("");
  const [withTemplate, setWithTemplate] = useState(true);

  const submit = () => {
    const code = stockCode.trim();
    const name = stockName.trim();
    const t = title.trim() || (name ? `${name}研究` : "");
    if (!code) { setError("标的代码不能为空"); return; }
    if (!name) { setError("标的名称不能为空"); return; }
    if (!t) { setError("项目标题不能为空"); return; }
    void run(async () => {
      const project = await createProject({
        stockCode: code,
        stockName: name,
        industryCode: industryCode.trim() || undefined,
        title: t,
        withTemplate,
      });
      router.push(`/research/${project.id}`);
    });
  };

  return (
    <div className="mx-auto max-w-3xl px-6 py-8 space-y-5" data-testid="research-new">
      <div className="flex flex-wrap items-center gap-3">
        <Link href="/research" className="text-sm text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-ink-dim)]">
          ← 研究项目
        </Link>
        <h1 className="font-[family-name:var(--font-display)] text-2xl">发起研究</h1>
      </div>
      <div className="space-y-4 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <label className={labelCls} htmlFor="np-code">标的代码</label>
            <input
              id="np-code" aria-label="标的代码" placeholder="如 600519"
              className={inputCls} value={stockCode}
              onChange={(e) => setStockCode(e.target.value)}
            />
          </div>
          <div>
            <label className={labelCls} htmlFor="np-name">标的名称</label>
            <input
              id="np-name" aria-label="标的名称" placeholder="如 贵州茅台"
              className={inputCls} value={stockName}
              onChange={(e) => setStockName(e.target.value)}
            />
          </div>
          <div>
            <label className={labelCls} htmlFor="np-industry">行业代码（可选）</label>
            <input
              id="np-industry" aria-label="行业代码" placeholder="如 BK0477"
              className={inputCls} value={industryCode}
              onChange={(e) => setIndustryCode(e.target.value)}
            />
          </div>
          <div>
            <label className={labelCls} htmlFor="np-title">项目标题</label>
            <input
              id="np-title" aria-label="项目标题" placeholder="缺省为「<标的名称>研究」"
              className={inputCls} value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
          </div>
        </div>
        <label className="flex items-center gap-2 text-sm text-[color:var(--color-ink-dim)]">
          <input
            type="checkbox" aria-label="带入新分析 SOP 模板"
            checked={withTemplate}
            onChange={(e) => setWithTemplate(e.target.checked)}
          />
          带入新分析 SOP 模板（按检查清单初始化新分析阶段）
        </label>
        {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
        <div className="flex gap-2">
          <button
            type="button"
            className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-60"
            disabled={saving}
            onClick={submit}
          >
            立项
          </button>
          <Link
            href="/research"
            className="rounded-md border border-[color:var(--color-line)] px-4 py-1.5 text-sm text-[color:var(--color-ink-dim)]"
          >
            取消
          </Link>
        </div>
      </div>
    </div>
  );
}

const inputCls = "w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm";
const labelCls = "block text-xs text-[color:var(--color-ink-faint)] mb-1";
