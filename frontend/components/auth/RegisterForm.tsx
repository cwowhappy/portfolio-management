"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { checkPassword } from "@/lib/password";
import { useAuth } from "@/lib/auth";

const inputClass =
  "w-full rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export function RegisterForm() {
  const { register, sendRegisterCode } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sending, setSending] = useState(false);
  const [countdown, setCountdown] = useState(0);
  const [done, setDone] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (countdown <= 0 && timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, [countdown]);

  // unmount 兜底清理：倒计时未走完就离开页面时，interval 不能泄漏
  useEffect(() => {
    return () => {
      if (timerRef.current) {
        clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, []);

  async function onSendCode() {
    setError(null);
    if (!username.trim()) return setError("请输入用户名");
    const pwd = checkPassword(password);
    if (!pwd.ok) return setError(pwd.error ?? "密码不符合要求");
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) return setError("请输入正确的邮箱");
    setSending(true);
    try {
      await sendRegisterCode(username.trim(), password, email.trim());
      setCountdown(60);
      timerRef.current = setInterval(() => setCountdown((n) => n - 1), 1000);
    } catch (err) {
      setError(err instanceof Error ? err.message : "验证码发送失败，请稍后重试");
    } finally {
      setSending(false);
    }
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!username.trim()) return setError("请输入用户名");
    const pwd = checkPassword(password);
    if (!pwd.ok) return setError(pwd.error ?? "密码不符合要求");
    if (password !== confirm) return setError("两次输入的密码不一致");
    if (!code.trim()) return setError("请输入邮箱验证码");
    setSubmitting(true);
    try {
      await register(username.trim(), password, email.trim(), code.trim());
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "注册失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <div className="flex flex-col items-center gap-3 py-6 text-center">
        <p className="text-[22px] leading-snug text-[color:var(--color-ink)]">注册成功 🎉</p>
        <p className="max-w-sm text-[14px] leading-relaxed text-[color:var(--color-ink-dim)]">
          账号已提交，等待管理员审核。审核通过后即可登录。
        </p>
      </div>
    );
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
          placeholder="至少 8 位，含字母和数字"
          autoComplete="new-password"
          disabled={submitting}
        />
      </label>
      <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
        确认密码
        <input
          className={inputClass}
          type="password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          placeholder="再次输入密码"
          autoComplete="new-password"
          disabled={submitting}
        />
      </label>
      <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
        邮箱
        <input
          className={inputClass}
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="邮箱"
          type="email"
          autoComplete="email"
          disabled={submitting}
        />
      </label>
      <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
        验证码
        <div className="flex gap-2">
          <input
            className={inputClass}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="6 位验证码"
            inputMode="numeric"
            maxLength={6}
            autoComplete="one-time-code"
            disabled={submitting}
          />
          <button
            type="button"
            onClick={() => void onSendCode()}
            disabled={sending || countdown > 0 || submitting}
            className="shrink-0 rounded-md border border-[color:var(--color-line)] px-3 text-[13px] text-[color:var(--color-ink-dim)] transition-all enabled:hover:border-[color:var(--color-up)] enabled:hover:text-[color:var(--color-up)] disabled:opacity-40"
          >
            {countdown > 0 ? `${countdown}s 后重发` : sending ? "发送中…" : "获取验证码"}
          </button>
        </div>
      </label>
      {error && (
        <p
          className="rounded-md border border-[color:var(--color-up)]/40 bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-up)]"
          role="alert"
        >
          {error}
        </p>
      )}
      <button
        type="submit"
        disabled={submitting}
        className="rounded-md bg-[color:var(--color-up)] px-4 py-2 text-[14px] font-medium text-white transition-all enabled:hover:brightness-110 disabled:opacity-40"
      >
        {submitting ? "提交中…" : "注 册"}
      </button>
    </form>
  );
}
