package com.test.batchstudy.config;

import com.test.batchstudy.domain.CustomerCsv;
import com.test.batchstudy.domain.CustomerStg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.batch.infrastructure.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

import javax.sql.DataSource;
import java.time.LocalDate;

/**
 * Week 02: CSV → Staging Chunk 처리 Job 설정
 * <p>
 * JobParameters:
 * - inputFile (String, identifying): 입력 CSV 파일 경로
 * - runDate (String, identifying): 실행 기준일 (yyyy-MM-dd)
 * <p>
 * 처리 흐름: CSV 파일 → FlatFileItemReader → ItemProcessor → JdbcBatchItemWriter → customer_stg 테이블
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CustomerImportJobConfig {

    private static final int CHUNK_SIZE = 100;

    private final DataSource dataSource;

    @Bean
    public Job customerImportJob(JobRepository jobRepository, Step csvToStagingStep) {
        return new JobBuilder("customerImportJob", jobRepository)
                .start(csvToStagingStep)
                .build();
    }

    @Bean
    public Step csvToStagingStep(JobRepository jobRepository,
                                 FlatFileItemReader<CustomerCsv> customerCsvReader,
                                 ItemProcessor<CustomerCsv, CustomerStg> customerCsvProcessor,
                                 JdbcBatchItemWriter<CustomerStg> customerStgWriter) {
        return new StepBuilder("csvToStagingStep", jobRepository)
                .<CustomerCsv, CustomerStg>chunk(CHUNK_SIZE)
                .reader(customerCsvReader)
                .processor(customerCsvProcessor)
                .writer(customerStgWriter)
                .build();
    }

    /**
     * CSV 파일을 읽어 CustomerCsv로 변환하는 Reader
     *
     * @StepScope: Step 실행 시점에 Bean 생성 → JobParameter Late Binding 가능
     */
    @Bean
    @StepScope
    public FlatFileItemReader<CustomerCsv> customerCsvReader(
            @Value("#{jobParameters['inputFile']}") String inputFile) {
        log.info("Creating customerCsvReader with inputFile: {}", inputFile);

        return new FlatFileItemReaderBuilder<CustomerCsv>()
                .name("customerCsvReader")
                .resource(new FileSystemResource(inputFile))
                .encoding("UTF-8")
                .linesToSkip(1)  // 헤더 스킵
                .delimited()
                .names("customerId", "email", "name", "phone")
                .targetType(CustomerCsv.class)
                .build();
    }

    /**
     * CustomerCsv → CustomerStg 변환 Processor
     * <p>
     * CSV 원본 데이터에 runDate를 추가하여 스테이징용 DTO로 변환합니다.
     */
    @Bean
    @StepScope
    public ItemProcessor<CustomerCsv, CustomerStg> customerCsvProcessor(
            @Value("#{jobParameters['runDate']}") String runDate) {
        log.info("Creating customerCsvProcessor with runDate: {}", runDate);

        LocalDate parsedRunDate = LocalDate.parse(runDate);

        // ItemProcessor<I, O>: I를 받아서 O를 반환하는 함수형 인터페이스
        // csv (입력) -> CustomerStg (출력)
        return csv -> new CustomerStg(
                csv.customerId(),
                csv.email(),
                csv.name(),
                csv.phone(),
                parsedRunDate
        );
    }

    /**
     * CustomerStg를 customer_stg 테이블에 Batch INSERT하는 Writer
     */
    @Bean
    public JdbcBatchItemWriter<CustomerStg> customerStgWriter() {
        String sql = """
                INSERT INTO customer_stg (customer_id, email, name, phone, run_date)
                VALUES (:customerId, :email, :name, :phone, :runDate)
                """;

        return new JdbcBatchItemWriterBuilder<CustomerStg>()
                .dataSource(dataSource)
                .sql(sql)
                .beanMapped()  // DTO 필드명을 Named Parameter로 자동 매핑
                .build();
    }
}
