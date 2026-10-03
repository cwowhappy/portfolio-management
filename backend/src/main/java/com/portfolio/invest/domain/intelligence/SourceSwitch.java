package com.portfolio.invest.domain.intelligence;

import java.time.Instant;

/**
 * 源切换留痕行（intelligence_source_switch 单行的读取口径）：from/to 方向 + 切换
 * 时刻。降级巡检（MacroHealthService，D19）以<b>最新记录的 to 方向</b>判定「当前
 * 态」——to=m2 即降级态、to=主源即健康态；仅时间戳（旧 lastSourceSwitchAt 口径）
 * 不足以表达「状态翻转才告警」的幂等语义。
 */
public record SourceSwitch(String fromSource, String toSource, Instant switchedAt) {
}
