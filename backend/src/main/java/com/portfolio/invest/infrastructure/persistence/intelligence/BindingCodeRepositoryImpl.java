package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 绑定码仓库实现（intelligence_binding_code）。单条 DELETE 即可表达，走
 * {@link JdbcTemplate} 原生 SQL，照 IntelligenceNewsRepositoryImpl 的先例
 * （PG 方言语句 JPQL 无增益，不建 JPA 门面）。事务边界在 application 层。
 */
@Repository
public class BindingCodeRepositoryImpl implements BindingCodeRepository {

    private final JdbcTemplate jdbc;

    public BindingCodeRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteExpiredBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM intelligence_binding_code WHERE expires_at < ?",
                cutoff.atOffset(ZoneOffset.UTC));
    }
}
