package com.finflow.batch;

import com.finflow.service.ApplicationService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Map;

// 만료 배치: 접수 후 만료 기간이 지나도록 심사를 시작하지 않은 신청을 EXPIRED로 (업무 명세 8장)
@Configuration
public class ExpireJobConfig {

    public static final String JOB_NAME = "applicationExpireJob";

    @Bean
    public Job applicationExpireJob(JobRepository jobRepository, Step expireStep) {
        return new JobBuilder(JOB_NAME, jobRepository).start(expireStep).build();
    }

    // 읽기(대상 id) → 처리(만료, 이제 대상이 아니면 걸러냄) → 쓰기 없음(처리에서 저장), 동시 수정만 건너뜀
    @Bean
    public Step expireStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
            JdbcPagingItemReader<Long> expireReader, ItemProcessor<Long, Long> expireProcessor,
            @Value("${app.batch.chunk-size}") int chunkSize) {
        return new StepBuilder("expireStep", jobRepository)
                .<Long, Long>chunk(chunkSize, transactionManager)
                .reader(expireReader)
                .processor(expireProcessor)
                .writer(items -> {
                })
                .faultTolerant()
                .skipPolicy((t, skipCount) -> t instanceof ObjectOptimisticLockingFailureException)
                .build();
    }

    // 대상 id를 id 순서로 이어 읽기 (실행 시각 기준 cutoff는 실행 파라미터)
    @Bean
    @StepScope
    public JdbcPagingItemReader<Long> expireReader(DataSource dataSource,
            @Value("#{jobParameters['cutoff']}") LocalDateTime cutoff,
            @Value("${app.batch.chunk-size}") int chunkSize) {
        return new JdbcPagingItemReaderBuilder<Long>()
                .name("expireReader")
                .dataSource(dataSource)
                .selectClause("select id")
                .fromClause("from applications")
                .whereClause("where status = 'RECEIVED' and received_at <= :cutoff")
                .sortKeys(Map.of("id", Order.ASCENDING))
                .parameterValues(Map.of("cutoff", cutoff))
                .rowMapper((rs, rowNum) -> rs.getLong(1))
                .pageSize(chunkSize)
                .build();
    }

    // 한 건 만료 (이제 대상이 아니면 null을 돌려 걸러냄 → 결과의 "대상 아님" 건수)
    @Bean
    @StepScope
    public ItemProcessor<Long, Long> expireProcessor(ApplicationService applicationService,
            @Value("#{jobParameters['cutoff']}") LocalDateTime cutoff) {
        return id -> applicationService.expireIfDue(id, cutoff, JOB_NAME) ? id : null;
    }
}
