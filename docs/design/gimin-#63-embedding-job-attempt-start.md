# Embedding Job Attempt 시작 기록 설계

Closes #63

## 1. 목표

Worker가 Claim한 Embedding Job의 실제 처리를 시작할 때 실행 시도 이력 한 건을 생성한다.

`embedding_jobs`는 현재 Queue 상태와 현재 소유권만 나타낸다. 반면 `embedding_job_attempts`는 같은 Job이
어떤 Worker와 Claim 세대에서 몇 번 실행됐는지를 시간순으로 보존한다. 이 기능의 운영 결과는 다음과
같다.

- 유효한 `PROCESSING` Job의 현재 소유자만 Attempt를 시작할 수 있다.
- 하나의 Claim Token에는 Attempt가 최대 한 건만 연결된다.
- 새 Claim Token에서만 Job 기준 다음 `attempt_no`가 할당된다.
- 같은 시작 요청이 재전송되면 새 Attempt를 만들지 않고 기존 Attempt를 반환한다.
- 첫 Attempt는 1번이며 이후 Attempt는 Job별로 단조 증가한다.
- 시작된 Attempt는 `STARTED`, 실행 Worker, Claim Token, 시작 시각을 보존한다.
- Worker는 응답으로 받은 Attempt ID를 이후 파싱·임베딩·완료·실패 흐름의 실행 식별자로 유지한다.

설계의 성공 기준은 정상 생성뿐 아니라 잘못된 Worker, 오래된 Claim Token, 만료 Lease, 중복 요청,
동시 요청이 Attempt 이력을 오염시키지 않는 것이다.

## 2. 비범위

- 원본 파일 다운로드와 텍스트 파싱
- Chunk 계산과 `document_chunks` 저장
- Embedding Server 호출과 `embeddings` 저장
- `DocumentVersion`의 파싱·청킹·임베딩 상태 전환
- Attempt의 `SUCCESS`, `FAILED`, `TIMED_OUT`, `ABANDONED` 전환
- Job 완료·실패와 자동 재시도
- 만료 Lease 회수와 새 Worker 재Claim
- Lease 연장과 Worker 자동 Polling
- Attempt 목록·상세 조회 API
- 일반 사용자용 문서 상태 응답에 Attempt 정보 노출
- Worker 전용 Machine Credential 도입
- `IndexingEventType` 확장과 별도 Attempt 시작 이벤트 추가
- Micrometer, Actuator 또는 외부 Monitoring Backend 도입
- 기존 Attempt 데이터에 알 수 없는 과거 Claim Token을 임의로 생성하는 Backfill

## 3. 현재 기준선

### 3.1 설계 기준

설계 기준은 구현 시작 시점 `origin/develop`의 `bd441f3`이다.

Claim 성능 Benchmark와 결과 문서는 이 기준선에 병합돼 있다. 해당 변경은 Production Service,
Entity, Repository, Migration, API, 설정을 바꾸지 않으므로 이 기능의 런타임 계약에는 포함하지
않는다. Benchmark가 확인한 Worker 수별 처리량과 정합성은 회귀 검증에 참고하지만 Attempt 기능의
선행 배포 조건은 아니다.

### 3.2 이미 구현된 Claim 계약

다음 구성요소는 `develop`에 구현돼 있으며 그대로 재사용한다.

| 구성요소 | 현재 계약 |
| --- | --- |
| `EmbeddingJobClaimService` | Worker 검증, PENDING Job 잠금, PROCESSING 전환, Lease와 Claim Token 발급을 하나의 Transaction으로 처리 |
| `EmbeddingJobRepository` | 우선순위 PENDING Job을 `FOR UPDATE SKIP LOCKED` 방식으로 한 건 선택 |
| `EmbeddingJob` | 현재 상태, 소유 Worker, Claim Token, Lease 시작·만료 시각, 최초 Job 시작 시각 보존 |
| `IndexingJobAdminController` | `/admin/indexing-jobs` 아래 관리자용 Claim API 제공 |
| `SecurityConfig` | `/admin/**` 전체를 ADMIN 역할로 보호 |
| `WorkerConfig` | 서비스에서 결정적인 시간 검증에 재사용할 `Clock` Bean 제공 |
| `ErrorCode`와 `GlobalExceptionHandler` | 비즈니스 오류와 입력·DB 오류를 공통 응답으로 변환 |

Claim 성공 후 Attempt 시작이 허용될 수 있는 Job은 다음 소유권 정보를 가진다.

| 필드 | 현재 의미 |
| --- | --- |
| `status` | `PROCESSING` |
| `locked_by_worker_id` | 현재 Claim 소유 Worker |
| `claim_token` | 현재 Claim 세대의 UUID |
| `locked_at` | 현재 Lease 시작 시각 |
| `lock_expires_at` | 현재 Lease 만료 시각 |
| `started_at` | Job 전체의 최초 처리 시작 시각 |

### 3.3 이미 존재하는 Attempt 스키마와 Domain 객체

`V19__create_embedding_job_attempts.sql`과 `EmbeddingJobAttempt`가 이미 존재한다. 따라서 테이블과
Entity를 새로 만들지 않는다.

현재 Attempt에는 다음 필드가 있다.

| 필드 | 현재 상태 |
| --- | --- |
| Job 연관 | 필수 |
| Worker 연관 | DB와 Entity Mapping에서 nullable |
| `attempt_no` | 필수 |
| `status` | 필수 |
| `started_at` | 필수 |
| 종료 시각·처리 시간·오류 | nullable |
| Claim Token | 없음 |

현재 DB에는 `(embedding_job_id, attempt_no)` Unique Constraint와 Worker·상태 Index가 있다.

`AttemptStatus`에는 `STARTED`, `SUCCESS`, `FAILED`가 있다. Entity에는 성공·실패 종료용 상태 변경
메서드가 있지만 이를 호출하는 Repository, Service, API는 아직 없다. 저장소 전체 검색 결과
`embedding_job_attempts`에 쓰는 Production 경로도 없다.

### 3.4 재사용할 기존 패턴

- Command Service의 클래스 수준 Transaction
- Controller, Service, Repository의 단방향 호출
- Request·Response record와 Converter를 통한 Entity 비노출
- `Clock`을 주입한 결정적 시각 계산
- `ResponseUtils` 기반 공통 성공 응답
- `DocGridException`과 `ErrorCode` 기반 오류 매핑
- 실제 OpenSQL과 격리 Test Schema를 사용하는 통합 테스트
- 독립 Thread와 Transaction으로 경쟁을 재현하는 동시성 테스트

## 4. 가정과 결정 사항

### 4.1 확인된 가정

- Claim 성공 시 Job은 `PROCESSING`이며 현재 Worker, UUID Claim Token, 미래 Lease 만료 시각을 가진다.
- Attempt 시작 API는 현재 Claim 응답을 받은 내부 Worker 실행 흐름에서 호출한다.
- 현재 배포 코드에는 Attempt Row를 생성하는 Production 경로가 없다.
- API는 현재 구조와 동일하게 ADMIN 보호 내부 API로 유지한다.
- PostgreSQL이 Transaction과 행 잠금의 최종 동시성 제어 지점이다.
- Job별 Attempt 수는 한 번에 조회 가능한 수준이며 기존 복합 Unique Index를 최신 번호 조회에 활용한다.

### 4.2 로드맵과 현재 구현의 차이

| 항목 | 초기 제안 | 현재 저장소 기준 설계 |
| --- | --- | --- |
| API Prefix | `/api/admin` | 기존 Controller 계약인 `/admin` 유지 |
| Attempt 테이블·Entity | 구현 대상으로 표현 | V19와 Entity가 이미 있으므로 수정·재사용 |
| Worker 조회 | 별도 `WorkerNodeRepository` 사용 제안 | 잠근 Job의 현재 소유 Worker를 기준으로 검증하므로 추가 조회하지 않음 |
| Attempt 번호 | 최대 번호에 1을 더함 | Job 행 잠금 안에서 계산하고 DB Unique Constraint를 최종 방어선으로 사용 |
| 중복 요청 | Unique Constraint로 충돌 방어 | Claim Token을 멱등성 키로 저장하고 같은 Claim의 재전송은 기존 Attempt 반환 |
| Attempt 상태 | 기반 설계에 `TIMED_OUT`, `ABANDONED` 포함 | 현재 Enum은 세 상태뿐이며 이번 범위에서는 `STARTED`만 사용 |
| Job 재시도 상태 | 기반 설계에 `RETRY_PENDING` 존재 | 현재 `EmbeddingJobStatus`에는 없으며 이번 기능에서 추가하지 않음 |

현재 코드와 직접 충돌하는 이름·상태를 로드맵 표현에 맞추기 위해 되돌리지 않는다.

### 4.3 Claim Token을 Attempt에 저장하는 결정

두 가지 선택지가 있다.

| 선택지 | 장점 | 문제 |
| --- | --- | --- |
| Attempt 번호 제약만 재사용 | Schema 변경이 작음 | 같은 Claim 요청을 순차 재전송하면 새 Attempt 번호가 생길 수 있고, 같은 Worker의 재Claim 세대를 구분할 수 없음 |
| Attempt에 Claim Token 저장 | Claim 세대 추적, 같은 요청 멱등 처리, 후속 stale 결과 분석 가능 | Migration과 민감값 관리가 추가됨 |

권장안은 Attempt에 Claim Token을 저장하는 것이다.

신규 Attempt는 Job ID와 Claim Token의 조합으로 유일해야 한다. Claim Token은 API 응답, 일반 로그,
이벤트 메시지에는 다시 노출하지 않는다. 저장 목적은 현재 소유권 검증과 실행 세대 추적이다.

### 4.4 중복 요청 응답 결정

첫 정상 요청은 Attempt를 생성하고 `201 Created`를 반환한다.

동일 Job, Worker, 현재 Claim Token으로 같은 요청이 재전송되면 새 Row를 만들지 않고 기존 Attempt를
`200 OK`로 반환한다. 이 멱등 재생은 Job이 여전히 같은 PROCESSING 소유권과 유효 Lease를 유지할 때만
허용한다.

Job이 완료됐거나 Lease가 만료됐거나 새 Claim Token으로 교체된 뒤 도착한 과거 요청은 기존 Attempt를
조회해 주는 성공 응답으로 처리하지 않고 소유권 오류로 거부한다.

### 4.5 시간과 Lease 결정

Attempt 시작 시각과 Lease 검증 시각은 기존 `Clock` Bean에서 한 번 계산한다.

Job 행 잠금을 획득한 뒤 기준 시각을 계산한다. Lock을 기다린 시간을 Lease 유효성에서 제외하지 않기
위해서다.

`lock_expires_at`이 기준 시각보다 뒤에 있을 때만 유효하다. 두 시각이 같으면 만료로 처리한다.

### 4.6 Worker 생존 재검증 결정

Attempt 시작에서는 Worker Heartbeat를 별도로 다시 판정하지 않는다.

현재 Job의 소유 Worker, Claim Token, Lease가 Attempt 시작 권한의 원천이다. Heartbeat를 추가로
검사하면 유효한 Lease를 가진 Worker가 일시적인 Heartbeat 지연 때문에 작업 시작을 거부당할 수 있다.
Worker 생존은 Claim 시점에 이미 확인됐고, 시작 이후 장애는 Lease 연장·만료 회수의 책임이다.

### 4.7 열린 결정

구현을 막는 열린 결정은 없다.

기존 Attempt Row는 Claim Token을 알 수 없으므로 Migration에서 임의 Backfill하지 않는다. 신규 Row만
Claim Token을 필수로 만들고, 기존 Row 호환을 위해 DB 컬럼은 nullable로 시작한다. 전체 환경에서
기존 Row가 없다는 운영 검증이 끝난 뒤에만 후속 Migration으로 `NOT NULL` 강화를 검토한다.

## 5. 핵심 규칙과 불변식

### 5.1 소유권 불변식

새 Attempt를 만들기 위한 Job은 다음 조건을 모두 만족해야 한다.

1. Job이 존재한다.
2. Job 상태가 `PROCESSING`이다.
3. Job의 소유 Worker가 null이 아니다.
4. 요청 Worker ID가 Job의 현재 소유 Worker ID와 같다.
5. Job의 Claim Token이 null이 아니다.
6. 요청 Claim Token이 현재 Job Claim Token과 같다.
7. Lease 만료 시각이 null이 아니다.
8. Lease 만료 시각이 행 잠금 후 계산한 기준 시각보다 뒤에 있다.

PROCESSING인데 소유 Worker, Claim Token, Lease 시작·만료 시각 중 하나라도 없으면 사용자 충돌이
아니라 서버 불변식 위반으로 처리한다.

### 5.2 Attempt 생성 불변식

신규 Attempt는 다음 조건을 만족한다.

| 필드 | 규칙 |
| --- | --- |
| Job | 소유권을 검증하고 잠근 현재 Job |
| Worker | Job의 현재 소유 Worker |
| Claim Token | 검증된 현재 Claim Token |
| Attempt 번호 | 같은 Job의 기존 최대 번호보다 1 큼, 기존 Row가 없으면 1 |
| 상태 | `STARTED` |
| 시작 시각 | 소유권 검증에 사용한 동일 기준 시각 |
| 종료 시각·처리 시간·오류 | 모두 비어 있음 |

Request의 Worker ID로 별도 Worker Entity를 다시 조회해 연결하지 않는다. 잠근 Job이 가진
`lockedByWorker`를 Attempt에 연결해 검증 대상과 저장 대상을 동일하게 유지한다.

### 5.3 유일성과 순서 불변식

- 같은 Job 안에서 `attempt_no`는 중복되지 않는다.
- 같은 Job과 Claim Token 조합은 중복되지 않는다.
- 같은 Claim Token의 유효한 재전송은 같은 Attempt ID와 Attempt 번호를 반환한다.
- 새 Claim Token만 다음 Attempt 번호를 만들 수 있다.
- Job A와 Job B의 Attempt 번호는 서로 독립적이다.
- `embedding_jobs.retry_count`를 Attempt 번호 계산에 사용하지 않는다.
- Attempt 번호 계산과 Insert는 같은 Job 행 잠금과 같은 Transaction 안에서 수행한다.

### 5.4 상태와 이력 불변식

- Attempt 시작은 Job 상태를 변경하지 않는다.
- Attempt 시작은 `EmbeddingJob.startedAt`을 다시 쓰지 않는다.
- Attempt 시작은 `DocumentVersion` 상태를 변경하지 않는다.
- Attempt 시작 자체로 `IndexingEvent`를 추가하지 않는다.
- Attempt Row는 실행 이력이며 시작 후 삭제하거나 번호를 다시 사용하지 않는다.
- 이번 범위에서는 Attempt를 `STARTED`에서 종료 상태로 바꾸지 않는다.

## 6. 전체 동작 흐름

### 6.1 최초 시작 요청

1. ADMIN 인증을 통과한 요청이 Job ID, Worker ID, Claim Token을 전달한다.
2. Controller가 Path와 Body 형식 및 값 범위를 검증한다.
3. Command Service의 Spring Proxy가 Transaction을 시작한다.
4. Repository가 대상 Embedding Job을 ID로 조회하면서 쓰기 행 잠금을 획득한다.
5. Job이 없으면 Transaction을 변경 없이 종료하고 Not Found 오류를 반환한다.
6. 행 잠금 획득 뒤 주입된 Clock으로 기준 시각을 한 번 계산한다.
7. Service가 PROCESSING 상태와 소유권 필드의 완전성을 확인한다.
8. 요청 Worker와 현재 Job 소유 Worker를 비교한다.
9. 요청 Claim Token과 현재 Job Token을 비교한다.
10. Lease가 기준 시각보다 뒤인지 확인한다.
11. Attempt Repository가 같은 Job과 Claim Token의 기존 Attempt를 찾는다.
12. 기존 Attempt가 없으면 같은 Job의 최대 Attempt 번호를 조회하고 다음 번호를 계산한다.
13. 현재 Job, Job 소유 Worker, Claim Token, 다음 번호, `STARTED`, 기준 시각으로 Attempt를 저장한다.
14. Converter가 Entity와 LAZY 연관 객체를 API 응답 식별자로 변환한다.
15. Transaction Commit이 성공한 뒤 Controller가 `201 Created`를 반환한다.

### 6.2 동일 요청 재전송

1. 요청은 최초 시작과 동일하게 Job 행 잠금을 획득하고 현재 소유권과 Lease를 검증한다.
2. Attempt Repository가 같은 Job과 Claim Token의 기존 Attempt를 찾는다.
3. 기존 Attempt의 Worker가 요청 Worker와 같은지 확인한다.
4. 새 Attempt 번호를 계산하거나 새 Row를 저장하지 않는다.
5. 기존 Attempt를 같은 응답 형식으로 반환한다.
6. Controller는 재생 결과를 `200 OK`로 반환한다.

### 6.3 새 Claim 세대의 시작

후속 Lease 회수 기능이 같은 Job에 새 Claim Token을 발급하면 이전 Attempt는 그대로 보존된다.

새 Worker 또는 같은 Worker가 새 Token으로 시작을 요청할 때 현재 Job 소유권과 Lease가 유효하면 기존
최대 Attempt 번호 다음 번호를 가진 새 `STARTED` Attempt를 만든다. 과거 Token 요청은 현재 Token과
다르므로 새 Attempt를 만들 수 없다.

## 7. 계층별 구현 설계

### 7.1 Controller

`IndexingJobAdminController`에 Attempt 시작 Endpoint를 추가한다.

책임은 다음으로 제한한다.

- Path Job ID와 Request Body 검증
- Command Service 호출
- 최초 생성과 멱등 재생 결과를 각각 `201`과 `200`으로 변환
- 공통 `ApiResponse` 적용
- Swagger에 호출 시점, 소유권 조건, 멱등 재생, 오류 조건 설명

Job 조회, Token 비교, Attempt 번호 계산, Entity 생성은 Controller에 두지 않는다.

기존 클래스 설명도 Claim만 담당한다는 표현에서 관리자용 Job 소유권·Attempt 시작 명령을 제공한다는
범위로 갱신한다.

### 7.2 Command Service

새 `EmbeddingJobAttemptService`를 embedding 도메인의 command 패키지에 둔다.

이 위치를 선택하는 이유는 API 진입점과 Job 소유권 Transaction이 embedding 도메인에 있고, 기존
`EmbeddingJobClaimService`도 worker 도메인의 Entity와 Repository를 조정하는 같은 의존 방향을
사용하기 때문이다.

Service의 책임은 다음과 같다.

- 대상 Job 행 잠금
- Job 존재·상태·소유권·Lease 검증
- 같은 Claim Token의 기존 Attempt 조회
- Job 기준 다음 Attempt 번호 결정
- 신규 `STARTED` Attempt 저장
- 최초 생성인지 멱등 재생인지 나타내는 내부 결과 반환
- 주입된 Clock으로 기준 시각 결정

Service는 클래스 수준 Transaction을 사용한다. Controller가 HTTP 상태를 선택할 수 있도록 응답 DTO와
생성 여부를 함께 가진 내부 결과를 제공하되, 생성 여부를 외부 응답 필드로 노출하지 않는다.

### 7.3 Embedding Job Repository

`EmbeddingJobRepository`에 특정 Job을 쓰기 잠금으로 조회하는 책임을 추가한다.

- 조회 대상은 Path의 Job ID 한 건이다.
- 잠금은 Transaction 종료까지 유지된다.
- Attempt 생성과 future Reaper가 같은 Job을 경쟁할 때 소유권 회전과 Attempt Insert가 겹치지 않게 한다.
- 기존 PENDING Queue Claim 쿼리는 변경하지 않는다.

표준 JPA Pessimistic Write Lock을 우선 사용한다. PostgreSQL 전용 Native Query가 필요하지 않은 단일 ID
조회이므로 기존 SKIP LOCKED 쿼리와 분리한다.

### 7.4 Attempt Repository

worker 도메인에 `EmbeddingJobAttemptRepository`를 새로 만든다.

책임은 다음과 같다.

- 같은 Job과 Claim Token으로 생성된 Attempt 조회
- 같은 Job의 가장 큰 Attempt 번호 조회
- 신규 Attempt 저장
- 후속 성공·실패 처리에서 Attempt ID 조회 기반 제공

최대 번호 조회는 기존 `(embedding_job_id, attempt_no)` Unique Constraint가 만드는 Index 순서를
활용할 수 있는 내림차순 단건 조회를 우선한다. 전체 Attempt 목록을 가져와 Java에서 최대값을 계산하지
않는다.

### 7.5 Attempt Entity와 Domain

기존 `EmbeddingJobAttempt`를 수정한다.

- Claim Token Mapping 추가
- Job, Worker, Claim Token, 양수 Attempt 번호, 시작 시각을 가진 시작 상태 생성 계약 명확화
- 클래스 설명에 한 Claim 세대당 한 Attempt라는 경계 추가
- 기존 Unique Constraint Mapping에 Job과 Claim Token 조합 추가
- 새 필드를 Builder와 생성 경로에 반영
- 기존 `markSuccess`와 `markFailed` 동작은 이번 범위에서 변경하지 않음

DB의 `worker_node_id`가 기존 호환성 때문에 nullable이더라도 신규 생성 경로에서는 Worker를 필수로 한다.

`AttemptStatus`에는 이미 `STARTED`가 있으므로 수정하지 않는다.

### 7.6 Request DTO

새 `StartEmbeddingJobAttemptRequest`는 다음 값을 받는다.

| 필드 | 형식과 검증 |
| --- | --- |
| `workerId` | 필수, 양수 정수 |
| `claimToken` | 필수, 공백 불가, canonical UUID 형식, 최대 36자 |

DTO에는 Swagger 필드 설명을 추가한다. Claim Token은 예시 외에 실제 값을 로그로 남기지 않는다.

### 7.7 Response DTO

새 `StartedEmbeddingJobAttemptResponse`는 다음 값을 반환한다.

| 필드 | 의미 |
| --- | --- |
| `attemptId` | 생성됐거나 재생된 Attempt 식별자 |
| `jobId` | Attempt가 속한 Embedding Job |
| `attemptNo` | Job 기준 실행 시도 번호 |
| `workerId` | 이 Attempt를 실행하는 Worker |
| `status` | `STARTED` |
| `startedAt` | Attempt 시작 시각 |

Claim Token, Lease 시각, 오류 필드는 응답하지 않는다. Worker는 이미 Claim 응답으로 Token을 보유하고
있으며, Attempt 응답에서 재노출할 필요가 없다.

### 7.8 Converter

새 `EmbeddingJobAttemptConverter`는 Attempt Entity를 시작 응답으로 변환한다.

- Entity 자체를 Controller에 노출하지 않는다.
- LAZY Job과 Worker의 Entity 대신 ID만 추출한다.
- Transaction 안에서 변환해 필요한 연관 식별자를 안전하게 읽는다.
- 생성과 재생이 같은 Response 계약을 사용하게 한다.

### 7.9 Configuration과 Infrastructure

기존 `Clock` Bean과 현재 PostgreSQL DataSource를 재사용한다.

새 Scheduler, Executor, Client, Message Queue, Cache, Object Storage 연결은 없다.

## 8. 파일 변경 계획

| 구분 | 경로 | 책임과 변경 이유 |
| --- | --- | --- |
| CREATE | `docs/design/gimin-#63-embedding-job-attempt-start.md` | 현재 설계와 구현·검증 계약 보존 |
| CREATE | `src/main/resources/db/migration/V34__add_claim_token_to_embedding_job_attempts.sql` | Attempt Claim Token 컬럼과 Job·Token 유일성 추가 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/worker/entity/EmbeddingJobAttempt.java` | Claim Token Mapping과 한 Claim당 한 Attempt 불변식 반영 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/worker/repository/EmbeddingJobAttemptRepository.java` | Token 기반 기존 Attempt와 Job별 최신 번호 조회 및 저장 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/embedding/repository/EmbeddingJobRepository.java` | 대상 Job ID의 쓰기 행 잠금 조회 추가 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/embedding/service/command/EmbeddingJobAttemptService.java` | 소유권 검증, 멱등 처리, Attempt 번호 할당과 저장 Transaction 조정 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/embedding/dto/request/StartEmbeddingJobAttemptRequest.java` | Worker ID와 Claim Token 입력·Swagger 계약 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/embedding/dto/response/StartedEmbeddingJobAttemptResponse.java` | 시작된 Attempt 식별자·번호·Worker·상태·시각 응답 |
| CREATE | `src/main/java/com/opensource/docgrid/domain/embedding/converter/EmbeddingJobAttemptConverter.java` | Attempt Entity를 API Response로 변환 |
| MODIFY | `src/main/java/com/opensource/docgrid/domain/embedding/controller/IndexingJobAdminController.java` | 관리자용 Attempt 시작 Endpoint와 응답·Swagger 계약 추가 |
| MODIFY | `src/main/java/com/opensource/docgrid/global/exception/ErrorCode.java` | Job 없음, 상태, 소유권, Lease, 불완전 소유권 오류 추가 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/worker/entity/EmbeddingJobAttemptTest.java` | 신규 Attempt 필드와 시작 상태 불변식 검증 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/embedding/converter/EmbeddingJobAttemptConverterTest.java` | Entity 비노출과 응답 Mapping 검증 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/embedding/service/command/EmbeddingJobAttemptServiceTest.java` | 소유권·Lease·멱등성·번호 할당·오류 흐름 단위 검증 |
| MODIFY | `src/test/java/com/opensource/docgrid/domain/embedding/controller/IndexingJobAdminControllerTest.java` | 신규 HTTP·Validation·Security·오류 계약 검증 |
| CREATE | `src/test/java/com/opensource/docgrid/domain/embedding/integration/EmbeddingJobAttemptIntegrationTest.java` | 실제 OpenSQL Migration, Transaction, 행 잠금, 동시 시작 불변식 검증 |

DELETE 대상은 없다.

다음 파일은 변경하지 않는다.

- `EmbeddingJobClaimService`
- `EmbeddingJob.claim`
- `WorkerNodeRepository`
- `AttemptStatus`
- `IndexingEventType`
- `IndexingEventRepository`
- `SecurityConfig`
- `IndexingWorkerProperties`
- `application.yml`과 Profile별 Application 설정
- `build.gradle`
- 기존 Claim 통합·동시성·성능 테스트

## 9. DB와 데이터 변경

### 9.1 Migration

현재 최신 Migration이 V33이므로 V34를 새로 추가한다. V19는 이미 적용된 Migration이므로 수정하지
않는다.

V34의 책임은 다음과 같다.

1. `embedding_job_attempts`에 길이 36의 Claim Token 컬럼을 nullable로 추가한다.
2. Job ID와 Claim Token 조합에 이름 있는 Unique Constraint를 추가한다.
3. 기존 Job ID와 Attempt 번호 Unique Constraint는 유지한다.

PostgreSQL의 일반 Unique Constraint는 null 값을 서로 같은 값으로 취급하지 않으므로 기존 null Row가
여러 건 있어도 Migration 호환성이 유지된다. 신규 Service는 null Token Attempt를 생성하지 않는다.

### 9.2 Backfill

기존 Attempt Row의 실제 Claim Token은 현재 데이터만으로 복원할 수 없다. `embedding_jobs`에는 현재
Token만 남고 과거 Claim 세대는 보존되지 않기 때문이다.

따라서 다음을 금지한다.

- 현재 Job Token을 모든 과거 Attempt에 복사
- 무작위 Token을 과거 Claim Token인 것처럼 생성
- Attempt 번호나 ID를 Token 문자열로 위장

기존 Row는 Token이 null인 Legacy 이력으로 유지한다. API를 통한 신규 Row만 non-null Token과 새
유일성 계약을 적용한다.

### 9.3 Constraint와 Index

| 제약 또는 Index | 용도 |
| --- | --- |
| 기존 Job ID·Attempt 번호 Unique | Job별 번호 중복 최종 차단과 최신 번호 조회 지원 |
| 신규 Job ID·Claim Token Unique | 같은 Claim 세대의 중복 Attempt 최종 차단과 멱등 조회 지원 |
| 기존 Worker Index | Worker별 실행 이력 조회 |
| 기존 Status Index | STARTED·SUCCESS·FAILED 상태 조회 |

추가 단일 컬럼 Index는 만들지 않는다. 실제 조회는 Job과 Token 또는 Job과 Attempt 번호 조합을 사용한다.

### 9.4 호환성과 배포

Migration을 먼저 적용해도 기존 애플리케이션은 새 nullable 컬럼을 사용하지 않으므로 동작할 수 있다.
새 애플리케이션은 신규 Attempt에 Token을 필수로 기록한다.

기존 Row가 없다는 사실이 모든 환경에서 확인되면 별도 후속 Migration으로 `NOT NULL`을 적용할 수
있다. 이번 Migration은 알 수 없는 기존 데이터를 삭제하거나 배포를 막지 않는 Expand 단계로 제한한다.

### 9.5 Rollback

Flyway 적용 파일은 되돌려 수정하지 않는다.

애플리케이션 Rollback 시 V34 컬럼과 Constraint를 남겨도 이전 애플리케이션에는 영향이 없다. 신규
Attempt가 생성된 뒤 컬럼을 삭제하면 Claim 세대 이력이 손실되므로 Schema 수동 축소는 하지 않는다.

## 10. API와 오류 계약

### 10.1 Endpoint

| 항목 | 계약 |
| --- | --- |
| Method | POST |
| Path | `/admin/indexing-jobs/{jobId}/attempts` |
| Content Type | `application/json` |
| 인증 | ADMIN 역할 |
| 목적 | 현재 Claim 소유권으로 Job 실행 Attempt 시작 |

Path의 `jobId`는 필수 양수 값이다. Controller는 Request Body에 `@Valid`를 적용하고 Path 검증이
동작하도록 Method Validation을 활성화한다.

### 10.2 요청

| 필드 | 필수 | 검증 |
| --- | --- | --- |
| `workerId` | 예 | 양수 |
| `claimToken` | 예 | 공백 불가, canonical UUID, 36자 이내 |

### 10.3 성공 응답

| 상황 | HTTP | Body |
| --- | ---: | --- |
| 현재 Claim에서 Attempt 최초 생성 | 201 | 새 Attempt 응답 |
| 같은 현재 Claim의 유효한 재전송 | 200 | 기존 Attempt와 동일한 응답 |

두 응답 모두 공통 `ApiResponse` 안에 `attemptId`, `jobId`, `attemptNo`, `workerId`, `status`,
`startedAt`을 담는다.

### 10.4 오류 응답

| 상황 | HTTP | 오류 코드 |
| --- | ---: | --- |
| Path 또는 Body Validation 실패 | 400 | `COMMON-002` |
| 인증되지 않았거나 ADMIN 역할 없음 | 403 | 현재 Security 동작 유지 |
| Job 없음 | 404 | 신규 `EMBEDDING-JOB-001` |
| Job 상태가 PROCESSING이 아님 | 409 | 신규 `EMBEDDING-JOB-002` |
| 요청 Worker 또는 Token이 현재 소유권과 다름 | 409 | 신규 `EMBEDDING-JOB-003` |
| Lease가 만료됐거나 만료 시각과 같음 | 409 | 신규 `EMBEDDING-JOB-004` |
| PROCESSING Job의 소유권 필드가 불완전함 | 500 | 신규 `EMBEDDING-JOB-005` |
| 예기치 않은 DB Unique 충돌 | 409 | 기존 `COMMON-008` |

오류 Enum 이름은 구현 시 다음 의미를 유지한다.

- Job을 찾지 못함
- Attempt를 시작할 수 없는 Job 상태
- 현재 Claim 소유권 불일치
- 현재 Lease 만료
- PROCESSING 소유권 데이터 불일치

소유권 오류 응답에는 현재 Worker ID, 실제 Claim Token, Lease 내부값을 포함하지 않는다.

## 11. 트랜잭션·동시성·멱등성

### 11.1 Transaction 경계

Attempt 시작 Command Service의 public 진입 전체를 하나의 짧은 Transaction으로 묶는다.

같은 Transaction에 포함되는 작업은 다음과 같다.

1. 대상 Job 쓰기 잠금 조회
2. 기준 시각 계산과 소유권·Lease 검증
3. 동일 Claim Token Attempt 조회
4. 다음 Attempt 번호 조회
5. 신규 Attempt Insert
6. Response 변환
7. Flush와 Commit

Controller와 외부 호출은 Transaction에 포함하지 않는다. 이번 흐름에는 파일 I/O, MinIO, 파싱,
Embedding Server 호출이 없다.

### 11.2 Job 행 잠금의 역할

Job 행 잠금은 다음 경쟁을 직렬화한다.

- 같은 Claim Token의 시작 요청 두 개
- 현재 Token 요청과 과거 Token 요청
- 향후 Lease 회수와 Attempt 시작
- 향후 완료·실패 처리와 Attempt 시작

잠금을 얻은 뒤 현재 소유권을 검증하므로, 대기 중 다른 Transaction이 Token을 교체했다면 새 Token을
관찰하고 과거 요청을 거부한다.

모든 관련 기능은 Job 행을 먼저 잠그고 Attempt를 조회·변경하는 동일 Lock 순서를 유지해야 한다.
Attempt를 먼저 잠근 뒤 Job을 잠그는 반대 순서를 추가하면 후속 기능과 Deadlock 위험이 생긴다.

### 11.3 Attempt 번호 동시성

단순 최대값 조회와 Insert만 수행하면 두 Transaction이 같은 번호를 계산할 수 있다.

현재 설계는 먼저 동일 Job 행을 잠가 Job별 Attempt 생성 Transaction을 한 번에 하나만 진행시킨다.
그 안에서 최신 번호를 조회하므로 다음 번호 계산이 직렬화된다.

기존 Job ID·Attempt 번호 Unique Constraint는 Service 잠금 경로를 우회한 쓰기나 구현 오류를 막는 최종
DB 방어선이다. Unique 예외를 정상적인 번호 할당 알고리즘으로 사용하지 않는다.

### 11.4 Claim Token 멱등성

Claim Token이 시작 요청의 멱등성 키다.

- 같은 Job, Worker, 현재 Token 재전송은 기존 Attempt 반환
- 같은 Job과 현재 Token의 동시 요청은 Job Lock 뒤 한 건만 Insert
- 같은 Worker라도 새 Token이면 다음 Attempt 생성
- 과거 Token이면 신규 Attempt 생성 금지
- 다른 Job에서 우연히 같은 Token이어도 Job과 Token 조합 기준으로 분리

DB의 Job ID·Claim Token Unique Constraint는 Service 검증과 함께 한 Claim당 한 Attempt를 보장한다.

### 11.5 Lease 만료 경쟁

Lease는 Job Lock을 획득한 뒤 검증한다. 검증 순간 Lease가 유효하면 해당 Transaction이 Attempt 시작의
선형화 지점이 된다.

Transaction이 Job Lock을 보유하는 동안 future Reaper는 같은 Job의 Token을 교체할 수 없다. Reaper가
먼저 잠금을 얻어 Token을 바꿨다면 Attempt 요청은 변경된 소유권을 보고 거부된다.

### 11.6 실패 지점

| 실패 지점 | DB 결과 |
| --- | --- |
| 입력 검증 실패 | Transaction 시작 전, 변경 없음 |
| Job 없음·상태 오류·소유권 오류·Lease 만료 | Attempt Insert 없음 |
| Attempt 번호 조회 실패 | Transaction Rollback, Attempt 없음 |
| Attempt Insert 또는 Commit 실패 | 신규 Attempt 전체 Rollback |
| Commit 후 HTTP 응답 유실 | Attempt는 저장됨, 같은 유효 Claim 재전송 시 기존 Attempt 반환 |
| 응답 유실 뒤 Lease 만료·재Claim | 과거 요청은 소유권 오류, 저장된 과거 Attempt는 보존 |

자동 DB 재시도는 추가하지 않는다. Deadlock이나 인프라 오류를 숨기지 않고 실패로 전달한다. 정상적인
HTTP 재전송만 Claim Token 멱등성으로 처리한다.

## 12. 기술·설정·외부 연결

### 12.1 기술

| 범주 | 결정 |
| --- | --- |
| Framework | 기존 Spring Boot, Spring Data JPA 사용 |
| Transaction | 기존 Spring `@Transactional` 사용 |
| Lock | JPA Pessimistic Write Lock과 PostgreSQL Row Lock 사용 |
| 시간 | 기존 `Clock` Bean 사용 |
| 입력 검증 | 기존 Jakarta Bean Validation 사용 |
| 추가 Library | 추가 없음 |

### 12.2 설정

| 항목 | 변경 |
| --- | --- |
| Application Property | 추가 없음 |
| 환경변수 | 추가 없음 |
| Secret | 추가 없음 |
| Timeout 설정 | 추가 없음 |
| Retry 설정 | 추가 없음 |
| Feature Flag | 추가 없음 |

Lease 기간은 기존 `indexing.worker.lease-duration`으로 Claim 시 이미 확정된다. Attempt 시작은 새로운
기간 설정을 만들지 않고 저장된 `lock_expires_at`을 검증한다.

### 12.3 외부 연결

| 시스템 | 변경 |
| --- | --- |
| PostgreSQL/OpenSQL | V34 Migration과 신규 Transaction 경로 사용 |
| MinIO | 추가 없음 |
| Embedding Server | 추가 없음 |
| Message Broker | 추가 없음 |
| Cache | 추가 없음 |
| 외부 Monitoring | 추가 없음 |

### 12.4 Health Check와 배포

- 신규 Health Check: 추가 없음
- 신규 Container와 배포 자원: 추가 없음
- 기본 Build Task: 변경 없음
- 배포 영향: V34가 Application 시작 전에 적용돼야 함
- 무중단 호환성: nullable 컬럼 Expand Migration으로 이전 애플리케이션과 호환

## 13. 보안과 관측성

### 13.1 인증과 권한

기존 `/admin/**` ADMIN 정책이 신규 Endpoint를 자동으로 보호하므로 `SecurityConfig`는 수정하지 않는다.

현재 ADMIN 인증은 요청 사용자가 내부 작업을 호출할 권한이 있음을 증명하지만, Body의 Worker ID가 실제
호출 Machine임을 증명하지는 않는다. 이번 범위에서는 현재 Claim Token과 Lease를 소유권 증명으로
사용한다.

Worker가 직접 호출하는 운영 구조로 확장할 때는 Machine Credential과 Worker Instance ID를 인증
Principal에 바인딩해야 한다. 이 보강은 이번 범위 밖이다.

### 13.2 Claim Token 보호

- Request Body 외에 Query String이나 Path에 Token을 넣지 않는다.
- 성공 Response에 Token을 다시 넣지 않는다.
- 일반 로그, 오류 로그, 이벤트 메시지, Metric Label에 Token을 넣지 않는다.
- 소유권 오류 메시지에 기대 Token이나 실제 Token을 노출하지 않는다.
- DB에는 이력 상 필요한 원문 Token을 저장하되 Application 응답으로 조회 기능을 제공하지 않는다.

### 13.3 로그

성공 Attempt마다 INFO 로그를 남겨 대량 Polling 환경의 로그를 증가시키지 않는다.

서버 불변식 오류에는 Job ID와 요청 Worker ID, 오류 종류만 남기고 Token은 제외한다. 입력·소유권
오류는 기존 Global Exception Handler의 안정적인 오류 코드 로그를 사용한다.

멱등 재생은 장애가 아니므로 WARN으로 남기지 않는다. 운영 분석이 필요하면 Token 없이 Job ID와
Attempt ID를 DEBUG 수준에서만 기록한다.

### 13.4 Metric과 이력

신규 Metric Library는 추가하지 않는다. 다음 값은 `embedding_job_attempts` 집계로 관찰할 수 있다.

- Job별 Attempt 수
- Worker별 STARTED Attempt 수
- 장시간 STARTED 상태인 Attempt 수
- Attempt 시작 시각 분포
- Claim 수 대비 Attempt 시작 수

Attempt 생성 자체가 실행 이력이므로 Job 상태 변화가 없는 이번 단계에서 별도 `IndexingEvent`를
중복 저장하지 않는다.

## 14. 테스트 설계

### 14.1 Entity 테스트

- 신규 Attempt가 Job, Worker, Claim Token, Attempt 번호, 시작 시각을 보존한다.
- 상태를 생략한 생성 경로가 `STARTED`가 된다.
- 신규 시작 상태의 종료 시각, 처리 시간, 오류 값이 비어 있다.
- 기존 성공·실패 상태 변경 동작이 회귀하지 않는다.
- 클래스 설명과 필드 Mapping이 V34 Schema와 일치한다.

### 14.2 Converter 테스트

- Attempt ID, Job ID, Attempt 번호, Worker ID, 상태, 시작 시각이 응답에 정확히 매핑된다.
- Entity와 LAZY 연관 Entity 자체를 응답하지 않는다.
- Claim Token과 오류 필드를 응답하지 않는다.

### 14.3 Service 단위 테스트

고정 Clock과 Mock Repository를 사용한다.

정상·멱등 케이스:

- 기존 Attempt가 없으면 Attempt 1을 STARTED로 생성한다.
- 기존 최대 번호가 있으면 새 Claim에서 다음 번호를 생성한다.
- 같은 현재 Claim Token의 재전송이면 기존 Attempt를 반환하고 저장하지 않는다.
- 같은 Claim Token 재전송 결과가 최초 응답과 같은 Attempt ID·번호를 가진다.
- Job의 소유 Worker Entity가 Attempt에 그대로 연결된다.
- Clock 기준 시각이 Attempt 시작과 Lease 검증에 동일하게 사용된다.

오류 케이스:

- Job이 없으면 404 오류이며 Attempt Repository를 호출하지 않는다.
- PENDING, INDEXED, FAILED, CANCELED Job은 시작할 수 없다.
- PROCESSING인데 소유 Worker가 없으면 서버 불변식 오류다.
- 요청 Worker가 현재 소유 Worker와 다르면 409다.
- Claim Token이 null이거나 요청과 다르면 409다.
- Lease 만료 시각이 null이면 서버 불변식 오류다.
- Lease 만료 시각이 기준 시각과 같거나 과거면 409다.
- 오류 경로에서 Attempt 저장과 Converter 호출이 없다.

### 14.4 Controller 테스트

- ADMIN의 최초 요청은 `201`과 신규 Attempt Body를 반환한다.
- ADMIN의 같은 Claim 재전송은 `200`과 기존 Attempt Body를 반환한다.
- 양수가 아닌 Job ID와 Worker ID는 `400`이다.
- Claim Token 누락, 공백, 잘못된 UUID는 `400`이다.
- Job 없음, 상태 오류, 소유권 오류, Lease 만료, 불변식 오류가 정의한 HTTP와 코드로 매핑된다.
- 일반 사용자와 미인증 사용자는 현재 Security 정책에 따라 `403`이다.
- Swagger 설명과 Content Type이 실제 계약과 일치한다.

### 14.5 Repository·Migration 통합 테스트

실제 OpenSQL과 격리 Schema를 사용한다.

- V34가 Claim Token을 길이 36의 nullable 컬럼으로 만든다.
- Job ID·Claim Token Unique Constraint가 존재한다.
- 기존 Job ID·Attempt 번호 Unique Constraint가 유지된다.
- 서로 다른 Legacy null Token Row가 Migration 호환성을 가진다.
- Job ID 잠금 조회가 실제 Row Lock을 획득한다.
- 기존 Attempt가 없을 때 최신 번호 조회가 빈 결과를 처리한다.
- 기존 번호가 여러 개면 가장 큰 번호를 정확히 반환한다.
- 동일 Job·Token 직접 중복 Insert가 DB에서 차단된다.
- 동일 Job·Attempt 번호 직접 중복 Insert가 DB에서 차단된다.

### 14.6 동시성 통합 테스트

Test 메서드 자체에 Transaction을 두지 않고, 서로 다른 Thread의 실제 Service Proxy 호출이 독립
Transaction을 사용하게 한다.

- 같은 Job, Worker, Token으로 두 요청을 동시에 시작한다.
- Barrier 또는 Latch로 두 Thread가 거의 동시에 진입하게 한다.
- DB에는 Attempt Row가 정확히 한 건만 존재해야 한다.
- 두 호출은 같은 Attempt ID와 번호를 받되 생성·재생 결과가 하나씩이어야 한다.
- 첫 Attempt 번호는 1이어야 한다.
- Worker, Token, STARTED 상태, 시작 시각이 완전해야 한다.
- Worker 오류, Timeout, Deadlock이 없어야 한다.

추가 경쟁 시나리오:

- 현재 Token 요청과 과거 Token 요청이 경쟁하면 현재 Token만 새 Attempt를 만든다.
- 향후 Reaper를 모사해 Job Token이 먼저 교체되면 과거 요청은 Attempt를 만들지 못한다.
- 서로 다른 Job의 Attempt 시작은 하나의 전역 Lock으로 직렬화되지 않는다.

각 Future와 Executor 종료에는 제한 시간을 둬 Deadlock 시 Test가 무기한 대기하지 않게 한다.

### 14.7 회귀 테스트

- 기존 Claim Service 단위 테스트
- 기존 Claim Controller 테스트
- 기존 OpenSQL Claim 통합 테스트
- 기존 다중 Worker Claim 정합성 테스트
- 기본 단위·통합 테스트
- 전체 Build

신규 테스트를 기본 Build에서 제외하지 않는다. 작은 Job 단위의 통합·동시성 검증이므로 별도 Gradle
Task를 추가하지 않는다.

## 15. 구현 순서

1. V34 Migration에 Claim Token 컬럼과 Job·Token Unique Constraint를 추가한다.
2. `EmbeddingJobAttempt` Mapping, 생성 계약, 설명을 Migration과 맞춘다.
3. Entity 테스트로 신규 시작 상태와 기존 종료 상태 변경 회귀를 고정한다.
4. `EmbeddingJobAttemptRepository`에 Token 조회와 최신 번호 조회 책임을 추가한다.
5. `EmbeddingJobRepository`에 ID 기반 쓰기 잠금 조회를 추가한다.
6. Request·Response DTO와 Converter를 추가한다.
7. 신규 오류 코드를 추가한다.
8. 고정 Clock 기반 `EmbeddingJobAttemptService`를 구현한다.
9. Service 단위 테스트로 검증 순서, 멱등 재생, 번호 할당, 오류 시 무저장을 확인한다.
10. Controller Endpoint와 Swagger 계약을 추가한다.
11. Controller Validation·Security·오류 테스트를 추가한다.
12. 실제 OpenSQL 통합 테스트로 Migration과 행 잠금·Unique Constraint를 확인한다.
13. 두 독립 Transaction의 동일 Token 동시 요청이 한 Row로 수렴하는지 검증한다.
14. 기존 Claim 테스트와 전체 회귀 테스트를 실행한다.
15. 설계 문서 이름과 Closing Keyword를 실제 Issue 번호 기준으로 정리한다.

## 16. 완료 기준

- 유효한 PROCESSING 소유권과 미래 Lease를 가진 Worker만 Attempt를 시작할 수 있다.
- 첫 Claim의 Attempt 번호는 1이다.
- 새 Claim Token마다 Job 기준 다음 Attempt 번호가 생성된다.
- 같은 Job의 Attempt 번호가 중복되지 않는다.
- 같은 Job과 Claim Token에 Attempt가 두 건 생기지 않는다.
- 같은 현재 Claim의 순차·동시 재전송은 기존 Attempt를 반환한다.
- 잘못된 Worker와 과거 Token은 Attempt를 만들지 못한다.
- Lease 만료 시각과 같은 시각부터 새 Attempt를 만들지 못한다.
- PROCESSING Job의 불완전 소유권은 서버 오류로 탐지된다.
- Attempt는 STARTED 상태와 Job, Worker, Token, 시작 시각을 함께 저장한다.
- Attempt 시작이 Job, Version, 이벤트 상태를 변경하지 않는다.
- Claim Token은 Response, 로그, 이벤트, Metric에 노출되지 않는다.
- 신규 Endpoint는 ADMIN만 호출할 수 있다.
- 최초 생성은 201, 멱등 재생은 200으로 구분된다.
- V34는 기존 null Token Attempt Row와 호환된다.
- 실제 OpenSQL에서 Job Lock과 두 Unique Constraint가 검증된다.
- 동시 동일 요청 두 건이 DB Row 한 건과 같은 Attempt 응답으로 수렴한다.
- Production 설정, 외부 연결, Build Task에 불필요한 변경이 없다.
- 기존 Claim과 전체 회귀 테스트가 통과한다.

## 17. 후속 작업 호환성

### 17.1 텍스트 파싱과 Chunk 저장

Worker는 Attempt 시작 응답의 Attempt ID를 실행 Context에 보존한다. 후속 파싱·Chunk 저장 단계는 새
Attempt를 만들지 않고 현재 Job ID, Worker ID, Claim Token, Attempt ID를 같은 실행 단위로 전달한다.

파일 읽기와 파싱 전후의 DB Transaction에서도 현재 Job 소유권과 Lease를 다시 검증해야 한다. Attempt
시작 성공만으로 장시간 외부 I/O 이후까지 소유권이 보장되는 것은 아니다.

### 17.2 Embedding과 완료 처리

같은 Attempt가 파싱, Chunking, Embedding, Vector 저장을 거쳐 Job 완료까지 이어진다. 완료 Transaction은
현재 Job Worker와 Claim Token을 다시 검증한 뒤 Attempt를 SUCCESS로 바꾸고 Job·Version·Document
완료 상태를 함께 처리해야 한다.

### 17.3 실패와 자동 재시도

실패 처리에서는 현재 Attempt를 FAILED로 종료하고 오류 코드·메시지·종료 시각·처리 시간을 기록한다.
재시도 가능 Job이 새 Claim Token으로 다시 Claim될 때만 다음 Attempt를 만든다.

`retry_count`는 재시도 정책 횟수이고 `attempt_no`는 실제 실행 이력 순서이므로 후속 기능에서도 같은
값으로 간주하지 않는다.

### 17.4 Lease 만료 복구

Worker 장애로 STARTED Attempt가 남으면 Reaper는 Job 행을 먼저 잠그고 현재 Token을 교체해야 한다.
이후 과거 Worker는 Attempt 시작·완료·실패를 수행할 수 없다.

현재 `AttemptStatus`에 없는 `TIMED_OUT`과 `ABANDONED`가 필요해지면 복구 기능에서 Enum과 상태 전환
규칙을 추가한다. 이번 시작 기능에서 미리 추가하지 않는다.

### 17.5 상태·이벤트 조회

현재 일반 문서 상태 API는 내부 Attempt ID와 Worker 정보를 노출하지 않는다. 운영자용 Attempt 조회와
인덱싱 이벤트 타임라인은 별도 관리자 조회 기능으로 추가한다.

Attempt의 Claim Token은 관리자 조회 기능이 생겨도 응답하지 않는다.

### 17.6 알려진 한계

- ADMIN 인증과 Body의 Worker ID가 Machine Identity로 암호학적으로 바인딩되지는 않는다.
- 기존 Legacy Attempt의 Claim Token은 복원하지 않는다.
- 이번 기능만으로 STARTED Attempt를 자동 종료하거나 장애 복구하지 않는다.
- Lease가 긴 처리 전체에 충분한지는 파싱·임베딩 실행과 Lease 연장 기능에서 검증해야 한다.
- 여러 애플리케이션 Pod에서도 PostgreSQL Job Row Lock이 동시성을 제어하지만 실제 다중 Pod Network
  지연은 이번 기능 테스트 범위가 아니다.

## 구현 인계

- GitHub에서 사용할 기능명: `Embedding Job Attempt 시작 기록`
- 권장 작업 유형: `feature`
- 설계 기준 브랜치: `develop`
- 구현 전 차단 결정: 없음
- 구현 시 반드시 유지할 핵심 계약: Job 행 잠금, 현재 Worker·Token·Lease 검증, 한 Claim당 한 Attempt,
  동일 Claim 재전송의 멱등 반환
- 구현 설계 문서: `docs/design/gimin-#63-embedding-job-attempt-start.md`
- 실행 결과 문서는 별도 테스트 이슈에서 요청될 때만 `docs/test-results/`에 기록
