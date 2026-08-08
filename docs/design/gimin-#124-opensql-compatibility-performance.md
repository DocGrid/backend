# Issue #124 공식 OpenSQL 17.8 호환성 및 성능 검증 상세 설계

## 1. 배경과 목적

DocGrid 로컬 개발 환경은 PostgreSQL 17.8과 pgvector 0.8.1에서 실제 문서 업로드, BGE-M3 임베딩,
`vector(1024)` 저장과 검색까지 검증됐다. 대회 지정 공식 환경은 Rocky Linux 9.7 x86-64 Single 구성의
OpenSQL 17.8이므로 PostgreSQL 호환성만으로 최종 판정할 수 없다.

이번 작업은 제품 기능을 변경하지 않고 공식 DB에서 다음 계약을 반복 실행 가능한 Test와 Runbook으로
고정한다.

```text
공식 Host 지원 환경 호환성 Preflight
→ Flyway 전체 Migration·Hibernate Validation
→ pgvector·vector(1024)·HNSW·Cosine Operator
→ SKIP LOCKED·Claim·Lease·Retry
→ 실제 문서 인덱싱 E2E
→ Claim·Vector 검색 성능 측정
→ 환경 Fingerprint와 결과 기록
```

### 1.1 성공 기준

- Rocky Linux 9.7 x86-64, OpenSQL 17.8, pgvector 0.8.1을 자동 확인한다.
- 전용 Test Schema에 Flyway 전체 Migration과 Hibernate Validation이 성공한다.
- 제품 `embeddings.vector`가 `vector(1024)`이고 Cosine HNSW Index가 존재한다.
- Vector 저장, `vector_dims`, `<=>`, HNSW 실행 계획이 실제 DB에서 동작한다.
- `FOR UPDATE SKIP LOCKED`, 다중 Worker Claim, Lease 갱신·복구와 Retry가 통과한다.
- MinIO·BGE-M3 전체 문서 인덱싱 E2E가 공식 DB 연결에서도 통과한다.
- Claim과 Vector 검색의 처리량·p50·p95·p99와 Lock·Pool 대기를 기록한다.
- Secret, 설치 번들, 라이선스와 개인 절대 경로가 Git 또는 Test Log에 포함되지 않는다.

## 2. 범위

### 2.1 포함

- 공식 Host 지원 OS·Architecture·선언된 설치 모드 Preflight Script
- 공식 접속 환경 변수의 Fail-fast Validation
- 공식 DB 전용 Gradle Test Task와 집계 Task
- Flyway·Schema·Extension·Vector·HNSW Compatibility Test
- HNSW 실행 계획과 Vector 검색 지연 측정 Probe
- 기존 Claim 동시성·성능 Test의 공식 DB 재사용
- 기존 로컬 전체 관통 E2E의 공식 DB 재사용
- 실행 Runbook, 결과 Template, 로컬 기준선과 공식 실측 결과

### 2.2 제외

- OpenSQL 설치 자동화와 설치 파일 재배포
- 라이선스 발급·변경·복사 자동화
- HA, Patroni, etcd와 OpenProxy 구성
- 운영 Database 또는 운영 Schema 대상 Test
- 제품 Entity·API·검색 순위 정책 변경
- 절대 성능값을 모든 장비에 적용하는 SLO 확정

## 3. 안전 경계

### 3.1 Git 비반입

다음은 Source, 문서, Test Log와 PR에 절대 기록하지 않는다.

- OpenSQL 설치 압축·압축 해제 파일
- 라이선스 XML과 라이선스 내용
- 다운로드 URL·비밀번호·메일 원문
- DB Password, IP, SSH Key와 내부 Host 식별자

`.gitignore`의 공급사 번들 보호 패턴을 유지하고 새 파일은 추가하지 않는다.

### 3.2 접속 Fail-fast

공식 Task는 다음 환경 변수가 모두 존재할 때만 Test JVM을 시작한다.

```text
OPENSQL_DB_HOST
OPENSQL_DB_PORT
OPENSQL_DB_NAME
OPENSQL_DB_USER
OPENSQL_DB_PASSWORD
OPENSQL_DB_SSLMODE
OPENSQL_MINIO_ENDPOINT
OPENSQL_MINIO_ACCESS_KEY
OPENSQL_MINIO_SECRET_KEY
OPENSQL_EMBEDDING_SERVER_URL
```

Gradle은 Database 값은 기존 Test Profile의 `DB_*`로, 전체 관통 E2E 값은 MinIO·Embedding Server
환경 변수로 전달한다. 명령행 Argument나 결과 문서에는 값을 출력하지 않는다. Password·Secret 또는
외부 Service 주소가 없거나 빈 값이면 Connection 시도 전에 실패한다.

### 3.3 Schema 격리

- 각 Test Class가 `docgrid_opensql_*_test` Schema를 사용한다.
- `public`, Application 운영 Schema 또는 기존 개발 Schema를 변경하지 않는다.
- Flyway가 전용 Schema만 생성·Migration한다.
- 종료 시 해당 Test Schema만 `CASCADE` 삭제한다.
- `KEEP_*_SCHEMA=true`를 명시한 수동 진단 실행만 Schema를 보존한다.

## 4. 실행 구조

### 4.1 Host 지원 환경 호환성 Preflight

Rocky Host에서 Script를 실행해 다음을 확인한다.

1. `/etc/os-release`의 ID가 `rocky`, VERSION_ID가 `9.7`이다.
2. `uname -m`이 `x86_64`다.
3. OpenSQL은 Single 구성으로 동작한다.
4. DB 연결 뒤 `server_version`이 `17.8`로 시작한다.
5. `vector` Extension Version이 `0.8.1`이다.

OS·Architecture가 다르면 로컬 기준선은 실행할 수 있어도 공식 검증은 즉시 실패한다. 이 Script만으로
OpenSQL 제품 식별, License 적용 또는 실제 Single Topology를 판정하지 않고 공급사 설치 기록으로 별도
확인한다.

### 4.2 Gradle Task

| Task | 역할 |
|---|---|
| `openSqlCompatibilityTest` | Migration, Schema, Vector, HNSW, SKIP LOCKED, 검색 지연 |
| `openSqlClaimConcurrencyTest` | 기존 다중 Worker Claim·Lease 정합성 |
| `openSqlDocumentE2eTest` | 기존 MinIO·BGE-M3 전체 문서 인덱싱 E2E |
| `openSqlClaimPerformanceTest` | 기존 Claim 처리량·경합 Benchmark |
| `openSqlVerification` | 위 네 Task 전체 집계 |

모든 Task는 공식 환경 변수를 같은 방식으로 검증·전달하며 일반 `test`와 CI에서는 실행되지 않는다.

## 5. Compatibility Test

### 5.1 Environment Fingerprint

- `current_database`, `current_schema`
- `server_version`, `server_version_num`
- pgvector Extension Version
- Flyway 성공 Migration과 최신 Version
- Product Vector Column Type
- Product HNSW Index Definition

Fingerprint는 Password·Host·IP·Username 없이 구조·Version만 Log로 남긴다.

### 5.2 Vector·HNSW Probe

1. 전용 Schema에 `vector(1024)` Probe Table을 만든다.
2. 고정 Seed로 정규화한 2,000개 Vector를 Batch Insert한다.
3. Cosine HNSW Index를 만들고 `ANALYZE`한다.
4. 동일 Seed Query Vector로 `<=>` Top-K를 30회 실행한다.
5. Warm-up을 제외하고 p50·p95·p99와 최대 지연을 계산한다.
6. `EXPLAIN`에서 HNSW Index Scan이 선택되는지 확인한다.
7. 거리 값이 유한하고 오름차순인지 확인한다.

절대 지연값은 Hardware 의존성이 있으므로 자동 실패 기준으로 삼지 않는다. Index 미사용, 오류, 비유한
거리와 결과 순서 위반만 실패시킨다.

### 5.3 SKIP LOCKED Probe

두 독립 Connection을 사용한다.

1. 첫 Connection이 Queue Row를 `FOR UPDATE`로 잠근다.
2. 둘째 Connection이 `FOR UPDATE SKIP LOCKED`로 같은 Queue를 조회한다.
3. 둘째 조회가 대기하지 않고 빈 결과를 반환하는지 확인한다.
4. 첫 Transaction Rollback 뒤 Row가 다시 조회되는지 확인한다.

## 6. 기존 검증 재사용

### 6.1 Claim·Lease

기존 PostgreSQL Test를 공식 DB 주소로 실행한다.

- ACTIVE Worker 100개 단일 Job 경쟁
- Worker 20개, Job 1,000개 Queue 소진
- Claim Token·소유권·LOCKED Event 단일성
- Lease 갱신 중 복구 차단과 갱신 중단 뒤 단일 복구

### 6.2 전체 문서 인덱싱

기존 `local-e2e` Test의 DB 연결만 공식 OpenSQL로 전환한다.

```text
PDF·DOCX HTTP 업로드
→ MinIO
→ Worker 자동 실행
→ BGE-M3 Batch
→ 공식 OpenSQL vector(1024)
→ INDEXED·current_version
→ Query Embedding·Cosine 검색
```

Embedding Provider 장애의 부분 저장 방지와 지연 재시도도 같은 공식 DB에서 재검증한다.

### 6.3 Claim Benchmark

기존 Benchmark의 기본 Profile을 그대로 사용할 수 있고, 사전 Smoke는 축소 Parameter로 실행한다.

```text
Warm-up Job = 100
측정 Job = 500
반복 = 2
Worker = 1, 10, 20
```

최종 공식 결과는 기본 Profile 또는 대회 제출에 합의한 고정 Profile로 다시 측정한다.

## 7. 결과 문서

`docs/test-results/gimin-#124-opensql-compatibility-performance.md`에 다음을 기록한다.

- 공개 가능한 환경 Version과 Architecture
- 실행한 Commit SHA와 명령 Template
- Test별 건수·시간·성공 여부
- Vector HNSW 실행 계획의 민감정보 제거 요약
- Vector 검색 p50·p95·p99·최대 지연
- Claim TPS·p50·p95·p99·Hikari·PG Lock 대기
- 로컬 PostgreSQL 기준선과 공식 OpenSQL 차이
- 미실행 또는 실패 항목과 재현 절차

공식 x86-64 실행 전 로컬 PostgreSQL 결과는 `LOCAL BASELINE`으로만 표시하고 공식 합격으로 쓰지 않는다.

## 8. 커밋 분할

1. `docs: #124 공식 OpenSQL 검증 설계 문서 추가`
2. `build: #124 공식 OpenSQL 검증 실행 경계 추가`
3. `test: #124 Vector·HNSW·SKIP LOCKED 호환성 검증 추가`
4. `docs: #124 공식 OpenSQL 검증 Runbook 추가`
5. `docs: #124 공식 OpenSQL 호환성·성능 결과 기록`

## 9. 완료 조건

- 일반 `./gradlew test`는 공식 환경 없이 계속 통과한다.
- 로컬 PostgreSQL 17.8 기준선에서 새 Compatibility Test가 통과한다.
- 공식 Rocky Linux 9.7 x86-64 OpenSQL 17.8에서 `openSqlVerification`이 통과한다.
- 공식 Claim·Vector 성능 실측과 실행 계획이 결과 문서에 기록된다.
- 공식 실행이 불가능하면 PR은 도구·Runbook까지 검증하되 공식 완료로 잘못 표시하지 않는다.
