package com.portfolio.invest.infrastructure.seed;

import java.sql.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/**
 * e2e/冷库样例数据种子：E2E_DEV_SEED 已配置且 stock_valuation_daily 为空时，
 * 应用 db/seed/e2e-seed.sql（估值/筛选样例，不含 industry_valuation——MS-09 空库门控保持）。
 * 与 AdminSeedRunner 同型的启动幂等种子；未配置或库已有数据时零副作用。
 * 背景：CI 的 invest 库只有 flyway schema，筛选空结果按设计不 emit 表格卡，
 * chat-tools 筛选 e2e 自引入起 8/8 超时（2026-09-24 排查，PR #42 引入用例）。
 */
@Component
public class DevMarketDataSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevMarketDataSeedRunner.class);
    static final String SEED_LOCATION = "db/seed/e2e-seed.sql";

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public DevMarketDataSeedRunner(JdbcTemplate jdbc, @Value("${E2E_DEV_SEED:}") String flag) {
        this.jdbc = jdbc;
        this.enabled = flag != null && !flag.isBlank();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM stock_valuation_daily", Integer.class);
        if (existing != null && existing > 0) {
            log.info("stock_valuation_daily 已有 {} 行，跳过样例种子（幂等防污染）", existing);
            return;
        }
        try (Connection con = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(con, new ClassPathResource(SEED_LOCATION));
        } catch (Exception e) {
            throw new IllegalStateException("应用样例种子失败: " + SEED_LOCATION, e);
        }
        log.info("已应用样例数据种子: {}", SEED_LOCATION);
    }
}
