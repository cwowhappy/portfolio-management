package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 飞书绑定仓库实现（intelligence_feishu_binding）。单条 SELECT 即可表达，走
 * {@link JdbcTemplate} 原生 SQL，照 {@link BindingCodeRepositoryImpl} 先例
 * （同族绑定表、PG 方言语句 JPQL 无增益，不建 JPA 门面）。事务边界在 application 层。
 */
@Repository
public class FeishuBindingRepositoryImpl implements FeishuBindingRepository {

    private final JdbcTemplate jdbc;

    public FeishuBindingRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> findOpenIdByUserId(Long userId) {
        List<String> found = jdbc.query(
                "SELECT open_id FROM intelligence_feishu_binding WHERE user_id = ?",
                (rs, i) -> rs.getString("open_id"), userId);
        return found.stream().findFirst();
    }

    @Override
    public boolean deleteByUserId(Long userId) {
        return jdbc.update("DELETE FROM intelligence_feishu_binding WHERE user_id = ?",
                userId) == 1;
    }
}
