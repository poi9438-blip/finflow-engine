package com.finflow.batch;

import com.finflow.common.BusinessException;
import com.finflow.common.TraceIds;
import com.finflow.dto.batch.BatchRunResult;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// 배치 실행 (스케줄러와 운영자 API가 같이 씀): traceId 붙이기, 실행 파라미터, 결과 정리
@Slf4j
@Component
public class BatchRunner {

    private final JobLauncher jobLauncher;
    private final Job expireJob;
    private final Job dailyStatsJob;
    private final int expireDays;

    public BatchRunner(JobLauncher jobLauncher, @Qualifier("applicationExpireJob") Job expireJob,
            @Qualifier("dailyStatsJob") Job dailyStatsJob, @Value("${app.batch.expire-days}") int expireDays) {
        this.jobLauncher = jobLauncher;
        this.expireJob = expireJob;
        this.dailyStatsJob = dailyStatsJob;
        this.expireDays = expireDays;
    }

    // 만료 배치: 지금으로부터 만료 기간 전(cutoff)까지 접수된 접수 상태 신청을 만료
    public BatchRunResult runExpire() {
        LocalDateTime runAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        JobParametersBuilder params = new JobParametersBuilder()
                .addLocalDateTime("cutoff", runAt.minusDays(expireDays));
        return run(expireJob, params, runAt);
    }

    // 일별 집계: 한국 날짜 하루
    public BatchRunResult runDailyStats(LocalDate koreanDate) {
        LocalDateTime runAt = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        JobParametersBuilder params = new JobParametersBuilder().addLocalDate("statDate", koreanDate);
        return run(dailyStatsJob, params, runAt);
    }

    // 실행: 매번 새 실행(runAt), traceId는 batch-UUID, 끝나면 요청의 traceId로 되돌림
    private BatchRunResult run(Job job, JobParametersBuilder params, LocalDateTime runAt) {
        String traceId = "batch-" + UUID.randomUUID();
        JobParameters parameters = params.addLocalDateTime("runAt", runAt)
                .addString("traceId", traceId, false)
                .toJobParameters();
        String previous = TraceIds.current();
        MDC.put(TraceIds.MDC_KEY, traceId);
        try {
            JobExecution execution = jobLauncher.run(job, parameters);
            BatchRunResult result = summarize(job.getName(), execution, traceId);
            log.info("Batch finished job={} status={} processed={} notDue={} skipped={}", result.jobName(),
                    result.status(), result.processed(), result.notDue(), result.skipped());
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Batch job " + job.getName() + " " + execution.getStatus());
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Batch could not start job={}", job.getName(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Batch job " + job.getName() + " failed");
        } finally {
            if (previous != null) {
                MDC.put(TraceIds.MDC_KEY, previous);
            } else {
                MDC.remove(TraceIds.MDC_KEY);
            }
        }
    }

    private static BatchRunResult summarize(String jobName, JobExecution execution, String traceId) {
        long processed = 0;
        long notDue = 0;
        long skipped = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            processed += step.getWriteCount();
            notDue += step.getFilterCount();
            skipped += step.getSkipCount();
        }
        return new BatchRunResult(execution.getId(), jobName, execution.getStatus().name(), traceId, processed,
                notDue, skipped);
    }
}
