"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/lib/auth";

export function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, user, router]);
  if (loading)
    return (
      <div className="grid h-full place-items-center text-[color:var(--color-ink-faint)]">
        加载中…
      </div>
    );
  if (!user) return null;
  return <>{children}</>;
}
