import type { Metadata } from "next";
import Link from "next/link";
import { RegisterForm } from "@/components/auth/RegisterForm";

export const metadata: Metadata = {
  title: "注册 · 九和",
};

export default function RegisterPage() {
  return (
    <div className="grid h-full place-items-center px-5">
      <div className="w-full max-w-sm rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] p-7 shadow-[var(--shadow-panel)]">
        <div className="mb-6 text-center">
          <h1 className="font-[family-name:var(--font-display)] text-[22px] tracking-wide text-[color:var(--color-ink)]">
            注册九和
          </h1>
          <p className="mt-1.5 text-[13px] text-[color:var(--color-ink-faint)]">
            注册后需管理员审核方可登录
          </p>
        </div>
        <RegisterForm />
        <p className="mt-6 text-center text-[13px] text-[color:var(--color-ink-faint)]">
          已有账号？{" "}
          <Link href="/login" className="text-[color:var(--color-up)] hover:brightness-110">
            去登录
          </Link>
        </p>
      </div>
    </div>
  );
}
