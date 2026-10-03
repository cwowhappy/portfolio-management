// 薄壳（照 industry 页先例）。/api/intelligence/** 不在公开白名单（需登录）→ RequireAuth；
// IntelligenceBoard 内 useSearchParams 预渲染需 Suspense 边界（照 research/new 页先例）。

import { Suspense } from "react";
import { RequireAuth } from "@/components/auth/RequireAuth";
import IntelligenceBoard from "@/components/intelligence/IntelligenceBoard";

export default function IntelligencePage() {
  return (
    <RequireAuth>
      <Suspense
        fallback={
          <div className="mx-auto max-w-6xl px-6 py-8">
            <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />
          </div>
        }
      >
        <IntelligenceBoard />
      </Suspense>
    </RequireAuth>
  );
}
