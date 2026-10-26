package com.finflow.controller;

import com.finflow.batch.BatchRunner;
import com.finflow.batch.DailyStatsAggregator;
import com.finflow.common.ApiResponse;
import com.finflow.common.BusinessException;
import com.finflow.common.TraceIds;
import com.finflow.dto.batch.BatchRunResult;
import com.finflow.dto.batch.DailyStat;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

// 운영자용 배치 API (운영자 토큰, ADMIN만): 수동 실행, 일별 집계 조회
@RestController
@RequiredArgsConstructor
public class AdminBatchController {

    // 집계 조회 최대 기간 (일)
    static final int MAX_STATS_DAYS = 92;

    private final BatchRunner batchRunner;
    private final DailyStatsAggregator dailyStatsAggregator;

    // 만료 배치를 지금 실행
    @PostMapping("/api/admin/batch/expire")
    public ResponseEntity<ApiResponse<BatchRunResult>> expire() {
        return ResponseEntity.ok(ApiResponse.success(batchRunner.runExpire(), TraceIds.current()));
    }

    // 한국 날짜 하루의 집계를 지금 실행 (?date=2026-10-26)
    @PostMapping("/api/admin/batch/daily-stats")
    public ResponseEntity<ApiResponse<BatchRunResult>> dailyStats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(batchRunner.runDailyStats(date), TraceIds.current()));
    }

    // 일별 집계 조회 (from ≤ to, 최대 92일)
    @GetMapping("/api/admin/stats/daily")
    public ResponseEntity<ApiResponse<List<DailyStat>>> stats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_STATS_DAYS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    "from must not be after to and the range must be at most " + MAX_STATS_DAYS + " days");
        }
        return ResponseEntity.ok(ApiResponse.success(dailyStatsAggregator.find(from, to), TraceIds.current()));
    }
}
