package com.finflow.batch;

import com.finflow.domain.ProductCode;
import com.finflow.dto.batch.DailyStat;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// 일별 집계 계산·저장·조회 (daily_application_stats, 엔티티 없이 SQL로)
@Component
@RequiredArgsConstructor
public class DailyStatsAggregator {

    public static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    // 한국 날짜 하루를 UTC 범위로 [시작, 끝)
    static LocalDateTime[] utcRange(LocalDate koreanDate) {
        LocalDateTime start = koreanDate.atStartOfDay(KOREA).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return new LocalDateTime[] { start, start.plusDays(1) };
    }

    // 그날 집계를 계산해서 그 날짜 줄을 지우고 새로 씀 (호출하는 스텝의 트랜잭션 안에서)
    public void aggregate(LocalDate koreanDate) {
        LocalDateTime[] range = utcRange(koreanDate);
        Map<ProductCode, int[]> counts = new EnumMap<>(ProductCode.class);
        for (ProductCode product : ProductCode.values()) {
            // 접수, 승인, 거절, 취소, 만료
            counts.put(product, new int[5]);
        }
        // 접수: 그날 접수 시각인 신청
        jdbc.query("select product_code, count(*) from applications"
                + " where received_at >= ? and received_at < ? group by product_code",
                rs -> {
                    counts.get(ProductCode.valueOf(rs.getString(1)))[0] = rs.getInt(2);
                }, range[0], range[1]);
        // 승인·거절·취소·만료: 그날 생긴 상태 이력
        jdbc.query("select a.product_code, h.to_status, count(*) from application_status_history h"
                + " join applications a on a.id = h.application_id"
                + " where h.created_at >= ? and h.created_at < ?"
                + " and h.to_status in ('APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')"
                + " group by a.product_code, h.to_status",
                rs -> {
                    int index = switch (rs.getString(2)) {
                        case "APPROVED" -> 1;
                        case "REJECTED" -> 2;
                        case "CANCELLED" -> 3;
                        default -> 4;
                    };
                    counts.get(ProductCode.valueOf(rs.getString(1)))[index] = rs.getInt(3);
                }, range[0], range[1]);

        jdbc.update("delete from daily_application_stats where stat_date = ?", Date.valueOf(koreanDate));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        counts.forEach((product, c) -> jdbc.update("insert into daily_application_stats (stat_date, product_code,"
                + " received, approved, rejected, cancelled, expired, aggregated_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                Date.valueOf(koreanDate), product.name(), c[0], c[1], c[2], c[3], c[4], now));
    }

    // 기간 조회 (날짜·상품 순)
    public List<DailyStat> find(LocalDate from, LocalDate to) {
        return jdbc.query("select stat_date, product_code, received, approved, rejected, cancelled, expired"
                + " from daily_application_stats where stat_date between ? and ? order by stat_date, product_code",
                (rs, i) -> new DailyStat(rs.getDate(1).toLocalDate(), ProductCode.valueOf(rs.getString(2)),
                        rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)),
                Date.valueOf(from), Date.valueOf(to));
    }
}
