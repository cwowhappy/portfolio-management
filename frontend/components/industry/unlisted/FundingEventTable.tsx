import type { FundingEvent } from "@/lib/types";

/**
 * 融资动态表（F08）：标题旁挂「月度摘录·非全量」口径徽标（需求 §三C 显著标注）；
 * 金额/投资方可缺省「未披露/—」，来源标题必填（留痕），URL 可缺省。
 */
export default function FundingEventTable({ events }: { events: FundingEvent[] }) {
  return (
    <section data-testid="unlisted-funding-events-table" className="space-y-2">
      <div className="flex items-center gap-2">
        <h2 className="font-[family-name:var(--font-display)] text-base">融资动态</h2>
        <span className="rounded-full border border-[color:var(--color-line)] px-2 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]">
          月度摘录·非全量
        </span>
      </div>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-[color:var(--color-ink-faint)]">
            <th className="py-1 pr-4 font-normal">日期</th>
            <th className="py-1 pr-4 font-normal">企业</th>
            <th className="py-1 pr-4 font-normal">轮次</th>
            <th className="py-1 pr-4 text-right font-normal">金额(亿元)</th>
            <th className="py-1 pr-4 font-normal">投资方</th>
            <th className="py-1 pr-4 font-normal">细分赛道</th>
            <th className="py-1 font-normal">来源</th>
          </tr>
        </thead>
        <tbody>
          {events.map((e) => (
            <tr key={e.id} className="border-t border-[color:var(--color-line-soft)]">
              <td className="py-1.5 pr-4 tabular">{e.eventDate}</td>
              <td className="py-1.5 pr-4">{e.companyName}</td>
              <td className="py-1.5 pr-4">{e.roundLabel}</td>
              <td className="py-1.5 pr-4 text-right tabular">{e.amountYi == null ? "未披露" : e.amountYi}</td>
              <td className="py-1.5 pr-4">{e.investors ?? "—"}</td>
              <td className="py-1.5 pr-4">{e.segment ?? "—"}</td>
              <td className="py-1.5">
                {e.sourceUrl
                  ? <a className="underline" href={e.sourceUrl} target="_blank" rel="noreferrer">{e.sourceTitle}</a>
                  : e.sourceTitle}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
