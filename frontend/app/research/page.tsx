"use client";
// 研究项目列表页（F07）：薄壳——过滤/搜索/归档逻辑在 components/research/ResearchBoard.tsx（F1 下沉）。

import { RequireAuth } from "@/components/auth/RequireAuth";
import ResearchBoard from "@/components/research/ResearchBoard";

export default function ResearchPage() {
  return (
    <RequireAuth>
      <ResearchBoard />
    </RequireAuth>
  );
}
