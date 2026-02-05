# Week 06: 파라미터 + Scope (Late Binding)

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] JobScope / StepScope 이해
- [ ] Late Binding (지연 바인딩) 활용
- [ ] SpEL로 JobParameters 참조
- [ ] identifying vs non-identifying 파라미터 운영 설계
- [ ] 파라미터 기반 동적 설정

---

## 핵심 개념 요약 (내 말로)

### Job Scope / Step Scope
> 한 줄 정의: Bean의 생성 시점을 Job/Step 실행 시점으로 지연시키는 스코프

| Scope | 생성 시점 | 용도 |
|-------|----------|------|
| singleton | 애플리케이션 시작 시 | 일반 Bean |
| @JobScope | Job 실행 시작 시 | Job당 1개, JobParameters 접근 가능 |
| @StepScope | Step 실행 시작 시 | Step당 1개, StepExecution 접근 가능 |

### Late Binding (지연 바인딩)
> 한 줄 정의: Bean 생성 시점에 런타임 값(JobParameters)을 주입받는 기법

```java
@StepScope
@Bean
public FlatFileItemReader<CustomerCsv> reader(
    @Value("#{jobParameters['inputFile']}") String inputFile) {
    // inputFile이 Step 실행 시점에 바인딩됨
}
```

### SpEL (Spring Expression Language)
> 한 줄 정의: Spring에서 런타임에 값을 평가하는 표현식 언어

| 표현식 | 의미 |
|--------|------|
| `#{jobParameters['key']}` | JobParameters에서 값 조회 |
| `#{stepExecutionContext['key']}` | StepExecutionContext에서 값 조회 |
| `#{jobExecutionContext['key']}` | JobExecutionContext에서 값 조회 |

### Identifying vs Non-identifying Parameters
> 한 줄 정의: JobInstance 구분에 사용되는지 여부

| 구분 | Identifying | Non-identifying |
|------|-------------|-----------------|
| JobInstance 구분 | O | X |
| 용도 | 실행 식별 (runDate, inputFile) | 동작 제어 (chunkSize, skipLimit) |
| 재실행 | 값 변경 시 새 JobInstance | 값 변경해도 같은 JobInstance |

---

## 실습 시나리오

### 입력
- JobParameters:
  - `inputFile` (identifying): 입력 파일 경로
  - `runDate` (identifying): 실행 기준일
  - `chunkSize` (non-identifying): 청크 크기
  - `skipLimit` (non-identifying): 스킵 허용 건수

### 처리
1. Reader에서 `inputFile` 파라미터로 파일 경로 결정
2. Step에서 `chunkSize` 파라미터로 청크 크기 결정
3. Processor에서 `runDate` 파라미터로 기준일 설정

### 출력
- 파라미터에 따라 동적으로 동작하는 Job

### 성공 기준
- [ ] 다른 inputFile로 실행 시 새 JobInstance 생성
- [ ] 같은 inputFile + 다른 chunkSize로 실행 시 같은 JobInstance (이미 완료 오류)
- [ ] SpEL로 JobParameters 값 주입 확인
- [ ] BATCH_JOB_EXECUTION_PARAMS에서 identifying 구분 확인

---

## 구현 체크리스트

### Scope 설정
- [ ] Reader Bean에 @StepScope 적용
- [ ] Processor Bean에 @StepScope 적용
- [ ] Writer Bean에 @StepScope 적용 (필요시)

### Late Binding
- [ ] Reader에서 inputFile 파라미터 바인딩
- [ ] Processor에서 runDate 파라미터 바인딩
- [ ] Step에서 chunkSize 파라미터 바인딩

### 파라미터 검증
- [ ] 필수 파라미터 존재 여부 체크
- [ ] 파라미터 형식 검증

### 운영 파라미터 설계
- [ ] 파라미터 표준 문서화
- [ ] 기본값 정의

---

## 예상 코드 구조

### StepScope Reader
```java
@StepScope
@Bean
public FlatFileItemReader<CustomerCsv> customerCsvReader(
    @Value("#{jobParameters['inputFile']}") String inputFile) {

    return new FlatFileItemReaderBuilder<CustomerCsv>()
        .name("customerCsvReader")
        .resource(new FileSystemResource(inputFile))  // 동적 경로
        .linesToSkip(1)
        .delimited()
        .names("customerId", "email", "name", "phone")
        .targetType(CustomerCsv.class)
        .build();
}
```

### StepScope Processor
```java
@StepScope
@Bean
public ItemProcessor<CustomerCsv, CustomerStg> customerProcessor(
    @Value("#{jobParameters['runDate']}") String runDateStr) {

    LocalDate runDate = LocalDate.parse(runDateStr);

    return item -> new CustomerStg(
        item.customerId(),
        item.email(),
        item.name(),
        item.phone(),
        runDate
    );
}
```

### 동적 Chunk Size Step
```java
@Bean
public Step csvToStagingStep(
    @Value("#{jobParameters['chunkSize'] ?: 100}") int chunkSize) {

    return new StepBuilder("csvToStagingStep", jobRepository)
        .<CustomerCsv, CustomerStg>chunk(chunkSize, transactionManager)
        .reader(customerCsvReader(null))  // null: Late Binding
        .processor(customerProcessor(null))
        .writer(customerStgWriter())
        .build();
}
```

### 파라미터 검증 Listener
```java
@Component
public class JobParameterValidator implements JobParametersValidator {

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        String inputFile = parameters.getString("inputFile");
        if (inputFile == null || inputFile.isBlank()) {
            throw new JobParametersInvalidException("inputFile is required");
        }

        if (!new File(inputFile).exists()) {
            throw new JobParametersInvalidException("inputFile not found: " + inputFile);
        }

        String runDate = parameters.getString("runDate");
        if (runDate == null) {
            throw new JobParametersInvalidException("runDate is required");
        }

        try {
            LocalDate.parse(runDate);
        } catch (DateTimeParseException e) {
            throw new JobParametersInvalidException("Invalid runDate format: " + runDate);
        }
    }
}
```

---

## 파라미터 표준 설계

### Identifying Parameters (JobInstance 구분)
| 파라미터 | 타입 | 필수 | 설명 | 예시 |
|----------|------|------|------|------|
| `inputFile` | String | Y | 입력 파일 경로 | `input/customers_20250205.csv` |
| `runDate` | String | Y | 실행 기준일 (YYYY-MM-DD) | `2025-02-05` |

### Non-identifying Parameters (동작 제어)
| 파라미터 | 타입 | 필수 | 기본값 | 설명 |
|----------|------|------|--------|------|
| `chunkSize` | Long | N | 100 | 청크 크기 |
| `skipLimit` | Long | N | 10 | 스킵 허용 건수 |
| `retryLimit` | Long | N | 3 | 재시도 횟수 |
| `failFast` | Boolean | N | false | 오류 시 즉시 실패 여부 |

---

## 실행 방법

```bash
# 기본 실행
./gradlew bootRun --args='inputFile=input/customers_20250205.csv runDate=2025-02-05'

# 청크 크기 변경 (non-identifying이므로 같은 JobInstance)
./gradlew bootRun --args='inputFile=input/customers_20250205.csv runDate=2025-02-05 -chunkSize=500'

# 새로운 파일 (identifying이므로 새 JobInstance)
./gradlew bootRun --args='inputFile=input/customers_20250206.csv runDate=2025-02-06'

# 기본값 사용
./gradlew bootRun --args='inputFile=input/customers.csv runDate=2025-02-05'
```

### 파라미터 전달 방식
```bash
# identifying (기본)
param=value

# non-identifying
-param=value

# 타입 명시
param(string)=value
param(long)=100
param(date)=2025-02-05
```

---

## 검증 방법

### 파라미터 저장 확인
```sql
-- 파라미터 값과 identifying 여부 확인
SELECT
    je.job_execution_id,
    jep.parameter_name,
    jep.parameter_type,
    jep.parameter_value,
    jep.identifying
FROM batch_job_execution je
JOIN batch_job_execution_params jep ON je.job_execution_id = jep.job_execution_id
WHERE je.job_execution_id = ?
ORDER BY jep.parameter_name;
```

### Scope 동작 확인
```java
// 디버깅: Bean 생성 시점 로깅
@StepScope
@Bean
public ItemProcessor<CustomerCsv, CustomerStg> customerProcessor(
    @Value("#{jobParameters['runDate']}") String runDateStr) {

    log.info("customerProcessor created with runDate: {}", runDateStr);
    // ...
}
```

### JobInstance 중복 확인
```sql
-- 같은 파라미터로 재실행 시 어떤 오류가 발생하는지 확인
SELECT
    ji.job_instance_id,
    ji.job_name,
    je.status,
    je.exit_code
FROM batch_job_instance ji
JOIN batch_job_execution je ON ji.job_instance_id = je.job_instance_id
WHERE ji.job_name = 'customerImportJob'
ORDER BY ji.job_instance_id DESC;
```

---

## Scope 동작 비교

### Singleton (기본)
```
Application Start
      ↓
[Bean 생성] ← JobParameters 접근 불가!
      ↓
Job 실행 1 → Bean 재사용
Job 실행 2 → Bean 재사용
```

### @StepScope
```
Application Start
      ↓
[Proxy Bean 생성]
      ↓
Job 실행 1
  └── Step 실행 시 [실제 Bean 생성] ← JobParameters 바인딩
Job 실행 2
  └── Step 실행 시 [새 Bean 생성] ← 새 JobParameters 바인딩
```

---

## 트러블슈팅 로그

### 이슈 1: SpEL 바인딩 안됨
- **현상**: `#{jobParameters['key']}`가 null
- **원인**: @StepScope 누락
- **해결**: Bean에 @StepScope 추가

### 이슈 2: Bean creation exception
- **현상**: 애플리케이션 시작 시 오류
- **원인**: @StepScope Bean에서 다른 @StepScope Bean 의존
- **해결**: 메서드 파라미터로 null 전달 (Late Binding)

### 이슈 3: non-identifying 파라미터가 identifying으로 동작
- **현상**: chunkSize 변경해도 새 JobInstance 생성
- **원인**: 파라미터 앞에 `-` 누락
- **해결**: `-chunkSize=100` 형식으로 전달

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- 병렬 처리 옵션 이해
- 성능 측정 방법 학습

---

## 참고 링크

### Spring 공식 문서
- [Late Binding of JobParameters and Execution Context](https://docs.spring.io/spring-batch/reference/step/late-binding.html)
- [Step Scope](https://docs.spring.io/spring-batch/reference/step/late-binding.html#step-scope)
- [Job Scope](https://docs.spring.io/spring-batch/reference/step/late-binding.html#job-scope)
- [JobParameters](https://docs.spring.io/spring-batch/reference/job/configuring.html#jobparameters)
- [Validating Job Parameters](https://docs.spring.io/spring-batch/reference/job/configuring.html#jobparametersvalidator)

### SpEL 참고
- [Spring Expression Language (SpEL)](https://docs.spring.io/spring-framework/reference/core/expressions.html)