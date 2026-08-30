package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.Conversation;
import com.portfolio.invest.domain.conversation.ConversationRepository;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

// @DataJpaTest 切片 + 真实 PG：@ServiceConnection 复用 testFixtures 的 JVM 单例容器，整个测试进程只起一个 Postgres。
// Boot 4 的 @DataJpaTest 不含 Flyway 自动配置（schema 由 Flyway 管），需 @ImportAutoConfiguration 显式引入；
// RepositoryImpl 适配器不在切片扫描范围内，用 @Import 显式装配。
// @DataJpaTest 默认每个用例事务回滚，避免 @BeforeEach 固定用户名 "conv-owner" 在用例间重复插入
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({ConversationRepositoryImpl.class, UserRepositoryImpl.class})
class ConversationRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired ConversationRepository conversations;
    @Autowired UserRepository users;

    private Long userId;

    @BeforeEach
    void seedUser() {
        userId = users.save(User.register("conv-owner", "h")).id();
    }

    @Test
    void 按归属保存与查询() {
        Conversation c = conversations.save(Conversation.create("t-1", userId, Instant.now()));
        assertThat(conversations.findByIdAndUserId("t-1", userId)).isPresent();
        assertThat(conversations.findByIdAndUserId("t-1", userId + 1)).isEmpty(); // 归属过滤
        assertThat(conversations.findByUserId(userId)).hasSize(1);
    }

    @Test
    void 消息替换与读取() {
        conversations.save(Conversation.create("t-2", userId, Instant.now()));
        ChatMessage m = ChatMessage.create(null, "m-1", "user", "你好", null, 1700000000000L);
        conversations.replaceMessages("t-2", List.of(m));
        assertThat(conversations.findMessages("t-2")).hasSize(1).first()
                .satisfies(x -> assertThat(x.content()).isEqualTo("你好"));

        conversations.replaceMessages("t-2", List.of(ChatMessage.create(null, "m-2", "user", "第二版", null, 1700000001000L)));
        assertThat(conversations.findMessages("t-2")).hasSize(1) // 全量替换
                .first().satisfies(x -> assertThat(x.content()).isEqualTo("第二版"));
    }

    @Test
    void 删除会话连带消息() {
        conversations.save(Conversation.create("t-3", userId, Instant.now()));
        conversations.replaceMessages("t-3", List.of(ChatMessage.create(null, "m-1", "user", "x", null, 0L)));
        conversations.delete("t-3");
        assertThat(conversations.findByIdAndUserId("t-3", userId)).isEmpty();
        assertThat(conversations.findMessages("t-3")).isEmpty();
    }
}
