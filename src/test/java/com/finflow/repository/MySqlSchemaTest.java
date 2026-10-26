package com.finflow.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 MySQL 8.0에서 Flyway·스키마 검증·MySQL 동작 확인 (Testcontainers, Docker가 없으면 건너뜀)
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class MySqlSchemaTest {

        @Container
        static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

        // 데이터소스를 컨테이너 MySQL로, Hibernate는 엔티티와 실제 테이블이 맞는지 검사
        @DynamicPropertySource
        static void mysqlProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
                registry.add("spring.datasource.username", MYSQL::getUsername);
                registry.add("spring.datasource.password", MYSQL::getPassword);
                registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
                registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        }

        @Autowired
        JdbcTemplate jdbc;

        private long insertApplication(String applicationId, String applicantRef, String status) {
                jdbc.update("insert into applications (application_id, type, product_code, applicant_ref, requested_amount,"
                                + " term_months, house_price, household_annual_income, dti, first_home, newlywed,"
                                + " newborn_within_2_years, child_count, status, received_at, updated_at)"
                                + " values (?, 'LOAN', 'BOGEUMJARI', ?, 1, 12, 1, 'x', 'y', true, false, false, 0, ?, now(6), now(6))",
                                applicationId, applicantRef, status);
                return jdbc.queryForObject("select id from applications where application_id = ?", Long.class,
                                applicationId);
        }

        @Test
        @DisplayName("[TC-APP-203] 실제 MySQL 8에서 Flyway V1 적용·엔티티 확인(validate) 통과, 이력 → 신청 외래키, application_id unique, 진행 중 신청만 신청자·상품 중복 금지")
        void schemaWorksOnMySql() {
                List<String> versions = jdbc.queryForList(
                                "select version from flyway_schema_history where success = 1 order by installed_rank",
                                String.class);
                assertThat(versions).containsExactly("1");

                long id = insertApplication("00000000-0000-0000-0000-000000000001", "homefin:1", "RECEIVED");

                // 없는 신청 id의 이력은 외래키로 거부
                assertThatThrownBy(() -> jdbc.update("insert into application_status_history"
                                + " (application_id, to_status, actor_type, actor_id, created_at) values (987654, 'RECEIVED', 'SERVICE',"
                                + " 'homefin-mate', now(6))")).isInstanceOf(DataIntegrityViolationException.class);
                jdbc.update("insert into application_status_history (application_id, to_status, actor_type, actor_id,"
                                + " created_at) values (?, 'RECEIVED', 'SERVICE', 'homefin-mate', now(6))", id);

                // 같은 신청 번호는 거부
                assertThatThrownBy(() -> insertApplication("00000000-0000-0000-0000-000000000001", "homefin:2",
                                "RECEIVED"))
                                .isInstanceOf(DataIntegrityViolationException.class);

                // 진행 중인 같은 신청자·상품은 거부, 끝난 신청은 여러 개 가능
                assertThatThrownBy(() -> insertApplication("00000000-0000-0000-0000-000000000002", "homefin:1",
                                "REVIEWING"))
                                .isInstanceOf(DataIntegrityViolationException.class);
                insertApplication("00000000-0000-0000-0000-000000000003", "homefin:1", "CANCELLED");
                insertApplication("00000000-0000-0000-0000-000000000004", "homefin:1", "REJECTED");

                // 끝난 상태로 바꾸면 같은 신청자·상품을 다시 접수할 수 있음
                jdbc.update("update applications set status = 'CANCELLED' where id = ?", id);
                insertApplication("00000000-0000-0000-0000-000000000005", "homefin:1", "RECEIVED");
        }
}
