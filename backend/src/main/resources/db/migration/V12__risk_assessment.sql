-- 风险测评：每用户仅存最新一条（user_id 唯一，重测由仓储 upsert 覆盖）
CREATE TABLE risk_assessment (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL UNIQUE REFERENCES app_user(id),
    total_score INT NOT NULL,
    profile     VARCHAR(16) NOT NULL,
    answers     JSONB NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
