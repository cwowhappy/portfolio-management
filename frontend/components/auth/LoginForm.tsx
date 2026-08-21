"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

const inputClass =
  "w-full rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export function LoginForm() {
  const { login } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!username || !password) {
      setError("请输入用户名和密码");
      return;
    }
    setSubmitting(true);
    try {
      await login(username, password, rememberMe);
      router.replace("/");
    } catch (err) {
      setError(err instanceof Error ? err.message : "登录失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-4" noValidate>
      <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
        用户名
        <input
          className={inputClass}
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="用户名"
          autoComplete="username"
          autoFocus
          disabled={submitting}
        />
      </label>
      <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
        密码
        <input
          className={inputClass}
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder="密码"
          autoComplete="current-password"
          disabled={submitting}
        />
      </label>
      <label className="flex items-center gap-2 text-[13px] text-[color:var(--color-ink-dim)]">
        <input
          type="checkbox"
          checked={rememberMe}
          onChange={(e) => setRememberMe(e.target.checked)}
          className="h-3.5 w-3.5 accent-[color:var(--color-up)]"
          disabled={submitting}
        />
        记住我（30 天内免登录）
      </label>
      {error && (
        <p className="rounded-md border border-[color:var(--color-up)]/40 bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-up)]" role="alert">
          {error}
        </p>
      )}
      <button
        type="submit"
        disabled={submitting}
        className="rounded-md bg-[color:var(--color-up)] px-4 py-2 text-[14px] font-medium text-white transition-all enabled:hover:brightness-110 disabled:opacity-40"
      >
        {submitting ? "登录中…" : "登 录"}
      </button>
    </form>
  );
}
