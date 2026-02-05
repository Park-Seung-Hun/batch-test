# Week 08: 테스트 + 운영 (Testing & Operations)

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] spring-batch-test로 Job/Step 테스트 작성
- [ ] End-to-End 테스트 구현
- [ ] 운영 환경에서 Job 실행/중단/재시작 방법 익히기
- [ ] 관측성 구현 (Actuator/Micrometer/JFR 중 선택)
- [ ] 모니터링 대시보드 구성 (선택)

---

## 핵심 개념 요약 (내 말로)

### spring-batch-test
> 한 줄 정의: Spring Batch 전용 테스트 유틸리티

주요 컴포넌트:
- `@SpringBatchTest`: Batch 테스트 설정 애노테이션
- `JobLauncherTestUtils`: Job 전체 또는 개별 Step 실행
- `JobRepositoryTestUtils`: 메타 테이블 초기화
- `StepScopeTestExecutionListener`: StepScope Bean 테스트 지원

### 테스트 전략

| 수준 | 대상 | 도구 |
|------|------|------|
| Unit | Processor, Tasklet 로직 | JUnit, Mockito |
| Integration | Step (Reader-Processor-Writer) | spring-batch-test |
| End-to-End | Job 전체 흐름 | spring-batch-test + 실제 DB |

### 관측성 (Observability)
> 한 줄 정의: 시스템의 내부 상태를 외부에서 파악할 수 있는 능력

3대 요소:
- **Metrics**: 수치화된 측정값 (처리 건수, 소요 시간)
- **Logs**: 이벤트 기록
- **Traces**: 요청 흐름 추적

### Spring Batch + Micrometer
Spring Batch 5.0부터 Micrometer 기본 통합:
- Job/Step 실행 메트릭 자동 수집
- `spring.batch.job.*` 메트릭 제공

---

## 실습 시나리오

### Part 1: 테스트
1. Processor 단위 테스트
2. Step 통합 테스트 (인메모리 DB)
3. Job End-to-End 테스트

### Part 2: 운영
1. Actuator로 Job 상태 조회
2. 실행 중인 Job 중단
3. 실패한 Job 재시작
4. Micrometer 메트릭 확인

### 성공 기준
- [ ] 모든 테스트 통과
- [ ] Actuator 엔드포인트로 Job 정보 조회 가능
- [ ] Micrometer 메트릭 수집 확인
- [ ] Job 중단 및 재시작 시나리오 검증

---

## 구현 체크리스트

### 테스트 의존성
- [ ] `spring-boot-starter-test`
- [ ] `spring-batch-test`
- [ ] `h2` (테스트용 인메모리 DB)

### 단위 테스트
- [ ] `CustomerProcessorTest`
- [ ] `ValidateTaskletTest`

### 통합 테스트
- [ ] `CsvToStagingStepTest`
- [ ] `CustomerImportJobTest`

### 운영 설정
- [ ] Actuator 의존성 추가
- [ ] Micrometer 설정
- [ ] Batch 엔드포인트 노출

---

## 예상 코드 구조

### 테스트 설정
```java
@SpringBatchTest
@SpringBootTest
@ActiveProfiles("test")
class CustomerImportJobTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private JobRepositoryTestUtils jobRepositoryTestUtils;

    @BeforeEach
    void setUp() {
        jobRepositoryTestUtils.removeJobExecutions();
    }

    @Test
    void testJob() throws Exception {
        // given
        JobParameters params = new JobParametersBuilder()
            .addString("inputFile", "classpath:test-data/customers.csv")
            .addString("runDate", "2025-02-05")
            .toJobParameters();

        // when
        JobExecution execution = jobLauncherTestUtils.launchJob(params);

        // then
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo("COMPLETED");
    }
}
```

### Step 테스트
```java
@Test
void testCsvToStagingStep() {
    // given
    JobParameters params = new JobParametersBuilder()
        .addString("inputFile", "classpath:test-data/customers_10.csv")
        .addString("runDate", "2025-02-05")
        .toJobParameters();

    // when
    JobExecution execution = jobLauncherTestUtils.launchStep(
        "csvToStagingStep", params);

    // then
    StepExecution stepExecution = execution.getStepExecutions().iterator().next();
    assertThat(stepExecution.getReadCount()).isEqualTo(10);
    assertThat(stepExecution.getWriteCount()).isEqualTo(10);
    assertThat(stepExecution.getSkipCount()).isEqualTo(0);
}
```

### Processor 단위 테스트
```java
class CustomerProcessorTest {

    private CustomerProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CustomerProcessor(LocalDate.of(2025, 2, 5));
    }

    @Test
    void shouldConvertValidCustomer() {
        // given
        CustomerCsv input = new CustomerCsv("C001", "kim@example.com", "김철수", "010-1234-5678");

        // when
        CustomerStg result = processor.process(input);

        // then
        assertThat(result.customerId()).isEqualTo("C001");
        assertThat(result.runDate()).isEqualTo(LocalDate.of(2025, 2, 5));
    }

    @Test
    void shouldThrowOnInvalidEmail() {
        // given
        CustomerCsv input = new CustomerCsv("C001", "invalid", "김철수", "010-1234-5678");

        // when & then
        assertThatThrownBy(() -> processor.process(input))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("Invalid email");
    }
}
```

### StepScope Bean 테스트
```java
@Test
void testStepScopedReader() {
    // StepScope Bean을 테스트하려면 StepExecution 컨텍스트 필요
    StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
    stepExecution.getExecutionContext().putString("inputFile", "test.csv");

    // StepScopeTestUtils로 StepScope 컨텍스트에서 실행
    StepScopeTestUtils.doInStepScope(stepExecution, () -> {
        FlatFileItemReader<CustomerCsv> reader = customerCsvReader();
        reader.open(stepExecution.getExecutionContext());

        CustomerCsv item = reader.read();
        assertThat(item).isNotNull();

        reader.close();
        return null;
    });
}
```

---

## 테스트 리소스 구조

```
src/test/resources/
├── application-test.yml
└── test-data/
    ├── customers_10.csv       # 정상 데이터 10건
    ├── customers_dirty.csv    # 오류 데이터 포함
    └── customers_empty.csv    # 빈 파일
```

### application-test.yml
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver

  batch:
    jdbc:
      initialize-schema: always
    job:
      enabled: false

  sql:
    init:
      mode: always
      schema-locations: classpath:schema-test.sql
```

---

## 운영 설정

### build.gradle
```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'  // 선택
}
```

### application.yml (운영)
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, batch
  endpoint:
    health:
      show-details: always

spring:
  batch:
    job:
      enabled: false  # HTTP/스케줄러로 실행
```

---

## Actuator 엔드포인트

### Job 정보 조회
```bash
# 실행 중인 Job 목록
curl http://localhost:8080/actuator/batch/jobs

# 특정 Job 실행 이력
curl http://localhost:8080/actuator/batch/jobs/customerImportJob/executions
```

### Micrometer 메트릭
```bash
# 전체 메트릭
curl http://localhost:8080/actuator/metrics

# Batch 관련 메트릭
curl http://localhost:8080/actuator/metrics/spring.batch.job

# Job 실행 시간
curl http://localhost:8080/actuator/metrics/spring.batch.job.active

# Step 처리 건수
curl http://localhost:8080/actuator/metrics/spring.batch.step
```

### 주요 Batch 메트릭

| 메트릭 | 설명 |
|--------|------|
| `spring.batch.job` | Job 실행 통계 |
| `spring.batch.job.active` | 현재 실행 중인 Job 수 |
| `spring.batch.step` | Step 실행 통계 |
| `spring.batch.item.read` | 읽기 건수 |
| `spring.batch.item.process` | 처리 건수 |
| `spring.batch.chunk.write` | 쓰기 건수 |

---

## Job 운영 시나리오

### 1. Job 실행 (HTTP)
```java
@RestController
@RequestMapping("/api/batch")
public class BatchController {

    @PostMapping("/jobs/{jobName}")
    public ResponseEntity<String> runJob(
        @PathVariable String jobName,
        @RequestParam Map<String, String> params) {

        JobParameters jobParameters = createJobParameters(params);
        JobExecution execution = jobLauncher.run(job, jobParameters);

        return ResponseEntity.ok("Job started: " + execution.getId());
    }
}
```

### 2. Job 중단
```java
@PostMapping("/jobs/{jobName}/stop")
public ResponseEntity<String> stopJob(@PathVariable String jobName) {
    Set<JobExecution> executions = jobExplorer.findRunningJobExecutions(jobName);

    for (JobExecution execution : executions) {
        jobOperator.stop(execution.getId());
    }

    return ResponseEntity.ok("Stop requested");
}
```

### 3. Job 재시작
```java
@PostMapping("/jobs/{executionId}/restart")
public ResponseEntity<String> restartJob(@PathVariable Long executionId) {
    Long newExecutionId = jobOperator.restart(executionId);
    return ResponseEntity.ok("Restarted as: " + newExecutionId);
}
```

---

## 실행 방법

### 테스트 실행
```bash
# 전체 테스트
./gradlew test

# 특정 테스트 클래스
./gradlew test --tests "CustomerImportJobTest"

# 테스트 리포트 확인
open build/reports/tests/test/index.html
```

### Actuator 확인
```bash
# 애플리케이션 실행
./gradlew bootRun

# Actuator 엔드포인트 확인
curl http://localhost:8080/actuator
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics
```

---

## 검증 방법

### 테스트 커버리지
```bash
# Jacoco 리포트 생성
./gradlew jacocoTestReport

# 리포트 확인
open build/reports/jacoco/test/html/index.html
```

### 메트릭 확인
```sql
-- DB에서 직접 확인
SELECT
    step_name,
    read_count,
    write_count,
    EXTRACT(EPOCH FROM (end_time - start_time)) AS duration_sec
FROM batch_step_execution
WHERE job_execution_id = ?;
```

### Prometheus 연동 (선택)
```yaml
# application.yml
management:
  metrics:
    export:
      prometheus:
        enabled: true
```

```bash
# Prometheus 메트릭 조회
curl http://localhost:8080/actuator/prometheus | grep spring_batch
```

---

## 모니터링 체크리스트

### 필수 모니터링 항목
- [ ] Job 실행 상태 (STARTED/COMPLETED/FAILED)
- [ ] Step별 처리 건수 (read/write/skip)
- [ ] 실행 시간 및 TPS
- [ ] 오류 건수 및 유형

### 알람 설정 (권장)
- [ ] Job FAILED 시 알람
- [ ] Skip 비율 임계치 초과 시 알람
- [ ] 실행 시간 임계치 초과 시 알람

---

## 트러블슈팅 로그

### 이슈 1: @SpringBatchTest 미동작
- **현상**: JobLauncherTestUtils 주입 안됨
- **원인**: @EnableBatchProcessing 누락 또는 Job Bean 미등록
- **해결**: 테스트 설정 클래스에 @EnableBatchProcessing 추가

### 이슈 2: 테스트 간 데이터 충돌
- **현상**: 테스트 순서에 따라 실패
- **원인**: 메타 테이블 데이터 잔존
- **해결**: @BeforeEach에서 jobRepositoryTestUtils.removeJobExecutions()

### 이슈 3: StepScope Bean 테스트 실패
- **현상**: StepScope Bean이 null
- **원인**: StepExecution 컨텍스트 없음
- **해결**: StepScopeTestUtils 또는 @TestExecutionListeners 사용

---

## 회고

### 잘한 점


### 개선할 점


### 스터디 전체 회고


---

## 참고 링크

### Spring 공식 문서
- [Testing a Step](https://docs.spring.io/spring-batch/reference/testing.html#testingIndividualSteps)
- [End-to-End Testing](https://docs.spring.io/spring-batch/reference/testing.html#endToEndTesting)
- [Testing Step-Scoped Components](https://docs.spring.io/spring-batch/reference/testing.html#testingStepScopedComponents)
- [Observability Support](https://docs.spring.io/spring-batch/reference/monitoring-and-metrics.html)

### Spring Boot Actuator
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/reference/actuator/index.html)
- [Micrometer](https://micrometer.io/docs)

### 운영
- [Running Jobs from the Command Line](https://docs.spring.io/spring-batch/reference/job/configuring.html#runningJobsFromCommandLine)
- [Stopping a Job](https://docs.spring.io/spring-batch/reference/job/configuring.html#stoppingAJob)