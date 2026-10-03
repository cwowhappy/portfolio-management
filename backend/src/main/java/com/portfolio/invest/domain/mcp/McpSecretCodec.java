package com.portfolio.invest.domain.mcp;

/**
 * MCP token 密文编解码（P1-10，AES-256-GCM）。
 *
 * <p>契约：
 * <ul>
 *   <li>密文格式 {@code v1:} + base64(12B 随机 nonce ‖ 密文 ‖ 16B GCM tag)，每次加密 nonce 随机；</li>
 *   <li>密钥缺失（空白）不阻断启动：encrypt 与解密 {@code v1:} 密文时抛
 *       {@link McpErrorCode#SECRET_KEY_MISSING}；</li>
 *   <li>存量明文兼容：无 {@code v1:} 前缀的值直读返回原值（不需要密钥）；</li>
 *   <li>解密失败（密钥不匹配/载荷损坏）抛 {@link McpErrorCode#SECRET_DECRYPT_FAILED}；</li>
 *   <li>null/空白输入 decrypt 原样返回（空白跳过由调用方负责）。</li>
 * </ul>
 */
public interface McpSecretCodec {

    /** 明文 → {@code v1:} 密文。空白明文抛 INVALID_INPUT（服务层已先行校验）。 */
    String encrypt(String plaintext);

    /** 密文/存量明文 → 明文。 */
    String decrypt(String stored);
}
