"use client";
// 情报订阅设置页（P4 Task 6）：三区块——①推送总开关（checkbox 受控，保存走 PUT 全量
// 三字段——updateSubscription 为整体替换语义，任一区块保存都带上当前 stocks/industries）；
// ②关注标的（行情关键词搜索 + 结果点选加标签可删 + 从持仓导入去重）与关注行业
// （申万枚举下拉，来源 /api/valuation/industries 行业估值板既有接口——与筛选器同源，
// 不在前端重抄 31 行业码表；加载失败降级为裸码标签 + 空下拉）；③飞书绑定（未绑定：
// 生成码 → 展示 6 位码 + mm:ss 倒计时 + 发码指引；已绑定：绑定时间 + 解绑）。
// 交互范式照 McpSettingsPage：受控 useState + 顶部红字 error + 成功原地 setState 更新。

import { useEffect, useState } from "react";
import {
  createBindingCode, getBindingStatus, getSubscription, unbind, updateSubscription,
  type BindingCodeView, type BindingStatus, type SubscriptionStock,
} from "@/lib/intelligenceApi";
import { searchStocks } from "@/lib/api";
import { fetchPositions } from "@/lib/portfolioApi";
import { fetchValuationIndustries } from "@/lib/valuationApi";
import type { IndustryValuation, StockHit } from "@/lib/types";

const inputClass =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-1.5 text-sm text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

const buttonClass =
  "rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm hover:bg-[color:var(--color-panel)]/60 disabled:opacity-50";

const tagClass =
  "inline-flex items-center gap-1 rounded bg-[color:var(--color-panel)] px-2 py-0.5 text-xs text-[color:var(--color-ink)]";

/** Instant（UTC ISO）→ 上海时区 yyyy/MM/dd HH:mm；非法值原样返回（照 formatPublished 口径）。 */
function formatBoundAt(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

/** 剩余毫秒 → mm:ss（过期夹 00:00）。 */
function formatCountdown(remainingMs: number): string {
  const clamped = Math.max(0, remainingMs);
  const mm = Math.floor(clamped / 60_000);
  const ss = Math.floor((clamped % 60_000) / 1000);
  return `${String(mm).padStart(2, "0")}:${String(ss).padStart(2, "0")}`;
}

export default function IntelligenceSettingsPage() {
  const [loaded, setLoaded] = useState(false);
  const [pushEnabled, setPushEnabled] = useState(true);
  const [stocks, setStocks] = useState<SubscriptionStock[]>([]);
  const [industries, setIndustries] = useState<string[]>([]);
  const [binding, setBinding] = useState<BindingStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [okMsg, setOkMsg] = useState<string | null>(null);

  // 标的搜索（行情台同款 searchStocks：/api/market/search 反代）
  const [stockQuery, setStockQuery] = useState("");
  const [hits, setHits] = useState<StockHit[]>([]);
  // 申万行业枚举（估值板接口同源；失败降级——标签退回裸码）
  const [industryOptions, setIndustryOptions] = useState<IndustryValuation[]>([]);
  // 绑定码与倒计时
  const [code, setCode] = useState<BindingCodeView | null>(null);
  const [remainingMs, setRemainingMs] = useState(0);

  useEffect(() => {
    Promise.all([getSubscription(), getBindingStatus()])
      .then(([view, status]) => {
        setPushEnabled(view.pushEnabled);
        setStocks(view.stocks);
        setIndustries(view.industries);
        setBinding(status);
        setLoaded(true);
      })
      .catch((e) => setError((e as Error).message));
    fetchValuationIndustries()
      .then(setIndustryOptions)
      .catch(() => {
        // 行业枚举加载失败不阻塞页面：标签退回裸码展示、下拉空，保存仍可提交码集
      });
  }, []);

  // 倒计时：每秒重算剩余有效期（expiresAt 服务端时刻，倒计时夹 0 不为负）
  useEffect(() => {
    if (!code) return;
    const tick = () =>
      setRemainingMs(Math.max(0, new Date(code.expiresAt).getTime() - Date.now()));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [code]);

  /** 保存（PUT 全量三字段）：成功以服务端回执原地更新 + 绿色提示，失败红字。 */
  const save = async () => {
    setError(null);
    setOkMsg(null);
    try {
      const view = await updateSubscription({ pushEnabled, industries, stocks });
      setPushEnabled(view.pushEnabled);
      setStocks(view.stocks);
      setIndustries(view.industries);
      setOkMsg("已保存");
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const onStockSearch = async () => {
    const q = stockQuery.trim();
    if (!q) return;
    try {
      setHits(await searchStocks(q));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const addStock = (hit: StockHit) => {
    setStocks((prev) =>
      prev.some((s) => s.code === hit.code) ? prev : [...prev, { code: hit.code, name: hit.name }],
    );
    setStockQuery("");
    setHits([]);
  };

  const removeStock = (code: string) => {
    setStocks((prev) => prev.filter((s) => s.code !== code));
  };

  const importFromPortfolio = async () => {
    try {
      const positions = await fetchPositions();
      setStocks((prev) => {
        const seen = new Set(prev.map((s) => s.code));
        const added = positions
          .map((p) => ({ code: p.stockCode, name: p.stockName }))
          .filter((s) => !seen.has(s.code));
        return [...prev, ...added];
      });
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const addIndustry = (code: string) => {
    if (!code) return;
    setIndustries((prev) => (prev.includes(code) ? prev : [...prev, code]));
  };

  const removeIndustry = (code: string) => {
    setIndustries((prev) => prev.filter((c) => c !== code));
  };

  const onGenerateCode = async () => {
    setError(null);
    try {
      setCode(await createBindingCode());
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const refreshBinding = async () => {
    try {
      setBinding(await getBindingStatus());
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const onUnbind = async () => {
    if (!confirm("解绑后将不再收到飞书推送，确定解绑？")) return;
    setError(null);
    try {
      await unbind();
      setBinding({ bound: false, boundAt: null });
      setCode(null);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const industryNameOf = (code: string) =>
    industryOptions.find((i) => i.industryCode === code)?.industryName;

  const expired = code !== null && remainingMs <= 0;

  return (
    <div className="mx-auto max-w-3xl px-6 py-8 space-y-4" data-testid="intel-settings">
      <h1 className="font-[family-name:var(--font-display)] text-2xl">情报订阅</h1>
      {error && (
        <div className="text-sm text-red-600" data-testid="settings-error">
          {error}
        </div>
      )}
      {okMsg && (
        <div className="text-sm text-green-600" data-testid="save-ok">
          {okMsg}
        </div>
      )}

      {/* ① 推送总开关 */}
      <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            aria-label="推送总开关"
            checked={pushEnabled}
            onChange={(e) => setPushEnabled(e.target.checked)}
          />
          <span className="font-medium">开启情报推送</span>
          <span className="text-xs text-[color:var(--color-ink-faint)]">
            每日简报与重大公告定向推送至已绑定的飞书
          </span>
        </label>
        <div>
          <button type="button" onClick={save} disabled={!loaded} className={buttonClass}>
            保存推送设置
          </button>
        </div>
      </section>

      {/* ② 关注标的 / 关注行业 */}
      <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4">
        <div className="space-y-2">
          <div className="text-sm font-medium">关注标的</div>
          <div className="flex flex-wrap items-center gap-2">
            <input
              type="search"
              aria-label="标的搜索"
              placeholder="代码/名称（回车搜索）"
              className={`${inputClass} w-56`}
              value={stockQuery}
              onChange={(e) => setStockQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") onStockSearch();
              }}
            />
            <button type="button" onClick={importFromPortfolio} className={buttonClass}>
              从持仓导入
            </button>
          </div>
          {hits.length > 0 && (
            <div
              className="flex flex-col gap-1 rounded-md border border-[color:var(--color-line)] p-1"
              data-testid="stock-hits"
            >
              {hits.map((hit) => (
                <button
                  key={hit.code}
                  type="button"
                  className="rounded px-2 py-1 text-left text-sm hover:bg-[color:var(--color-panel)]"
                  onClick={() => addStock(hit)}
                >
                  {hit.code} {hit.name}
                </button>
              ))}
            </div>
          )}
          <div className="flex flex-wrap gap-1.5">
            {stocks.map((s) => (
              <span key={s.code} className={tagClass}>
                {s.code}
                {s.name ? ` ${s.name}` : ""}
                <button
                  type="button"
                  aria-label={`删除标的 ${s.code}`}
                  className="text-[color:var(--color-ink-faint)] hover:text-red-600"
                  onClick={() => removeStock(s.code)}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        </div>

        <div className="space-y-2">
          <div className="text-sm font-medium">关注行业</div>
          <select
            aria-label="关注行业"
            className={`${inputClass} w-56`}
            value=""
            onChange={(e) => addIndustry(e.target.value)}
          >
            <option value="">选择行业</option>
            {industryOptions.map((i) => (
              <option key={i.industryCode} value={i.industryCode}>
                {i.industryName}
              </option>
            ))}
          </select>
          <div className="flex flex-wrap gap-1.5">
            {industries.map((code) => (
              <span key={code} className={tagClass}>
                {code}
                {industryNameOf(code) ? ` ${industryNameOf(code)}` : ""}
                <button
                  type="button"
                  aria-label={`删除行业 ${code}`}
                  className="text-[color:var(--color-ink-faint)] hover:text-red-600"
                  onClick={() => removeIndustry(code)}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        </div>

        <div>
          <button type="button" onClick={save} disabled={!loaded} className={buttonClass}>
            保存关注设置
          </button>
        </div>
      </section>

      {/* ③ 飞书绑定 */}
      <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
        <div className="text-sm font-medium">飞书绑定</div>
        {binding?.bound ? (
          <div className="space-y-2 text-sm">
            <div>
              已绑定 · 绑定时间 <span data-testid="binding-bound-at">{formatBoundAt(binding.boundAt ?? "")}</span>
            </div>
            <button type="button" onClick={onUnbind} className={buttonClass}>
              解绑
            </button>
          </div>
        ) : (
          <div className="space-y-2 text-sm">
            {code ? (
              <>
                <div className="flex items-center gap-3">
                  <span className="font-mono text-lg tracking-widest" data-testid="binding-code">
                    {code.code}
                  </span>
                  <span data-testid="binding-countdown" className="text-[color:var(--color-ink-dim)]">
                    {formatCountdown(remainingMs)}
                  </span>
                </div>
                {expired ? (
                  <div className="text-xs text-red-600">绑定码已过期，请生成新码</div>
                ) : (
                  <div className="text-xs text-[color:var(--color-ink-dim)]">
                    在飞书中对机器人发送此码完成绑定（有效期 10 分钟）；若要查询股票，请改用“绑定
                    600519”格式发送
                  </div>
                )}
                <div className="flex gap-2">
                  <button type="button" onClick={onGenerateCode} className={buttonClass}>
                    生成新码
                  </button>
                  <button type="button" onClick={refreshBinding} className={buttonClass}>
                    刷新绑定状态
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="text-xs text-[color:var(--color-ink-dim)]">
                  绑定后每日简报与重大公告推送至你的飞书
                </div>
                <button type="button" onClick={onGenerateCode} className={buttonClass}>
                  生成绑定码
                </button>
              </>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
