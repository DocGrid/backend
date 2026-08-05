# #97 PostgreSQL 17 및 pgvector 0.8.1 로컬 실행 환경 검증 결과

## 1. 검증 개요

- 실행 일자: 2026-08-05
- Branch: `feature/97`
- 검증 기준 Commit: `e6f6ef3bf967600129c32b871e426bf272e707a0`
- Host OS: macOS 26.5.2
- Host Architecture: arm64
- CPU: Apple M5, 10 Core
- Docker Engine: 29.4.1
- Container Image: `pgvector/pgvector:0.8.1-pg17`
- Container Image Architecture: `linux/arm64`
- Database: PostgreSQL 17.8
- Vector Extension: pgvector 0.8.1
- Database SSL: 비활성화

공급사 설치 Archive, 다운로드 정보와 라이선스는 검증에 사용하지 않았고 Repository 및 Docker
Build Context에도 포함하지 않았다.

## 2. 정적 검증

### 2.1 Compose 해석

```bash
docker compose --env-file /dev/null config
```

결과: 성공

- 기본 Port: `55432:5432`
- PostgreSQL Image: `pgvector/pgvector:0.8.1-pg17`
- Data Volume: `docgrid_postgres17_data:/var/lib/postgresql/data`
- 초기화 SQL: `docker/postgres/init/001-enable-vector.sql`
- Health Check: Container의 `POSTGRES_USER`, `POSTGRES_DB` 사용
- `platform: linux/amd64` 강제 없음
- PostgreSQL 14 전용 실행 경로 없음

### 2.2 Gradle 리소스와 Test Compile

| 명령 | 결과 |
| --- | --- |
| `./gradlew processResources` | 성공 |
| `./gradlew testClasses` | 성공 |
| `./gradlew build` | 성공, 1초 |

## 3. Docker 기동 및 데이터 격리

### 3.1 Image Pull 및 최초 기동

```bash
docker compose --env-file /dev/null pull postgres
docker compose --env-file /dev/null up -d postgres
```

결과:

- `docgrid-postgres17` Container: `healthy`
- 새 `docgrid_postgres17_data` Volume 생성
- 실제 Image Architecture: `linux/arm64`
- 중지 상태였던 같은 Compose Service의 기존 Container 객체는 새 PostgreSQL 17 Container로 재생성

### 3.2 기존 볼륨 보존

검증 후 확인된 관련 Named Volume:

```text
docgrid_claim_performance_opensql_data
docgrid_postgres17_data
opensql_data
```

기존 `opensql_data`와 Claim 성능 측정용 PostgreSQL 14 Volume은 삭제되거나 PostgreSQL 17 Container에
Mount되지 않았다.

## 4. Database 정상 시나리오

### 4.1 버전과 Extension

```text
server_version = 17.8 (Debian 17.8-1.pgdg12+1)
vector extversion = 0.8.1
```

`001-enable-vector.sql`이 새 Data Directory 초기화 과정에서 실행돼 Flyway보다 먼저
`vector` Extension을 준비했다.

### 4.2 Flyway와 Hibernate

Local Profile 기동 결과:

- PostgreSQL 17.8 연결 성공
- Flyway Migration 37개 검증 및 적용 성공
  - Versioned Migration: V1~V35
  - Repeatable Migration: 2개
- 최종 Schema Version: V35
- Hibernate `ddl-auto=validate` 성공
- Tomcat 8080 기동 성공
- Application 시작 시간: 3.271초
- 기동 확인 후 Application Process만 `SIGINT`로 종료

### 4.3 Vector Column과 HNSW

```text
embeddings.vector = vector(1024)
CREATE INDEX embeddings_vector_idx
    ON public.embeddings
    USING hnsw (vector vector_cosine_ops)
```

기존 `vector(1024)` 저장 계약과 Cosine Distance용 HNSW Index가 PostgreSQL 17 + pgvector 0.8.1에서도
그대로 적용됐다.

## 5. 전체 테스트

### 5.1 최초 실행의 설정 오류

첫 `./gradlew test` 실행은 558개 중 11개 Context 초기화 테스트가 실패했다.

원인:

```text
Could not resolve placeholder 'JWT_SECRET'
```

이 실패는 PostgreSQL, Flyway 또는 pgvector 호환성 오류가 아니라 필수 Test 환경변수를 주입하지 않은
실행 설정 오류였다. Repository 설정 파일에 Secret을 추가하지 않고 실행 Process에만 임시 Test 값을
주입해 재실행했다.

### 5.2 설정 보정 후 전체 테스트

```bash
JWT_SECRET=<ephemeral-test-value> \
DB_HOST=localhost \
DB_PORT=55432 \
DB_NAME=app \
DB_USER=app \
DB_PASSWORD=<local-test-value> \
DB_SSLMODE=disable \
./gradlew test
```

결과:

```text
BUILD SUCCESSFUL in 19s
558 tests, 0 failed
```

기본 Test Task는 `benchmark`, `minio-integration`, `claim-concurrency` Tag를 제외한다.

### 5.3 Swagger 수동 검증

결과: 적용 제외

- 이 작업은 Controller, Request/Response DTO, API 경로 및 Swagger 설정을 변경하지 않는다.
- 검증 대상은 PostgreSQL 17 실행 환경, Flyway/Hibernate Schema, Vector Index와 Claim
  Benchmark 환경 계약이다.
- API 계약이 변경되는 작업에는 Swagger 수동 호출 결과를 자동 테스트 결과와 함께 기록해야 하지만,
  이번 DB 실행 환경 전환은 해당 예외 기준에 해당한다.

## 6. Claim 동시성 통합 테스트

```bash
./gradlew claimConcurrencyTest
```

결과:

```text
BUILD SUCCESSFUL in 7s
```

실제 PostgreSQL 17에서 다중 Worker의 PENDING Job Claim 경쟁, 단일 Claim 소유권과 Claim Token
정합성 테스트가 통과했다.

## 7. Claim Benchmark Smoke

환경 전제조건과 Claim 흐름을 확인하기 위해 성능 기준선 전체 측정 대신 작은 Smoke Profile을 실행했다.

조건:

- Warm-up Job: 10
- 측정 Job: Profile별 20
- Worker: 1, 2
- 반복: 1
- Hikari Pool: 20
- Sampling Interval: 10ms

환경 가드 결과:

- PostgreSQL Server Version: 17.8
- pgvector Version: 0.8.1
- Configured SSL Mode: `disable`
- Test Schema: `docgrid_embedding_job_claim_performance_test`
- Hikari Pool Size: 20

측정 결과:

| Worker | TPS | Queue 소진 | P95 | Hikari 대기 | PostgreSQL Lock 대기 | Deadlock |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 170.13 | 117.56ms | 7.40ms | 0 | 0 | 0 |
| 2 | 353.34 | 56.60ms | 8.96ms | 0 | 0 | 0 |

두 Profile 모두 다음 정합성을 만족했다.

- Worker 오류 0
- PENDING Job 0
- 불완전 소유권 0
- 중복 Claim Token 0
- 잘못된 LOCKED Event 0
- Rollback 0
- Deadlock 0

결과:

```text
BUILD SUCCESSFUL in 16s
```

이 수치는 환경 가드와 실행 경로를 확인하기 위한 작은 Smoke 결과이며, 정식 처리량 기준선으로 사용하지
않는다.

## 8. 오류 및 경계 시나리오

### 8.1 필수 Secret 누락

- 입력: `JWT_SECRET` 없이 전체 테스트 실행
- 결과: Spring Context 초기화 단계에서 명확하게 실패
- 조치: Repository에 Secret을 추가하지 않고 Test Process에만 임시 값 주입
- 재실행: 전체 테스트 성공

### 8.2 Test Schema와 Local Schema 분리

전체 테스트는 `docgrid_test` 격리 Schema에 Flyway를 적용했다. Local Profile을 별도로 기동하기
전에는 `public.flyway_schema_history`가 없는 것이 정상이며, Local 기동 후 `public`에 37개
Migration이 적용됐다.

### 8.3 PostgreSQL Major Version 볼륨 분리

- PostgreSQL 17 Container Mount: `docgrid_postgres17_data`
- 기존 PostgreSQL 14 `opensql_data`: 보존, 미Mount
- 자동 In-place Upgrade: 수행하지 않음

## 9. 미실행 및 후속 검증

- Rocky Linux 9.7 x86-64의 공식 OpenSQL 17.8 원격 검증은 아직 실행하지 않았다.
- Claim Benchmark 정식 기준선은 기본 Job 수와 반복 수로 별도 실행해야 한다.
- MinIO 실제 Object 경쟁 Test와 외부 Embedding Server E2E는 이번 DB Runtime 전환 범위에서 별도
  실행하지 않았다.

공식 OpenSQL 환경이 준비되면 [로컬 DB Runbook](../local-db.md)의 동일 SQL과 회귀 시나리오로
호환성을 확인하고, 차이가 있으면 독립된 이슈로 기록한다.

## 10. 최종 판정

| 완료 조건 | 결과 |
| --- | --- |
| PostgreSQL 17 기동 | 통과 |
| pgvector 0.8.1 | 통과 |
| Host Architecture 자동 선택 | 통과, linux/arm64 |
| 새 Volume 분리 | 통과 |
| 기존 PostgreSQL 14 Volume 보존 | 통과 |
| Flyway V1~V35 + Repeatable 2개 | 통과 |
| Hibernate Schema Validation | 통과 |
| `vector(1024)` | 통과 |
| HNSW `vector_cosine_ops` | 통과 |
| 전체 기본 테스트 | 통과, 558개 |
| Claim 동시성 테스트 | 통과 |
| Benchmark 환경 가드 및 Smoke | 통과 |
| 공식 OpenSQL 17.8 원격 검증 | 후속 작업 |

로컬 PostgreSQL 17 + pgvector 0.8.1 전환 범위는 완료 조건을 충족했다.

Closes #97
