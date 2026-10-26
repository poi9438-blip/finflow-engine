package com.finflow.dto.batch;

// 배치 실행 결과 (만료: processed = 만료한 건수, notDue = 처리 직전에 대상이 아니게 된 건수, skipped = 동시 수정으로 건너뛴 건수)
public record BatchRunResult(Long jobExecutionId, String jobName, String status, String traceId, long processed,
        long notDue, long skipped) {
}
