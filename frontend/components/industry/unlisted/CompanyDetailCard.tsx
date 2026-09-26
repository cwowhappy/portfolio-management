import type { FundingEvent, UnlistedCompany } from "@/lib/types";

const dash = (v: string | null) => v ?? "—";
const fmtYi = (v: number | null) => (v == null ? "未披露" : `${v} 亿元`);

/**
 * 企业详情卡（F10）：策展字段摘要 + 该企业历史融资事件（由 Panel 按 companyName 预关联，
 * 事件为月度摘录非全量）。嵌在名单表展开行内。
 */
export default function CompanyDetailCard({ company, events }: {
  company: UnlistedCompany;
  events: FundingEvent[];
}) {
  return (
    <div data-testid="company-detail-card"
      className="space-y-2 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)]/40 p-4 text-sm">
      <div className="font-[family-name:var(--font-display)] text-[15px]">{company.companyName}</div>
      <div className="flex flex-wrap gap-x-6 gap-y-1 text-[13px] text-[color:var(--color-ink-dim)]">
        <span>最新轮次：{company.latestRoundLabel}</span>
        <span>最近融资：{dash(company.lastFundingDate)}</span>
        <span>累计融资：{fmtYi(company.totalFundingYi)}</span>
        <span>细分赛道：{dash(company.segment)}</span>
        <span>来源：{dash(company.sourceNote)}</span>
      </div>
      {company.summary && <div>{company.summary}</div>}
      {events.length > 0 ? (
        <table className="w-full text-[13px]" aria-label="该企业历史融资事件">
          <thead>
            <tr className="text-left text-xs text-[color:var(--color-ink-faint)]">
              <th className="py-1 pr-4 font-normal">日期</th>
              <th className="py-1 pr-4 font-normal">轮次</th>
              <th className="py-1 pr-4 font-normal">金额</th>
              <th className="py-1 pr-4 font-normal">投资方</th>
              <th className="py-1 font-normal">来源</th>
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.id} className="border-t border-[color:var(--color-line-soft)]">
                <td className="py-1 pr-4 tabular">{e.eventDate}</td>
                <td className="py-1 pr-4">{e.roundLabel}</td>
                <td className="py-1 pr-4 tabular">{e.amountYi == null ? "未披露" : e.amountYi}</td>
                <td className="py-1 pr-4">{e.investors ?? "—"}</td>
                <td className="py-1">
                  {e.sourceUrl
                    ? <a className="underline" href={e.sourceUrl} target="_blank" rel="noreferrer">{e.sourceTitle}</a>
                    : e.sourceTitle}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <div className="text-xs text-[color:var(--color-ink-faint)]">暂无该企业融资事件记录</div>
      )}
    </div>
  );
}
