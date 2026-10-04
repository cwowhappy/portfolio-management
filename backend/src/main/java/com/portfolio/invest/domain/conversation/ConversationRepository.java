package com.portfolio.invest.domain.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 会话仓库端口：归属过滤在实现与用例两层双重保障。 */
public interface ConversationRepository {
    Optional<Conversation> findByIdAndUserId(String id, Long userId);
    List<Conversation> findByUserId(Long userId);
    /** 全局存在性检查（不做归属过滤）：用于 create 时判定 id 是否被他人占用。 */
    boolean existsById(String id);
    Conversation save(Conversation conversation);
    void delete(String id);
    List<ChatMessage> findMessages(String conversationId);
    void replaceMessages(String conversationId, List<ChatMessage> messages);
    /**
     * 乐观校验更新元数据（B6）：仅当 updated_at 仍等于 expectedUpdatedAt 时更新 title/updatedAt。
     * 返回 true 表示命中更新；false 表示期间已被他人改动（并发写冲突），调用方须放弃后续消息替换。
     */
    boolean updateIfUnchanged(String id, Long userId, Instant expectedUpdatedAt, String title, Instant newUpdatedAt);
}
