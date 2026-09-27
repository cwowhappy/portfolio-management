"use client";

import { useState } from "react";
import { checkPassword } from "@/lib/password";
import { useAuth } from "@/lib/auth";

const inputClass =
  "w-full rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export function ForgotPasswordForm() {
  const { sendResetCode, resetPassword } = useAuth();
  const [identifier, setIdentifier] = useState("");
  const [code, setCode] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [done, setDone] = useState(false);

  async function onSendCode() {
    setError(null);
    if (!identifier.trim()) return setError("请输入用户名或邮箱");
    setBusy(true);
    try {
      await sendResetCode(identifier.trim());
      setSent(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "发送失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  async function onSubmit() {
    setError(null);
    const pwd = checkPassword(newPassword);
    if (!pwd.ok) return setError(pwd.error ?? "密码不符合要求");
    if (!code.trim()) return setError("请输入验证码");
    setBusy(true);
    try {
      await resetPassword(identifier.trim(), code.trim(), newPassword);
      setDone(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "重置失败，请稍后重试");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="flex flex-col items-center gap-3 py-6 text-center">
        <p className="text-[22px] leading-snug text-[color:var(--color-ink)]">密码已重置 ✅</p>
        <p className="max-w-sm text-[14px] leading-relaxed text-[color:var(--color-ink-dim)]">
          请使用新密码登录；其他设备上的自动登录已全部失效。
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
        用户名或邮箱
        <input
          className={inputClass}
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          placeholder="用户名或邮箱"
          disabled={busy || sent}
        />
      </label>
      {!sent ? (
        <button
          type="button"
          disabled={busy}
          onClick={() => void onSendCode()}
          className="rounded-md bg-[color:var(--color-up)] px-4 py-2 text-[14px] font-medium text-white transition-all enabled:hover:brightness-110 disabled:opacity-40"
        >
          {busy ? "发送中…" : "发送验证码"}
        </button>
      ) : (
        <>
          <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
            邮箱验证码
            <input
              className={inputClass}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="6 位验证码"
              inputMode="numeric"
              maxLength={6}
              autoComplete="one-time-code"
            />
          </label>
          <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
            新密码
            <input
              className={inputClass}
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              placeholder="新密码（至少 8 位，含字母和数字）"
              autoComplete="new-password"
            />
          </label>
          <button
            type="button"
            disabled={busy}
            onClick={() => void onSubmit()}
            className="rounded-md bg-[color:var(--color-up)] px-4 py-2 text-[14px] font-medium text-white transition-all enabled:hover:brightness-110 disabled:opacity-40"
          >
            {busy ? "提交中…" : "重置密码"}
          </button>
        </>
      )}
      {error && (
        <p role="alert" className="rounded-md border border-[color:var(--color-up)]/40 bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-up)]">
          {error}
        </p>
      )}
    </div>
  );
}
