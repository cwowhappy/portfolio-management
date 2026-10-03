package com.portfolio.invest.infrastructure.persistence.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * intelligence_source_switch ORM 映射（V3；D19 社融降级留痕）。
 *
 * <p>写入走 {@link IntelligenceMacroRepositoryImpl} 原生 SQL（switched_at 落库默认
 * now()），本实体作为表结构的 JPA 注册载体（照 {@link IntelligenceNewsRawJpaEntity}
 * 先例）。幂等语义（「状态变化才插」）在服务层调用方保证，本表只追加不更新。
 */
@Entity
@Table(name = "intelligence_source_switch")
public class IntelligenceSourceSwitchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 指标码（如 AFMI 社融）。 */
    @Column(nullable = false, length = 16)
    private String indicator;

    /** 切换前源。 */
    @Column(name = "from_source", nullable = false, length = 32)
    private String fromSource;

    /** 切换后源。 */
    @Column(name = "to_source", nullable = false, length = 32)
    private String toSource;

    /** 切换原因（巡检判定依据）。 */
    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "switched_at", nullable = false)
    private Instant switchedAt;

    protected IntelligenceSourceSwitchEntity() {}
}
