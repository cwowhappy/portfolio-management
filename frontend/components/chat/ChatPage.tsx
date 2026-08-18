"use client";

import { useEffect, useState } from "react";
import { fetchHealth } from "@/lib/api";
import Sidebar from "./Sidebar";
import { RuntimeProvider } from "./RuntimeProvider";
import ThreadArea from "./ThreadArea";

function ChatShell() {
  const [health, setHealth] = useState<{ llmKey: boolean; marketOk: boolean } | null>(null);

  useEffect(() => {
    const refresh = () =>
      fetchHealth()
        .then((h) => setHealth({ llmKey: h.llm.keyConfigured, marketOk: h.market.ok }))
        .catch(() => setHealth(null));
    refresh();
    const timer = setInterval(refresh, 60_000);
    return () => clearInterval(timer);
  }, []);

  return (
    <div className="flex h-full">
      <Sidebar health={health} />
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
