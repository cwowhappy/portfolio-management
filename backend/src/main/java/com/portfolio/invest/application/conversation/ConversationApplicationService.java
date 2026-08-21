package com.portfolio.invest.application.conversation;

import com.portfolio.invest.domain.conversation.Conversation;
import com.portfolio.invest.domain.conversation.ConversationException;
import com.portfolio.invest.domain.conversation.ConversationRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationApplicationService {

    private static final int ID_MAX_LENGTH = 64; // 与 V2 conversation.id VARCHAR(64) 对齐

    private final ConversationRepository repository;

    public ConversationApplicationService(ConversationRepository repository) {
        this.repository = repository;
    }

    public List<ConversationView> list(Long userId) {
        return repository.findByUserId(userId).stream().map(ConversationView::from).toList();
    }

    @Transactional
    public ConversationView create(Long userId, String id) {
        validateId(id);
        return repository.findByIdAndUserId(id, userId)
                .map(ConversationView::from)
                .orElseGet(() -> ConversationView.from(
                        repository.save(Conversation.create(id, userId, Instant.now()))));
    }

    public List<ChatMessageWire> messages(Long userId, String conversationId) {
        requireOwned(userId, conversationId);
        return repository.findMessages(conversationId).stream().map(ChatMessageWire::from).toList();
    }

    @Transactional
    public void saveMessages(Long userId, String conversationId, List<ChatMessageWire> wires) {
        Conversation conv = requireOwned(userId, conversationId);
        String firstUser = wires.stream()
                .filter(w -> "user".equals(w.role()))
                .findFirst().map(ChatMessageWire::content).orElse(null);
        repository.save(conv.renameIfDefault(firstUser).touch(Instant.now()));
        repository.replaceMessages(conversationId,
                wires.stream().map(ChatMessageWire::toDomain).toList());
    }

    @Transactional
    public void delete(Long userId, String conversationId) {
        requireOwned(userId, conversationId);
        repository.delete(conversationId);
    }

    private Conversation requireOwned(Long userId, String conversationId) {
        return repository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ConversationException("NOT_FOUND", "会话不存在"));
    }

    /**
     * 会话 id 校验：仅约束非空/非空白与长度上限（≤64），不做 UUID 格式校验——
     * 前端 {@code newThreadId()} 可能生成 {@code "t-"+timestamp} 的非 UUID 回退 id。
     */
    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ConversationException("INVALID_ID", "会话 id 不能为空");
        }
        if (id.length() > ID_MAX_LENGTH) {
            throw new ConversationException("INVALID_ID", "会话 id 格式不正确");
        }
    }
}
