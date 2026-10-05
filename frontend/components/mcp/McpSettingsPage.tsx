"use client";

import { useEffect, useState } from "react";
import {
  deleteConfig, fetchConfigs, fetchProviders, fetchTools, saveConfig, testConnection,
  McpProviderItem, McpConfigItem, McpToolItem,
} from "@/lib/mcpApi";

export default function McpSettingsPage() {
  const [providers, setProviders] = useState<McpProviderItem[]>([]);
  const [configs, setConfigs] = useState<McpConfigItem[]>([]);
  const [tools, setTools] = useState<Record<number, McpToolItem[]>>({});
  const [testResult, setTestResult] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);
  /** in-flight 防连点（全局互斥）：任一动作在途时忽略所有动作按钮的后续触发 */
  const [pending, setPending] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([fetchProviders(), fetchConfigs()])
      .then(([p, c]) => { setProviders(p); setConfigs(c); })
      .catch((e) => setError(e instanceof Error ? e.message : "数据加载失败"));
  }, []);

  const configOf = (providerId: number) => configs.find((c) => c.providerId === providerId);

  const onTest = async (providerId: number) => {
    if (pending) return; // 防连点：上一次动作仍在途，本次触发直接忽略
    const key = `test:${providerId}`;
    setPending(key);
    try {
      const r = await testConnection(providerId);
      setTestResult((p) => ({ ...p, [providerId]: r.success
        ? `连接成功（${r.latencyMs}ms，${r.tools.length} 个工具）` : r.errorMessage ?? "失败" }));
      if (r.success) {
        const ts = await fetchTools(providerId);
        setTools((p) => ({ ...p, [providerId]: ts }));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setPending(null);
    }
  };

  const onSave = async (providerId: number, enabled: boolean, disabledTools: string[]) => {
    if (pending) return;
    const key = `save:${providerId}`;
    setPending(key);
    try {
      const saved = await saveConfig(providerId, { enabled, disabledTools });
      setConfigs((p) => p.map((c) => c.providerId === providerId ? saved : c));
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setPending(null);
    }
  };

  const onDelete = async (providerId: number) => {
    if (pending) return;
    const key = `delete:${providerId}`;
    setPending(key);
    try {
      await deleteConfig(providerId);
      setConfigs((p) => p.filter((c) => c.providerId !== providerId));
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setPending(null);
    }
  };

  const toggleTool = (providerId: number, name: string) => {
    setTools((p) => ({
      ...p,
      [providerId]: (p[providerId] ?? []).map((t) => t.name === name ? { ...t, enabled: !t.enabled } : t),
    }));
  };

  return (
    <div className="p-6 max-w-3xl mx-auto space-y-4">
      <h1 className="text-xl font-semibold">MCP 数据源设置</h1>
      {error && <div className="text-[color:var(--color-up)] text-sm">{error}</div>}
      {providers.map((item) => {
        const cfg = configOf(item.id);
        const toolList = tools[item.id] ?? [];
        return (
          <div key={item.id} className="border rounded-lg p-4 space-y-2">
            <div className="flex items-center justify-between">
              <div>
                <span className="font-medium">{item.name}</span>
                <span className="ml-2 text-xs text-[color:var(--color-ink-dim)]">{item.authType}</span>
              </div>
              <span className="text-xs text-[color:var(--color-ink-faint)]">{cfg ? (cfg.enabled ? "已启用" : "已停用") : "未配置"}</span>
            </div>
            {item.domains.length > 0 && (
              <div className="flex flex-wrap gap-1">
                {item.domains.map((d) => <span key={d} className="text-xs bg-[color:var(--color-panel-2)] rounded px-2 py-0.5">{d}</span>)}
              </div>
            )}
            <div className="flex gap-2">
              <button onClick={() => onTest(item.id)} disabled={pending !== null}
                      className="border rounded px-3 py-1 text-sm">测试连接</button>
              <button onClick={() => onSave(item.id, true, toolList.filter((t) => !t.enabled).map((t) => t.name))}
                      disabled={pending !== null}
                      className="border rounded px-3 py-1 text-sm">保存</button>
              {cfg && (
                <button onClick={() => onDelete(item.id)} disabled={pending !== null}
                        className="border rounded px-3 py-1 text-sm text-[color:var(--color-up)]">删除</button>
              )}
            </div>
            {testResult[item.id] && <div className="text-xs text-[color:var(--color-ink-faint)]">{testResult[item.id]}</div>}
            {toolList.length > 0 && (
              <div className="space-y-1">
                {toolList.map((t) => (
                  <label key={t.name} className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={t.enabled} onChange={() => toggleTool(item.id, t.name)} />
                    <span className="font-mono text-xs">{t.name}</span>
                    <span className="text-xs text-[color:var(--color-ink-dim)]">{t.description}</span>
                  </label>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
