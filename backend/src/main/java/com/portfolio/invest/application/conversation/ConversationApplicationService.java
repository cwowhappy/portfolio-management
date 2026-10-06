package com.portfolio.invest.application.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.conversation.ChatMessage;
import com.portfolio.invest.domain.conversation.Conversation;
import com.portfolio.invest.domain.conversation.ConversationConflictException;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;
import com.portfolio.invest.domain.conversation.ConversationException;
import com.portfolio.invest.domain.conversation.ConversationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationApplicationService.class);

    private static final int ID_MAX_LENGTH = 64; // 与 V1 基线 conversation.id VARCHAR(64) 对齐
    private static final int MAX_MESSAGES_PER_REQUEST = 500; // 单次保存条数上限，防存储滥用

    /** payload 合法性轻量树解析（无 schema 校验）；尾随垃圾也视为非法——与 PG jsonb 落库口径对齐，避免 DB 500。 */
    private final ObjectMapper payloadJson = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ConversationRepository repository;
    private final InvestProperties properties;

    public ConversationApplicationService(ConversationRepository repository, InvestProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public List<ConversationView> list(Long userId) {
        return repository.findByUserId(userId).stream().map(ConversationView::from).toList();
    }

    @Transactional
    public ConversationView create(Long userId, String id) {
        validateId(id);
        var owned = repository.findByIdAndUserId(id, userId);
        if (owned.isPresent()) {
            return ConversationView.from(owned.get()); // 本人已有 → 幂等返回
        }
        if (repository.existsById(id)) {
            // id 已被他人占用：直接 404（不泄露存在性），绝不落到 save——
            // ConversationJpaEntity 为 assigned @Id 且无 @Version，Spring Data save 会走
            // EntityManager.merge 把 user_id 改成当前用户，导致越权接管他人会话。
            throw new ConversationException(ConversationErrorCode.NOT_FOUND, "会话不存在");
        }
        return ConversationView.from(repository.save(Conversation.create(id, userId, Instant.now())));
    }

    public ConversationMessagesView messages(Long userId, String conversationId) {
        Conversation conv = requireOwned(userId, conversationId);
        return new ConversationMessagesView(conv.updatedAt(),
                repository.findMessages(conversationId).stream().map(ChatMessageWire::from).toList());
    }

    /**
     * 保存消息（B6 乐观校验）：expectedUpdatedAt 为客户端 GET 得到的 updated_at（If-Match）。
     * 携带时先条件更新会话元数据——影响行数=0 抛 {@link ConversationConflictException}，
     * 此时不得进入 replaceMessages（其为 delete+saveAll 全替换，冲突时必须零删除）；
     * 未携带（null）保持旧行为整体保存，向后兼容。
     */
    @Transactional
    public ConversationSaveResult saveMessages(Long userId, String conversationId,
                                               List<ChatMessageWire> wires, Instant expectedUpdatedAt) {
        Conversation conv = requireOwned(userId, conversationId);
        if (wires.size() > MAX_MESSAGES_PER_REQUEST) {
            throw new ConversationException(ConversationErrorCode.INVALID_MESSAGE, "单次最多保存500条消息");
        }
        String firstUser = wires.stream()
                .filter(w -> "user".equals(w.role()))
                .findFirst().map(ChatMessageWire::content).orElse(null);
        // toDomain 内做逐条边界校验（role 白名单/id/content 长度），payload 守卫逐条降级，先校验再落库
        var messages = wires.stream().map(this::toDomainWithPayloadGuard).toList();
        Instant now = Instant.now();
        Conversation renamed = conv.renameIfDefault(firstUser).touch(now);
        if (expectedUpdatedAt == null) {
            repository.save(renamed);
        } else {
            boolean updated = repository.updateIfUnchanged(
                    conversationId, userId, expectedUpdatedAt, renamed.title(), now);
            if (!updated) {
                throw new ConversationConflictException("会话已被其他窗口修改，请刷新后重试");
            }
        }
        repository.replaceMessages(conversationId, messages);
        return new ConversationSaveResult(now);
    }

    @Transactional
    public void delete(Long userId, String conversationId) {
        requireOwned(userId, conversationId);
        repository.delete(conversationId);
    }

    private Conversation requireOwned(Long userId, String conversationId) {
        return repository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ConversationException(ConversationErrorCode.NOT_FOUND, "会话不存在"));
    }

    /**
     * B8 payload 守卫：单条降级不整批拒——user 消息带 payload 拒收、超 {@code invest.trust.payload-max-bytes}
     * （UTF-8 字节口径）、非法 JSON 三者皆置 null 存文本；id/role/content 结构性校验仍由 {@code toDomain} 抛出。
     */
    private ChatMessage toDomainWithPayloadGuard(ChatMessageWire w) {
        String payload = sanitizePayload(w);
        return new ChatMessageWire(w.id(), w.role(), w.content(), payload, w.createdAt()).toDomain();
    }

    /** 降级时打一条结构化告警（messageId 可关联到具体消息），消息本体照常保存。 */
    private String sanitizePayload(ChatMessageWire w) {
        String payload = w.payload();
        if (payload == null || payload.isBlank()) {
            return null; // 空白载荷无信息量，静默归一为 null（空串进 PG jsonb 会触发解析错误）
        }
        String reason;
        if (!"assistant".equals(w.role())) {
            reason = "user-role"; // payload 仅 assistant 消息携带（设计规格 §4.4），user 恒 null
        } else if (payload.getBytes(StandardCharsets.UTF_8).length > properties.getTrust().getPayloadMaxBytes()) {
            reason = "oversize";
        } else if (!isLegalJson(payload)) {
            reason = "invalid-json";
        } else {
            return payload;
        }
        log.warn("消息 payload 已降级置空（messageId={}，reason={}，payloadBytes={}）",
                w.id(), reason, payload.getBytes(StandardCharsets.UTF_8).length);
        return null;
    }

    private boolean isLegalJson(String payload) {
        try {
            payloadJson.readTree(payload);
            return true;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 会话 id 校验：仅约束非空/非空白与长度上限（≤64），不做 UUID 格式校验——
     * 前端 {@code newThreadId()} 可能生成 {@code "t-"+timestamp} 的非 UUID 回退 id。
     */
    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new ConversationException(ConversationErrorCode.INVALID_ID, "会话 id 不能为空");
        }
        if (id.length() > ID_MAX_LENGTH) {
            throw new ConversationException(ConversationErrorCode.INVALID_ID, "会话 id 格式不正确");
        }
    }
}
