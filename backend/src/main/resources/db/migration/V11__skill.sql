-- 系统内置 Skill：skill 目录在 classpath（resources/skills/**/SKILL.md），DB 只存用户选择
CREATE TABLE skill_user_config (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES app_user(id),
    skill_code  VARCHAR(64) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, skill_code)
);
CREATE INDEX idx_skill_user_config_user ON skill_user_config(user_id);
