package com.finflow.dto.batch;

import com.finflow.domain.ProductCode;

import java.time.LocalDate;

// 일별 집계 한 줄 (한국 날짜·상품)
public record DailyStat(LocalDate statDate, ProductCode productCode, int received, int approved, int rejected,
        int cancelled, int expired) {
}
