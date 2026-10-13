"use client";

import { useState } from "react";
import type { ProjectDetailView, ResearchStage } from "@/lib/researchSchemas";

/**
 * 项目信息表单草稿（标题/当前阶段/情报提醒）与后端读模型的渲染期同步。
 * detail 引用变更（首载/归档重载/PATCH 回填）即把草稿重置为读模型值；用户编辑期间
 * detail 不变则草稿不被打扰。照 IndustryStockTable 排序守卫写法（React 官方
 * 「You Might Not Need an Effect」派生状态范式），避免 effect 内同步 setState
 * 触发级联渲染（react-hooks/set-state-in-effect）。
 */
export function useDraftSync(detail: ProjectDetailView | null) {
  const [titleDraft, setTitleDraft] = useState("");
  const [stageDraft, setStageDraft] = useState<ResearchStage>("NEW_ANALYSIS");
  const [alertDraft, setAlertDraft] = useState(true);
  const [prevDetail, setPrevDetail] = useState(detail);
  if (detail !== prevDetail) {
    setPrevDetail(detail);
    if (detail) {
      setTitleDraft(detail.project.title);
      setStageDraft(detail.project.currentStage);
      setAlertDraft(detail.project.intelligenceAlertEnabled);
    }
  }
  return { titleDraft, setTitleDraft, stageDraft, setStageDraft, alertDraft, setAlertDraft };
}
