package com.portfolio.invest.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.mcp.McpSecretCodec;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AES-256-GCM 密文编解码：v1 前缀格式 / 随机 nonce / 空 key 三态 / 存量明文兼容。 */
class AesGcmSecretCodecTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String newKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static McpSecretCodec codec(String base64Key) {
        return new AesGcmSecretCodec(base64Key);
    }

    @Test
    @DisplayName("加密后带 v1 前缀且不含明文，解密还原")
    void givenPlainToken_whenEncryptAndDecrypt_thenRoundTripWithV1Prefix() {
        McpSecretCodec codec = codec(newKey());
        String enc = codec.encrypt("tushare-token-abc123");
        assertThat(enc).startsWith("v1:").doesNotContain("tushare-token-abc123");
        assertThat(codec.decrypt(enc)).isEqualTo("tushare-token-abc123");
    }

    @Test
    @DisplayName("同一明文两次加密产生不同密文（随机 nonce），均可解密")
    void givenSameToken_whenEncryptTwice_thenDifferentCiphertextBothDecrypt() {
        McpSecretCodec codec = codec(newKey());
        String enc1 = codec.encrypt("same-token");
        String enc2 = codec.encrypt("same-token");
        assertThat(enc1).isNotEqualTo(enc2);
        assertThat(codec.decrypt(enc1)).isEqualTo("same-token");
        assertThat(codec.decrypt(enc2)).isEqualTo("same-token");
    }

    @Test
    @DisplayName("密钥不匹配解密失败：SECRET_DECRYPT_FAILED")
    void givenCiphertext_whenDecryptWithWrongKey_thenDecryptFailed() {
        String enc = codec(newKey()).encrypt("secret");
        assertThatThrownBy(() -> codec(newKey()).decrypt(enc))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).code())
                .isEqualTo(McpErrorCode.SECRET_DECRYPT_FAILED);
    }

    @Test
    @DisplayName("v1 前缀但载荷非 base64：SECRET_DECRYPT_FAILED")
    void givenCiphertextNotBase64_whenDecrypt_thenDecryptFailed() {
        McpSecretCodec codec = codec(newKey());
        assertThatThrownBy(() -> codec.decrypt("v1:!!!not-base64!!!"))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).code())
                .isEqualTo(McpErrorCode.SECRET_DECRYPT_FAILED);
    }

    @Test
    @DisplayName("v1 前缀但载荷短于 nonce+tag 最小长度：SECRET_DECRYPT_FAILED")
    void givenTruncatedCiphertext_whenDecrypt_thenDecryptFailed() {
        McpSecretCodec codec = codec(newKey());
        String truncated = "v1:" + Base64.getEncoder().encodeToString(new byte[10]);
        assertThatThrownBy(() -> codec.decrypt(truncated))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).code())
                .isEqualTo(McpErrorCode.SECRET_DECRYPT_FAILED);
    }

    @Test
    @DisplayName("密钥缺失时加密：SECRET_KEY_MISSING（不阻断启动、调用时报错）")
    void givenNoKey_whenEncrypt_thenKeyMissing() {
        McpSecretCodec codec = codec("");
        assertThatThrownBy(() -> codec.encrypt("token"))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).code())
                .isEqualTo(McpErrorCode.SECRET_KEY_MISSING);
    }

    @Test
    @DisplayName("密钥缺失时解密 v1 密文：SECRET_KEY_MISSING")
    void givenNoKey_whenDecryptCiphertext_thenKeyMissing() {
        String enc = codec(newKey()).encrypt("token");
        assertThatThrownBy(() -> codec("").decrypt(enc))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).code())
                .isEqualTo(McpErrorCode.SECRET_KEY_MISSING);
    }

    @Test
    @DisplayName("密钥缺失时存量明文直读不受影响（明文兼容不需要密钥）")
    void givenNoKey_whenDecryptLegacyPlaintext_thenPassThrough() {
        assertThat(codec("").decrypt("legacy-plaintext-token")).isEqualTo("legacy-plaintext-token");
    }

    @Test
    @DisplayName("存量明文（无 v1 前缀）直读返回原值")
    void givenLegacyPlaintext_whenDecrypt_thenPassThrough() {
        McpSecretCodec codec = codec(newKey());
        assertThat(codec.decrypt("legacy-plaintext-token")).isEqualTo("legacy-plaintext-token");
    }

    @Test
    @DisplayName("非法密钥材料（非 base64 / 长度≠32B）构造即失败（fail fast）")
    void givenInvalidKeyMaterial_whenConstruct_thenFailFast() {
        assertThatThrownBy(() -> codec("not-base64!!!"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空值直读原样返回（空白跳过由调用方负责）")
    void givenBlankStored_whenDecrypt_thenPassThrough() {
        McpSecretCodec codec = codec(newKey());
        assertThat(codec.decrypt(null)).isNull();
        assertThat(codec.decrypt("")).isEmpty();
    }

    @Test
    @DisplayName("加密空白明文：INVALID_INPUT（编程错误，服务层已先行校验）")
    void givenBlankPlaintext_whenEncrypt_thenInvalidInput() {
        McpSecretCodec codec = codec(newKey());
        assertThatThrownBy(() -> codec.encrypt(" "))
                .isInstanceOf(McpException.class)
                .extracting(e -> ((McpException) e).code())
                .isEqualTo(McpErrorCode.INVALID_INPUT);
    }
}
