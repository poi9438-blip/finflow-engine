package com.finflow.util;

import com.finflow.dto.application.CreateApplicationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

// 요청 값 지문(해시) 단위 테스트
class RequestFingerprintTest {

    private static CreateApplicationRequest base() {
        return new CreateApplicationRequest("BOGEUMJARI", "homefin:7", 300_000_000L, 360, 500_000_000L, 60_000_000L,
                new BigDecimal("0.3041"), true, false, false, 1, "NONE", false, "부산", 42L);
    }

    // 항목 하나만 바꾼 요청
    private static CreateApplicationRequest with(UnaryOperator<Object[]> change) {
        CreateApplicationRequest b = base();
        Object[] v = { b.productCode(), b.applicantRef(), b.requestedAmount(), b.termMonths(), b.housePrice(),
                b.householdAnnualIncome(), b.dti(), b.firstHome(), b.newlywed(), b.newbornWithin2Years(),
                b.childCount(), b.houseOwnership(), b.dualIncome(), b.region(), b.simulationRef() };
        v = change.apply(v);
        return new CreateApplicationRequest((String) v[0], (String) v[1], (Long) v[2], (Integer) v[3], (Long) v[4],
                (Long) v[5], (BigDecimal) v[6], (Boolean) v[7], (Boolean) v[8], (Boolean) v[9], (Integer) v[10],
                (String) v[11], (Boolean) v[12], (String) v[13], (Long) v[14]);
    }

    private static UnaryOperator<Object[]> set(int index, Object value) {
        return v -> {
            v[index] = value;
            return v;
        };
    }

    @Test
    @DisplayName("[TC-IDEM-201] 요청 값 지문: 같은 값의 다른 표기(DTI 0.30410)는 같은 해시, 15개 항목 중 하나라도 바뀌면 다른 해시, 64자 16진수")
    void fingerprint() {
        String original = RequestFingerprint.of(base());
        assertThat(original).matches("[0-9a-f]{64}");
        assertThat(RequestFingerprint.of(base())).isEqualTo(original);
        assertThat(RequestFingerprint.of(with(set(6, new BigDecimal("0.30410"))))).isEqualTo(original);

        List<CreateApplicationRequest> changed = List.of(
                with(set(0, "DIDIMDOL")), with(set(1, "homefin:8")), with(set(2, 300_000_001L)), with(set(3, 359)),
                with(set(4, 500_000_001L)), with(set(5, 60_000_001L)), with(set(6, new BigDecimal("0.3042"))),
                with(set(7, false)), with(set(8, true)), with(set(9, true)), with(set(10, 2)),
                // 5단계 추가: 주택 보유, 맞벌이
                with(set(11, "OWN")), with(set(12, true)),
                with(set(13, "서울")), with(set(14, 43L)));
        assertThat(changed).hasSize(15);
        for (CreateApplicationRequest c : changed) {
            assertThat(RequestFingerprint.of(c)).as(c.toString()).isNotEqualTo(original);
        }
        // 선택 항목이 비어 있는 것과 빈 문자열은 구분하지 않아도 되지만, 값이 있는 것과는 달라야 함
        assertThat(RequestFingerprint.of(with(set(13, null)))).isNotEqualTo(original);
    }
}
