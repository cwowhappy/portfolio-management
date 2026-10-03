package com.portfolio.invest.infrastructure.security;

import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpSecretCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM 密文编解码（P1-10）。密钥来自 {@code invest.mcp.secret-key}
 * （env {@code MCP_SECRET_KEY}，base64 32B）：空白 = 未启用（不阻断启动，调用时报
 * {@link McpErrorCode#SECRET_KEY_MISSING}）；非空白但非法（非 base64 / ≠32B）构造即失败
 * ——密钥配错应 fail fast 而非静默。
 */
@Component
public class AesGcmSecretCodec implements McpSecretCodec {

    private static final Logger log = LoggerFactory.getLogger(AesGcmSecretCodec.class);
    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    /** 载荷最小长度：nonce(12B) + 密文(≥1B) + tag(16B)。 */
    private static final int MIN_PAYLOAD_BYTES = NONCE_BYTES + 1 + TAG_BITS / 8;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    /** 存量明文 warn 去重（按存量值，量级 = provider 数，不记明文进日志）。 */
    private final Set<String> warnedLegacyValues = ConcurrentHashMap.newKeySet();

    @Autowired
    public AesGcmSecretCodec(InvestProperties props) {
        this(props.getMcp().getSecretKey());
    }

    public AesGcmSecretCodec(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MCP_SECRET_KEY 必须为合法 base64", e);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "MCP_SECRET_KEY 必须解码为 32 字节（AES-256），当前 " + raw.length + " 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new McpException(McpErrorCode.INVALID_INPUT, "token 明文不能为空");
        }
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(nonce.length + ct.length);
            buf.put(nonce).put(ct);
            return PREFIX + Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            // 构造已保证 32B 合法密钥，此处纯防御：JVM 加密设施异常不该伪装成业务错误码
            throw new IllegalStateException("token 加密失败", e);
        }
    }

    @Override
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            if (warnedLegacyValues.add(stored)) {
                log.warn("MCP token 为存量明文（无 {} 前缀），建议经 admin 端点覆写为密文", PREFIX);
            }
            return stored;
        }
        requireKey();
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new McpException(McpErrorCode.SECRET_DECRYPT_FAILED, "token 密文载荷非 base64");
        }
        if (payload.length < MIN_PAYLOAD_BYTES) {
            throw new McpException(McpErrorCode.SECRET_DECRYPT_FAILED, "token 密文载荷不完整");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, NONCE_BYTES));
            byte[] plain = cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new McpException(McpErrorCode.SECRET_DECRYPT_FAILED, "token 解密失败（密钥不匹配或载荷损坏）");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new McpException(McpErrorCode.SECRET_KEY_MISSING,
                    "MCP_SECRET_KEY 未配置：请设置后重启（base64 编码的 32 字节 AES-256 密钥）");
        }
    }
}
