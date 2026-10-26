package com.finflow.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

// 이메일을 비밀키 기반 해시(HMAC-SHA256)로 바꿈 ("hmac:" + 64자 16진수)
@Component
public class EmailHasher {

    private static final String ALGORITHM = "HmacSHA256";
    // 암호화 키를 그대로 쓰지 않고 용도를 붙여서 HMAC 키로 씀 (같은 키를 다른 용도에 그대로 재사용하지 않음)
    private static final String KEY_PURPOSE = "email-hash:";

    private final byte[] key;

    public EmailHasher(@Value("${app.encryption.key}") String encryptionKey) {
        this.key = (KEY_PURPOSE + encryptionKey).getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String email) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            return "hmac:" + HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Email hashing failed", e);
        }
    }
}
