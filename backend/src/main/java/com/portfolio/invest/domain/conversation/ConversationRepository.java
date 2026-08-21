package com.portfolio.invest.domain.conversation;

import java.util.List;
import java.util.Optional;

/** 会话仓库端口：归属过滤在实现与用例两层双重保障。 */
public interface ConversationRepository {
    Optional<Conversation> findByIdAndUserId(String id, Long userId);
    List<Conversation> findByUserId(Long userId);
    Conversation save(Conversation conversation);
    void delete(String id);
    List<ChatMessage> findMessages(String conversationId);
    void replaceMessages(String conversationId, List<ChatMessage> messages);
}
