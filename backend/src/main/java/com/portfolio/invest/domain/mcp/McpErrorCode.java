package com.portfolio.invest.domain.mcp;

public final class McpErrorCode {
    private McpErrorCode() {}
    public static final String PROVIDER_NOT_FOUND = "PROVIDER_NOT_FOUND";
    public static final String CONFIG_NOT_FOUND = "CONFIG_NOT_FOUND";
    public static final String INVALID_INPUT = "INVALID_INPUT";
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";
    /** MCP_SECRET_KEY 未配置（缺失不阻断启动，加解密调用时报错）。 */
    public static final String SECRET_KEY_MISSING = "SECRET_KEY_MISSING";
    /** 密文解密失败（密钥不匹配/载荷损坏）。 */
    public static final String SECRET_DECRYPT_FAILED = "SECRET_DECRYPT_FAILED";
}
