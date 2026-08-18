"use client";

import { useEffect, useState } from "react";
import { fetchHealth } from "@/lib/api";
import Sidebar from "./Sidebar";
import { RuntimeProvider, useChatRuntime } from "./RuntimeProvider";
import ThreadArea from "./ThreadArea";

function ChatShell() {
  const { sessions, currentThreadId, switchThread, newThread, removeThread } =
    useChatRuntime();
  const [health, setHealth] = useState<{ llmKey: boolean; marketOk: boolean } | null>(null);

  useEffect(() => {
    fetchHealth()
      .then((h) => setHealth({ llmKey: h.llm.keyConfigured, marketOk: h.market.ok }))
      .catch(() => setHealth(null));
    const timer = setInterval(() => {
      fetchHealth()
        .then((h) => setHealth({ llmKey: h.llm.keyConfigured, marketOk: h.market.ok }))
        .catch(() => setHealth(null));
    }, 60_000);
    return () => clearInterval(timer);
  }, []);

  return (
    <div className="flex h-full">
      <Sidebar
        sessions={sessions}
        activeId={currentThreadId}
        health={health}
        onSelect={switchThread}
        onNew={newThread}
        onDelete={removeThread}
      />
      <ThreadArea llmReady={health?.llmKey ?? false} />
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
