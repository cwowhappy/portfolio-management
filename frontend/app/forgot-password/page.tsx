import type { Metadata } from "next";
import Link from "next/link";
import { ForgotPasswordForm } from "@/components/auth/ForgotPasswordForm";

export const metadata: Metadata = {
  title: "找回密码 · 九和",
};

export default function ForgotPasswordPage() {
  return (
    <div className="grid h-full place-items-center px-5">
      <div className="w-full max-w-sm rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] p-7 shadow-[var(--shadow-panel)]">
        <div className="mb-6 text-center">
          <h1 className="font-[family-name:var(--font-display)] text-[22px] tracking-wide text-[color:var(--color-ink)]">
            找回密码
          </h1>
          <p className="mt-1.5 text-[13px] text-[color:var(--color-ink-faint)]">
            通过邮箱验证码重置密码
          </p>
        </div>
        <ForgotPasswordForm />
        <p className="mt-6 text-center text-[13px] text-[color:var(--color-ink-faint)]">
          想起来了？{" "}
          <Link href="/login" className="text-[color:var(--color-up)] hover:brightness-110">
            去登录
          </Link>
        </p>
      </div>
    </div>
  );
}
