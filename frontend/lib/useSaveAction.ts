"use client";

import { useCallback, useRef, useState } from "react";

/**
 * 保存类交互的统一防线（MS-11 复盘优化建议 #3 收口）：
 * - in-flight 防连点：run 执行期间的重入直接忽略（ref 守卫，闭包陈旧也拦得住）；
 * - 失败不吞错：异常翻译成行内文案写入 error，由调用方展示；
 * - saving 驱动按钮 disabled，error 在每次 run 开始时清除。
 *
 * 表单校验在调用 run 之前做，用 setError 写校验文案（复用同一条行内错误位）。
 */
export function useSaveAction(fallbackMessage = "保存失败") {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);

  const run = useCallback(async (action: () => Promise<void>) => {
    if (inFlight.current) return; // 防连点：上一次仍在途，本次触发直接忽略
    inFlight.current = true;
    setError(null);
    setSaving(true);
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : fallbackMessage);
    } finally {
      inFlight.current = false;
      setSaving(false);
    }
  }, [fallbackMessage]);

  return { saving, error, setError, run };
}
