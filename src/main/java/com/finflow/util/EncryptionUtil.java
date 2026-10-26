package com.finflow.util;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

// AES-256-GCM 암호화/복호화 (신청의 연소득·DTI, HomeFin과 같은 방식)
@Component
public class EncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    // @Value는 값을 못 찾으면 서버가 안 켜짐
    @Value("${app.encryption.key}")
    private String encryptionKeyConfig;

    // 암호화: 매번 새 IV를 만들어 암호문 앞에 붙여서 Base64로 반환
    public String encrypt(String plainText) {
        try {
            byte[] iv = generateIv();
            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(Cipher.ENCRYPT_MODE, buildKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    // 복호화: IV와 암호문 나눠서 복원
    public String decrypt(String encryptedBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH);
            byte[] cipherBytes = Arrays.copyOfRange(combined, IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    // 서버 시작 시 키 길이 확인 (UTF-8 32바이트가 아니면 시작 실패)
    @PostConstruct
    void validateKeyLength() {
        int length = encryptionKeyConfig == null ? 0 : encryptionKeyConfig.getBytes(StandardCharsets.UTF_8).length;
        if (length != KEY_BYTES) {
            throw new IllegalStateException("ENCRYPTION_KEY must be exactly " + KEY_BYTES
                    + " bytes for AES-256 (current: " + length + " bytes)");
        }
    }

    // 32바이트 키 (시작 시 길이를 확인했으므로 그대로 사용)
    private SecretKey buildKey() {
        return new SecretKeySpec(encryptionKeyConfig.getBytes(StandardCharsets.UTF_8), "AES");
    }

    private byte[] generateIv() {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }
}
