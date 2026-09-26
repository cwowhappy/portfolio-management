"use client";

import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

/**
 * 策展管理入口（MS-10 决策 #1 内嵌管理）：「编辑」「批量导入」两按钮。父层以
 * {user && <CurationPanel/>} 登录态条件渲染（照 ResearchNoteDialog 入口先例）；组件内
 * 保留未登录守卫（⭐ 关注先例 IndustryBoard:50-54——会话中途失效等防御路径跳登录带
 * redirect）。对话框本体挂载在 UnlistedPanel（新增/行编辑复用同一 UnlistedCompanyDialog）。
 */
export default function CurationPanel({ industryCode, onAdd, onImport }: {
  industryCode: string;
  onAdd: () => void;
  onImport: () => void;
}) {
  const { user } = useAuth();
  const router = useRouter();

  const requireLogin = () => {
    if (!user) {
      router.push(`/login?redirect=/industry/${industryCode}`);
      return false;
    }
    return true;
  };

  return (
    <div className="flex items-center gap-2" data-testid="curation-panel">
      <button type="button" data-testid="curation-edit-open"
        className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-xs hover:bg-[color:var(--color-panel)]"
        onClick={() => { if (requireLogin()) onAdd(); }}>
        编辑
      </button>
      <button type="button" data-testid="curation-import-open"
        className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-xs hover:bg-[color:var(--color-panel)]"
        onClick={() => { if (requireLogin()) onImport(); }}>
        批量导入
      </button>
    </div>
  );
}
