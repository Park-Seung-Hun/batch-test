# Week 01: 배치 도메인 언어

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] Job, Step, Execution 개념과 관계 이해
- [ ] JobInstance vs JobExecution 차이 명확히 구분
- [ ] JobParameters의 identifying/non-identifying 이해
- [ ] JobRepository의 역할 이해
- [ ] 동일 파라미터 재실행 시 동작 확인

---

## 핵심 개념 요약 (내 말로)

### Job
> 한 줄 정의: 배치 처리의 최상위 단위, 하나 이상의 Step으로 구성

Job은 "무엇을 할 것인가"를 정의한다. Step들의 컨테이너이며, 실행 순서와 흐름을 제어한다.

### Step
> 한 줄 정의: Job 내의 독립적인 처리 단위

Step은 "어떻게 할 것인가"를 정의한다. Chunk 기반(Reader-Processor-Writer) 또는 Tasklet 기반으로 구현.

### JobInstance
> 한 줄 정의: Job + identifying parameters의 조합으로 식별되는 논리적 실행 단위

같은 Job이라도 파라미터가 다르면 다른 JobInstance. 예: `customerImportJob(runDate=2025-02-05)` ≠ `customerImportJob(runDate=2025-02-06)`

### JobExecution
> 한 줄 정의: JobInstance의 실제 실행 기록

같은 JobInstance가 실패 후 재실행되면 새로운 JobExecution이 생성된다.
- 첫 실행: JobInstance 1 → JobExecution 1 (FAILED)
- 재실행: JobInstance 1 → JobExecution 2 (COMPLETED)

### JobParameters
> 한 줄 정의: Job 실행 시 전달되는 파라미터, identifying 여부에 따라 JobInstance 구분에 사용

- **Identifying**: JobInstance 구분에 사용 (예: runDate, inputFile)
- **Non-identifying**: JobInstance 구분에 미사용 (예: chunkSize, skipLimit)

### JobRepository
> 한 줄 정의: 모든 배치 메타데이터를 저장/조회하는 중앙 저장소

JobRepository는 재시작, 이력 관리, 동시 실행 방지의 핵심이다.

### StepExecution
> 한 줄 정의: Step의 실제 실행 기록, read/write/skip count 등 포함

각 Step 실행마다 생성되며, 처리 통계와 상태를 기록한다.

---

## 실습 시나리오

### 입력
- 파라미터: `runDate=2025-02-05`

### 처리
1. 간단한 2-Step Job 생성
2. 동일 파라미터로 재실행 시도
3. 파라미터 변경 후 실행
4. 강제 실패 후 재실행

### 출력
- BATCH_JOB_INSTANCE / BATCH_JOB_EXECUTION 관계 이해
- BATCH_JOB_EXECUTION_PARAMS 데이터 확인

### 성공 기준
- [ ] 동일 파라미터 재실행 시 "already completed" 동작 확인
- [ ] 파라미터 변경 시 새로운 JobInstance 생성 확인
- [ ] 실패 후 재실행 시 새로운 JobExecution 생성 확인
- [ ] identifying vs non-identifying 파라미터 동작 차이 확인

---

## 구현 체크리스트

### Job 구성
- [ ] `domainStudyJob` 생성
- [ ] Step 1: 로그 출력 Tasklet
- [ ] Step 2: 로그 출력 Tasklet

### JobParameters 구성
- [ ] `runDate` (identifying)
- [ ] `chunkSize` (non-identifying)

### 테스트 시나리오
- [ ] 시나리오 1: 최초 실행 → COMPLETED
- [ ] 시나리오 2: 동일 파라미터 재실행 → 이미 완료 오류
- [ ] 시나리오 3: 파라미터 변경 실행 → 새 JobInstance
- [ ] 시나리오 4: 강제 실패 → 재실행 → 새 JobExecution

---

## 실행 방법

```bash
# 시나리오 1: 최초 실행
./gradlew bootRun --args='--spring.batch.job.name=domainStudyJob runDate=2025-02-05'

# 시나리오 2: 동일 파라미터 재실행 (오류 예상)
./gradlew bootRun --args='--spring.batch.job.name=domainStudyJob runDate=2025-02-05'

# 시나리오 3: 파라미터 변경
./gradlew bootRun --args='--spring.batch.job.name=domainStudyJob runDate=2025-02-06'

# 시나리오 4: Non-identifying 파라미터 추가 (동일 JobInstance)
./gradlew bootRun --args='--spring.batch.job.name=domainStudyJob runDate=2025-02-05 -chunkSize=500'
```

### 파라미터 표기법
| 표기 | 의미 |
|------|------|
| `param=value` | identifying (기본) |
| `-param=value` | non-identifying |
| `param(type)=value` | 타입 명시 (string, long, double, date) |

---

## 검증 방법

### JobInstance vs JobExecution 관계
```sql
-- JobInstance 목록
SELECT * FROM batch_job_instance ORDER BY job_instance_id DESC;

-- JobExecution 목록 (JobInstance별)
SELECT
    ji.job_instance_id,
    ji.job_name,
    je.job_execution_id,
    je.status,
    je.start_time,
    je.end_time
FROM batch_job_instance ji
JOIN batch_job_execution je ON ji.job_instance_id = je.job_instance_id
ORDER BY ji.job_instance_id DESC, je.job_execution_id DESC;

-- 하나의 JobInstance에 여러 JobExecution (재시작 케이스)
SELECT ji.job_instance_id, COUNT(je.job_execution_id) AS execution_count
FROM batch_job_instance ji
JOIN batch_job_execution je ON ji.job_instance_id = je.job_instance_id
GROUP BY ji.job_instance_id
HAVING COUNT(je.job_execution_id) > 1;
```

### JobParameters 확인
```sql
-- 파라미터 값 확인
SELECT
    jep.job_execution_id,
    jep.parameter_name,
    jep.parameter_type,
    jep.parameter_value,
    jep.identifying
FROM batch_job_execution_params jep
ORDER BY jep.job_execution_id DESC;
```

### StepExecution 확인
```sql
-- Step별 처리 건수
SELECT
    step_name,
    status,
    read_count,
    write_count,
    commit_count,
    rollback_count
FROM batch_step_execution
WHERE job_execution_id = ?;
```

---

## 개념 관계도

```
Job (customerImportJob)
 │
 ├── JobInstance 1 (runDate=2025-02-05)
 │    ├── JobExecution 1 (FAILED)
 │    └── JobExecution 2 (COMPLETED)  ← 재시작
 │
 └── JobInstance 2 (runDate=2025-02-06)
      └── JobExecution 3 (COMPLETED)

JobExecution 1
 ├── StepExecution 1 (csvToStagingStep, COMPLETED)
 └── StepExecution 2 (validateStep, FAILED)

JobExecution 2 (재시작)
 └── StepExecution 3 (validateStep, COMPLETED)  ← 실패한 Step부터 재시작
```

---

## 트러블슈팅 로그

### 이슈 1: JobInstanceAlreadyCompleteException
- **현상**: 동일 파라미터로 재실행 시 오류
- **원인**: 이미 COMPLETED된 JobInstance는 재실행 불가
- **해결**: 파라미터 변경 또는 `JobParametersIncrementer` 사용

### 이슈 2: Non-identifying 파라미터가 적용 안됨
- **현상**: `-param=value`로 전달했는데 반영 안됨
- **원인**: JobParameters 빌더에서 non-identifying 설정 누락
- **해결**: `JobParametersBuilder.addString(key, value, false)` 사용

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- FlatFileItemReader 학습
- Chunk 처리 이해

---

## 참고 링크

### Spring 공식 문서
- [Domain Language of Batch](https://docs.spring.io/spring-batch/reference/domain.html)
- [Configuring and Running a Job](https://docs.spring.io/spring-batch/reference/job/configuring.html)
- [JobParameters](https://docs.spring.io/spring-batch/reference/job/configuring.html#jobparameters)
- [JobRepository](https://docs.spring.io/spring-batch/reference/job/configuring.html#configuringJobRepository)
- [Schema Appendix](https://docs.spring.io/spring-batch/reference/schema-appendix.html)

### 추가 자료
- [Spring Batch 5.0 Migration Guide](https://github.com/spring-projects/spring-batch/wiki/Spring-Batch-5.0-Migration-Guide)