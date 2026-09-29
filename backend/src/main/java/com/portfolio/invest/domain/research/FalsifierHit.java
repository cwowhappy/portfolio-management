package com.portfolio.invest.domain.research;

import java.time.Instant;

/**
 * 证伪命中留痕行（research_falsifier_hit，D10/D21 append-only）：由日终扫描/实时判定落库的
 * 命中历史，评审后由 review 模块回填 review_id（P4，本记录不含该可空回填位）。
 * <b>Ruling-18：hit 表只落 PREDICATE 命中行，EVENT 不落表</b>（事件命中与否由人工勾选语境决定）。
 * 纯数据载体（照 {@link FalsifierHitResult} 先例）：无状态无行为，只增不改。
 */
public record FalsifierHit(Long id, Long projectId, Long falsifierId, String basis, Instant createdAt) {
}
