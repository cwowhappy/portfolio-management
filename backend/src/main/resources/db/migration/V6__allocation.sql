-- 资产配置：方案 + 方案权重
-- 本 schema 为配置域跨服务契约，表名与列类型不可随意变更。

CREATE TABLE allocation_plan (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    name       VARCHAR(64) NOT NULL,
    source     VARCHAR(16) NOT NULL,
    active     BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_allocation_plan_user ON allocation_plan(user_id);

CREATE TABLE allocation_plan_weight (
    id          BIGSERIAL PRIMARY KEY,
    plan_id     BIGINT NOT NULL REFERENCES allocation_plan(id) ON DELETE CASCADE,
    asset_class VARCHAR(16) NOT NULL,
    weight      NUMERIC(18,4) NOT NULL,
    UNIQUE (plan_id, asset_class)
);
CREATE INDEX idx_allocation_plan_weight_plan ON allocation_plan_weight(plan_id);
