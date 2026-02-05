# Week 00: 환경 세팅

> 작성일: YYYY-MM-DD
> 상태: ⬜ 예정

---

## 이번 주 목표

- [ ] PostgreSQL 설치 및 실행 확인
- [ ] Spring Batch 메타 테이블 스키마 생성
- [ ] 프로젝트 의존성 추가 (spring-boot-starter-batch, postgresql)
- [ ] 기본 Job 실행 및 메타 테이블 확인
- [ ] 비즈니스 테이블 DDL 생성 (customer_stg, customer, customer_err)

---

## 핵심 개념 요약 (내 말로)

### Spring Batch 메타 테이블
> 한 줄 정의: Job/Step 실행 이력과 상태를 저장하는 Spring Batch의 내부 테이블

메타 테이블은 재시작, 멱등성, 실행 이력 추적의 핵심이다.
- `BATCH_JOB_INSTANCE`: Job + identifying parameters 조합 (논리적 실행 단위)
- `BATCH_JOB_EXECUTION`: 실제 실행 기록 (시작/종료 시간, 상태)
- `BATCH_JOB_EXECUTION_PARAMS`: 실행 파라미터
- `BATCH_STEP_EXECUTION`: Step별 실행 기록 (read/write/skip count)
- `BATCH_STEP_EXECUTION_CONTEXT`: Step의 ExecutionContext (재시작용)

### DataSource 구성
> 한 줄 정의: Spring Batch는 메타 테이블용 DataSource가 필수

Spring Batch는 기본적으로 트랜잭션 관리를 위해 DataSource를 요구한다.
개발 환경에서는 H2 인메모리도 가능하지만, 운영 환경에서는 반드시 영속적인 DB 사용.

---

## 실습 시나리오

### 입력
- 없음 (환경 세팅 단계)

### 처리
- PostgreSQL 컨테이너 실행
- 메타 스키마 자동 생성 확인
- 비즈니스 테이블 DDL 실행
- 빈 Job 실행

### 출력
- 메타 테이블 6개 생성 확인
- 비즈니스 테이블 4개 생성 확인
- BATCH_JOB_EXECUTION에 COMPLETED 레코드 1건

### 성공 기준
- [ ] PostgreSQL 접속 가능
- [ ] 메타 테이블 SELECT 성공
- [ ] 빈 Job 실행 후 COMPLETED 상태 확인

---

## 구현 체크리스트

### 의존성 추가 (build.gradle)
- [ ] `spring-boot-starter-batch`
- [ ] `postgresql` (또는 `org.postgresql:postgresql`)
- [ ] `spring-boot-starter-jdbc`

### application.yml 설정
- [ ] PostgreSQL 접속 정보
- [ ] `spring.batch.jdbc.initialize-schema: always` (최초 1회)
- [ ] `spring.batch.job.enabled: false` (수동 실행 모드)

### DDL 스크립트
- [ ] `schema/V001__create_business_tables.sql`

### 기본 Job 구성
- [ ] 빈 Tasklet으로 helloJob 생성
- [ ] JobLauncher로 실행

---

## 실행 방법

### 1. PostgreSQL 실행 (Docker)
```bash
docker run -d \
  --name batch-postgres \
  -e POSTGRES_USER=batch \
  -e POSTGRES_PASSWORD=batch123 \
  -e POSTGRES_DB=batchdb \
  -p 5432:5432 \
  postgres:16
```

### 2. 접속 확인
```bash
docker exec -it batch-postgres psql -U batch -d batchdb
```

### 3. 애플리케이션 실행
```bash
./gradlew bootRun
```

### 4. 특정 Job 실행
```bash
./gradlew bootRun --args='--spring.batch.job.name=helloJob'
```

---

## 검증 방법

### 메타 테이블 확인
```sql
-- 테이블 목록 확인
SELECT table_name FROM information_schema.tables
WHERE table_name LIKE 'batch_%';

-- Job 실행 결과
SELECT * FROM batch_job_execution ORDER BY job_execution_id DESC LIMIT 5;

-- Step 실행 결과
SELECT * FROM batch_step_execution ORDER BY step_execution_id DESC LIMIT 5;
```

### 비즈니스 테이블 확인
```sql
-- 테이블 존재 확인
SELECT table_name FROM information_schema.tables
WHERE table_name IN ('customer_stg', 'customer', 'customer_err', 'customer_daily_stats');
```

---

## 비즈니스 테이블 DDL

```sql
-- 스테이징 테이블
CREATE TABLE customer_stg (
    id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(50) NOT NULL,
    email VARCHAR(255),
    name VARCHAR(100),
    phone VARCHAR(20),
    run_date DATE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(customer_id, run_date)
);

-- 타깃 테이블
CREATE TABLE customer (
    id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(255),
    name VARCHAR(100),
    phone VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 오류 격리 테이블
CREATE TABLE customer_err (
    id BIGSERIAL PRIMARY KEY,
    customer_id VARCHAR(50),
    email VARCHAR(255),
    name VARCHAR(100),
    phone VARCHAR(20),
    error_message TEXT,
    run_date DATE NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 일별 집계 테이블 (선택)
CREATE TABLE customer_daily_stats (
    id BIGSERIAL PRIMARY KEY,
    run_date DATE NOT NULL UNIQUE,
    total_count INT DEFAULT 0,
    success_count INT DEFAULT 0,
    error_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 인덱스
CREATE INDEX idx_customer_stg_run_date ON customer_stg(run_date);
CREATE INDEX idx_customer_err_run_date ON customer_err(run_date);
```

---

## application.yml 예시

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/batchdb
    username: batch
    password: batch123
    driver-class-name: org.postgresql.Driver

  batch:
    jdbc:
      initialize-schema: always  # 최초 실행 후 never로 변경 권장
    job:
      enabled: false  # 수동 실행 모드
```

---

## 트러블슈팅 로그

### 이슈 1: 메타 테이블 생성 안됨
- **현상**: BATCH_* 테이블이 없음
- **원인**: `initialize-schema: never` 설정
- **해결**: `always`로 변경 후 재실행, 이후 `never`로 복원

### 이슈 2: PostgreSQL 접속 실패
- **현상**: Connection refused
- **원인**: Docker 컨테이너 미실행 또는 포트 충돌
- **해결**: `docker ps`로 확인, 포트 변경 또는 재시작

---

## 회고

### 잘한 점


### 개선할 점


### 다음 주 준비
- Job/Step/Execution 개념 정리
- JobParameters 이해

---

## 참고 링크

### Spring 공식 문서
- [Spring Batch Reference](https://docs.spring.io/spring-batch/reference/)
- [Schema Appendix (메타 테이블 구조)](https://docs.spring.io/spring-batch/reference/schema-appendix.html)
- [Spring Boot Batch Auto-configuration](https://docs.spring.io/spring-boot/reference/io/batch.html)

### PostgreSQL
- [PostgreSQL Docker Hub](https://hub.docker.com/_/postgres)