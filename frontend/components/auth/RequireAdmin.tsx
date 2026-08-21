"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/lib/auth";

export function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (!loading && user?.role !== "ADMIN") router.replace("/");
  }, [loading, user, router]);
  if (loading)
    return (
      <div className="grid h-full place-items-center text-[color:var(--color-ink-faint)]">
        加载中…
      </div>
    );
  if (!user || user.role !== "ADMIN") return null;
  return <>{children}</>;
}
