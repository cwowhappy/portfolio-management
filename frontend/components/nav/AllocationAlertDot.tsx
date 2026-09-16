"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth";
import { fetchRebalance } from "@/lib/allocationApi";

/** 「配置」导航红点：已登录挂载拉一次再平衡提醒态；allocation 页 ack/激活后经
 *  rebalance-refresh 事件通知重拉。未登录/失败静默（不打扰匿名浏览）。 */
export default function AllocationAlertDot() {
  const { user, loading } = useAuth();
  const [alert, setAlert] = useState(false);

  const refresh = useCallback(() => {
    fetchRebalance()
      .then((v) => setAlert(v.anyAlert))
      .catch(() => setAlert(false)); // 401/网络异常静默
  }, []);

  useEffect(() => {
    if (loading || !user) return; // 未登录不请求
    refresh();
    window.addEventListener("rebalance-refresh", refresh);
    return () => window.removeEventListener("rebalance-refresh", refresh);
  }, [user, loading, refresh]);

  if (!alert) return null;
  return (
    <span
      data-testid="allocation-alert-dot"
      aria-label="配置提醒"
      className="absolute -top-0.5 -right-1 h-2 w-2 rounded-full bg-[color:var(--color-down)]"
    />
  );
}
