package com.portfolio.invest.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationJpaRepository extends JpaRepository<ConversationJpaEntity, String> {
    Optional<ConversationJpaEntity> findByIdAndUserId(String id, Long userId);
    List<ConversationJpaEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * B6 乐观校验：仅当 updated_at 未被他人改动时更新 title/updated_at，返回影响行数（0=冲突）。
     * 原子 UPDATE...WHERE 由数据库保证，先校验冲突、后执行消息替换，冲突时零删除。
     * 不加 clearAutomatically（2026-10-12 B5 复核）：同事务只读入会话实体（未修改，提交脏检查
     * 无 SQL），UPDATE 后为异表消息整替，无同实体 save/读回，批量更新不被覆盖。
     */
    @Modifying
    @Query("UPDATE ConversationJpaEntity c SET c.title = :title, c.updatedAt = :newUpdatedAt "
            + "WHERE c.id = :id AND c.userId = :userId AND c.updatedAt = :expectedUpdatedAt")
    int updateMetadataIfUnchanged(@Param("id") String id,
                                  @Param("userId") Long userId,
                                  @Param("expectedUpdatedAt") Instant expectedUpdatedAt,
                                  @Param("title") String title,
                                  @Param("newUpdatedAt") Instant newUpdatedAt);
}
