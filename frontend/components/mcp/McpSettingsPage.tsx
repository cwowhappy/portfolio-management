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

  useEffect(() => {
    Promise.all([fetchProviders(), fetchConfigs()])
      .then(([p, c]) => { setProviders(p); setConfigs(c); })
      .catch((e) => setError(e.message));
  }, []);

  const configOf = (providerId: number) => configs.find((c) => c.providerId === providerId);

  const onTest = async (providerId: number) => {
    try {
      const r = await testConnection(providerId);
      setTestResult((p) => ({ ...p, [providerId]: r.success
        ? `连接成功（${r.latencyMs}ms，${r.tools.length} 个工具）` : r.errorMessage ?? "失败" }));
      if (r.success) {
        const ts = await fetchTools(providerId);
        setTools((p) => ({ ...p, [providerId]: ts }));
      }
    } catch (e) { setError((e as Error).message); }
  };

  const onSave = async (providerId: number, enabled: boolean, disabledTools: string[]) => {
    try {
      const saved = await saveConfig(providerId, { enabled, disabledTools });
      setConfigs((p) => p.map((c) => c.providerId === providerId ? saved : c));
    } catch (e) { setError((e as Error).message); }
  };

  const onDelete = async (providerId: number) => {
    try {
      await deleteConfig(providerId);
      setConfigs((p) => p.filter((c) => c.providerId !== providerId));
    } catch (e) { setError((e as Error).message); }
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
      {error && <div className="text-red-600 text-sm">{error}</div>}
      {providers.map((item) => {
        const cfg = configOf(item.id);
        const toolList = tools[item.id] ?? [];
        return (
          <div key={item.id} className="border rounded-lg p-4 space-y-2">
            <div className="flex items-center justify-between">
              <div>
                <span className="font-medium">{item.name}</span>
                <span className="ml-2 text-xs text-gray-500">{item.authType}</span>
              </div>
              <span className="text-xs text-gray-400">{cfg ? (cfg.enabled ? "已启用" : "已停用") : "未配置"}</span>
            </div>
            {item.domains.length > 0 && (
              <div className="flex flex-wrap gap-1">
                {item.domains.map((d) => <span key={d} className="text-xs bg-gray-100 rounded px-2 py-0.5">{d}</span>)}
              </div>
            )}
            <div className="flex gap-2">
              <button onClick={() => onTest(item.id)} className="border rounded px-3 py-1 text-sm">测试连接</button>
              <button onClick={() => onSave(item.id, true, toolList.filter((t) => !t.enabled).map((t) => t.name))}
                      className="border rounded px-3 py-1 text-sm">保存</button>
              {cfg && (
                <button onClick={() => onDelete(item.id)} className="border rounded px-3 py-1 text-sm text-red-600">删除</button>
              )}
            </div>
            {testResult[item.id] && <div className="text-xs text-gray-600">{testResult[item.id]}</div>}
            {toolList.length > 0 && (
              <div className="space-y-1">
                {toolList.map((t) => (
                  <label key={t.name} className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={t.enabled} onChange={() => toggleTool(item.id, t.name)} />
                    <span className="font-mono text-xs">{t.name}</span>
                    <span className="text-xs text-gray-500">{t.description}</span>
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
