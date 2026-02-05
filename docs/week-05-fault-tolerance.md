# Week 05: 내결함성 (Fault Tolerance)

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] Skip 정책 구현 (잘못된 데이터 건너뛰기)
- [ ] Retry 정책 구현 (일시적 오류 재시도)
- [ ] Rollback 제어 이해
- [ ] Listener로 오류 추적 및 로깅
- [ ] 오류 레코드 격리 테이블 적재

---

## 핵심 개념 요약 (내 말로)

### Skip
> 한 줄 정의: 특정 예외 발생 시 해당 아이템을 건너뛰고 계속 진행

- Read Skip: 읽기 실패한 레코드 건너뛰기
- Process Skip: 변환 실패한 레코드 건너뛰기
- Write Skip: 쓰기 실패한 레코드 건너뛰기

### Retry
> 한 줄 정의: 특정 예외 발생 시 지정 횟수만큼 재시도

- 일시적 오류(네트워크, 락)에 유용
- 재시도 횟수 초과 시 Skip 또는 실패

### Skip vs Retry
| 구분 | Skip | Retry |
|------|------|-------|
| 목적 | 잘못된 데이터 무시 | 일시적 오류 극복 |
| 대상 예외 | ParseException, ValidationException | DeadlockException, ConnectException |
| 결과 | 건너뛰고 계속 진행 | 성공할 때까지 재시도 |

### Listener
> 한 줄 정의: 배치 실행 시점에 콜백을 받아 부가 작업 수행

| Listener | 주요 메서드 |
|----------|------------|
| `SkipListener` | `onSkipInRead`, `onSkipInProcess`, `onSkipInWrite` |
| `RetryListener` | `onRetry`, `onSuccess` |
| `ChunkListener` | `beforeChunk`, `afterChunk`, `afterChunkError` |
| `ItemReadListener` | `beforeRead`, `afterRead`, `onReadError` |
| `ItemWriteListener` | `beforeWrite`, `afterWrite`, `onWriteError` |

### Rollback 제어
> 한 줄 정의: 특정 예외 발생 시 롤백 여부 결정

기본적으로 모든 예외는 롤백을 유발. `noRollback()`으로 특정 예외는 롤백 없이 Skip 가능.

---

## 실습 시나리오

### 입력
- `input/customers_dirty_20250205.csv`
- 포함된 오류 데이터:
  - 빈 이메일
  - 잘못된 이메일 형식
  - 중복 customerId
  - 빈 라인

### 처리
```
csvToStagingStep
├── Skip: ParseException (파싱 오류)
├── Skip: ValidationException (검증 오류)
├── Retry: DataAccessResourceFailureException (DB 연결 오류)
└── Listener: SkipListener로 오류 레코드 격리
```

### 출력
- `customer_stg`: 정상 레코드만 적재
- `customer_err`: 스킵된 레코드 격리
- BATCH_STEP_EXECUTION: skip_count 기록

### 성공 기준
- [ ] 잘못된 레코드가 스킵되고 Job이 계속 진행됨
- [ ] 스킵된 레코드가 customer_err 테이블에 저장됨
- [ ] skip_count가 정확히 기록됨
- [ ] skipLimit 초과 시 Job FAILED

---

## 구현 체크리스트

### Skip 정책
- [ ] Skip 대상 예외 정의
- [ ] skipLimit 설정
- [ ] noRollback 예외 설정

### Retry 정책
- [ ] Retry 대상 예외 정의
- [ ] retryLimit 설정
- [ ] backOffPolicy 설정 (선택)

### Listener 구현
- [ ] `ErrorIsolationSkipListener` 구현
- [ ] onSkipInRead/Process/Write에서 customer_err 적재
- [ ] 로깅 추가

### 오류 격리
- [ ] customer_err 테이블 INSERT
- [ ] 오류 메시지 기록

---

## 예상 코드 구조

### Skip/Retry 설정
```java
@Bean
public Step csvToStagingStep() {
    return new StepBuilder("csvToStagingStep", jobRepository)
        .<CustomerCsv, CustomerStg>chunk(100, transactionManager)
        .reader(customerCsvReader())
        .processor(customerProcessor())
        .writer(customerStgWriter())
        // Skip 설정
        .faultTolerant()
        .skip(FlatFileParseException.class)
        .skip(ValidationException.class)
        .skipLimit(10)
        .noRollback(ValidationException.class)
        // Retry 설정
        .retry(DeadlockLoserDataAccessException.class)
        .retryLimit(3)
        // Listener
        .listener(errorIsolationSkipListener())
        .build();
}
```

### SkipListener 구현
```java
@Component
public class ErrorIsolationSkipListener implements SkipListener<CustomerCsv, CustomerStg> {

    private final JdbcTemplate jdbcTemplate;
    private final LocalDate runDate;

    @Override
    public void onSkipInRead(Throwable t) {
        log.warn("Skip in read: {}", t.getMessage());
        insertErrorRecord(null, t.getMessage());
    }

    @Override
    public void onSkipInProcess(CustomerCsv item, Throwable t) {
        log.warn("Skip in process: {} - {}", item.customerId(), t.getMessage());
        insertErrorRecord(item, t.getMessage());
    }

    @Override
    public void onSkipInWrite(CustomerStg item, Throwable t) {
        log.warn("Skip in write: {} - {}", item.customerId(), t.getMessage());
        insertErrorRecord(item, t.getMessage());
    }

    private void insertErrorRecord(Object item, String errorMessage) {
        jdbcTemplate.update("""
            INSERT INTO customer_err (customer_id, email, name, phone, error_message, run_date)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            // 파라미터 매핑
        );
    }
}
```

### 검증용 Processor
```java
@Component
public class ValidatingCustomerProcessor implements ItemProcessor<CustomerCsv, CustomerStg> {

    @Override
    public CustomerStg process(CustomerCsv item) {
        // 필수 필드 검증
        if (item.email() == null || item.email().isBlank()) {
            throw new ValidationException("Email is required: " + item.customerId());
        }

        // 이메일 형식 검증
        if (!item.email().matches("^[\\w.-]+@[\\w.-]+\\.[a-zA-Z]{2,}$")) {
            throw new ValidationException("Invalid email format: " + item.email());
        }

        return convert(item);
    }
}
```

---

## 더러운 데이터 샘플

```csv
customerId,email,name,phone
C001,kim@example.com,김철수,010-1234-5678
C002,,이영희,010-2345-6789
C003,invalid-email,박민수,010-3456-7890
C004,park@example.com,박지영,010-4567-8901
,choi@example.com,최동훈,010-5678-9012
C006,jung@example.com,정수민,
```

오류 유형:
- C002: 빈 이메일
- C003: 잘못된 이메일 형식
- 5번째 줄: 빈 customerId

---

## 실행 방법

```bash
# Skip 테스트 (오류 데이터 포함 파일)
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_dirty_20250205.csv runDate=2025-02-05 -skipLimit=10'

# skipLimit 초과 테스트
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_very_dirty.csv runDate=2025-02-05 -skipLimit=3'
```

---

## 검증 방법

### Skip 통계 확인
```sql
SELECT
    step_name,
    read_count,
    read_skip_count,
    process_skip_count,
    write_skip_count,
    write_count,
    commit_count,
    rollback_count,
    status
FROM batch_step_execution
WHERE job_execution_id = ?;
```

### 오류 격리 테이블 확인
```sql
-- 격리된 오류 레코드
SELECT * FROM customer_err WHERE run_date = '2025-02-05';

-- 오류 유형별 집계
SELECT error_message, COUNT(*) AS cnt
FROM customer_err
WHERE run_date = '2025-02-05'
GROUP BY error_message;
```

### 정상 적재 vs 오류 건수 비교
```sql
SELECT 'stg' AS tbl, COUNT(*) AS cnt FROM customer_stg WHERE run_date = '2025-02-05'
UNION ALL
SELECT 'err' AS tbl, COUNT(*) AS cnt FROM customer_err WHERE run_date = '2025-02-05';
```

---

## Skip/Retry 동작 흐름

### Skip 흐름
```
[Read Item] → 예외 발생
     ↓
Skip 대상 예외인가? → No → Job FAILED
     ↓ Yes
skipLimit 초과? → Yes → Job FAILED
     ↓ No
onSkipInRead() 호출 → skip_count++ → 다음 아이템 읽기
```

### Retry 흐름
```
[Write Chunk] → 예외 발생
     ↓
Retry 대상 예외인가? → No → Rollback → Skip 시도
     ↓ Yes
retryLimit 초과? → Yes → Rollback → Skip 시도
     ↓ No
onRetry() 호출 → 재시도
```

---

## 트러블슈팅 로그

### 이슈 1: Skip이 동작하지 않음
- **현상**: 예외 발생 시 바로 Job FAILED
- **원인**: `faultTolerant()` 호출 누락
- **해결**: Step 구성에 `.faultTolerant()` 추가

### 이슈 2: skipLimit 초과 시 오류 격리 누락
- **현상**: 마지막 스킵 레코드가 customer_err에 없음
- **원인**: skipLimit 초과 시 Listener 미호출
- **해결**: skipLimit 여유있게 설정 또는 별도 오류 처리

### 이슈 3: Retry 시 전체 Chunk 재실행
- **현상**: 성공한 아이템도 다시 처리됨
- **원인**: Write Retry는 Chunk 단위로 재시도
- **해결**: 정상 동작 (Writer는 Chunk 단위로 동작하므로)

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- Job/Step Scope 이해
- Late Binding 학습

---

## 참고 링크

### Spring 공식 문서
- [Configuring Skip Logic](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-skip.html)
- [Configuring Retry Logic](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-retry.html)
- [Controlling Rollback](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/controlling-rollback.html)
- [Intercepting Step Execution (Listeners)](https://docs.spring.io/spring-batch/reference/step/intercepting-execution.html)
- [SkipListener](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring-skip.html#skipListeners)