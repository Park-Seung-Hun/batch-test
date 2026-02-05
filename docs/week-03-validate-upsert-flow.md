# Week 03: 검증 + 업서트 + Flow

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] 멀티 Step Job 구성 이해
- [ ] Tasklet으로 검증/집계 로직 구현
- [ ] 업서트(UPSERT) 패턴 구현
- [ ] Step Flow와 Decider 이해
- [ ] 조건부 분기 (on/to/from) 사용

---

## 핵심 개념 요약 (내 말로)

### Tasklet vs Chunk
> 한 줄 정의: Tasklet은 단일 작업, Chunk는 반복 처리

| 구분 | Tasklet | Chunk |
|------|---------|-------|
| 용도 | 단일 작업 (검증, 집계, 클린업) | 대량 데이터 반복 처리 |
| 반환 | `RepeatStatus.FINISHED` / `CONTINUABLE` | 자동 (데이터 소진까지) |
| 트랜잭션 | 전체가 하나의 트랜잭션 | Chunk 단위 트랜잭션 |

### Step Flow
> 한 줄 정의: Step 간 실행 순서와 조건부 분기를 정의하는 구성

```java
.start(stepA)
    .on("COMPLETED").to(stepB)
    .from(stepA).on("FAILED").to(stepC)
.end()
```

### JobExecutionDecider
> 한 줄 정의: Step 결과가 아닌 비즈니스 로직으로 분기를 결정하는 컴포넌트

ExitStatus가 아닌 커스텀 FlowExecutionStatus를 반환하여 분기 결정.

### UPSERT 패턴
> 한 줄 정의: 존재하면 UPDATE, 없으면 INSERT

PostgreSQL에서는 `INSERT ... ON CONFLICT ... DO UPDATE` 사용.

---

## 실습 시나리오

### 입력
- `customer_stg` 테이블 (Week 02에서 적재한 데이터)

### 처리
```
customerImportJob
├── csvToStagingStep (Chunk)      # CSV → customer_stg
├── validateStep (Tasklet)        # 스테이징 데이터 검증
│       ↓
│   [VALID] → stagingToTargetStep
│   [INVALID] → errorStep → FAILED
├── stagingToTargetStep (Chunk)   # customer_stg → customer (UPSERT)
└── statsStep (Tasklet)           # 일별 집계
```

### 출력
- `customer` 테이블에 업서트 완료
- `customer_daily_stats` 테이블에 집계 기록

### 성공 기준
- [ ] 스테이징 검증 통과 시 타깃 테이블 업서트 완료
- [ ] 검증 실패 시 errorStep으로 분기
- [ ] 동일 데이터 재실행 시 UPDATE 발생 (INSERT 중복 없음)
- [ ] 일별 집계 테이블에 통계 기록

---

## 구현 체크리스트

### Tasklet 구현
- [ ] `ValidateTasklet`: 스테이징 데이터 검증
  - 필수 필드 존재 여부
  - 이메일 형식 검증 (정규식)
  - 중복 customer_id 체크
- [ ] `StatsTasklet`: 일별 집계
  - 성공/실패 건수 계산
  - customer_daily_stats INSERT

### Step Flow 구현
- [ ] validateStep 성공 → stagingToTargetStep
- [ ] validateStep 실패 → errorStep → Job FAILED
- [ ] JobExecutionDecider로 검증 결과 판단

### UPSERT Writer 구현
- [ ] PostgreSQL `ON CONFLICT` 사용
- [ ] updated_at 갱신

### Step 구성
- [ ] validateStep (Tasklet)
- [ ] stagingToTargetStep (Chunk + UPSERT)
- [ ] statsStep (Tasklet)

---

## 예상 코드 구조

### Tasklet 예시
```java
@Component
public class ValidateTasklet implements Tasklet {

    @Override
    public RepeatStatus execute(StepContribution contribution,
                                ChunkContext chunkContext) throws Exception {
        // 검증 로직
        long invalidCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM customer_stg WHERE email NOT LIKE '%@%'",
            Long.class
        );

        if (invalidCount > 0) {
            contribution.setExitStatus(new ExitStatus("INVALID"));
        } else {
            contribution.setExitStatus(ExitStatus.COMPLETED);
        }

        return RepeatStatus.FINISHED;
    }
}
```

### Decider 예시
```java
@Component
public class ValidationDecider implements JobExecutionDecider {

    @Override
    public FlowExecutionStatus decide(JobExecution jobExecution,
                                      StepExecution stepExecution) {
        // 비즈니스 로직으로 분기 결정
        long errorCount = getErrorCount();
        if (errorCount > threshold) {
            return new FlowExecutionStatus("INVALID");
        }
        return new FlowExecutionStatus("VALID");
    }
}
```

### Job Flow 구성
```java
@Bean
public Job customerImportJob() {
    return new JobBuilder("customerImportJob", jobRepository)
        .start(csvToStagingStep)
        .next(validateStep)
        .next(validationDecider)
            .on("VALID").to(stagingToTargetStep)
            .from(validationDecider).on("INVALID").to(errorStep)
        .from(stagingToTargetStep)
            .on("*").to(statsStep)
        .end()
        .build();
}
```

### UPSERT SQL (PostgreSQL)
```sql
INSERT INTO customer (customer_id, email, name, phone, created_at, updated_at)
VALUES (:customerId, :email, :name, :phone, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (customer_id) DO UPDATE SET
    email = EXCLUDED.email,
    name = EXCLUDED.name,
    phone = EXCLUDED.phone,
    updated_at = CURRENT_TIMESTAMP
```

---

## 실행 방법

```bash
# 정상 데이터로 실행
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv runDate=2025-02-05'

# 재실행 (UPSERT 동작 확인)
# 먼저 CSV 수정 후 재실행
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250206.csv runDate=2025-02-06'
```

---

## 검증 방법

### Flow 분기 확인
```sql
-- Step 실행 순서와 상태 확인
SELECT step_name, status, exit_code, start_time
FROM batch_step_execution
WHERE job_execution_id = ?
ORDER BY step_execution_id;
```

### UPSERT 동작 확인
```sql
-- 동일 customer_id로 재실행 후
SELECT customer_id, email, name, created_at, updated_at
FROM customer
WHERE customer_id = 'C001';

-- created_at은 유지, updated_at만 변경되어야 함
```

### 집계 확인
```sql
SELECT * FROM customer_daily_stats WHERE run_date = '2025-02-05';
```

---

## Flow 패턴 정리

### 순차 실행
```java
.start(stepA).next(stepB).next(stepC).end()
```

### 조건부 분기
```java
.start(stepA)
    .on("COMPLETED").to(stepB)
    .from(stepA).on("FAILED").to(stepC)
.end()
```

### Decider 사용
```java
.start(stepA)
.next(decider)
    .on("OPTION_A").to(stepB)
    .from(decider).on("OPTION_B").to(stepC)
.end()
```

### ExitStatus 패턴
| 패턴 | 의미 |
|------|------|
| `*` | 모든 상태 |
| `COMPLETED` | 정상 완료 |
| `FAILED` | 실패 |
| `CUSTOM_STATUS` | 커스텀 상태 |

---

## 트러블슈팅 로그

### 이슈 1: Flow 분기가 동작하지 않음
- **현상**: 항상 같은 경로로 실행
- **원인**: ExitStatus 설정 누락 또는 on() 조건 불일치
- **해결**: contribution.setExitStatus() 확인, 대소문자 일치 확인

### 이슈 2: UPSERT 시 created_at도 갱신됨
- **현상**: 기존 레코드의 created_at이 변경됨
- **원인**: ON CONFLICT DO UPDATE에 created_at 포함
- **해결**: DO UPDATE SET에서 created_at 제외

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- ExecutionContext 이해
- 재시작 메커니즘 학습

---

## 참고 링크

### Spring 공식 문서
- [Tasklet Step](https://docs.spring.io/spring-batch/reference/step/tasklet.html)
- [Controlling Step Flow](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html)
- [JobExecutionDecider](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html#programmaticFlowDecisions)
- [Batch Status vs Exit Status](https://docs.spring.io/spring-batch/reference/step/controlling-flow.html#batchStatusVsExitStatus)

### PostgreSQL
- [INSERT ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT)