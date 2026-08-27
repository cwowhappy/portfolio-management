import type { IndexValuationPoint } from "@/lib/types";

const INDEX_NAME: Record<string, string> = {
  "000016": "上证50", "000300": "沪深300", "000905": "中证500",
  "399006": "创业板指", "000688": "科创50",
};

export default function IndexValuationTable({ indices }: { indices: IndexValuationPoint[] }) {
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">主要指数估值</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            <th className="text-left py-1">指数</th>
            <th className="text-right py-1">PE</th>
            <th className="text-right py-1">PB</th>
            <th className="text-right py-1">PE 分位</th>
          </tr>
        </thead>
        <tbody className="tabular">
          {indices.map((i) => (
            <tr key={i.indexCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="py-2">{i.indexName || INDEX_NAME[i.indexCode] || i.indexCode}</td>
              <td className="text-right">{i.pe ?? "—"}</td>
              <td className="text-right">{i.pb ?? "—"}</td>
              <td className="text-right">{i.pePercentile == null ? "积累中" : `${i.pePercentile}%`}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
