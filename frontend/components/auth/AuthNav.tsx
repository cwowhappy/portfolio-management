"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth";

const linkClass =
  "rounded-md px-3 py-1.5 text-[color:var(--color-ink-dim)] transition-colors hover:bg-[color:var(--color-panel)] hover:text-[color:var(--color-ink)]";

export function AuthNav() {
  const { user, loading, logout } = useAuth();
  const router = useRouter();

  if (loading) {
    return <span className="px-3 py-1.5 text-[13px] text-[color:var(--color-ink-faint)]">…</span>;
  }

  if (!user) {
    return (
      <>
        <Link href="/login" className={linkClass}>
          登录
        </Link>
        <Link href="/register" className={linkClass}>
          注册
        </Link>
      </>
    );
  }

  return (
    <>
      {user.role === "ADMIN" && (
        <Link href="/admin" className={linkClass}>
          管理
        </Link>
      )}
      <span className="px-1 text-[13px] text-[color:var(--color-ink)]">{user.username}</span>
      <button
        type="button"
        onClick={async () => {
          try {
            await logout();
          } finally {
            router.push("/login");
          }
        }}
        className="rounded-md px-3 py-1.5 text-[13px] text-[color:var(--color-ink-faint)] transition-colors hover:text-[color:var(--color-up)]"
      >
        退出
      </button>
    </>
  );
}
