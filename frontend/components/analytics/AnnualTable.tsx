import type { AnnualReturnRow } from "@/lib/types";

function pct(n: number | undefined): string {
  return n == null ? "—" : `${(n * 100).toFixed(2)}%`;
}

/** 年度收益表：年份 ×（组合 TWR、各基准同年 TWR、超额）。列集取全行基准码并集，缺年数据补「—」。 */
export default function AnnualTable({ rows, names }: { rows: AnnualReturnRow[]; names: Record<string, string> }) {
  const codes = [...new Set(rows.flatMap((r) => Object.keys(r.benchmarkTwr)))];
  const label = (code: string) => names[code] ?? code;
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">年度收益</div>
      <table data-testid="annual-table" className="w-full text-sm tabular">
        <thead>
          <tr className="border-b border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]">
            <th className="py-2 text-left font-normal">年份</th>
            <th className="py-2 text-right font-normal">组合</th>
            {codes.flatMap((c) => [
              <th key={c} className="py-2 text-right font-normal">{label(c)}</th>,
              <th key={`${c}-excess`} className="py-2 text-right font-normal">{label(c)}超额</th>,
            ])}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={2 + codes.length * 2} className="py-4 text-center text-[color:var(--color-ink-faint)]">
                暂无数据
              </td>
            </tr>
          ) : (
            rows.map((r) => (
              <tr key={r.year} className="border-b border-[color:var(--color-line)]/50">
                <td className="py-2 text-left">{r.year}</td>
                <td className="py-2 text-right">{pct(r.portfolioTwr)}</td>
                {codes.flatMap((c) => [
                  <td key={c} className="py-2 text-right">{pct(r.benchmarkTwr[c])}</td>,
                  <td key={`${c}-excess`} className="py-2 text-right">{pct(r.excess[c])}</td>,
                ])}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
