# Issue #124 공식 OpenSQL 17.8 호환성·성능 검증 Runbook

## 1. 목적

이 문서는 Rocky Linux 9.7 x86-64 Single 구성의 공식 OpenSQL 17.8에서 DocGrid DB 호환성, 문서
인덱싱 E2E와 성능 측정을 재현하는 절차다. 일반 PostgreSQL 17.8 실행은 로컬 기준선일 뿐 공식 결과가
아니다.

## 2. 사전 조건

### 2.1 공식 Host

| 항목 | 필수 값 |
|---|---|
| OS | Rocky Linux 9.7 |
| Architecture | x86-64 |
| OpenSQL | PostgreSQL 17.8 기반 공식 배포본 |
| pgvector | 0.8.1 |
| 설치 모드 | Single |
| License | 해당 Hostname·CPU 조건으로 발급된 유효 License |

Apple Silicon의 일반 UTM Virtualization VM은 `aarch64`일 수 있다. `uname -m`이 `x86_64`가 아니면
공식 판정에 사용하지 않는다. Hostname 또는 CPU Topology 변경이 필요한 경우 기존 License를 임의로
재사용하지 않고 공급사 재발급 절차를 따른다.

### 2.2 저장소 밖 보관

다음 파일은 Repository, 작업 Branch, PR 첨부와 Test 결과 폴더로 복사하지 않는다.

- OpenSQL 설치 압축과 압축 해제 디렉터리
- License XML
- 공급사 다운로드 URL·비밀번호
- SSH Key와 DB 접속 Secret

공식 설치와 License 적용은 공급사 통합 설치 가이드의 Single 절차로 완료한 상태여야 한다. 이
Runbook은 설치 자체를 자동화하지 않는다.

### 2.3 검증 전용 Database 권한

검증 계정은 운영 계정과 분리하고 다음 권한만 준비한다.

- 검증 Database 연결
- `docgrid_opensql_*_test` Schema 생성·삭제
- Schema 안 Table·Index·Sequence 생성
- `vector` Extension 조회와, 초기 설치 시 필요한 경우 관리자에 의한 Extension 생성
- `pg_stat_activity`, `pg_locks`, `pg_stat_database` 성능 관측

운영 Database나 운영 Schema에서는 실행하지 않는다.

## 3. 환경 변수 준비

값은 Shell Session에만 주입한다. `.env`, Markdown, Shell History와 CI Log에 실제 Password를 추가하지
않는다.

```bash
export OPENSQL_DB_HOST=<official-db-host>
export OPENSQL_DB_PORT=<official-db-port>
export OPENSQL_DB_NAME=<verification-database>
export OPENSQL_DB_USER=<verification-user>
read -s OPENSQL_DB_PASSWORD
export OPENSQL_DB_PASSWORD
export OPENSQL_DB_SSLMODE=require
export OPENSQL_INSTALL_MODE=single
```

공식 환경이 TLS를 제공하지 않는 격리 Network라면 공급사 설정을 확인한 뒤 `OPENSQL_DB_SSLMODE`만
조정한다. Password와 Host 값은 성공 출력에 포함되지 않는다.

## 4. Rocky Host Preflight

이 단계는 Application을 실행하는 macOS가 아니라 OpenSQL이 설치된 Rocky Host에서 수행한다.

```bash
scripts/opensql/verify-host.sh
```

정상 출력 예시는 Version과 공개 가능한 환경 정보만 포함한다.

```text
OpenSQL 공식 Host preflight 통과
OS=Rocky Linux 9.7, architecture=x86_64, mode=single
server_version=17.8..., pgvector=0.8.1
```

다음 중 하나라도 다르면 공식 Test를 실행하지 않는다.

- Rocky 9.7이 아님
- x86_64가 아님
- Single 모드가 아님
- `server_version`이 17.8로 시작하지 않음
- pgvector가 없거나 0.8.1이 아님

## 5. Application 측 연결 점검

공식 Host에서 직접 Gradle을 실행하거나, 접근 제한 Network에서는 승인된 SSH Tunnel을 사용한다.

```bash
ssh -N -L <local-port>:127.0.0.1:<db-port> <rocky-host>
```

Tunnel을 사용할 때 `OPENSQL_DB_HOST=127.0.0.1`, `OPENSQL_DB_PORT=<local-port>`로 현재 Shell만
변경한다. SSH Host, IP와 Key 경로는 결과 문서에 기록하지 않는다.

환경 변수 누락 차단은 DB 접속 전에 확인할 수 있다.

```bash
env -u OPENSQL_DB_HOST ./gradlew openSqlCompatibilityTest
```

이 명령은 `OPENSQL_DB_HOST` 누락 오류로 실패해야 하며 DB Connection을 시도하지 않는다.

## 6. 단계별 검증

### 6.1 Migration·Vector·HNSW·SKIP LOCKED

```bash
./gradlew openSqlCompatibilityTest
```

기대 결과:

- 3 tests passed
- Flyway V35와 모든 Migration 성공
- `vector(1024)`, pgvector 0.8.1, Cosine HNSW Index 확인
- 2,000개 Vector Insert와 Top-K 30회 성공
- HNSW Index Scan 실행 계획 확인
- `SKIP LOCKED` 비대기와 Lock 해제 뒤 재조회 성공

공개 가능한 Log Marker:

```text
OPENSQL_COMPATIBILITY_ENV
OPENSQL_VECTOR_RESULT
OPENSQL_SKIP_LOCKED_RESULT
```

### 6.2 다중 Worker Claim·Lease

```bash
./gradlew openSqlClaimConcurrencyTest
```

기대 결과:

- ACTIVE Worker 100개가 단일 Job에 경쟁해도 Claim 한 건
- Worker 20개가 Job 1,000개를 중복 없이 소진
- Claim Token·Worker 소유권·LOCKED Event 단일성
- Lease 갱신 중 복구 차단, 중단 뒤 단일 복구

### 6.3 실제 문서 인덱싱 E2E

로컬 MinIO와 현재 Source의 BGE-M3 Batch Server를 먼저 기동한다.

```bash
docker compose up -d minio embedding-server
./gradlew openSqlDocumentE2eTest
```

DB만 공식 OpenSQL을 사용하며 MinIO Bucket과 Test Schema는 실행마다 격리된다.

- PDF·DOCX 실제 HTTP 업로드
- Worker 자동 Claim·Attempt·Lease
- BGE-M3 Batch와 Query Embedding
- 공식 DB의 `vector(1024)` 저장과 Cosine 검색
- 관리자 Job·Attempt·Event 인증 HTTP
- Provider 장애와 부분 Vector 방지·지연 재시도

### 6.4 Claim 성능 Smoke

최종 장시간 측정 전에 축소 Profile로 환경과 권한을 점검한다.

```bash
./gradlew openSqlClaimPerformanceTest \
  -Dclaim.performance.warm-up-jobs=100 \
  -Dclaim.performance.job-count=500 \
  -Dclaim.performance.repetitions=2 \
  -Dclaim.performance.workers=1,10,20
```

### 6.5 전체 공식 검증

```bash
./gradlew openSqlVerification
```

이 Task는 Compatibility, Claim 정합성, 문서 E2E와 기본 Claim Benchmark를 순서대로 실행한다. 기본
Benchmark는 장시간·대량 Data를 사용하므로 공식 측정 시간과 DB 자원을 확보한 뒤 실행한다.

## 7. 결과 수집

결과 문서에는 다음만 기록한다.

- Rocky Version, x86-64, Single 모드
- OpenSQL `server_version`, pgvector Version
- Commit SHA
- Test Suite별 건수·실행 시간·결과
- Vector 행 수·질의 수·p50·p95·p99·max
- HNSW Index Scan 사용 여부
- Claim Worker·Job·반복 수, TPS·p50·p95·p99
- Hikari 대기와 PostgreSQL Lock 대기 최대값
- Deadlock·Rollback과 정합성 판정

다음은 삭제하거나 `<redacted>`로 치환한다.

- Host·IP·Database Username
- JDBC URL
- Password·Token·License 내용
- SSH Command의 실제 Host와 Key 경로

## 8. Schema 정리

정상 종료 시 Test가 자신의 Schema를 자동 삭제한다. JVM 강제 종료 등으로 남은 경우 정확한 대상 이름을
먼저 조회하고 검증 계정으로 해당 Schema만 제거한다.

```sql
SELECT nspname
FROM pg_namespace
WHERE nspname LIKE 'docgrid_opensql_%_test';
```

예상하지 않은 이름이 함께 조회되면 삭제하지 않는다. 공식 결과 보존을 위해 `KEEP_*_SCHEMA=true`를
사용했다면 결과 수집 뒤 같은 원칙으로 수동 정리한다.

## 9. 로컬 기준선

공식 접속 전에 같은 Task를 PostgreSQL 17.8 + pgvector 0.8.1 로컬 DB에 연결해 Test Code 자체를
검증할 수 있다. 이 결과는 반드시 `LOCAL BASELINE`으로 표시한다.

```bash
OPENSQL_DB_HOST=<local-host> \
OPENSQL_DB_PORT=<local-port> \
OPENSQL_DB_NAME=<local-database> \
OPENSQL_DB_USER=<local-user> \
OPENSQL_DB_PASSWORD=<local-password> \
OPENSQL_DB_SSLMODE=disable \
./gradlew openSqlCompatibilityTest
```

로컬 기준선 통과는 Rocky Linux 9.7 x86-64, 공급사 OpenSQL Binary와 License 검증을 대신하지 않는다.

## 10. Git 안전 확인

공식 실행 전후에 작업 Tree를 확인한다.

```bash
git status --short
git diff --check
```

설치 번들, License, `.env` 또는 결과 원시 Log가 표시되면 Stage·Commit하지 않는다. 이 작업의 Git
변경은 Source Test, 실행 경계와 민감정보를 제거한 Markdown 결과만 포함해야 한다.
