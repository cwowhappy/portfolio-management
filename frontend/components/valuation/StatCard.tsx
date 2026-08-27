export default function StatCard({ title, value, unit = "", percentile }: {
  title: string; value: number | null; unit?: string; percentile: number | null;
}) {
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 animate-rise">
      <div className="text-sm text-[color:var(--color-ink-dim)]">{title}</div>
      <div className="mt-2 text-2xl font-semibold tabular">
        {value == null ? "—" : value}{unit}
      </div>
      <div className="mt-1 text-xs text-[color:var(--color-ink-faint)]">
        {percentile == null ? "分位：积累中" : `历史分位：${percentile}%`}
      </div>
    </div>
  );
}
