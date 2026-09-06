-- MCP 数据源接入：内置 provider + endpoint 两级目录 + 用户配置
CREATE TABLE mcp_provider (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32) NOT NULL UNIQUE,
    name            VARCHAR(64) NOT NULL,
    auth_type       VARCHAR(16) NOT NULL,
    auth_header     VARCHAR(64),
    auth_secret_enc TEXT,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    remark          VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mcp_endpoint (
    id          BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES mcp_provider(id),
    domain      VARCHAR(32),
    name        VARCHAR(64) NOT NULL,
    url         VARCHAR(512) NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mcp_endpoint_provider ON mcp_endpoint(provider_id);

CREATE TABLE mcp_user_config (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user(id),
    provider_id     BIGINT NOT NULL REFERENCES mcp_provider(id),
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    disabled_tools  JSONB NOT NULL DEFAULT '[]',
    config_version  INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, provider_id)
);
CREATE INDEX idx_mcp_user_config_user ON mcp_user_config(user_id);

-- seed provider：auth_secret_enc 留 NULL（真实 token 由部署脚本填，绝不进 git）
INSERT INTO mcp_provider (code, name, auth_type, auth_header, auth_secret_enc, remark) VALUES
    ('mx-ds',   '东方财富妙想', 'HEADER', 'em_api_key', NULL, 'A股/港股/美股/债券/基金/指数/宏观/公告'),
    ('tushare', 'Tushare 官方', 'BEARER', NULL, NULL, 'A股/港股/美股行情、财务、宏观、债券、基金、期货、指数'),
    ('wind',    'Wind AIFin',  'BEARER', NULL, NULL, 'A股/港美股行情、财务、基金、债券、指数、宏观');

INSERT INTO mcp_endpoint (provider_id, domain, name, url) VALUES
    (1, NULL, '妙想数据', 'https://mxapi.eastmoney.com/mxds/mcp'),
    (2, NULL, 'Tushare 数据', 'https://api.tushare.pro/mcp/'),
    (3, 'stock',     'Wind 股票', 'https://mcp.wind.com.cn/vserver_stock_data/mcp/'),
    (3, 'fund',      'Wind 基金', 'https://mcp.wind.com.cn/vserver_fund_data/mcp/'),
    (3, 'index',     'Wind 指数', 'https://mcp.wind.com.cn/vserver_index_data/mcp/'),
    (3, 'bond',      'Wind 债券', 'https://mcp.wind.com.cn/vserver_bond_data/mcp/'),
    (3, 'economic',  'Wind 宏观', 'https://mcp.wind.com.cn/vserver_economic_data/mcp/'),
    (3, 'analytics', 'Wind 分析', 'https://mcp.wind.com.cn/vserver_analytics_data/mcp/');
