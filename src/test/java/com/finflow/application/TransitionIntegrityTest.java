package com.finflow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finflow.domain.ApplicationStatus;
import com.finflow.domain.ProductCode;
import com.finflow.entity.Application;
import com.finflow.repository.ApplicationRepository;
import com.finflow.repository.ApplicationStatusHistoryRepository;
import com.finflow.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static com.finflow.support.ApiTestSupport.applicantRef;
import static com.finflow.support.ApiTestSupport.applicationBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 상태 변경과 감사 기록의 정합성: 같은 트랜잭션 롤백, 낙관적 잠금, 진행 중 중복을 막는 DB 제약
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransitionIntegrityTest {

        @Autowired
        MockMvc mockMvc;
        @Autowired
        ObjectMapper objectMapper;
        @Autowired
        JdbcTemplate jdbc;
        // 실제 저장소를 감싸서 진행 중 신청 확인만 일부러 건너뛰게 함 (나머지는 실제 동작)
        @SpyBean
        ApplicationRepository applicationRepository;
        // 실제 저장소를 감싸서 이력 저장만 일부러 실패시킴
        @SpyBean
        ApplicationStatusHistoryRepository historyRepository;

        private ApiTestSupport api;

        @BeforeEach
        void setUp() {
                api = new ApiTestSupport(mockMvc, objectMapper);
        }

        @Test
        @DisplayName("[TC-APP-202] 심사 시작 중 이력 저장이 실패하면 상태 변경도 롤백 (상태만 바뀌고 이력이 없는 신청 없음), 응답은 500")
        void historyFailure_rollsBackStatus() throws Exception {
                String applicationId = api.receiveOk(applicationBody(applicantRef()));
                String token = api.operatorToken();

                // 이 테스트가 끝나면 @SpyBean이 스텁을 자동으로 지움
                doThrow(new IllegalStateException("history insert failed")).when(historyRepository).save(any());
                api.adminPost(token, "/api/admin/applications/" + applicationId + "/review", null)
                                .andExpect(status().isInternalServerError());

                assertThat(jdbc.queryForObject("select status from applications where application_id = ?", String.class,
                                applicationId)).isEqualTo("RECEIVED");
                assertThat(jdbc.queryForObject("select count(*) from application_status_history h join applications a"
                                + " on a.id = h.application_id where a.application_id = ?", Long.class, applicationId))
                                .isEqualTo(1);
        }

        @Test
        @DisplayName("[TC-APP-204] 먼저 읽어 둔(버전이 낡은) 신청을 다른 요청이 바꾼 뒤 저장 → 낙관적 잠금 실패, 나중 변경은 반영 안 됨")
        void staleUpdate_failsWithOptimisticLock() throws Exception {
                String applicationId = api.receiveOk(applicationBody(applicantRef()));
                Application stale = applicationRepository.findByApplicationId(applicationId).orElseThrow();

                api.adminPost(api.operatorToken(), "/api/admin/applications/" + applicationId + "/review", null)
                                .andExpect(status().isOk());

                stale.changeStatus(ApplicationStatus.CANCELLED, LocalDateTime.now());
                assertThatThrownBy(() -> applicationRepository.saveAndFlush(stale))
                                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
                assertThat(jdbc.queryForObject("select status from applications where application_id = ?", String.class,
                                applicationId)).isEqualTo("REVIEWING");
        }

        // 앞쪽 확인을 통과한 중복 접수도 DB unique 제약(active_key)이 막아 409
        @Test
        @DisplayName("[TC-APP-205] 진행 중 신청 확인을 건너뛰어도(동시 접수 상황) DB unique 제약이 두 번째 저장을 막아 409, 진행 중 신청은 1건")
        void activeKeyConstraint_blocksDuplicate() throws Exception {
                String ref = applicantRef();
                api.receiveOk(applicationBody(ref));

                // 두 요청이 동시에 확인을 통과한 상황: 확인 결과를 "없음"으로
                doReturn(false).when(applicationRepository)
                                .existsByApplicantRefAndProductCodeAndStatusIn(anyString(), any(ProductCode.class),
                                                anyCollection());
                api.receive(applicationBody(ref))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.message").value("Active application already exists"));

                assertThat(jdbc.queryForObject("select count(*) from applications where applicant_ref = ?"
                                + " and status in ('RECEIVED', 'REVIEWING')", Long.class, ref)).isEqualTo(1);
        }
}
