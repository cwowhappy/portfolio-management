"use client";

import { useCallback, useEffect, useState } from "react";
import { adminApi } from "@/lib/adminApi";
import { fetchProviders, type McpProviderItem } from "@/lib/mcpApi";

const ghostBtn =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-1.5 text-[12px] text-[color:var(--color-ink-dim)] transition-all enabled:hover:border-[color:var(--color-line)] enabled:hover:text-[color:var(--color-ink)] disabled:cursor-not-allowed disabled:opacity-40";

const authLabel: Record<Exclude<McpProviderItem["authType"], "NONE">, string> = {
  BEARER: "Bearer",
  HEADER: "Header",
};

/**
 * MCP 数据源 Token 管理（P1-10，拍板 D1）：状态点 + 设置/更换输入。
 * 明文只经输入框 → 请求体一次，界面与接口均不回显已存值（NFR-1）；
 * 已存值在库内为 AES-256-GCM 密文，更换后需重启后端生效（客户端池缓存）。
 */
export default function McpTokenSection() {
  const [providers, setProviders] = useState<McpProviderItem[] | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busyCode, setBusyCode] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);

  const load = useCallback(async () => {
    try {
      setProviders(await fetchProviders());
    } catch {
      setNotice({ ok: false, text: "数据源列表加载失败" });
    }
  }, []);

  useEffect(() => {
    // async 包裹：让 setState 明确发生在异步回调中，避免 effect 体内同步 setState 触发级联渲染
    void (async () => {
      await load();
    })();
  }, [load]);

  const save = async (provider: McpProviderItem) => {
    const token = (drafts[provider.code] ?? "").trim();
    if (!token) return;
    setBusyCode(provider.code);
    setNotice(null);
    try {
      await adminApi.setMcpProviderToken(provider.code, token);
      setDrafts((prev) => ({ ...prev, [provider.code]: "" }));
      setNotice({ ok: true, text: `${provider.name} token 已更新（密文落库，重启后端后生效）` });
      await load();
    } catch (e) {
      setNotice({ ok: false, text: e instanceof Error ? e.message : "保存失败" });
    } finally {
      setBusyCode(null);
    }
  };

  const managed = (providers ?? []).filter((p) => p.authType !== "NONE");

  return (
    <section aria-label="MCP 数据源 Token" className="mt-7">
      <h2 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">
        MCP 数据源 Token
        <span className="ml-2 text-[12px] text-[color:var(--color-ink-faint)]">
          （AES-256-GCM 加密落库，不回显）
        </span>
      </h2>

      {notice && (
        <p
          role="status"
          className={
            notice.ok
              ? "mt-3 rounded-md border border-[color:var(--color-down)]/40 bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-down)]"
              : "mt-3 rounded-md border border-[color:var(--color-up)]/40 bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-up)]"
          }
        >
          {notice.text}
        </p>
      )}

      <ul className="mt-3 flex flex-col gap-2">
        {managed.map((p) => (
          <li
            key={p.id}
            data-testid={`mcp-provider-${p.code}`}
            className="flex flex-wrap items-center gap-3 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-3"
          >
            <div className="min-w-[180px]">
              <p className="text-[14px] text-[color:var(--color-ink)]">
                {p.name}
                <span className="ml-2 text-[12px] text-[color:var(--color-ink-faint)]">
                  {p.code} · {authLabel[p.authType as Exclude<McpProviderItem["authType"], "NONE">]}
                </span>
              </p>
              <p
                className={
                  p.hasToken
                    ? "mt-0.5 text-[12px] text-[color:var(--color-down)]"
                    : "mt-0.5 text-[12px] text-[color:var(--color-ink-faint)]"
                }
              >
                {p.hasToken ? "● 已设置" : "○ 未设置"}
              </p>
            </div>
            <input
              type="password"
              aria-label={`${p.name} 新 token`}
              value={drafts[p.code] ?? ""}
              onChange={(e) => setDrafts((prev) => ({ ...prev, [p.code]: e.target.value }))}
              placeholder="输入新 token"
              autoComplete="off"
              className="min-w-[200px] flex-1 rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg)] px-3 py-2 text-[13px] text-[color:var(--color-ink)] focus:outline-none focus:border-[color:var(--color-up)]"
            />
            <button
              type="button"
              disabled={busyCode === p.code || !(drafts[p.code] ?? "").trim()}
              onClick={() => void save(p)}
              className={ghostBtn}
            >
              {busyCode === p.code ? "保存中…" : "保存"}
            </button>
          </li>
        ))}
        {providers !== null && managed.length === 0 && (
          <li className="rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]">
            无需要 token 的数据源
          </li>
        )}
      </ul>
    </section>
  );
}
