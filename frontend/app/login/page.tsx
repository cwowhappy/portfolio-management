import type { Metadata } from "next";
import Link from "next/link";
import { LoginForm } from "@/components/auth/LoginForm";

export const metadata: Metadata = {
  title: "登录 · 砚台",
};

export default function LoginPage() {
  return (
    <div className="grid h-full place-items-center px-5">
      <div className="w-full max-w-sm rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] p-7 shadow-[var(--shadow-panel)]">
        <div className="mb-6 text-center">
          <h1 className="font-[family-name:var(--font-display)] text-[22px] tracking-wide text-[color:var(--color-ink)]">
            登录砚台
          </h1>
          <p className="mt-1.5 text-[13px] text-[color:var(--color-ink-faint)]">
            A股投研助手 · 请使用已审核账号
          </p>
        </div>
        <LoginForm />
        <p className="mt-6 text-center text-[13px] text-[color:var(--color-ink-faint)]">
          还没有账号？{" "}
          <Link href="/register" className="text-[color:var(--color-up)] hover:brightness-110">
            去注册
          </Link>
        </p>
      </div>
    </div>
  );
}
