"use client";

import { useEffect } from "react";

/** 路由段错误边界：单页渲染异常（如后端 DTO drift）降级为友好提示，避免整页白屏。 */
export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("[app] 渲染错误:", error);
  }, [error]);

  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 px-6 text-center">
      <p className="font-[family-name:var(--font-display)] text-[18px] text-[color:var(--color-ink)]">
        页面出错了
      </p>
      <p className="max-w-md text-[13px] leading-relaxed text-[color:var(--color-ink-dim)]">
        {error.message || "发生未知错误"}
      </p>
      <button
        onClick={reset}
        className="rounded-md bg-[color:var(--color-up)] px-4 py-2 text-[13px] text-white transition-all hover:brightness-110"
      >
        重试
      </button>
    </div>
  );
}
