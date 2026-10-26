package com.finflow.batch;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;

// 일별 집계 배치: 한국 날짜 하루의 상품별 접수·승인·거절·취소·만료 건수 (업무 명세 8장)
@Configuration
public class DailyStatsJobConfig {

    public static final String JOB_NAME = "dailyStatsJob";

    @Bean
    public Job dailyStatsJob(JobRepository jobRepository, Step dailyStatsStep) {
        return new JobBuilder(JOB_NAME, jobRepository).start(dailyStatsStep).build();
    }

    // 집계는 SQL 몇 번이면 끝나서 chunk 대신 tasklet 하나 (스텝 트랜잭션 안에서 지우고 다시 씀)
    @Bean
    public Step dailyStatsStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            Tasklet dailyStatsTasklet) {
        return new StepBuilder("dailyStatsStep", jobRepository)
                .tasklet(dailyStatsTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    public Tasklet dailyStatsTasklet(DailyStatsAggregator aggregator,
            @Value("#{jobParameters['statDate']}") LocalDate statDate) {
        return (contribution, chunkContext) -> {
            aggregator.aggregate(statDate);
            return RepeatStatus.FINISHED;
        };
    }
}
