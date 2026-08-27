function tier(value: number): { color: string; label: string } {
  if (value < 30) return { color: "var(--color-down)", label: "低估" };
  if (value <= 70) return { color: "var(--color-amber)", label: "中性" };
  return { color: "var(--color-up)", label: "高估" };
}

export default function Thermometer({ value }: { value: number | null }) {
  if (value == null) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">市场情绪温度计</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">数据积累中</div>
      </div>
    );
  }
  const { color, label } = tier(value);
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">市场情绪温度计</div>
      <div className="flex items-center gap-3">
        <div className="text-4xl font-semibold tabular" style={{ color }}>{value}</div>
        <span className="text-sm" style={{ color }}>{label}</span>
      </div>
      <div className="mt-2 h-2 rounded-full bg-[color:var(--color-line-soft)]">
        <div className="h-2 rounded-full" style={{ width: `${value}%`, background: color }} />
      </div>
    </div>
  );
}
