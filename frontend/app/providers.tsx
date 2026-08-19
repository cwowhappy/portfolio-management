"use client";

import { CopilotKit } from "@copilotkit/react-core/v2";
import "@copilotkit/react-core/v2/styles.css";
import type { ReactNode } from "react";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <CopilotKit
      runtimeUrl="/api/copilotkit"
      // 用 REST 传输（GET /info + POST /agent/{id}/run）；默认的 single 端点模式会 POST 到根路径而 404
      useSingleEndpoint={false}
      // 关闭 AG-UI 调试检查器浮层（它会拦截页面点击事件，且生产环境无需暴露调试面板）
      enableInspector={false}
      onError={(errorEvent) => {
        console.error("[copilotkit]", errorEvent.type, errorEvent.error);
      }}
    >
      {children}
    </CopilotKit>
  );
}
