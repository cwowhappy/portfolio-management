// FundingRound 轮次表（后端 domain/industry/FundingRound.java 枚举的同源镜像）：
// 策展编辑下拉（15 项全量）与全景卡轮次分布标签用；数组序 = 后端 order()（声明序，早→晚）。
// 双文件镜像有漂移风险——由 UnlistedCompanyDialog/OverviewCard 测试锁定 15 项与顺序。
export const FUNDING_ROUNDS = [
  { value: "SEED", label: "种子轮" },
  { value: "ANGEL", label: "天使轮" },
  { value: "PRE_A", label: "Pre-A轮" },
  { value: "A", label: "A轮" },
  { value: "A_PLUS", label: "A+轮" },
  { value: "B", label: "B轮" },
  { value: "B_PLUS", label: "B+轮" },
  { value: "C", label: "C轮" },
  { value: "C_PLUS", label: "C+轮" },
  { value: "D", label: "D轮" },
  { value: "STRATEGIC", label: "战略投资" },
  { value: "PRE_IPO", label: "Pre-IPO轮" },
  { value: "IPO", label: "IPO" },
  { value: "ACQUIRED", label: "被收购" },
  { value: "UNKNOWN", label: "未知" },
] as const;

export const roundLabel = (value: string): string =>
  FUNDING_ROUNDS.find((r) => r.value === value)?.label ?? value;
