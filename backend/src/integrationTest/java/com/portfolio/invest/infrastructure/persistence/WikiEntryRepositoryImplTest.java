package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.domain.wiki.WikiSeedStateRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({WikiEntryRepositoryImpl.class, WikiSeedStateRepositoryImpl.class})
class WikiEntryRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private WikiEntryRepository repository;

    @Autowired
    private WikiSeedStateRepository seedStateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "wiki_" + System.nanoTime());
    }

    @DisplayName("保存与按用户/类型查询（updatedAt 倒序）、归属过滤、删除")
    @Test
    @Transactional
    void givenEntries_whenFindDelete_thenBehave() {
        Long user = seedUser();
        Instant early = Instant.parse("2026-09-21T00:00:00Z");
        Instant late = Instant.parse("2026-09-22T00:00:00Z");
        WikiEntry saved1 = repository.save(WikiEntry.create(user, WikiEntryType.CONCEPT,
                "护城河", "解释", "质量", null, early));
        WikiEntry saved2 = repository.save(WikiEntry.create(user, WikiEntryType.BOOK_NOTE,
                "笔记", "内容", null, null, late));
        assertThat(saved1.id()).isNotNull();
        assertThat(saved2.id()).isNotNull();

        assertThat(repository.findByUserId(user, null))
                .extracting(WikiEntry::title).containsExactly("笔记", "护城河"); // updatedAt 倒序
        assertThat(repository.findByUserId(user, WikiEntryType.CONCEPT))
                .extracting(WikiEntry::title).containsExactly("护城河");
        assertThat(repository.findByUserId(user + 1, null)).isEmpty(); // 用户隔离

        assertThat(repository.findByIdAndUserId(saved1.id(), user)).isPresent();
        assertThat(repository.findByIdAndUserId(saved1.id(), user + 1)).isEmpty();

        // update 语义：改标题重新 save，updatedAt 变化（version 乐观锁由 JPA @Version 维护）
        WikiEntry updated = repository.save(saved1.update("护城河（修订）", "解释2", "质量", null));
        assertThat(updated.title()).isEqualTo("护城河（修订）");

        repository.deleteById(saved2.id());
        assertThat(repository.findByIdAndUserId(saved2.id(), user)).isEmpty();
    }

    @DisplayName("seed 标记：插入后可查存在，重复 insert 走 PK 约束由服务层幂等语义规避")
    @Test
    @Transactional
    void givenSeedMarker_whenExists_thenTrue() {
        Long user = seedUser();
        assertThat(seedStateRepository.existsByUserId(user)).isFalse();
        seedStateRepository.insert(user);
        assertThat(seedStateRepository.existsByUserId(user)).isTrue();
        assertThat(seedStateRepository.existsByUserId(user + 1)).isFalse();
    }
}
