"use client";

import { CopilotKit } from "@copilotkit/react-core/v2";
import "@copilotkit/react-core/v2/styles.css";
import type { ReactNode } from "react";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <CopilotKit
      runtimeUrl="/api/copilotkit"
      onError={(errorEvent) => {
        console.error("[copilotkit]", errorEvent.type, errorEvent.error);
      }}
    >
      {children}
    </CopilotKit>
  );
}
