# Week 04: 재시작 (Restartability)

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] ExecutionContext의 역할과 동작 이해
- [ ] ItemStream 인터페이스 이해
- [ ] 재시작 시 "이어서 처리" 구현
- [ ] 멱등성 있는 Job 설계
- [ ] 실패 후 재시작 시나리오 테스트

---

## 핵심 개념 요약 (내 말로)

### ExecutionContext
> 한 줄 정의: Step/Job 실행 중 상태를 저장하는 Key-Value 저장소

- **StepExecutionContext**: Step 범위, Step 재시작 시 복원
- **JobExecutionContext**: Job 범위, Step 간 데이터 공유

재시작 시 마지막 커밋된 상태가 복원되어 "이어서 처리" 가능.

### ItemStream
> 한 줄 정의: Reader/Writer의 상태를 ExecutionContext에 저장/복원하는 인터페이스

```java
public interface ItemStream {
    void open(ExecutionContext executionContext);
    void update(ExecutionContext executionContext);
    void close();
}
```

- `open()`: 실행 시작 시 호출, 이전 상태 복원
- `update()`: Chunk 커밋 후 호출, 현재 상태 저장
- `close()`: 실행 종료 시 호출

### 재시작 메커니즘
> 한 줄 정의: FAILED 상태의 JobExecution이 있으면 이어서 실행

```
1. Job 시작 → JobInstance 조회
2. 마지막 JobExecution이 FAILED인지 확인
3. FAILED면 → 실패한 Step부터 재시작 (ExecutionContext 복원)
4. COMPLETED면 → JobInstanceAlreadyCompleteException
```

### 멱등성 (Idempotency)
> 한 줄 정의: 같은 입력으로 여러 번 실행해도 결과가 동일한 성질

재시작 시 이미 처리된 레코드가 중복 처리되지 않도록 설계 필요.

---

## 실습 시나리오

### 입력
- `input/customers_20250205.csv` (1000건)

### 처리
1. 500건 처리 후 강제 실패 발생
2. 재시작 시 501건부터 이어서 처리
3. 최종 완료

### 출력
- 총 1000건 적재 (중복 없음)
- BATCH_STEP_EXECUTION에 재시작 이력

### 성공 기준
- [ ] 강제 실패 후 재시작 시 처리 건수가 이어짐
- [ ] 중복 레코드 없이 정확히 1000건 적재
- [ ] ExecutionContext에 처리 위치 저장 확인
- [ ] JobExecution 2개 생성 확인 (같은 JobInstance)

---

## 구현 체크리스트

### ExecutionContext 활용
- [ ] Reader의 현재 위치 저장 (read.count 등)
- [ ] 재시작 시 저장된 위치부터 읽기

### ItemStream 구현 (필요시)
- [ ] 커스텀 Reader에서 ItemStream 구현
- [ ] open()에서 이전 상태 복원
- [ ] update()에서 현재 상태 저장

### 멱등성 보장
- [ ] INSERT 전 EXISTS 체크 또는
- [ ] UPSERT 사용 또는
- [ ] Unique 제약 조건 활용

### 강제 실패 로직
- [ ] Processor에서 특정 조건 시 예외 발생
- [ ] `--failAt=500` 파라미터로 실패 지점 지정

---

## 예상 코드 구조

### ExecutionContext 저장/복원
```java
@Component
public class CustomerItemProcessor implements ItemProcessor<CustomerCsv, CustomerStg>, ItemStream {

    private int processedCount = 0;

    @Override
    public void open(ExecutionContext executionContext) {
        if (executionContext.containsKey("processedCount")) {
            this.processedCount = executionContext.getInt("processedCount");
            log.info("Restored processedCount: {}", processedCount);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putInt("processedCount", processedCount);
    }

    @Override
    public CustomerStg process(CustomerCsv item) {
        processedCount++;
        // 강제 실패 테스트
        if (failAt > 0 && processedCount == failAt) {
            throw new RuntimeException("Forced failure at " + failAt);
        }
        return convert(item);
    }
}
```

### FlatFileItemReader의 자동 상태 관리
```java
// FlatFileItemReader는 ItemStream을 이미 구현
// 내부적으로 read.count를 ExecutionContext에 저장
// 재시작 시 자동으로 해당 라인부터 읽기 시작
```

### Step 구성 (재시작 허용)
```java
@Bean
public Step csvToStagingStep() {
    return new StepBuilder("csvToStagingStep", jobRepository)
        .<CustomerCsv, CustomerStg>chunk(100, transactionManager)
        .reader(customerCsvReader())
        .processor(customerProcessor())
        .writer(customerStgWriter())
        .allowStartIfComplete(false)  // 완료된 Step 재실행 방지 (기본값)
        .build();
}
```

---

## 실행 방법

```bash
# 1. 강제 실패 실행 (500건에서 실패)
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv runDate=2025-02-05 -failAt=500'

# 2. 재시작 (동일 파라미터로 실행)
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv runDate=2025-02-05 -failAt=0'
```

### 강제 실패 테스트 시나리오
| 단계 | 명령 | 예상 결과 |
|------|------|----------|
| 1 | failAt=500 실행 | 500건 처리 후 FAILED |
| 2 | 재시작 (failAt=0) | 501건부터 이어서 처리, COMPLETED |

---

## 검증 방법

### ExecutionContext 확인
```sql
-- Step ExecutionContext 확인
SELECT
    se.step_name,
    se.status,
    se.read_count,
    se.write_count,
    sec.short_context
FROM batch_step_execution se
JOIN batch_step_execution_context sec ON se.step_execution_id = sec.step_execution_id
WHERE se.job_execution_id IN (
    SELECT job_execution_id FROM batch_job_execution
    WHERE job_instance_id = ?
)
ORDER BY se.step_execution_id;
```

### JobInstance당 JobExecution 수 확인
```sql
-- 재시작된 Job 확인 (같은 JobInstance에 여러 JobExecution)
SELECT
    ji.job_instance_id,
    ji.job_name,
    COUNT(je.job_execution_id) AS execution_count,
    STRING_AGG(je.status::text, ' → ') AS status_history
FROM batch_job_instance ji
JOIN batch_job_execution je ON ji.job_instance_id = je.job_instance_id
GROUP BY ji.job_instance_id, ji.job_name
HAVING COUNT(je.job_execution_id) > 1;
```

### 데이터 무결성 확인
```sql
-- 중복 레코드 없는지 확인
SELECT customer_id, COUNT(*) AS cnt
FROM customer_stg
WHERE run_date = '2025-02-05'
GROUP BY customer_id
HAVING COUNT(*) > 1;

-- 총 건수 확인
SELECT COUNT(*) FROM customer_stg WHERE run_date = '2025-02-05';
```

---

## 재시작 시나리오 다이어그램

```
실행 1 (FAILED)
─────────────────────────────────────────────
[Chunk 1] ✓ → [Chunk 2] ✓ → ... → [Chunk 5] ✗
  100건       100건                  실패
             ↓
    ExecutionContext 저장: read.count = 500

재시작 (COMPLETED)
─────────────────────────────────────────────
         ExecutionContext 복원: read.count = 500
             ↓
[Chunk 6] ✓ → [Chunk 7] ✓ → ... → [Chunk 10] ✓
  100건       100건                  100건

총 결과: 1000건 처리 (중복 없음)
```

---

## 트러블슈팅 로그

### 이슈 1: 재시작해도 처음부터 실행됨
- **현상**: 이미 처리된 레코드가 다시 처리됨
- **원인**: Reader가 ItemStream 미구현 또는 커스텀 Reader 사용
- **해결**: ItemStream 구현 또는 Spring 제공 Reader 사용

### 이슈 2: ExecutionContext가 저장되지 않음
- **현상**: 재시작 후 context가 비어있음
- **원인**: Step에서 saveState(false) 설정
- **해결**: saveState(true) 확인 (기본값)

### 이슈 3: 재시작 시 중복 레코드 발생
- **현상**: 마지막 Chunk가 중복 적재됨
- **원인**: 롤백 후 재시작 시 마지막 커밋 지점부터 재처리
- **해결**: Writer에서 UPSERT 또는 중복 체크 로직 추가

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- Skip/Retry 정책 이해
- Listener로 오류 추적

---

## 참고 링크

### Spring 공식 문서
- [ExecutionContext](https://docs.spring.io/spring-batch/reference/domain.html#executionContext)
- [ItemStream](https://docs.spring.io/spring-batch/reference/readers-and-writers/item-stream.html)
- [Configuring a Step (Restartability)](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/configuring.html)
- [Restarting a Job](https://docs.spring.io/spring-batch/reference/job/configuring.html#restartability)
- [Schema Appendix (BATCH_STEP_EXECUTION_CONTEXT)](https://docs.spring.io/spring-batch/reference/schema-appendix.html)