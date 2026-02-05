# Week 07: 병렬/튜닝 (Scalability)

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] Spring Batch 확장 옵션 이해 (Multi-threaded, Parallel, Partitioning, Remote)
- [ ] Multi-threaded Step 구현 및 테스트
- [ ] Partitioning 구현 (선택)
- [ ] 성능 측정 및 병목 분석
- [ ] 최적의 chunk size / thread 수 도출

---

## 핵심 개념 요약 (내 말로)

### Spring Batch 확장 옵션

| 방식 | 설명 | 적용 시점 |
|------|------|----------|
| Multi-threaded Step | 하나의 Step을 여러 스레드로 병렬 처리 | 단순 성능 향상 |
| Parallel Steps | 독립적인 Step들을 동시 실행 | Step 간 의존성 없을 때 |
| Partitioning | 데이터를 분할하여 여러 Step 인스턴스가 처리 | 대용량 데이터 |
| Remote Chunking | 처리를 원격 노드에 분산 | 극대용량/분산 환경 |

### Multi-threaded Step
> 한 줄 정의: TaskExecutor를 사용해 Chunk 처리를 여러 스레드로 병렬 실행

```
[Main Thread]
     ↓
[Thread 1] → Chunk 1 (Read-Process-Write)
[Thread 2] → Chunk 2 (Read-Process-Write)
[Thread 3] → Chunk 3 (Read-Process-Write)
     ↓
   완료
```

주의: Reader가 Thread-safe해야 함!

### Partitioning
> 한 줄 정의: 데이터를 논리적으로 분할하고 각 파티션을 독립적인 Step으로 처리

```
[Master Step]
     ↓ (Partitioner: 데이터 분할)
[Slave Step 1] → 파티션 1 (1-1000)
[Slave Step 2] → 파티션 2 (1001-2000)
[Slave Step 3] → 파티션 3 (2001-3000)
     ↓
   완료
```

각 파티션은 독립적인 ExecutionContext를 가짐 → 재시작 가능

### Thread-safe Reader
> 한 줄 정의: 여러 스레드가 동시에 read()해도 안전한 Reader

- `JdbcPagingItemReader`: Thread-safe (synchronized)
- `FlatFileItemReader`: Thread-safe 아님! → `SynchronizedItemStreamReader`로 래핑

---

## 실습 시나리오

### 입력
- `input/customers_large.csv` (10만 건)

### 처리
1. 단일 스레드 실행 → 소요 시간 측정
2. Multi-threaded (4 스레드) 실행 → 소요 시간 측정
3. Chunk Size 변경 → 성능 비교
4. (선택) Partitioning 적용

### 출력
- 성능 측정 결과 표
- 최적 설정 도출

### 성공 기준
- [ ] Multi-threaded 적용 후 처리 시간 단축
- [ ] 데이터 무결성 유지 (중복/누락 없음)
- [ ] 최적 chunk size / thread 수 도출
- [ ] 성능 측정 결과 문서화

---

## 구현 체크리스트

### Multi-threaded Step
- [ ] TaskExecutor 설정
- [ ] Reader Thread-safe 처리
- [ ] throttleLimit 설정

### 성능 측정
- [ ] 실행 시간 측정 방법 구현
- [ ] 처리량(TPS) 계산
- [ ] 리소스 사용량 모니터링

### Partitioning (선택)
- [ ] Partitioner 구현
- [ ] PartitionHandler 설정
- [ ] Slave Step 구성

### 데이터 무결성 검증
- [ ] 처리 건수 확인
- [ ] 중복 레코드 확인

---

## 예상 코드 구조

### Multi-threaded Step
```java
@Bean
public TaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(25);
    executor.setThreadNamePrefix("batch-");
    executor.initialize();
    return executor;
}

@Bean
public Step csvToStagingStep() {
    return new StepBuilder("csvToStagingStep", jobRepository)
        .<CustomerCsv, CustomerStg>chunk(100, transactionManager)
        .reader(synchronizedReader())
        .processor(customerProcessor())
        .writer(customerStgWriter())
        .taskExecutor(taskExecutor())
        .throttleLimit(4)  // 동시 스레드 수 제한
        .build();
}
```

### Thread-safe Reader 래핑
```java
@StepScope
@Bean
public SynchronizedItemStreamReader<CustomerCsv> synchronizedReader() {
    FlatFileItemReader<CustomerCsv> delegate = new FlatFileItemReaderBuilder<CustomerCsv>()
        .name("customerCsvReader")
        .resource(new FileSystemResource("input/customers_large.csv"))
        .linesToSkip(1)
        .delimited()
        .names("customerId", "email", "name", "phone")
        .targetType(CustomerCsv.class)
        .build();

    SynchronizedItemStreamReader<CustomerCsv> reader = new SynchronizedItemStreamReader<>();
    reader.setDelegate(delegate);
    return reader;
}
```

### Partitioner 구현
```java
@Component
public class CustomerPartitioner implements Partitioner {

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>();

        // 예: 10만 건을 4개 파티션으로 분할
        int totalCount = 100000;
        int partitionSize = totalCount / gridSize;

        for (int i = 0; i < gridSize; i++) {
            ExecutionContext context = new ExecutionContext();
            int start = i * partitionSize + 1;
            int end = (i == gridSize - 1) ? totalCount : (i + 1) * partitionSize;

            context.putInt("minId", start);
            context.putInt("maxId", end);

            partitions.put("partition" + i, context);
        }

        return partitions;
    }
}
```

### Partitioned Step
```java
@Bean
public Step masterStep() {
    return new StepBuilder("masterStep", jobRepository)
        .partitioner("slaveStep", customerPartitioner())
        .step(slaveStep())
        .gridSize(4)
        .taskExecutor(taskExecutor())
        .build();
}

@Bean
public Step slaveStep() {
    return new StepBuilder("slaveStep", jobRepository)
        .<CustomerCsv, CustomerStg>chunk(100, transactionManager)
        .reader(partitionedReader(null, null))  // Late Binding
        .processor(customerProcessor())
        .writer(customerStgWriter())
        .build();
}

@StepScope
@Bean
public JdbcPagingItemReader<CustomerCsv> partitionedReader(
    @Value("#{stepExecutionContext['minId']}") Integer minId,
    @Value("#{stepExecutionContext['maxId']}") Integer maxId) {
    // 파티션 범위에 맞는 데이터만 읽기
}
```

---

## 성능 실험 기록 표

### 실험 환경
- CPU:
- Memory:
- 데이터 건수: 100,000
- DB: PostgreSQL 16

### 실험 결과

| 실험 # | Chunk Size | Thread 수 | 소요 시간 | TPS | 비고 |
|--------|------------|-----------|-----------|-----|------|
| 1 | 100 | 1 | - | - | 기준선 |
| 2 | 100 | 4 | - | - | Multi-threaded |
| 3 | 100 | 8 | - | - | 스레드 증가 |
| 4 | 500 | 4 | - | - | Chunk 증가 |
| 5 | 1000 | 4 | - | - | Chunk 증가 |
| 6 | 100 | 4 (Partition) | - | - | Partitioning |

### TPS 계산
```
TPS = 총 처리 건수 / 소요 시간(초)
```

---

## 실행 방법

```bash
# 단일 스레드 (기준선)
./gradlew bootRun --args='inputFile=input/customers_large.csv runDate=2025-02-05 -threadCount=1 -chunkSize=100'

# Multi-threaded (4 스레드)
./gradlew bootRun --args='inputFile=input/customers_large.csv runDate=2025-02-06 -threadCount=4 -chunkSize=100'

# Chunk Size 변경
./gradlew bootRun --args='inputFile=input/customers_large.csv runDate=2025-02-07 -threadCount=4 -chunkSize=500'
```

### 대용량 테스트 데이터 생성
```bash
# 10만 건 생성
echo "customerId,email,name,phone" > input/customers_large.csv
for i in $(seq 1 100000); do
  echo "C$(printf '%06d' $i),user${i}@example.com,사용자${i},010-0000-$(printf '%04d' $((i % 10000)))" >> input/customers_large.csv
done
```

---

## 검증 방법

### 실행 시간 확인
```sql
SELECT
    step_name,
    start_time,
    end_time,
    EXTRACT(EPOCH FROM (end_time - start_time)) AS duration_sec,
    read_count,
    write_count
FROM batch_step_execution
WHERE job_execution_id = ?;
```

### 데이터 무결성 확인
```sql
-- 총 건수 확인
SELECT COUNT(*) FROM customer_stg WHERE run_date = '2025-02-05';

-- 중복 확인
SELECT customer_id, COUNT(*) AS cnt
FROM customer_stg
WHERE run_date = '2025-02-05'
GROUP BY customer_id
HAVING COUNT(*) > 1;

-- 누락 확인 (연속성)
SELECT MIN(customer_id), MAX(customer_id)
FROM customer_stg
WHERE run_date = '2025-02-05';
```

### 처리량 계산
```sql
SELECT
    step_name,
    write_count,
    EXTRACT(EPOCH FROM (end_time - start_time)) AS duration_sec,
    write_count / EXTRACT(EPOCH FROM (end_time - start_time)) AS tps
FROM batch_step_execution
WHERE job_execution_id = ?;
```

---

## 주의사항

### Multi-threaded Step
- Reader가 Thread-safe해야 함
- ExecutionContext 재시작 보장 어려움 (saveState=false 권장)
- 순서 보장 안됨

### Partitioning
- 데이터 분할 기준이 명확해야 함
- 각 파티션이 독립적이어야 함 (데이터 겹침 X)
- 재시작 시 파티션별 상태 복원 가능

### 병목 지점
| 병목 | 증상 | 해결 |
|------|------|------|
| CPU | CPU 100%, I/O Wait 낮음 | 스레드 수 줄이거나 로직 최적화 |
| DB | DB Wait 높음, 커넥션 대기 | Connection Pool 증가, Chunk Size 조정 |
| I/O | I/O Wait 높음 | 파일 분할, SSD 사용 |
| Memory | OOM, GC 빈번 | Chunk Size 줄이기, Heap 증가 |

---

## 트러블슈팅 로그

### 이슈 1: 중복 레코드 발생
- **현상**: 같은 레코드가 여러 번 적재됨
- **원인**: Reader가 Thread-safe 아님
- **해결**: SynchronizedItemStreamReader 사용

### 이슈 2: 스레드 증가해도 성능 안 오름
- **현상**: 4스레드 → 8스레드 성능 동일
- **원인**: DB 커넥션 풀 부족 또는 DB 자체가 병목
- **해결**: Connection Pool 증가, DB 성능 확인

### 이슈 3: 재시작 시 처음부터 다시 실행
- **현상**: 실패 후 재시작 시 0건부터 시작
- **원인**: Multi-threaded에서 saveState 동작 불안정
- **해결**: saveState(false) 설정, 멱등성으로 해결

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- spring-batch-test 학습
- 관측성 도구 선택

---

## 참고 링크

### Spring 공식 문서
- [Scaling and Parallel Processing](https://docs.spring.io/spring-batch/reference/scalability.html)
- [Multi-threaded Step](https://docs.spring.io/spring-batch/reference/scalability.html#multithreadedStep)
- [Parallel Steps](https://docs.spring.io/spring-batch/reference/scalability.html#parallelSteps)
- [Partitioning](https://docs.spring.io/spring-batch/reference/scalability.html#partitioning)
- [Remote Chunking](https://docs.spring.io/spring-batch/reference/scalability.html#remoteChunking)

### 성능 튜닝
- [Common Batch Patterns - Performance](https://docs.spring.io/spring-batch/reference/common-patterns.html)