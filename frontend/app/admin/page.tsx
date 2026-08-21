"use client";

import { useCallback, useEffect, useState } from "react";
import { RequireAdmin } from "@/components/auth/RequireAdmin";
import { adminApi, type AdminUserView } from "@/lib/adminApi";

const roleLabel: Record<AdminUserView["role"], string> = {
  ADMIN: "管理员",
  USER: "用户",
};

const statusLabel: Record<AdminUserView["status"], string> = {
  PENDING: "待审核",
  APPROVED: "已通过",
  REJECTED: "已拒绝",
};

const statusClass: Record<AdminUserView["status"], string> = {
  PENDING: "text-[color:var(--color-amber)]",
  APPROVED: "text-[color:var(--color-down)]",
  REJECTED: "text-[color:var(--color-up)]",
};

const ghostBtn =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-1.5 text-[12px] text-[color:var(--color-ink-dim)] transition-all enabled:hover:border-[color:var(--color-line)] enabled:hover:text-[color:var(--color-ink)] disabled:cursor-not-allowed disabled:opacity-40";

function AdminPanel() {
  const [users, setUsers] = useState<AdminUserView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const refresh = useCallback(async () => {
    try {
      setUsers(await adminApi.list());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载用户列表失败");
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function act(id: number, fn: (id: number) => Promise<unknown>) {
    setBusyId(id);
    setError(null);
    try {
      await fn(id);
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "操作失败");
    } finally {
      setBusyId(null);
    }
  }

  async function handleResetPassword(u: AdminUserView) {
    const newPassword = window.prompt(`为 ${u.username} 设置新密码（至少 8 位，含字母和数字）`);
    if (!newPassword) return;
    await act(u.id, (id) => adminApi.resetPassword(id, newPassword));
  }

  if (!users) {
    return (
      <div className="grid h-full place-items-center text-[13px] text-[color:var(--color-ink-faint)]">
        {error ?? "加载中…"}
      </div>
    );
  }

  const pending = users.filter((u) => u.status === "PENDING");

  return (
    <div className="mx-auto max-w-[1000px] px-5 py-8">
      <h1 className="font-[family-name:var(--font-display)] text-[20px] tracking-wide text-[color:var(--color-ink)]">
        用户管理
      </h1>
      <p className="mt-1 text-[13px] text-[color:var(--color-ink-faint)]">
        审核注册申请，或停用 / 启用 / 重置用户密码
      </p>

      {error && (
        <p
          role="alert"
          className="mt-4 rounded-md border border-[color:var(--color-up)]/40 bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-up)]"
        >
          {error}
        </p>
      )}

      <section className="mt-7">
        <h2 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">
          待审核用户
          <span className="ml-2 text-[12px] text-[color:var(--color-ink-faint)]">({pending.length})</span>
        </h2>
        {pending.length === 0 ? (
          <p className="mt-3 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]">
            暂无待审核用户
          </p>
        ) : (
          <ul className="mt-3 flex flex-col gap-2">
            {pending.map((u) => (
              <li
                key={u.id}
                className="flex items-center justify-between rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-3"
              >
                <div>
                  <p className="text-[14px] text-[color:var(--color-ink)]">{u.username}</p>
                  <p className="mt-0.5 text-[12px] text-[color:var(--color-ink-faint)]">
                    申请注册，等待管理员审核
                  </p>
                </div>
                <div className="flex gap-2">
                  <button
                    type="button"
                    disabled={busyId === u.id}
                    onClick={() => act(u.id, adminApi.approve)}
                    className="rounded-md bg-[color:var(--color-up)] px-3 py-1.5 text-[12px] font-medium text-white transition-all enabled:hover:brightness-110 disabled:opacity-40"
                  >
                    通过
                  </button>
                  <button
                    type="button"
                    disabled={busyId === u.id}
                    onClick={() => act(u.id, adminApi.reject)}
                    className={ghostBtn}
                  >
                    拒绝
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="mt-8">
        <h2 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">
          全部用户
          <span className="ml-2 text-[12px] text-[color:var(--color-ink-faint)]">({users.length})</span>
        </h2>
        <div className="mt-3 overflow-x-auto rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)]">
          <table className="w-full text-left text-[13px]">
            <thead>
              <tr className="border-b border-[color:var(--color-line)] font-[family-name:var(--font-mono)] text-[11px] tracking-wider text-[color:var(--color-ink-faint)]">
                <th className="px-4 py-2.5 font-normal">用户名</th>
                <th className="px-4 py-2.5 font-normal">角色</th>
                <th className="px-4 py-2.5 font-normal">状态</th>
                <th className="px-4 py-2.5 font-normal">启用</th>
                <th className="px-4 py-2.5 text-right font-normal">操作</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr
                  key={u.id}
                  className="border-b border-[color:var(--color-line-soft)] last:border-b-0"
                >
                  <td className="px-4 py-2.5 text-[color:var(--color-ink)]">{u.username}</td>
                  <td className="px-4 py-2.5 text-[color:var(--color-ink-dim)]">{roleLabel[u.role]}</td>
                  <td className={"px-4 py-2.5 " + statusClass[u.status]}>{statusLabel[u.status]}</td>
                  <td className="px-4 py-2.5 text-[color:var(--color-ink-dim)]">
                    {u.enabled ? "是" : "否"}
                  </td>
                  <td className="px-4 py-2.5 text-right">
                    {u.role !== "ADMIN" && u.status === "APPROVED" ? (
                      <div className="flex justify-end gap-2">
                        <button
                          type="button"
                          disabled={busyId === u.id}
                          onClick={() => act(u.id, u.enabled ? adminApi.disable : adminApi.enable)}
                          className={ghostBtn}
                        >
                          {u.enabled ? "停用" : "启用"}
                        </button>
                        <button
                          type="button"
                          disabled={busyId === u.id}
                          onClick={() => handleResetPassword(u)}
                          className={ghostBtn}
                        >
                          重置密码
                        </button>
                      </div>
                    ) : (
                      <span className="text-[12px] text-[color:var(--color-ink-faint)]">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

export default function AdminPage() {
  return (
    <RequireAdmin>
      <AdminPanel />
    </RequireAdmin>
  );
}
