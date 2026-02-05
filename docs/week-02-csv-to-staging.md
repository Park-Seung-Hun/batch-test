# Week 02: CSV → Staging (Chunk 처리)

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] Chunk 기반 처리 모델 이해 (Reader-Processor-Writer)
- [ ] FlatFileItemReader로 CSV 파일 읽기 구현
- [ ] JdbcBatchItemWriter로 DB 적재 구현
- [ ] Chunk Size와 Commit Interval 이해
- [ ] 트랜잭션 경계와 롤백 단위 이해

---

## 핵심 개념 요약 (내 말로)

### Chunk 처리 모델
> 한 줄 정의: N개 아이템을 읽고(Read), 변환하고(Process), 한 번에 쓰는(Write) 패턴

```
[Read] → [Read] → [Read] → ... (N번)
            ↓
[Process] → [Process] → [Process] → ... (N번)
            ↓
      [Write] (1번, N개 일괄)
            ↓
        [Commit]
```

- Chunk Size = Commit Interval = 트랜잭션 단위
- 실패 시 해당 Chunk만 롤백 (이전 Chunk는 이미 커밋됨)

### FlatFileItemReader
> 한 줄 정의: 플랫 파일(CSV, 고정폭 등)을 한 줄씩 읽어 객체로 매핑하는 Reader

구성 요소:
- `Resource`: 읽을 파일 지정
- `LineMapper`: 한 줄 → 객체 변환
- `LineTokenizer`: 한 줄 → 필드 분리
- `FieldSetMapper`: 필드 → 객체 매핑

### JdbcBatchItemWriter
> 한 줄 정의: JDBC Batch를 사용해 여러 레코드를 한 번에 DB에 적재하는 Writer

내부적으로 `PreparedStatement.addBatch()` + `executeBatch()` 사용.
Chunk Size만큼 모아서 한 번에 실행하므로 성능 우수.

### Commit Interval
> 한 줄 정의: 트랜잭션 커밋 주기 = Chunk Size

- Chunk Size 100 = 100개 처리 후 커밋
- 너무 작으면: 커밋 오버헤드 증가
- 너무 크면: 롤백 시 손실 증가, 메모리 사용량 증가

---

## 실습 시나리오

### 입력
- 파일: `input/customers_20250205.csv`
- 형식:
```csv
customerId,email,name,phone
C001,kim@example.com,김철수,010-1234-5678
C002,lee@example.com,이영희,010-2345-6789
C003,park@example.com,박민수,010-3456-7890
```

### 처리
```
csvToStagingStep (Chunk 기반)
├── Reader: FlatFileItemReader (CSV → CustomerCsv DTO)
├── Processor: CustomerCsv → CustomerStg 변환 (run_date 추가)
└── Writer: JdbcBatchItemWriter (customer_stg INSERT)
```

### 출력
- `customer_stg` 테이블에 데이터 적재
- `run_date` 컬럼에 실행일자 기록

### 성공 기준
- [ ] CSV 파일의 모든 레코드가 `customer_stg`에 적재됨
- [ ] `run_date`가 정확히 기록됨
- [ ] BATCH_STEP_EXECUTION의 READ_COUNT = WRITE_COUNT 일치
- [ ] Chunk Size 변경 시 COMMIT_COUNT 변화 확인

---

## 구현 체크리스트

### DTO 클래스
- [ ] `CustomerCsv` (CSV 레코드 매핑용)
- [ ] `CustomerStg` (스테이징 테이블 엔티티)

### Reader 구현
- [ ] FlatFileItemReader 빈 생성
- [ ] DelimitedLineTokenizer 설정 (컬럼 매핑)
- [ ] BeanWrapperFieldSetMapper 설정
- [ ] 헤더 스킵 설정 (`linesToSkip=1`)

### Processor 구현
- [ ] CustomerCsv → CustomerStg 변환
- [ ] run_date 주입

### Writer 구현
- [ ] JdbcBatchItemWriter 빈 생성
- [ ] INSERT SQL 작성
- [ ] ItemSqlParameterSourceProvider 설정

### Step/Job 구성
- [ ] csvToStagingStep 생성
- [ ] Chunk Size 설정
- [ ] customerImportJob에 Step 추가

---

## 예상 코드 구조

### DTO
```java
// CustomerCsv.java
public record CustomerCsv(
    String customerId,
    String email,
    String name,
    String phone
) {}

// CustomerStg.java
public record CustomerStg(
    String customerId,
    String email,
    String name,
    String phone,
    LocalDate runDate
) {}
```

### Reader 설정
```java
@Bean
public FlatFileItemReader<CustomerCsv> customerCsvReader() {
    return new FlatFileItemReaderBuilder<CustomerCsv>()
        .name("customerCsvReader")
        .resource(new FileSystemResource("input/customers_20250205.csv"))
        .linesToSkip(1)  // 헤더 스킵
        .delimited()
        .names("customerId", "email", "name", "phone")
        .targetType(CustomerCsv.class)
        .build();
}
```

### Writer 설정
```java
@Bean
public JdbcBatchItemWriter<CustomerStg> customerStgWriter(DataSource dataSource) {
    return new JdbcBatchItemWriterBuilder<CustomerStg>()
        .dataSource(dataSource)
        .sql("""
            INSERT INTO customer_stg (customer_id, email, name, phone, run_date)
            VALUES (:customerId, :email, :name, :phone, :runDate)
            """)
        .beanMapped()
        .build();
}
```

---

## 실행 방법

```bash
# 기본 실행 (Chunk Size 기본값)
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv runDate=2025-02-05'

# Chunk Size 변경 테스트
./gradlew bootRun --args='--spring.batch.job.name=customerImportJob inputFile=input/customers_20250205.csv runDate=2025-02-06 -chunkSize=10'
```

### 테스트용 CSV 생성
```bash
# 샘플 데이터 생성 (100건)
echo "customerId,email,name,phone" > input/customers_20250205.csv
for i in $(seq 1 100); do
  echo "C$(printf '%03d' $i),user${i}@example.com,사용자${i},010-0000-$(printf '%04d' $i)" >> input/customers_20250205.csv
done
```

---

## 검증 방법

### 적재 결과 확인
```sql
-- 건수 확인
SELECT COUNT(*) FROM customer_stg WHERE run_date = '2025-02-05';

-- 샘플 데이터 확인
SELECT * FROM customer_stg WHERE run_date = '2025-02-05' LIMIT 10;
```

### Step 실행 통계 확인
```sql
SELECT
    step_name,
    read_count,
    write_count,
    commit_count,
    rollback_count,
    status
FROM batch_step_execution
WHERE job_execution_id = (
    SELECT MAX(job_execution_id) FROM batch_job_execution
);
```

### Chunk Size 검증
```
Chunk Size = 100, 총 1000건 → COMMIT_COUNT = 10
Chunk Size = 50,  총 1000건 → COMMIT_COUNT = 20
```

---

## 트러블슈팅 로그

### 이슈 1: 파일을 찾을 수 없음
- **현상**: `FileNotFoundException`
- **원인**: 상대 경로 문제 또는 파일 미존재
- **해결**: 절대 경로 사용 또는 `FileSystemResource` 경로 확인

### 이슈 2: CSV 컬럼 매핑 오류
- **현상**: `IncorrectTokenCountException`
- **원인**: CSV 컬럼 수와 names 배열 불일치
- **해결**: `names()` 배열과 CSV 헤더 일치 확인

### 이슈 3: 한글 깨짐
- **현상**: DB에 한글이 ??? 로 저장됨
- **원인**: Reader의 인코딩 설정 누락
- **해결**: `.encoding(StandardCharsets.UTF_8.name())` 추가

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- Tasklet으로 검증 로직 구현
- Step Flow 분기 이해

---

## 참고 링크

### Spring 공식 문서
- [Chunk-oriented Processing](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing.html)
- [Configuring a Step (Commit Interval)](https://docs.spring.io/spring-batch/reference/step/chunk-oriented-processing/commit-interval.html)
- [FlatFileItemReader](https://docs.spring.io/spring-batch/reference/readers-and-writers/flat-files.html)
- [Database ItemWriters](https://docs.spring.io/spring-batch/reference/readers-and-writers/database.html)

### 추가 자료
- [ItemReader/ItemWriter 구현체 목록](https://docs.spring.io/spring-batch/reference/readers-and-writers/item-reader-writer-implementations.html)