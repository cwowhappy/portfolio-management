import { RequireAuth } from "@/components/auth/RequireAuth";

// MS-31 F1 路由薄壳：F2（记忆管理区）/F3（画像授权区）在 components/memory/
// MemorySettingsPage.tsx 落地后替换占位内容
export default function Page() {
  return (
    <RequireAuth>
      <div className="p-6 max-w-3xl mx-auto space-y-4">
        <h1 className="text-xl font-semibold">记忆与画像</h1>
        <p className="text-sm text-[color:var(--color-ink-dim)]">记忆管理区建设中</p>
      </div>
    </RequireAuth>
  );
}
