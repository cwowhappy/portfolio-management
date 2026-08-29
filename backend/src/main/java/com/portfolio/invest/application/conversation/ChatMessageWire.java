package com.portfolio.invest.application.conversation;

import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;
import com.portfolio.invest.domain.conversation.ConversationException;
import java.util.Set;

/** 消息线上格式：与前端 ChatMessage 对齐（不含 payload，payload 预留）。 */
public record ChatMessageWire(String id, String role, String content, long createdAt) {

    static final int MAX_ID_LENGTH = 64;            // 与 V2 chat_message.message_id VARCHAR(64) 对齐
    static final int MAX_CONTENT_CHARS = 100 * 1024; // 100KB（按字符数）
    static final Set<String> ALLOWED_ROLES = Set.of("user", "assistant");

    public ChatMessage toDomain() {
        validate();
        return ChatMessage.create(null, id, role, content, null, createdAt);
    }

    /** wire → domain 边界校验：落库前拦截超长/非法字段，避免约束违例冒泡成 500。 */
    private void validate() {
        if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE, "消息 id 不能为空且最长64字符");
        }
        if (!ALLOWED_ROLES.contains(role)) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE, "消息 role 仅支持 user/assistant");
        }
        if (content == null || content.isEmpty()) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE, "消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE, "消息内容超长（上限100KB）");
        }
    }

    public static ChatMessageWire from(ChatMessage m) {
        return new ChatMessageWire(m.id(), m.role(), m.content(), m.createdAtMs());
    }
}
