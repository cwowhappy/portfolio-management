"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { fetchHealth } from "@/lib/api";
import { RequireAuth } from "@/components/auth/RequireAuth";
import { useAuth } from "@/lib/auth";
import Sidebar from "./Sidebar";
import { RuntimeProvider } from "./RuntimeProvider";
import ThreadArea from "./ThreadArea";

function ChatShell({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [health, setHealth] = useState<{ llmKey: boolean; marketOk: boolean } | null>(null);

  useEffect(() => {
    let cancelled = false;
    const refresh = () =>
      fetchHealth()
        .then((h) => {
          if (!cancelled) setHealth({ llmKey: h.llm.keyConfigured, marketOk: h.market.ok });
        })
        .catch(() => {
          // 失败保持“检测中”，避免误判为未配置
          if (!cancelled) setHealth(null);
        });
    refresh();
    const timer = setInterval(refresh, 60_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  return (
    <div className="flex h-full">
      <Sidebar health={health} />
      {/* null=检测中，true=已配置，false=确实未配置 */}
      <ThreadArea llmReady={health ? health.llmKey : null} onUnauthorized={onUnauthorized} />
    </div>
  );
}

export default function ChatPage() {
  const { logout } = useAuth();
  const router = useRouter();
  // AI 对话遇 401（如使用中被停用）→ 清本地登录态并跳登录（对齐 /api/auth/me 401 处理）
  const handleUnauthorized = useCallback(() => {
    void logout().finally(() => router.replace("/login"));
  }, [logout, router]);

  return (
    <RequireAuth>
      <RuntimeProvider>
        <ChatShell onUnauthorized={handleUnauthorized} />
      </RuntimeProvider>
    </RequireAuth>
  );
}
