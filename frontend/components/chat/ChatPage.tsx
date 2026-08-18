"use client";

import { useEffect, useState } from "react";
import { fetchHealth } from "@/lib/api";
import Sidebar from "./Sidebar";
import { RuntimeProvider } from "./RuntimeProvider";
import ThreadArea from "./ThreadArea";

function ChatShell() {
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
      <ThreadArea llmReady={health ? health.llmKey : null} />
    </div>
  );
}

export default function ChatPage() {
  return (
    <RuntimeProvider>
      <ChatShell />
    </RuntimeProvider>
  );
}
