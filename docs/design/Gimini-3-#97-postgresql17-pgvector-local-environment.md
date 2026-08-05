# #97 PostgreSQL 17 및 pgvector 0.8.1 로컬 실행 환경 지원 추가 구현

## 1. 배경

현재 로컬 데이터베이스 실행 경로는 OpenSQL/PostgreSQL 14.6에 강하게 결합되어 있다.

- `docker/opensql/Dockerfile`은 `tmaxopensql/postgres:14.6`을 기반으로 pgvector 0.8.0을 컴파일한다.
- `docker/opensql/init-and-start.sh`은 `/usr/pgsql-14`와 `/var/lib/pgsql/14/data`를 사용한다.
- `docker-compose.yml`은 `linux/amd64`를 강제하고 `opensql_data` 볼륨을 사용한다.
- Local/Test Datasource의 기본 `sslmode=require`는 SSL을 활성화하지 않은 로컬 컨테이너와 맞지 않는다.
- Claim 성능 Benchmark는 PostgreSQL 14.6을 실행 전제조건으로 고정한다.
- README는 더 이상 사용하는 로컬 기준이 아닌 OpenSQL 14.6 실행 절차를 안내한다.

공식 OpenSQL 17.8 설치 환경은 Rocky Linux 9.7 x86-64 단일 서버다. macOS arm64 개발 환경에서
공급사 설치 파일을 직접 실행하는 대신, 로컬 개발은 PostgreSQL 17 + pgvector 0.8.1로 표준화한다.
공식 OpenSQL 호환성은 별도 원격 환경에서 같은 검증 SQL과 애플리케이션 회귀 시나리오로 확인한다.

## 2. 목표

1. macOS arm64와 x86-64 개발 환경에서 동일한 로컬 DB 구성을 실행한다.
2. PostgreSQL 17과 pgvector 0.8.1 버전을 재현 가능하게 고정한다.
3. 기존 JDBC, Flyway, `vector(1024)`, HNSW 검색 계약을 유지한다.
4. PostgreSQL 14 데이터 볼륨을 PostgreSQL 17에서 잘못 재사용하지 않는다.
5. 공급사 설치 파일, 라이선스, 다운로드 정보와 비밀정보가 Git 및 Docker Build Context에 유입되지 않게 한다.
6. 로컬 PostgreSQL과 공식 OpenSQL 원격 환경의 공통 검증 기준을 문서화한다.

## 3. 환경 분리 결정

| 구분 | 로컬 개발 | 공식 최종 검증 |
| --- | --- | --- |
| DBMS | PostgreSQL 17 | OpenSQL 17.8 |
| Vector Extension | pgvector 0.8.1 | pgvector 0.8.1 |
| 실행 환경 | Docker Desktop, host architecture | Rocky Linux 9.7 x86-64 Single |
| 설치 파일 | 공개 pgvector Docker Image | 공급사 제공 파일, Repository 외부 보관 |
| 데이터 | 새 PostgreSQL 17 전용 Volume | 원격 검증용 격리 Database |
| 목적 | 반복 개발 및 회귀 테스트 | 공식 호환성 확인 |

로컬 기본 이미지는 공개 공식 이미지 `pgvector/pgvector:0.8.1-pg17`을 사용한다. 이미지가 제공하는
PostgreSQL 공식 Entry Point를 그대로 사용하므로 공급사 전용 시작 Script와 PostgreSQL 14 경로가
필요하지 않다.

## 4. 로컬 컨테이너 설계

### 4.1 PostgreSQL Service

- `docker-compose.yml`에서 Custom Build 대신 `pgvector/pgvector:0.8.1-pg17`을 직접 사용한다.
- `platform: linux/amd64`를 제거해 Image Manifest가 Host Architecture를 선택하도록 한다.
- 기존 기본 접속 계약을 유지한다.
  - Host: `localhost`
  - Port: `55432`
  - Database: `app`
  - User: `app`
  - Password: 환경변수 `DB_PASSWORD`, 로컬 기본값만 Compose에서 제공
- Health Check는 PostgreSQL 표준 `pg_isready`를 사용한다.
- MinIO, Embedding Server, Ollama Service는 수정하지 않는다.

### 4.2 Vector Extension 초기화

`docker/postgres/init/001-enable-vector.sql`을 `/docker-entrypoint-initdb.d/`에 Read-only로
Mount한다.

~~~sql
CREATE EXTENSION IF NOT EXISTS vector;
~~~

PostgreSQL 공식 Entry Point는 새 Data Directory를 초기화할 때 이 SQL을 대상 Database에서 실행한다.
따라서 Spring Boot가 기동해 Flyway V32를 적용하기 전에 `vector` Type과 HNSW Access Method가
준비된다.

### 4.3 데이터 볼륨

- PostgreSQL 17은 새 Named Volume `docgrid_postgres17_data`를 사용한다.
- Mount 경로는 `/var/lib/postgresql/data`다.
- 기존 `opensql_data`는 자동 삭제, Mount 또는 In-place Upgrade하지 않는다.
- 기존 데이터 이전이 필요하면 `pg_dump`/`pg_restore` 기반의 별도 작업으로 분리한다.

새 Volume을 사용하는 이유는 PostgreSQL Major Version이 다른 Data Directory의 직접 재사용을
차단하고, Rollback 시 기존 PostgreSQL 14 데이터를 보존하기 위해서다.

## 5. 애플리케이션 연결 설계

### 5.1 Local Profile

`application-local.yml`의 기본 `DB_SSLMODE`를 `disable`로 변경한다. 로컬 Docker Network는
SSL을 활성화하지 않으므로 기본 실행이 실제 컨테이너 설정과 일치해야 한다. 환경변수 Override 계약은
유지한다.

### 5.2 Test Profile

`application-test.yml`도 기본 `DB_SSLMODE=disable`을 사용한다. 기존 격리 Schema와
`public` Search Path는 유지한다.

~~~text
currentSchema={TEST_DB_SCHEMA},public
~~~

격리 Schema에는 Flyway Table과 애플리케이션 Table을 생성하고, `public`은 pgvector Extension이
제공하는 `vector` Type과 Operator를 찾는 용도로 사용한다.

### 5.3 Production Profile

`application-prod.yml`의 `PROD_DB_URL`, `PROD_DB_USERNAME`, `PROD_DB_PASSWORD` 계약은
변경하지 않는다. 운영 SSL Mode는 운영 URL에서 명시한다.

## 6. Schema 및 검색 호환성

이번 작업은 Flyway Migration을 추가하거나 이미 적용된 Migration을 수정하지 않는다.

- `embeddings.vector`: `vector(1024) NOT NULL`
- `search_queries.query_vector`: `vector(1024)`
- `embeddings.vector`: `vector_cosine_ops` 기반 HNSW Index
- Java Mapping: 기존 `float[]`과 PostgreSQL `PGobject` 변환 유지
- 검색: 기존 `<=>` Cosine Distance Query 유지

PostgreSQL 17 및 pgvector 0.8.1에서 기존 계약이 그대로 동작하는지는 실제 Database를 사용해 검증한다.

## 7. Benchmark 환경 가드

`EmbeddingJobClaimPerformanceBenchmark` 실행 전 다음을 검증한다.

1. 전용 Test Schema 사용
2. Benchmark 전용 PostgreSQL Application Name 사용
3. `SHOW server_version` 결과가 17 계열
4. `vector` Extension Version이 정확히 0.8.1
5. Hikari Pool Size와 MXBean 준비

성능 합격 기준 자체는 변경하지 않는다. 환경 전환 후의 실제 결과는 새 Test Result 문서에 기록해
이전 PostgreSQL 14.6 결과와 환경 Fingerprint를 구분한다.

## 8. API 및 상태 계약

이 작업에서 REST API, 요청/응답 DTO, 인증·권한, Domain Entity 상태 전이는 변경하지 않는다.
DB Runtime과 개발 환경만 교체한다.

## 9. 공급사 파일 및 보안 경계

- OpenSQL 설치 Archive, 압축 해제 비밀번호, 다운로드 URL과 라이선스 XML은 Repository 내부에 두지 않는다.
- 공급사 파일은 Repository 외부 접근 제한 디렉터리 또는 원격 Rocky Linux 서버에서만 관리한다.
- 공급사 파일을 Docker Build Context에 복사하거나 Mount하지 않는다.
- 실제 운영 DB Password와 라이선스 정보는 문서, Commit, Log와 Test Result에 기록하지 않는다.
- Repository 내부에서 공급사 파일 유입이 감지되면 작업을 중단하고 추적 여부부터 확인한다.

## 10. 변경 대상

| 영역 | 변경 |
| --- | --- |
| 설계 | 이 문서 추가 |
| Container | `docker-compose.yml`, `docker/postgres/init/001-enable-vector.sql` |
| 기존 Container | `docker/opensql/`의 로컬 기본 구성 제거 |
| Application | `application-local.yml`, `application-test.yml` |
| Benchmark | `EmbeddingJobClaimPerformanceBenchmark` |
| 운영 문서 | `README.md`, `docs/local-db.md` |
| 검증 기록 | `docs/test-results/Gimini-3-#97-postgresql17-pgvector-local-environment.md` |

과거 `docs/test-results/` 문서는 당시 실행 사실을 보존해야 하므로 소급 수정하지 않는다.

## 11. 검증 계획

### 11.1 정적 검증

~~~bash
docker compose config
git status --short --ignored
rg '14\.6|pgsql-14|opensql_data|linux/amd64' docker-compose.yml docker README.md src/main src/test
~~~

### 11.2 Database 검증

~~~sql
SHOW server_version;
SELECT extversion FROM pg_extension WHERE extname = 'vector';

SELECT format_type(a.atttypid, a.atttypmod)
FROM pg_attribute a
JOIN pg_class c ON c.oid = a.attrelid
WHERE c.relname = 'embeddings'
  AND a.attname = 'vector';

SELECT indexdef
FROM pg_indexes
WHERE tablename = 'embeddings'
  AND indexdef ILIKE '%USING hnsw%';
~~~

### 11.3 애플리케이션 회귀 검증

- `./gradlew test`
- 실제 PostgreSQL 기반 Claim 동시성 통합 테스트
- 문서 인덱싱 완료·실패·Lease 복구·Worker Polling 통합 테스트
- Vector 저장 및 검색 통합 테스트
- Claim Benchmark 환경 사전검증과 Smoke 실행
- Local Profile Application 기동 및 Hibernate `ddl-auto=validate`

## 12. Rollback

1. Application과 PostgreSQL 17 Container를 중지한다.
2. Compose와 Local/Test 설정 Commit을 Revert한다.
3. 기존 PostgreSQL 14 `opensql_data`는 삭제하지 않고 이전 구성에서 다시 Mount한다.
4. PostgreSQL 17 전용 Volume은 명시적인 사용자 확인 없이 삭제하지 않는다.
5. 두 환경 간 데이터 자동 변환을 시도하지 않는다.

## 13. 커밋 분리

1. 상세 설계 문서
2. PostgreSQL 17 + pgvector 0.8.1 Container Runtime
3. Local/Test 연결 및 Benchmark 환경 가드
4. 로컬 실행 및 공식 OpenSQL 원격 검증 Runbook
5. 실제 회귀 검증 결과

## 14. 완료 조건

- PostgreSQL 17 + pgvector 0.8.1 로컬 DB가 Host Architecture 강제 없이 기동된다.
- PostgreSQL 14 볼륨을 재사용하거나 삭제하지 않는다.
- Vector Extension이 Flyway보다 먼저 준비된다.
- Flyway 전체 Migration과 Hibernate Schema Validation이 통과한다.
- `vector(1024)` 저장과 HNSW Index가 유지된다.
- Local/Test 연결 기본값이 로컬 컨테이너와 일치한다.
- Production Datasource 계약은 바뀌지 않는다.
- 공급사 파일과 비밀정보가 Git 및 Docker Build Context에 포함되지 않는다.
- 전체 테스트와 실제 PostgreSQL 회귀 검증 결과가 기록된다.

Closes #97
