"use client";
// 研究项目列表页（F07）：薄壳——过滤/搜索/归档逻辑在 components/research/ResearchBoard.tsx（F1 下沉）。

import { use } from "react";
import { RequireAuth } from "@/components/auth/RequireAuth";
import ProjectDetail from "@/components/research/ProjectDetail";

export default function ProjectDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <RequireAuth>
      <ProjectDetail projectId={Number(id)} />
    </RequireAuth>
  );
}
