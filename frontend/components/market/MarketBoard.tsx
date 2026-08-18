"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchFinancials,
  fetchKline,
  fetchNews,
  fetchOverview,
  fetchQuote,
  searchStocks,
} from "@/lib/api";
import type {
  Financials,
  KlineBar,
  MarketOverview,
  NewsItem,
  Quote,
  StockHit,
} from "@/lib/types";
import KlineChart from "./KlineChart";

function fmt(v: number | null | undefined, digits = 2): string {
  return v == null ? "—" : v.toFixed(digits);
}

function fmtYi(v: number | null | undefined): string {
  if (v == null) return "—";
  const yi = v / 1e8;
  if (Math.abs(yi) >= 10000) return (yi / 10000).toFixed(2) + " 万亿";
  return yi.toFixed(2) + " 亿";
}

export default function MarketBoard() {
  const [overview, setOverview] = useState<MarketOverview | null>(null);
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<StockHit[]>([]);
  const [selected, setSelected] = useState<StockHit | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [kline, setKline] = useState<KlineBar[]>([]);
  const [period, setPeriod] = useState<"day" | "week" | "month">("day");
  const [financials, setFinancials] = useState<Financials | null>(null);
  const [news, setNews] = useState<NewsItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    fetchOverview().then(setOverview).catch(() => setOverview(null));
    const t = setInterval(() => {
      fetchOverview().then(setOverview).catch(() => {});
    }, 30_000);
    return () => clearInterval(t);
  }, []);

  const onQuery = useCallback((q: string) => {
    setQuery(q);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      if (!q.trim()) {
        setHits([]);
        return;
      }
      try {
        setHits(await searchStocks(q.trim()));
      } catch {
        setHits([]);
      }
    }, 300);
  }, []);

  const select = useCallback(async (hit: StockHit) => {
    setSelected(hit);
    setHits([]);
    setQuery(hit.name);
    setLoading(true);
    setError(null);
    try {
      const [q, k, f, n] = await Promise.all([
        fetchQuote(hit.code),
        fetchKline(hit.code, "day", 120),
        fetchFinancials(hit.code),
        fetchNews(hit.code, 8),
      ]);
      setQuote(q);
      setKline(k);
      setFinancials(f);
      setNews(n);
    } catch (e) {
      setError(e instanceof Error ? e.message : "数据加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  const switchPeriod = useCallback(
    async (p: "day" | "week" | "month") => {
      setPeriod(p);
      if (!selected) return;
      try {
        setKline(await fetchKline(selected.code, p, 120));
      } catch {
        // 保持旧图
      }
    },
    [selected],
  );

  const upCls = (v: number) => (v > 0 ? "up" : v < 0 ? "down" : "flat");

  return (
    <div className="mx-auto max-w-[1240px] px-6 pb-16 pt-8">
      {/* 指数条 */}
      <section className="animate-rise mb-8 grid grid-cols-3 gap-3">
        {(overview?.indices ?? []).map((idx) => (
          <div
            key={idx.code}
            className="rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 px-5 py-4"
          >
            <p className="text-[12px] text-[color:var(--color-ink-faint)]">{idx.name}</p>
            <p className={"tabular mt-1.5 text-[26px] font-medium " + upCls(idx.changePct)}>
              {idx.price.toFixed(2)}
            </p>
            <p className={"tabular mt-1 text-[12px] " + upCls(idx.changePct)}>
              {idx.change > 0 ? "+" : ""}
              {idx.change.toFixed(2)}（{idx.changePct > 0 ? "+" : ""}
              {idx.changePct.toFixed(2)}%）
            </p>
          </div>
        ))}
        {!overview && (
          <>
            <div className="skeleton h-[96px]" />
            <div className="skeleton h-[96px]" />
            <div className="skeleton h-[96px]" />
          </>
        )}
      </section>

      {/* 搜索 */}
      <section className="animate-rise relative mb-8">
        <input
          value={query}
          onChange={(e) => onQuery(e.target.value)}
          placeholder="输入股票名称或代码搜索，如 茅台 / 600519"
          className="composer w-full px-4 py-3 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:outline-none"
        />
        {hits.length > 0 && (
          <ul className="absolute left-0 right-0 top-[calc(100%+6px)] z-20 overflow-hidden rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)] shadow-[var(--shadow-panel)]">
            {hits.map((h) => (
              <li key={h.code}>
                <button
                  type="button"
                  onClick={() => select(h)}
                  className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-[color:var(--color-panel-2)]"
                >
                  <span className="text-[13px] text-[color:var(--color-ink)]">{h.name}</span>
                  <span className="tabular text-[12px] text-[color:var(--color-ink-faint)]">
                    {h.code}
                  </span>
                  <span className="ml-auto rounded border border-[color:var(--color-line-soft)] px-1.5 py-0.5 text-[10px] text-[color:var(--color-ink-faint)]">
                    {h.marketName}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {error && (
        <p className="mb-4 rounded-lg border border-[color:var(--color-amber)]/40 px-4 py-2.5 text-[13px] text-[color:var(--color-amber)]">
          {error}
        </p>
      )}

      {loading && (
        <div className="space-y-4">
          <div className="skeleton h-[120px]" />
          <div className="skeleton h-[380px]" />
        </div>
      )}

      {quote && selected && (
        <>
          {/* 报价头 */}
          <section className="animate-rise mb-6 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-6">
            <div className="flex flex-wrap items-end justify-between gap-6">
              <div>
                <p className="text-[13px] text-[color:var(--color-ink-dim)]">
                  {quote.name}
                  <span className="tabular ml-2 text-[12px] text-[color:var(--color-ink-faint)]">
                    {quote.code} · {quote.time || "—"}
                  </span>
                </p>
                <p className={"tabular mt-1 text-[44px] font-medium leading-none " + upCls(quote.changePct)}>
                  {quote.price.toFixed(2)}
                </p>
                <p className={"tabular mt-2 text-[13px] " + upCls(quote.changePct)}>
                  {quote.change > 0 ? "+" : ""}
                  {quote.change.toFixed(2)}　{quote.changePct > 0 ? "+" : ""}
                  {quote.changePct.toFixed(2)}%
                </p>
              </div>
              <div className="tabular grid grid-cols-3 gap-x-10 gap-y-2 text-right text-[12px]">
                {[
                  ["今开", fmt(quote.open)],
                  ["最高", fmt(quote.high)],
                  ["最低", fmt(quote.low)],
                  ["昨收", fmt(quote.prevClose)],
                  ["成交量", (quote.volume / 10000).toFixed(2) + " 万手"],
                  ["成交额", fmtYi(quote.amount)],
                  ["市盈率", fmt(quote.pe ?? financials?.pe)],
                  ["市净率", fmt(quote.pb ?? financials?.pb)],
                ].map(([k, v]) => (
                  <div key={k}>
                    <span className="mr-2 text-[color:var(--color-ink-faint)]">{k}</span>
                    <span className="text-[color:var(--color-ink)]">{v}</span>
                  </div>
                ))}
              </div>
            </div>
          </section>

          {/* K线 */}
          <section className="animate-rise mb-6 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="font-[family-name:var(--font-display)] text-[15px] text-[color:var(--color-ink)]">
                走势 · 前复权
              </h2>
              <div className="flex gap-1 rounded-lg border border-[color:var(--color-line-soft)] p-0.5">
                {(["day", "week", "month"] as const).map((p) => (
                  <button
                    key={p}
                    type="button"
                    onClick={() => switchPeriod(p)}
                    className={
                      "rounded-md px-3 py-1 text-[12px] transition-colors " +
                      (period === p
                        ? "bg-[color:var(--color-panel-2)] text-[color:var(--color-up)]"
                        : "text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-ink-dim)]")
                    }
                  >
                    {{ day: "日K", week: "周K", month: "月K" }[p]}
                  </button>
                ))}
              </div>
            </div>
            <KlineChart bars={kline} />
            <div className="mt-2 flex gap-4 text-[11px] text-[color:var(--color-ink-faint)]">
              <span>
                <span className="mr-1 inline-block h-0.5 w-4 bg-[color:var(--color-amber)] align-middle" />
                MA5
              </span>
              <span>
                <span className="mr-1 inline-block h-0.5 w-4 bg-[#7d9bd9] align-middle" />
                MA20
              </span>
            </div>
          </section>

          {/* 财务 + 新闻 */}
          <div className="grid gap-6 lg:grid-cols-2">
            <section className="animate-rise rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
              <h2 className="mb-4 font-[family-name:var(--font-display)] text-[15px] text-[color:var(--color-ink)]">
                财务指标
              </h2>
              <div className="overflow-x-auto">
                <table className="w-full text-[12px]">
                  <thead>
                    <tr className="text-left text-[color:var(--color-ink-faint)]">
                      <th className="pb-2 pr-3 font-normal">报告期</th>
                      <th className="pb-2 pr-3 text-right font-normal">营收</th>
                      <th className="pb-2 pr-3 text-right font-normal">净利润</th>
                      <th className="pb-2 pr-3 text-right font-normal">ROE</th>
                      <th className="pb-2 pr-3 text-right font-normal">毛利率</th>
                      <th className="pb-2 text-right font-normal">EPS</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(financials?.indicators ?? []).slice(0, 6).map((row) => (
                      <tr key={row.reportDate} className="border-t border-[color:var(--color-line-soft)]">
                        <td className="tabular py-2 pr-3 text-[color:var(--color-ink-dim)]">
                          {row.reportDate}
                        </td>
                        <td className="tabular py-2 pr-3 text-right text-[color:var(--color-ink)]">
                          {fmtYi(row.totalRevenue)}
                        </td>
                        <td className={"tabular py-2 pr-3 text-right " + upCls(row.netProfit ?? 0)}>
                          {fmtYi(row.netProfit)}
                        </td>
                        <td className="tabular py-2 pr-3 text-right text-[color:var(--color-ink)]">
                          {fmt(row.weightedRoe)}%
                        </td>
                        <td className="tabular py-2 pr-3 text-right text-[color:var(--color-ink)]">
                          {fmt(row.grossMargin)}%
                        </td>
                        <td className="tabular py-2 text-right text-[color:var(--color-ink)]">
                          {fmt(row.eps)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>

            <section className="animate-rise rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
              <h2 className="mb-4 font-[family-name:var(--font-display)] text-[15px] text-[color:var(--color-ink)]">
                近期新闻
              </h2>
              <ul className="space-y-3.5">
                {news.map((n) => (
                  <li key={n.url + n.title} className="border-b border-[color:var(--color-line-soft)] pb-3 last:border-0">
                    <a
                      href={n.url}
                      target="_blank"
                      rel="noreferrer"
                      className="block text-[13px] leading-snug text-[color:var(--color-ink)] transition-colors hover:text-[color:var(--color-amber)]"
                    >
                      {n.title}
                    </a>
                    <p className="mt-1 line-clamp-2 text-[12px] leading-relaxed text-[color:var(--color-ink-dim)]">
                      {n.summary}
                    </p>
                    <p className="mt-1 text-[11px] text-[color:var(--color-ink-faint)]">
                      {n.source} · {n.date}
                    </p>
                  </li>
                ))}
                {news.length === 0 && !loading && (
                  <li className="py-6 text-center text-[12px] text-[color:var(--color-ink-faint)]">
                    暂无新闻
                  </li>
                )}
              </ul>
            </section>
          </div>
        </>
      )}

      {!selected && !loading && (
        <div className="animate-rise py-16 text-center text-[13px] text-[color:var(--color-ink-faint)]">
          搜索一只股票，查看行情、走势、财务与新闻
        </div>
      )}
    </div>
  );
}
