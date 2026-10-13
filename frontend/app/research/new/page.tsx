"use client";
// 立项表单页（F05）：薄壳——表单逻辑在 components/research/NewProjectForm.tsx（F1 下沉）；
// useSearchParams 预渲染需 Suspense 边界（照 market/screener 页先例）。

import { Suspense } from "react";
import { RequireAuth } from "@/components/auth/RequireAuth";
import NewProjectForm from "@/components/research/NewProjectForm";

export default function NewProjectPage() {
  return (
    <RequireAuth>
      <Suspense fallback={<div className="mx-auto max-w-3xl px-6 py-8"><div className="h-40 rounded-2xl skeleton" /></div>}>
        <NewProjectForm />
      </Suspense>
    </RequireAuth>
  );
}
