CREATE TABLE conversation (
    id         VARCHAR(64) PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    title      VARCHAR(64) NOT NULL DEFAULT '新会话',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_user ON conversation(user_id);

CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    message_id      VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         TEXT NOT NULL,
    payload         JSONB NULL,
    created_at      BIGINT NOT NULL
);
CREATE INDEX idx_chat_message_conversation ON chat_message(conversation_id);
