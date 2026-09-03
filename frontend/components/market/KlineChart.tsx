"use client";

import { useMemo, useState } from "react";
import type { KlineBar } from "@/lib/types";

const W = 760;
const H = 340;
const PAD = { l: 10, r: 64, t: 12, b: 22 };
const VOL_H = 60;

function ma(bars: KlineBar[], days: number): (number | null)[] {
  const out: (number | null)[] = [];
  let sum = 0;
  for (let i = 0; i < bars.length; i++) {
    sum += bars[i].close;
    if (i >= days) sum -= bars[i - days].close;
    out.push(i >= days - 1 ? sum / days : null);
  }
  return out;
}

export default function KlineChart({ bars }: { bars: KlineBar[] }) {
  const [hover, setHover] = useState<number | null>(null);

  const model = useMemo(() => {
    const n = bars.length;
    const step = (W - PAD.l - PAD.r) / n;
    const bodyW = Math.max(2, Math.min(11, step * 0.62));
    // 用循环求 min/max，避免超大数组的 Math.min(...spread) 参数上限与空数组的 Infinity
    let priceMin = Infinity;
    let priceMax = -Infinity;
    let volMax = 0;
    for (const b of bars) {
      if (b.low < priceMin) priceMin = b.low;
      if (b.high > priceMax) priceMax = b.high;
      if (b.volume > volMax) volMax = b.volume;
    }
    const priceH = H - PAD.t - PAD.b - VOL_H;
    const y = (p: number) => PAD.t + priceH * (1 - (p - priceMin) / (priceMax - priceMin || 1));
    const vy = (v: number) => H - PAD.b - VOL_H * (v / (volMax || 1));
    const ma5 = ma(bars, 5);
    const ma20 = ma(bars, 20);
    const linePath = (arr: (number | null)[]) =>
      arr
        .map((v, i) =>
          v == null
            ? ""
            : (i === 0 ? "M" : "L") +
              (PAD.l + step * i + step / 2).toFixed(1) +
              " " +
              y(v).toFixed(1),
        )
        .join(" ")
        .replace(/^L/, "M");
    return { n, step, bodyW, priceMin, priceMax, volMax, y, vy, ma5, ma20, linePath };
  }, [bars]);

  if (bars.length === 0) {
    return null;
  }

  const last = bars[bars.length - 1];
  const lastUp = last.close >= last.open;

  return (
    <div className="relative">
      <svg
        viewBox={"0 0 " + W + " " + H}
        className="w-full"
        onMouseMove={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          const x = ((e.clientX - rect.left) / rect.width) * W;
          const i = Math.floor((x - PAD.l) / model.step);
          setHover(i >= 0 && i < bars.length ? i : null);
        }}
        onMouseLeave={() => setHover(null)}
      >
        {/* 网格 */}
        {[0.25, 0.5, 0.75].map((f) => (
          <line
            key={f}
            x1={PAD.l}
            x2={W - PAD.r}
            y1={PAD.t + (H - PAD.t - PAD.b - VOL_H) * f}
            y2={PAD.t + (H - PAD.t - PAD.b - VOL_H) * f}
            stroke="var(--color-line-soft)"
            strokeDasharray="3 5"
          />
        ))}

        {/* 成交量 */}
        {bars.map((b, i) => {
          const up = b.close >= b.open;
          return (
            <rect
              key={"v" + i}
              x={PAD.l + model.step * i + (model.step - model.bodyW) / 2}
              y={model.vy(b.volume)}
              width={model.bodyW}
              height={Math.max(0.5, H - PAD.b - model.vy(b.volume))}
              fill={up ? "var(--color-up)" : "var(--color-down)"}
              opacity={hover === i ? 0.9 : 0.35}
            />
          );
        })}

        {/* 均线 */}
        <path d={model.linePath(model.ma5)} fill="none" stroke="var(--color-accent)" strokeWidth="1" opacity="0.9" />
        <path d={model.linePath(model.ma20)} fill="none" stroke="#7d9bd9" strokeWidth="1" opacity="0.9" />

        {/* K线 */}
        {bars.map((b, i) => {
          const up = b.close >= b.open;
          const color = up ? "var(--color-up)" : "var(--color-down)";
          const cx = PAD.l + model.step * i + model.step / 2;
          return (
            <g key={i} opacity={hover === null || hover === i ? 1 : 0.35}>
              <line x1={cx} x2={cx} y1={model.y(b.high)} y2={model.y(b.low)} stroke={color} strokeWidth="1" />
              <rect
                x={cx - model.bodyW / 2}
                y={model.y(Math.max(b.open, b.close))}
                width={model.bodyW}
                height={Math.max(1, Math.abs(model.y(b.open) - model.y(b.close)))}
                fill={up ? color : "var(--color-bg)"}
                stroke={color}
                strokeWidth="1"
              />
            </g>
          );
        })}

        {/* 最新价线 */}
        <line
          x1={PAD.l}
          x2={W - PAD.r}
          y1={model.y(last.close)}
          y2={model.y(last.close)}
          stroke={lastUp ? "var(--color-up)" : "var(--color-down)"}
          strokeDasharray="4 4"
          opacity="0.6"
        />
        <text
          x={W - PAD.r + 4}
          y={model.y(last.close) + 4}
          fill={lastUp ? "var(--color-up)" : "var(--color-down)"}
          fontSize="11"
          fontFamily="var(--font-mono)"
        >
          {last.close.toFixed(2)}
        </text>

        {/* 坐标标签 */}
        <text x={W - PAD.r + 4} y={PAD.t + 10} fill="var(--color-ink-faint)" fontSize="10" fontFamily="var(--font-mono)">
          {model.priceMax.toFixed(2)}
        </text>
        <text
          x={W - PAD.r + 4}
          y={PAD.t + H - PAD.b - VOL_H}
          fill="var(--color-ink-faint)"
          fontSize="10"
          fontFamily="var(--font-mono)"
        >
          {model.priceMin.toFixed(2)}
        </text>
        {[0, Math.floor(bars.length / 2), bars.length - 1].map((i) => (
          <text
            key={i}
            x={Math.min(W - PAD.r - 30, Math.max(12, PAD.l + model.step * i))}
            y={H - 6}
            fill="var(--color-ink-faint)"
            fontSize="10"
            fontFamily="var(--font-mono)"
          >
            {bars[i].date}
          </text>
        ))}
      </svg>

      {/* hover 详情 */}
      {hover !== null && (
        <div className="pointer-events-none absolute left-3 top-2 rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg)]/95 px-2.5 py-1.5 text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
          <span className="text-[color:var(--color-ink)]">{bars[hover].date}</span>
          <br />
          开 {bars[hover].open.toFixed(2)} · 收{" "}
          <span className={bars[hover].close >= bars[hover].open ? "up" : "down"}>
            {bars[hover].close.toFixed(2)}
          </span>
          <br />
          高 {bars[hover].high.toFixed(2)} · 低 {bars[hover].low.toFixed(2)}
          <br />量 {Math.round(bars[hover].volume / 100).toLocaleString()} 手
        </div>
      )}
    </div>
  );
}
