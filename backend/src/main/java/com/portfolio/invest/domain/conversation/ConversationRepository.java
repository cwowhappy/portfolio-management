package com.portfolio.invest.domain.conversation;

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
}
