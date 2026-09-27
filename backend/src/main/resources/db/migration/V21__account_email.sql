-- M01-F06 邮箱验证与找回密码：用户表邮箱列 + 验证码表
ALTER TABLE app_user ADD COLUMN email VARCHAR(254);
ALTER TABLE app_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
-- 部分唯一索引：存量/管理员行 email 为 NULL 不受约束
CREATE UNIQUE INDEX uk_app_user_email ON app_user(email) WHERE email IS NOT NULL;

CREATE TABLE verification_code (
    id         BIGSERIAL PRIMARY KEY,
    email      VARCHAR(254) NOT NULL,
    purpose    VARCHAR(16)  NOT NULL,
    code_hash  VARCHAR(100) NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    used_at    TIMESTAMPTZ,
    expires_at TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_verification_code_email ON verification_code(email, purpose);
