# Issue #124 공식 OpenSQL 17.8 호환성·성능 검증 결과

## 1. 결과 요약

공급사가 제공한 OpenSQL 3.17.8.7 배포본을 Rocky Linux 9.7 x86-64 Single 구성에 설치하고 DocGrid의
Migration, Vector 저장·검색, Worker Claim·Lease, 실제 문서 인덱싱과 Claim 성능을 검증했다.

| 검증 항목 | 결과 |
|---|---|
| Rocky Linux 9.7·x86-64 지원 환경 호환성 사전 점검 | PASS |
| OpenSQL PostgreSQL 17.8·pgvector 0.8.1 | PASS |
| Flyway V1~V35·Hibernate Schema Validation | PASS |
| `vector(1024)`·Cosine HNSW·`<=>` 검색 | PASS |
| `FOR UPDATE SKIP LOCKED` | PASS |
| 다중 Worker Claim·Lease·자동 실행 | PASS, 10 tests |
| 실제 문서·BGE-M3·Vector 전체 관통 | PASS, 2 tests |
| 기본 Claim 성능 Profile | PASS, 25 measurements |
| 일반 Test 회귀 | PASS, 709 tests |
| 공급사 지원 VM·원격 Server 최종 인수 판정 | PENDING |

마지막 항목은 이번 실행이 Apple Silicon Host의 Docker에서 `linux/amd64`로 실행한 Container 검증이기
때문이다. 공식 배포 Binary와 Database 기능의 호환성은 확인했지만, Container는 Rocky Linux 9.7
x86-64 VM 또는 물리·원격 Server와 동일한 운영 경계가 아니다. 특히 아래 성능값은 제품 SLO나 최종
대회 제출 성능으로 사용하지 않고, 공급사 지원 Host에서 같은 Runbook을 한 번 더 실행해야 한다.

## 2. 공개 가능한 실행 환경

| 항목 | 값 |
|---|---|
| Database 배포본 | OpenSQL 3.17.8.7 |
| Database 호환 Version | PostgreSQL 17.8 |
| OS | Rocky Linux 9.7 (Blue Onyx) |
| Database Architecture | x86-64 |
| 설치 모드 | Single |
| pgvector | 0.8.1 |
| pgvectorscale | 0.9.0 |
| Database Container 자원 | 4 CPU, 6 GiB Memory |
| Application JVM | Java 17, Apple Silicon Host |
| DB 실행 경계 | Docker `linux/amd64` Emulation |
| Test 대상 Commit | `e98903202ed83c9784afb5d1908cda7938ca3799` |
| 실행 일자 | 2026-08-08 KST |

License, 설치 파일, 다운로드 정보, Database 접속 정보와 개인 절대 경로는 결과에 포함하지 않았다.
격리된 Local 검증 Network여서 TLS는 사용하지 않았으며 운영 연결 정책을 의미하지 않는다.

## 3. 설치와 사전 점검

### 3.1 설치 결과

공식 설치기의 Single Mode 설치는 완료됐고 etcd, Patroni와 OpenSQL PostgreSQL이 정상 기동했다.
검증 Database에는 Runbook의 사전 조건에 따라 관리자가 `vector` Extension을 생성했다. 그 후
Application 계정으로 실행한 지원 환경 호환성 Preflight가 다음 조건을 모두 확인했다. OpenSQL 제품,
License와 실제 Single Topology는 공급사 설치 기록으로 별도 확인했다.

```text
OS=Rocky Linux 9.7
architecture=x86_64
mode=single
server_version=17.8
pgvector=0.8.1
```

### 3.2 설치 중 확인한 공급사 설치기 주의점

1. 설치기가 참조한 `EL-9.6-x86_64` PGDG Repository RPM URL은 실행 시점에 404를 반환했다.
2. Rocky 기본·EPEL Repository의 SFCGAL은 설치기가 요구한 2.0 이상보다 낮았다.
3. 현재 공식 PGDG EL9 Repository를 등록하고 PGDG의 SFCGAL 2.2.0 Package를 설치한 뒤 설치가
   진행됐다.
4. 이는 DocGrid Source 변경이 아니라 검증 Host의 설치 사전 작업이며, 공급사 원본 설치기는
   수정하지 않았다.

실제 VM·원격 Server 설치 전 공급사에 Repository URL과 SFCGAL 선행 조건을 확인하는 것이 필요하다.

## 4. 호환성·Vector 검색 결과

`openSqlCompatibilityTest`의 3개 Test가 통과했다.

| 항목 | 결과 |
|---|---|
| Flyway 최신 Version | 35 |
| 제품 Vector Column | `vector(1024)` |
| Probe Vector | 2,000 rows |
| Query | 30회, Top-K 10 |
| Cosine HNSW Index Scan | 사용 확인 |
| Vector p50 | 4.207 ms |
| Vector p95 | 4.706 ms |
| Vector p99 | 5.087 ms |
| Vector max | 5.087 ms |
| `SKIP LOCKED` 잠금 건너뛰기 | 4 ms |
| 잠금 해제 후 재조회 | 성공 |

같은 Test를 Local PostgreSQL 17.8 + pgvector 0.8.1에서 실행한 기준선은 p50 2.086 ms, p95
2.344 ms, p99·max 3.875 ms였다. 공식 배포본의 이번 측정은 x86-64 Emulation과 Container Network를
포함하므로 두 값의 차이를 Database Engine만의 차이로 해석할 수 없다.

## 5. Claim·Lease 정합성 결과

`openSqlClaimConcurrencyTest`의 10개 Test가 통과했다.

- Worker 100개의 단일 Job 경쟁에서 Claim 한 건만 생성
- Worker 20개의 Queue 소진에서 중복 Claim 없음
- Claim Token·Worker 소유권·LOCKED Event 정합성 유지
- Lease 갱신 중 복구 차단과 갱신 중단 뒤 단일 복구
- Worker 자동 Polling·실행·Graceful Shutdown 계약 유지

## 6. 실제 문서 인덱싱 전체 관통 결과

`openSqlDocumentE2eTest`의 2개 Test가 통과했다.

```text
PDF·DOCX HTTP 업로드
→ MinIO 저장
→ Worker Claim·Attempt·Lease
→ Parsing·Chunk 저장
→ 실제 BGE-M3 Batch
→ OpenSQL vector(1024) 저장
→ INDEXED·current_version 전환
→ Query Embedding·Cosine 검색
```

Embedding Provider 장애 시 부분 Vector가 저장되지 않고 지연 재시도로 연결되는 시나리오도 통과했다.

## 7. Claim 성능 결과

### 7.1 기본 정식 Profile

- Warm-up: 500 Jobs
- 측정: Worker별 5,000 Jobs
- 반복: 5회
- Worker: 1, 5, 10, 20, 40
- Hikari Pool: 20

| Worker | 중앙 TPS | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | Hikari 대기 | PG Lock 대기 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 273.61 | 3.619 | 5.525 | 6.077 | 37.349 | 0 | 0 |
| 5 | 748.45 | 6.524 | 9.872 | 13.187 | 32.063 | 0 | 0 |
| 10 | 563.07 | 9.451 | 61.265 | 67.780 | 90.505 | 0 | 0 |
| 20 | 591.32 | 18.662 | 79.235 | 89.913 | 162.080 | 0 | 0 |
| 40 | 591.89 | 67.921 | 166.329 | 212.795 | 398.824 | 20 | 1 |

25회 측정 전체에서 Worker 오류, 불완전 소유권, 중복 Claim Token, 잘못된 LOCKED Event, Rollback과
Deadlock은 모두 0건이었다. 이번 환경에서는 Worker 5개가 가장 높은 처리량을 보였고, Worker 40개는
20개 Connection Pool 한계로 대기 수가 20까지 증가했다. Worker 수를 Pool보다 크게 늘리는 것은
처리량을 높이지 않고 지연만 증가시켰다.

### 7.2 Smoke Profile

동일 환경에서 500 Jobs, 2회 반복, Worker 1·10·20으로 먼저 실행한 Smoke도 통과했다. 중앙 TPS는
각각 387.77, 1,431.85, 1,047.78이었다. 짧은 실행은 Cache·초기 상태의 영향을 크게 받아 정식
Profile보다 높은 수치가 나왔으므로 최종 비교에는 기본 정식 Profile을 사용한다.

## 8. 전체 실행 결과

| Task | Test 수 | 결과 |
|---|---:|---|
| `openSqlCompatibilityTest` | 3 | PASS |
| `openSqlClaimConcurrencyTest` | 10 | PASS |
| `openSqlDocumentE2eTest` | 2 | PASS |
| `openSqlClaimPerformanceTest` | 1 | PASS |
| `openSqlVerification` | 위 4개 Task 집계 | PASS, 6m 59s |
| 일반 `test --rerun-tasks` | 709 | PASS, failure/error/skipped 0 |

일반 Test의 강제 재실행에는 Local DB의 `sslmode=disable`과 Test 전용 JWT Secret을 Process 환경으로만
주입했다. 실제 값은 Source, 결과 문서 또는 Git에 저장하지 않았다.

## 9. 남은 최종 인수 절차

공급사 지원 범위를 최종 충족하려면 Rocky Linux 9.7 x86-64 VM 또는 원격 Server에 같은 Single 구성을
설치하고 다음을 다시 실행한다.

1. `scripts/opensql/verify-host.sh`
2. `./gradlew openSqlVerification`
3. 이 문서의 Container 결과와 VM·Server 결과를 분리해 기록
4. 운영에 가까운 CPU·Memory·Network 조건으로 성능값 재측정

이 재실행 전까지 본 결과는 공식 OpenSQL 배포본의 기능 호환성 증거이며, 공급사 지원 운영 Host의
최종 인수 완료 증거는 아니다.
