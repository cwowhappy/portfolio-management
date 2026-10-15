"use client";
// 研究项目详情页（F07）：薄壳——详情编排逻辑在 components/research/ProjectDetail.tsx（F1 下沉）。

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
