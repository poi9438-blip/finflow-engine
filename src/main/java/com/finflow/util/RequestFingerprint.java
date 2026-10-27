package com.finflow.util;

import com.finflow.dto.application.CreateApplicationRequest;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// 신청 요청 값의 지문 (SHA-256, 64자 16진수, 같은 값이면 표기와 관계없이 같은 값, 업무 명세 7장)
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(CreateApplicationRequest r) {
        String canonical = Stream.of(
                field("productCode", r.productCode()),
                field("applicantRef", r.applicantRef()),
                field("requestedAmount", r.requestedAmount()),
                field("termMonths", r.termMonths()),
                field("housePrice", r.housePrice()),
                field("householdAnnualIncome", r.householdAnnualIncome()),
                field("dti", normalize(r.dti())),
                field("firstHome", r.firstHome()),
                field("newlywed", r.newlywed()),
                field("newbornWithin2Years", r.newbornWithin2Years()),
                field("childCount", r.childCount()),
                field("houseOwnership", r.houseOwnership()),
                field("dualIncome", r.dualIncome()),
                field("region", r.region()),
                field("simulationRef", r.simulationRef()))
                .collect(Collectors.joining("\n"));
        return sha256(canonical);
    }

    private static String field(String name, Object value) {
        return name + "=" + Objects.toString(value, "");
    }

    // 0.30410과 0.3041을 같은 값으로 (끝의 0 제거, 지수 표기 없이)
    private static String normalize(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
