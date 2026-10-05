package com.portfolio.invest.application.conversation;

import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.ChatMessageRole;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;
import com.portfolio.invest.domain.conversation.ConversationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * 消息线上格式：与前端 ChatMessage 对齐。payload 为可选 JSON 文本（MS-29 B8 可信溯源通道，
 * 仅 assistant 消息携带，user 恒 null；PUT 侧逐条守卫降级见 ConversationApplicationService）。
 */
public record ChatMessageWire(
        @NotBlank @Size(max = MAX_ID_LENGTH) String id,
        @NotNull @Pattern(regexp = "^(user|assistant)$", message = "消息 role 仅支持 user/assistant") String role,
        @NotNull @Size(min = 1, max = MAX_CONTENT_CHARS) String content,
        String payload,
        long createdAt) {

    static final int MAX_ID_LENGTH = 64;            // 与 V1 基线 chat_message.message_id VARCHAR(64) 对齐
    static final int MAX_CONTENT_CHARS = 100 * 1024; // 100KB（按字符数）
    static final Set<String> ALLOWED_ROLES = Set.of("user", "assistant");

    /** 旧四参形态（payload 缺省 null）：存量调用方与测试零改动兼容。 */
    public ChatMessageWire(String id, String role, String content, long createdAt) {
        this(id, role, content, null, createdAt);
    }

    public ChatMessage toDomain() {
        validate();
        return ChatMessage.create(null, id, ChatMessageRole.fromWire(role), content, payload, createdAt);
    }

    /** wire → domain 边界校验：落库前拦截超长/非法字段，避免约束违例冒泡成 500（Bean Validation 之外的纵深防御）。 */
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
        return new ChatMessageWire(m.id(), m.role().wire(), m.content(), m.payload(), m.createdAtMs());
    }
}
