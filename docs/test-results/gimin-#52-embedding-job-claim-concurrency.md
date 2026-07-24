# 다중 Worker Embedding Job Claim 정합성 테스트

- GitHub Issue: [#52](https://github.com/DocGrid/backend/issues/52)
- 브랜치: `test/52`
- 테스트 대상: PENDING Job Claim 및 Lease Lock
- 테스트 종류: 실제 OpenSQL 동시성 통합 테스트

## 1. 이 문서의 목적

이 문서는 다중 Worker Claim 테스트의 설계만 설명하는 문서가 아니다. 처음 저장소를 받은 개발자도 아래
순서대로 명령어를 실행해 같은 테스트를 재현할 수 있도록 다음 내용을 모두 제공한다.

```text
1. Docker와 OpenSQL 준비
2. 테스트용 환경 변수 설정
3. 단일 시나리오 실행
4. 전체 동시성 테스트 실행
5. 5회 반복 검증
6. 결과 리포트 확인
7. 필요할 때 테스트 스키마 보존
8. SQL로 최종 상태 직접 확인
9. 흔한 실패 원인 진단
10. 테스트 컨테이너와 볼륨 정리
```

명령어는 macOS와 프로젝트 루트 디렉터리를 기준으로 작성한다.

```text
/Users/giminkim/IdeaProjects/backend
```

다른 경로에 저장소를 받은 경우 절대 경로가 들어간 Docker Volume Mount 부분만 자신의 경로로 바꾼다.

## 2. 테스트가 증명하려는 것

PENDING Job Claim 기능은 다음 Transaction으로 소유권을 기록한다.

```text
Worker 조회
→ Heartbeat 기준 실질 상태 검증
→ PENDING Job SELECT FOR UPDATE SKIP LOCKED
→ PROCESSING 전환
→ locked_by_worker_id 기록
→ UUID claim_token 발급
→ locked_at / lock_expires_at 기록
→ LOCKED 이벤트 저장
→ Commit
```

순차 테스트만으로는 두 Worker가 같은 PENDING Row를 읽으려는 경쟁을 확인할 수 없다.

```text
Claim A 완료
→ Claim B 시작
```

이 동시성 테스트는 여러 Java Thread가 각각 독립된 Spring Transaction을 시작하도록 만들어 다음 불변식을
검증한다.

```text
한 Job
→ 한 성공 응답
→ 한 Worker
→ 한 Claim Token
→ 한 Lease
→ 한 LOCKED 이벤트
```

대량 Queue에서는 다음 불변식을 추가로 검증한다.

```text
초기 PENDING Job ID 집합
=
성공한 Claim 응답 Job ID 집합
```

개수만 1,000개인지 확인하지 않고 Job ID 집합을 직접 비교하므로 다음 오류를 함께 찾을 수 있다.

```text
Job A가 두 번 응답되고 Job B가 누락됨
→ 전체 응답 수는 1,000일 수 있음
→ Job ID 집합 비교에서는 실패
```

## 3. 테스트하지 않는 것

이 테스트의 통과 시간은 컴퓨터 사양, Docker 상태, ARM에서 AMD64 Image를 실행하는 비용에 따라 달라진다.
따라서 다음 값은 합격 기준으로 사용하지 않는다.

```text
Claim TPS
평균 Claim Latency
P95
P99
DB CPU
DB Lock 대기 시간
Worker별 처리 건수의 균등성
```

로그에 전체 실행 시간을 남기는 이유는 이전 실행보다 갑자기 지나치게 느려진 상황을 발견하기 위한 참고다.
성능 수치와 Lock 경합 비교는 후속 처리량·Lock 경합 성능 테스트에서 별도 환경을 고정해 측정한다.

## 4. 구현 파일

### 4.1 테스트 클래스

```text
src/test/java/com/opensource/docgrid/domain/embedding/integration/
└─ EmbeddingJobClaimConcurrencyIntegrationTest.java
```

두 시나리오가 들어 있다.

```text
claim_singleJobWithOneHundredWorkers_assignsExactlyOneOwner
→ Worker 100개 / Job 1개

claim_oneThousandJobsWithTwentyWorkers_claimsEveryJobExactlyOnce
→ Worker 20개 / Job 1,000개
```

### 4.2 Gradle Task

```text
claimConcurrencyTest
```

이 테스트는 다음 Tag를 가진다.

```text
integration
claim-concurrency
```

일반 `test`와 `build`에서는 `claim-concurrency`를 제외한다. Thread 100개와 Claim Transaction 1,000개
이상을 실행하는 테스트를 모든 단위 테스트 실행에 포함하면 개발 피드백이 느려지기 때문이다.

대신 PR 병합 전에는 반드시 다음 전용 Task를 별도로 실행한다.

```bash
./gradlew claimConcurrencyTest
```

### 4.3 처음 보는 사람을 위한 기본 개념

동시성 테스트 코드를 읽기 전에 `Task`, `Executor`, `readyLatch`, `startLatch`, `Claim`의 역할을 먼저
구분해야 한다. 이름은 비슷하게 느껴질 수 있지만 각각 담당하는 범위가 다르다.

| 개념 | 의미 | 이 테스트에서의 역할 |
|---|---|---|
| Task | Thread가 실행할 수 있도록 만든 작업 단위 | 특정 Worker ID로 Job을 한 번 Claim하거나 Queue가 빌 때까지 반복 Claim하는 코드 |
| Executor | Task를 받아 Thread에서 실행하는 관리자 | 정해진 크기의 Thread Pool을 만들고 Worker Task를 실제 Thread에 배정 |
| readyLatch | 모든 Task가 출발 준비를 마쳤는지 세는 카운터 | 각 Task가 준비될 때마다 1씩 감소하고, 0이 될 때까지 메인 Test Thread가 기다림 |
| startLatch | 준비된 Task를 같은 시점에 출발시키는 문 | 닫혀 있는 동안 모든 Task가 대기하고, 메인 Test Thread가 열면 Claim을 함께 시작 |
| Claim | Job 처리 권한과 소유권을 확보하는 동작 | PENDING Job을 골라 PROCESSING으로 바꾸고 Worker·Token·Lease를 기록 |

#### Task

Task는 Worker가 실행할 작업 하나다. Java에서는 보통 `Runnable`이나 `Callable` 형태로 Executor에
전달한다.

```text
Task
→ 실행할 코드 묶음
→ 아직 Thread 자체는 아님
```

이 테스트의 단일 Job 경쟁에서 Task 하나는 다음 의미다.

```text
내 Worker ID로 embeddingJobClaimService.claim(workerId)를 한 번 호출한다.
```

1,000개 Queue 소진 테스트에서 Task 하나는 다음 의미다.

```text
내 Worker ID로 Claim을 반복한다.
→ Job을 받으면 결과를 모은다.
→ Queue가 비면 반복을 끝낸다.
```

Task와 DB의 `embedding_jobs` Job은 같은 것이 아니다.

```text
Task
→ Java에서 실행되는 코드 단위

Job
→ DB Queue에 저장된 실제 처리 대상 Row
```

#### Executor

Executor는 Task를 실행해 주는 Thread 관리자다. 내부에 Thread Pool을 가지고 있고, 제출된 Task를 실행
가능한 Thread에 배정한다.

```text
Task 제출
→ Executor가 Task를 Queue에 받음
→ 비어 있는 Thread를 선택
→ Thread가 Task 코드 실행
```

이 테스트에서는 모든 Task가 `startLatch` 앞까지 도착해야 하므로 Executor의 Thread 수를 Worker Task 수와
같게 만든다.

```text
Worker Task 100개
→ Executor Thread 100개
```

Executor Thread 수가 10개뿐이면 처음 10개 Task만 `startLatch` 앞에 도착하고 나머지 90개는 Executor
Queue에서 기다린다. 그러면 `readyLatch`가 0이 되지 않아 테스트를 시작할 수 없다.

#### readyLatch

`readyLatch`는 모든 Task가 출발 준비를 마쳤는지 확인하는 카운터다. Worker 수로 초기화한다.

```text
초기값 100

Task 1 준비 완료   → 99
Task 2 준비 완료   → 98
...
Task 100 준비 완료 → 0
```

메인 Test Thread는 값이 0이 될 때까지 기다린다. 따라서 일부 Task만 먼저 실행되는 순차 테스트를 동시성
테스트로 잘못 판단하지 않게 한다.

#### startLatch

`startLatch`는 준비된 Task들을 거의 동시에 출발시키는 문이다.

```text
startLatch 닫힘
→ 준비된 모든 Task가 문 앞에서 대기

startLatch 개방
→ 대기 중인 모든 Task가 Claim 시작
```

`readyLatch`가 준비 상태를 확인하는 카운터라면 `startLatch`는 실제 출발 신호다.

#### Claim

Claim은 처리할 Job 하나를 가져와서 “이 Job은 내가 처리한다”라고 소유권을 확보하는 동작이다.

```text
PENDING Job 선택
→ 다른 Worker와 겹치지 않도록 DB Row Lock 획득
→ PROCESSING 전환
→ locked_by_worker_id 기록
→ claim_token 기록
→ Lease 시간 기록
→ Commit
```

단순 조회와 다른 점은 DB에 현재 소유자를 기록한다는 것이다. Claim 성공 후에는 다른 Worker가 같은 Job을
동시에 자신의 작업으로 가져가면 안 된다.

#### 전체 실행 흐름

```text
Executor가 Task들을 실행
        ↓
각 Task가 readyLatch 감소
        ↓
각 Task가 startLatch 앞에서 대기
        ↓
모든 Task가 준비되면 startLatch 개방
        ↓
모든 Task가 거의 동시에 Claim 시도
        ↓
DB의 FOR UPDATE SKIP LOCKED가 소유권 경쟁 제어
        ↓
한 Job에는 한 Worker만 Claim 성공
```

예를 들어 Worker 100개가 하나의 Job을 동시에 Claim하면 정상적인 구현에서는 한 Worker만 Claim에
성공해야 한다. 나머지 99개 Worker는 오류가 아니라 현재 Claim할 수 있는 Job이 없다는 빈 결과를 받는다.

```text
Task       = 실행할 작업
Executor   = Task를 실행하는 Thread 관리자
readyLatch = 모두 준비됐는지 확인하는 카운터
startLatch = 준비된 Task를 동시에 출발시키는 문
Claim      = Job 처리 권한과 소유권을 가져오는 동작
```

## 5. 내부 동작 원리

### 5.1 Java Worker와 실제 Worker Row

단일 Job 시나리오는 다음 자원을 만든다.

```text
worker_nodes Row = 100개
ExecutorService Thread = 100개
PENDING embedding_jobs Row = 1개
```

각 Thread에는 서로 다른 `worker_nodes.id`를 전달한다.

```text
Thread 1   → Worker ID 1
Thread 2   → Worker ID 2
...
Thread 100 → Worker ID 100
```

`worker_name`은 모두 `indexing-worker`로 같아도 된다. Worker 역할명은 중복될 수 있고 실제 프로세스
실행 단위는 Unique `instance_id`로 구분하기 때문이다.

### 5.2 동시 시작 Gate

Executor에 Task를 제출하는 것만으로는 동시 실행이 보장되지 않는다.

```text
Task 1 제출
→ Task 1 Claim 완료
→ Task 2 실행
```

테스트는 `readyLatch`와 `startLatch`를 사용한다.

```text
1. 모든 Task를 Executor에 제출한다.
2. 각 Task가 readyLatch.countDown()을 호출한다.
3. 각 Task는 startLatch에서 기다린다.
4. 메인 Test Thread가 readyLatch = 0인지 확인한다.
5. 메인 Test Thread가 startLatch를 연다.
6. 준비된 모든 Worker가 Claim 호출을 시작한다.
```

Executor Thread 수는 Worker 수와 같아야 한다.

```text
Worker Task 100개
Executor Thread 10개
→ 처음 10개만 Gate에 도착
→ 나머지 90개는 실행되지 못함
→ readyLatch가 0이 되지 않는 교착
```

따라서 단일 Job 시나리오는 Thread 100개, 다중 Job 시나리오는 Thread 20개를 만든다.

### 5.3 Thread별 독립 Transaction

테스트는 Spring이 관리하는 실제 `EmbeddingJobClaimService` Bean을 호출한다.

```text
Thread A
→ Spring Transaction Proxy
→ Transaction A

Thread B
→ Spring Transaction Proxy
→ Transaction B
```

테스트 메서드에는 `@Transactional`을 붙이지 않는다. Worker Thread가 Service Proxy를 호출할 때 각
Thread에 새로운 Transaction이 연결되도록 하기 위해서다.

Repository를 직접 호출하지 않고 Service를 호출하므로 다음 전체 동작을 함께 검증한다.

```text
Worker 생존 검증
Queue Row Lock
상태 전환
Worker 소유권
Claim Token
Lease
LOCKED 이벤트
Commit 원자성
```

### 5.4 Java Thread 100개와 DB Connection 20개의 차이

테스트 설정은 다음과 같다.

```text
Java Worker Thread = 100
Hikari Maximum Pool Size = 20
Hikari Minimum Idle = 20
Hikari Connection Timeout = 60,000ms
```

100개 Task가 동시에 시작되지만 DB에서 동시에 SQL을 실행할 수 있는 Transaction은 최대 20개다.

```text
Worker 요청 100개
→ Hikari Connection 경쟁
→ 20개가 DB Transaction 실행
→ 80개는 Connection 반환 대기
```

Connection을 100개로 설정하지 않는 이유는 다음과 같다.

- OpenSQL의 최대 Connection을 테스트 하나가 모두 점유할 수 있다.
- Flyway와 검증 SQL이 Connection을 얻지 못할 수 있다.
- Connection 고갈 오류가 Row Lock 정합성 결과를 가릴 수 있다.
- 실제 애플리케이션도 제한된 Connection Pool을 통해 Backpressure를 건다.

따라서 이 테스트가 증명하는 표현은 다음과 같다.

```text
100개의 Worker 요청 Burst가 제한된 Connection Pool을 통과해도
Job 소유권의 중복과 누락이 발생하지 않는다.
```

Connection Pool 크기별 처리량은 이 테스트의 범위가 아니다.

### 5.5 단일 Job에서 가능한 실행 순서

첫 Transaction이 Job Row Lock을 얻는다.

```text
Transaction A
→ PENDING Job SELECT FOR UPDATE SKIP LOCKED
→ Row Lock 획득
→ PROCESSING 변경
→ Commit
```

Transaction A가 Commit하기 전에 조회한 다른 Transaction은 잠긴 Row를 건너뛴다.

```text
Transaction B
→ 같은 PENDING Row 확인
→ 다른 Transaction이 Lock 보유
→ SKIP LOCKED
→ Optional.empty()
```

Transaction A가 Commit한 뒤 조회한 Transaction은 해당 Row가 더 이상 PENDING이 아니므로 선택하지 않는다.

```text
Transaction C
→ WHERE status = 'PENDING'
→ Job 상태는 PROCESSING
→ Optional.empty()
```

실행 시점이 Lock 전이든 Commit 후이든 성공 결과는 한 건이어야 한다.

### 5.6 Queue 마지막 구간의 빈 결과

Job 1,000개를 Worker 20개가 처리할 때 마지막에는 다음 상황이 생길 수 있다.

```text
남은 PENDING Job = 5개
동시에 Claim 중인 Worker = 20개
```

5개 Worker가 남은 Row를 잠그면 나머지 Worker는 잠기지 않은 PENDING Row가 없어 빈 결과를 받는다.

```text
Worker 1~5
→ 마지막 5개 Row Claim 진행

Worker 6~20
→ 모든 남은 PENDING Row가 잠김
→ SKIP LOCKED
→ Optional.empty()
→ 반복 종료
```

마지막 5개 Transaction이 Commit되면 최종 PENDING은 0이다. 하나라도 Rollback되면 PENDING Row가 남고
최종 Assertion이 실패한다.

## 6. 사전 요구사항

다음 도구가 필요하다.

```text
Docker Desktop
Java 17
Git
저장소의 Gradle Wrapper
```

버전 확인 명령어:

```bash
docker --version
java -version
git --version
./gradlew --version
```

기대할 핵심 정보:

```text
Java = 17
Gradle이 Java 17 Toolchain을 사용
Docker Server에 연결 가능
```

Docker Client 버전만 출력되고 Server 연결 오류가 발생하면 Docker Desktop을 먼저 실행한다.

```bash
open -a Docker
```

Docker Engine 준비 확인:

```bash
docker info --format '{{.ServerVersion}}'
```

## 7. 프로젝트 디렉터리로 이동

```bash
cd /Users/giminkim/IdeaProjects/backend
```

현재 브랜치 확인:

```bash
git status -sb
git branch --show-current
```

이 테스트 작업 브랜치에서 실행할 때 기대값:

```text
test/52
```

## 8. OpenSQL Image 준비

### 8.1 기존 Image 확인

```bash
docker image inspect backend-postgres:latest
```

정상이라면 Image JSON 정보가 출력된다. `No such image`가 나오면 다음 명령으로 저장소의 OpenSQL과
pgvector Image를 빌드한다.

```bash
docker build \
  --platform linux/amd64 \
  -t backend-postgres:latest \
  -f docker/opensql/Dockerfile \
  docker/opensql
```

이 Image는 기본 OpenSQL Image 위에 pgvector 0.8.0을 컴파일하므로 최초 빌드에는 시간이 걸릴 수 있다.

### 8.2 테스트 포트 확인

이 가이드에서는 기존 개발 DB와 충돌하지 않도록 `55433` 포트를 사용한다.

```bash
lsof -nP -iTCP:55433 -sTCP:LISTEN
```

아무것도 출력되지 않으면 사용할 수 있다. 다른 프로세스가 출력되면 그 프로세스를 확인하거나 테스트
컨테이너와 환경 변수에서 동일하게 다른 포트를 사용한다.

## 9. 격리 OpenSQL 컨테이너 생성

### 9.1 컨테이너 실행

다음 명령을 그대로 실행한다.

```bash
docker run -d \
  --platform linux/amd64 \
  --name docgrid-claim-concurrency-opensql \
  -e POSTGRES_DB=docgrid \
  -e POSTGRES_USER=docgrid \
  -e POSTGRES_PASSWORD=docgrid1234 \
  -p 55433:5432 \
  -v docgrid_claim_concurrency_opensql_data:/var/lib/pgsql \
  -v /Users/giminkim/IdeaProjects/backend/docker/opensql/vars.yml:/tmp/settings/vars/vars.yml:ro \
  backend-postgres:latest
```

`vars.yml` Mount를 빼면 OpenSQL Ansible 초기화가 실패한다. 저장소 경로가 다르면 다음 부분을 자신의
절대 경로로 바꾼다.

```text
/Users/giminkim/IdeaProjects/backend/docker/opensql/vars.yml
```

### 9.2 초기화 로그 확인

```bash
docker logs -f docgrid-claim-concurrency-opensql
```

최초 실행은 OpenSQL Ansible 초기화 때문에 수 분 걸릴 수 있다. 로그 Follow를 종료할 때는
`Control + C`를 누른다. 컨테이너는 종료되지 않는다.

### 9.3 준비 상태 반복 확인

OpenSQL의 Ansible 초기화 과정에서는 기본 `postgres` Database가 먼저 연결 가능해지고, 그 뒤 Image의
시작 Script가 `docgrid` Database와 pgvector Extension을 만든다. `pg_isready`만 확인하면 `docgrid`가 아직
없는 중간 시점에도 준비됐다고 판단할 수 있으므로 실제 `docgrid` SQL 성공까지 기다린다.

```bash
until docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid -tAc 'SELECT 1' >/dev/null 2>&1; do
  sleep 5
done
```

준비 완료 확인:

```bash
docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid -tAc 'SELECT 1'
```

정상 결과는 `1`이다.

컨테이너 상태 확인:

```bash
docker ps --filter name=docgrid-claim-concurrency-opensql
```

### 9.4 Database와 pgvector 확인

```bash
docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid \
  -c "SELECT current_database(), current_user, version();"
```

```bash
docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid \
  -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"
```

두 번째 명령에서 `vector`가 한 행 출력되어야 한다.

## 10. 테스트 환경 변수 설정

현재 Terminal Session에 다음 값을 설정한다.

```bash
export DB_HOST=localhost
export DB_PORT=55433
export DB_NAME=docgrid
export DB_USER=docgrid
export DB_PASSWORD=docgrid1234
export DB_SSLMODE=disable
export JWT_SECRET='claim-concurrency-test-secret-key-must-be-at-least-sixty-four-characters-long-20260723'
```

설정 확인:

```bash
env | grep '^DB_'
```

JWT 값은 Test ApplicationContext 생성에 필요하다. 운영 Secret이 아니라 로컬 테스트 전용 문자열을
사용한다.

환경 변수를 `application.yml`에 직접 적지 않는다.

## 11. 빠른 사전 검증

### 11.1 테스트 코드 컴파일

```bash
./gradlew compileTestJava
```

기대 결과:

```text
BUILD SUCCESSFUL
```

### 11.2 전용 Task 등록 확인

```bash
./gradlew tasks --group verification
```

출력에 다음 Task가 있어야 한다.

```text
claimConcurrencyTest
```

## 12. 시나리오별 실행

### 12.1 Worker 100개와 Job 1개

```bash
./gradlew claimConcurrencyTest \
  --tests '*EmbeddingJobClaimConcurrencyIntegrationTest.claim_singleJobWithOneHundredWorkers_assignsExactlyOneOwner' \
  --rerun-tasks
```

검증 내용:

```text
전체 Worker 요청 = 100
성공 = 1
빈 결과 = 99
예외 = 0
LOCKED 이벤트 = 1
```

### 12.2 Worker 20개와 Job 1,000개

```bash
./gradlew claimConcurrencyTest \
  --tests '*EmbeddingJobClaimConcurrencyIntegrationTest.claim_oneThousandJobsWithTwentyWorkers_claimsEveryJobExactlyOnce' \
  --rerun-tasks
```

검증 내용:

```text
초기 PENDING = 1,000
성공 Claim = 1,000
고유 Job ID = 1,000
최종 PENDING = 0
최종 PROCESSING = 1,000
LOCKED 이벤트 = 1,000
```

## 13. 전체 Claim 동시성 테스트 실행

```bash
./gradlew claimConcurrencyTest --rerun-tasks
```

정상 종료:

```text
BUILD SUCCESSFUL
```

테스트가 남기는 요약 로그 예시:

```text
단일 Job Claim 경쟁 완료: workers=100, success=1, empty=99, elapsedMs=...
다중 Job Claim 경쟁 완료: workers=20, jobs=1000, uniqueClaims=1000, elapsedMs=...
```

Worker별 Claim 수는 다를 수 있다.

```text
Worker A = 70
Worker B = 41
Worker C = 53
```

분배가 균등하지 않다는 이유로 실패하지 않는다. `SKIP LOCKED`는 공정한 분배가 아니라 중복 없는 병렬
선택을 제공한다.

## 14. 5회 반복 검증

동시성 오류는 Thread Scheduling에 따라 간헐적으로 발생할 수 있으므로 한 번의 성공으로 끝내지 않는다.

```bash
for run in 1 2 3 4 5; do
  echo "===== claim concurrency run ${run}/5 ====="
  ./gradlew claimConcurrencyTest --rerun-tasks || break
done
```

다섯 번 모두 다음 결과여야 한다.

```text
BUILD SUCCESSFUL
```

중간에 한 번이라도 실패하면 다시 실행해 성공 횟수를 채우지 않는다. 실패한 실행의 XML과 HTML Report를
먼저 확인한다.

## 15. 전체 Build 실행

전용 동시성 테스트 통과 후 기존 테스트가 깨지지 않았는지 확인한다.

```bash
./gradlew clean build
```

`clean build`는 무거운 `claim-concurrency` Tag를 제외한다. 따라서 병합 전 검증은 다음 두 명령이 모두
필요하다.

```bash
./gradlew clean build
./gradlew claimConcurrencyTest --rerun-tasks
```

## 16. 결과 Report 확인

### 16.1 HTML Report

```text
build/reports/tests/claimConcurrencyTest/index.html
```

macOS에서 열기:

```bash
open build/reports/tests/claimConcurrencyTest/index.html
```

HTML Report에서 확인할 항목:

```text
Tests
Failures
Duration
실패한 Assertion Message
Stack Trace
```

### 16.2 JUnit XML

```text
build/test-results/claimConcurrencyTest/
```

파일 목록:

```bash
find build/test-results/claimConcurrencyTest -type f -print
```

실패와 오류 검색:

```bash
rg -n '<failure|<error' build/test-results/claimConcurrencyTest
```

테스트 표준 출력 확인:

```bash
rg -n 'Claim 경쟁 완료|Worker Claim 분포' \
  build/test-results/claimConcurrencyTest
```

## 17. 테스트가 자동으로 확인하는 SQL 불변식

테스트는 실행 중 다음과 같은 의미의 SQL을 자동으로 수행한다.

### 17.1 최종 상태별 개수

```sql
SELECT status, COUNT(*)
FROM embedding_jobs
GROUP BY status
ORDER BY status;
```

1,000-Job 시나리오 기대값:

```text
PROCESSING = 1,000
PENDING = 0
```

### 17.2 소유권 누락

```sql
SELECT COUNT(*)
FROM embedding_jobs
WHERE status = 'PROCESSING'
  AND (
        locked_by_worker_id IS NULL
     OR claim_token IS NULL
     OR locked_at IS NULL
     OR lock_expires_at IS NULL
  );
```

기대값:

```text
0
```

### 17.3 LOCKED 이벤트 수

```sql
SELECT COUNT(*)
FROM indexing_events
WHERE event_type = 'LOCKED';
```

1,000-Job 시나리오 기대값:

```text
1,000
```

### 17.4 이벤트 중복 또는 누락 Job

```sql
SELECT job.id, COUNT(event.id) AS locked_event_count
FROM embedding_jobs job
LEFT JOIN indexing_events event
       ON event.embedding_job_id = job.id
      AND event.event_type = 'LOCKED'
GROUP BY job.id
HAVING COUNT(event.id) <> 1;
```

기대 결과:

```text
0 rows
```

## 18. 테스트 스키마를 남겨 직접 SQL 확인하기

기본 실행은 테스트 종료 후 전용 스키마를 자동 삭제한다.

수동 확인 결과를 결정적으로 만들기 위해 실행 순서는 다음과 같이 고정돼 있다.

```text
1. Worker 100개 / Job 1개
2. Worker 20개 / Job 1,000개
```

각 테스트는 `@BeforeEach`에서 독립적으로 데이터를 초기화한다. 순서를 고정한 이유는 테스트 간 의존성
때문이 아니라 스키마를 보존했을 때 마지막 1,000-Job 결과를 항상 같은 SQL로 확인하기 위해서다.

수동으로 DB 결과를 보고 싶으면 환경 변수를 추가해 실행한다.

```bash
export KEEP_CLAIM_CONCURRENCY_SCHEMA=true
./gradlew claimConcurrencyTest --rerun-tasks
```

테스트 종료 로그에 다음 경고가 나타난다.

```text
수동 검증을 위해 테스트 스키마를 유지합니다
```

OpenSQL 접속:

```bash
docker exec -it docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid
```

psql 안에서 Search Path 설정:

```sql
SET search_path TO docgrid_embedding_job_claim_concurrency_test, public;
```

상태 확인:

```sql
SELECT status, COUNT(*)
FROM embedding_jobs
GROUP BY status
ORDER BY status;
```

Worker별 Claim 분포:

```sql
SELECT locked_by_worker_id, COUNT(*) AS claimed_jobs
FROM embedding_jobs
GROUP BY locked_by_worker_id
ORDER BY claimed_jobs DESC, locked_by_worker_id;
```

Token과 Lease 누락 확인:

```sql
SELECT id, locked_by_worker_id, claim_token, locked_at, lock_expires_at
FROM embedding_jobs
WHERE locked_by_worker_id IS NULL
   OR claim_token IS NULL
   OR locked_at IS NULL
   OR lock_expires_at IS NULL;
```

중복 Token 확인:

```sql
SELECT claim_token, COUNT(*)
FROM embedding_jobs
GROUP BY claim_token
HAVING COUNT(*) > 1;
```

이벤트 누락·중복 확인:

```sql
SELECT job.id, COUNT(event.id) AS event_count
FROM embedding_jobs job
LEFT JOIN indexing_events event
       ON event.embedding_job_id = job.id
      AND event.event_type = 'LOCKED'
GROUP BY job.id
HAVING COUNT(event.id) <> 1;
```

psql 종료:

```text
\q
```

수동 확인이 끝나면 스키마를 삭제한다.

```bash
docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid \
  -c "DROP SCHEMA IF EXISTS docgrid_embedding_job_claim_concurrency_test CASCADE;"
```

환경 변수도 해제한다.

```bash
unset KEEP_CLAIM_CONCURRENCY_SCHEMA
```

## 19. IntelliJ에서 실행하기

가장 재현성이 높은 방법은 IntelliJ Terminal에서 Gradle 명령을 사용하는 것이다.

```bash
./gradlew claimConcurrencyTest --rerun-tasks
```

Gradle Run Configuration을 만들 경우:

```text
Run
→ Edit Configurations
→ +
→ Gradle
```

설정값:

```text
Name: Claim Concurrency Test
Gradle project: backend
Tasks: claimConcurrencyTest --rerun-tasks
```

Environment Variables:

```text
DB_HOST=localhost
DB_PORT=55433
DB_NAME=docgrid
DB_USER=docgrid
DB_PASSWORD=docgrid1234
DB_SSLMODE=disable
JWT_SECRET=claim-concurrency-test-secret-key-must-be-at-least-sixty-four-characters-long-20260723
```

JUnit Class 옆 실행 버튼을 직접 누르면 `claimConcurrencyTest`의 Gradle 설정과 환경 변수가 적용되지 않을
수 있다. 처음 실행할 때는 전용 Gradle Task를 권장한다.

## 20. 실패 진단

### 20.1 Docker API 연결 실패

오류 예시:

```text
Cannot connect to the Docker daemon
```

해결:

```bash
open -a Docker
docker info
```

### 20.2 포트 사용 중

오류 예시:

```text
Bind for 0.0.0.0:55433 failed: port is already allocated
```

확인:

```bash
lsof -nP -iTCP:55433 -sTCP:LISTEN
docker ps --format '{{.Names}}\t{{.Ports}}'
```

기존 테스트 컨테이너가 남아 있다면 상태를 확인한 뒤 다시 사용하거나 아래 정리 절차로 제거한다.

### 20.3 OpenSQL Ansible 초기화 실패

오류 예시:

```text
/tmp/settings/vars/vars.yml : No such file or directory
Failed Ansible play
```

원인:

```text
docker/opensql/vars.yml Volume Mount 누락
```

컨테이너 생성 명령에 다음 Mount가 포함됐는지 확인한다.

```text
-v /Users/giminkim/IdeaProjects/backend/docker/opensql/vars.yml:/tmp/settings/vars/vars.yml:ro
```

실패한 테스트 전용 컨테이너와 볼륨을 정리한 후 다시 생성한다.

```bash
docker rm docgrid-claim-concurrency-opensql
docker volume rm docgrid_claim_concurrency_opensql_data
```

### 20.4 Connection Refused

오류 예시:

```text
Connection to localhost:55433 refused
```

확인:

```bash
docker ps --filter name=docgrid-claim-concurrency-opensql
docker logs --tail 200 docgrid-claim-concurrency-opensql
docker exec docgrid-claim-concurrency-opensql \
  /usr/pgsql-14/bin/pg_isready -U docgrid -d docgrid
```

OpenSQL 최초 초기화가 끝나기 전에 테스트를 실행하지 않는다.

### 20.5 Database 또는 Role 없음

오류 예시:

```text
database "docgrid" does not exist
role "docgrid" does not exist
```

확인:

```bash
docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d postgres \
  -c "SELECT datname FROM pg_database ORDER BY datname;"
```

`vars.yml`을 사용한 초기화가 완료됐는지 로그를 확인한다.

### 20.6 pgvector 타입 없음

오류 예시:

```text
type "vector" does not exist
```

확인:

```bash
docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid \
  -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"
```

없으면 테스트 전용 DB에 Extension을 생성한다.

```bash
docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid \
  -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### 20.7 Worker가 DEAD로 판정됨

오류 예시:

```text
WORKER_NOT_AVAILABLE
```

테스트는 동적 설정으로 DEAD 기준을 10분으로 늘린다.

```text
indexing.worker.dead-threshold=10m
```

이 설정이 적용되지 않았다면 테스트를 일반 JUnit Configuration이 아니라 `claimConcurrencyTest` Task로
실행했는지 확인한다.

### 20.8 Hikari Connection Timeout

오류 예시:

```text
Connection is not available, request timed out
```

확인할 항목:

```text
OpenSQL이 정상 실행 중인가?
다른 애플리케이션이 같은 DB Connection을 점유하는가?
이 테스트를 여러 Terminal에서 동시에 실행했는가?
Docker에 할당된 CPU와 Memory가 지나치게 낮은가?
```

Claim 동시성 테스트는 한 번에 하나만 실행한다. Timeout을 무작정 늘리기 전에 DB와 중복 테스트 실행을 먼저
확인한다.

### 20.9 성공 수가 1보다 큼

단일 Job 테스트에서 성공이 2건 이상이면 핵심 소유권 불변식이 깨진 것이다.

확인 순서:

```text
1. 실패한 JUnit XML 보존
2. 테스트 재실행 전에 로그 보존
3. EmbeddingJobRepository 쿼리 확인
4. Service의 @Transactional 경계 확인
5. Entity 상태 전이와 Commit 순서 확인
6. Assertion을 완화하지 않고 별도 Fix Issue 생성
```

### 20.10 응답은 1,000개지만 고유 Job이 1,000개 미만

이 결과는 같은 Job이 두 번 응답되고 다른 Job이 누락됐다는 뜻이다.

```text
응답 개수 = 1,000
고유 Job ID = 999
```

응답 개수만 검증했다면 놓칠 수 있지만 현재 테스트는 Job ID 집합 비교로 실패한다.

### 20.11 LOCKED 이벤트가 1,000개가 아님

```text
Job = 1,000
LOCKED Event = 999
```

Job 상태 변경과 이벤트 저장이 같은 Transaction으로 Commit되지 않았거나 특정 Transaction에서 이벤트가
누락됐을 가능성이 있다.

Job별 Group 결과를 확인한다.

```sql
SELECT job.id, COUNT(event.id)
FROM embedding_jobs job
LEFT JOIN indexing_events event
       ON event.embedding_job_id = job.id
      AND event.event_type = 'LOCKED'
GROUP BY job.id
HAVING COUNT(event.id) <> 1;
```

## 21. 테스트 환경 정리

### 21.1 컨테이너 중지 및 삭제

테스트 전용 컨테이너만 삭제한다.

```bash
docker stop docgrid-claim-concurrency-opensql
docker rm docgrid-claim-concurrency-opensql
```

### 21.2 테스트 데이터 볼륨 삭제

앞으로 재사용하지 않을 때만 이 테스트가 만든 전용 볼륨을 삭제한다.

```bash
docker volume rm docgrid_claim_concurrency_opensql_data
```

이 명령은 다음 개발용 볼륨을 건드리지 않는다.

```text
opensql_data
```

### 21.3 환경 변수 해제

```bash
unset DB_HOST
unset DB_PORT
unset DB_NAME
unset DB_USER
unset DB_PASSWORD
unset DB_SSLMODE
unset JWT_SECRET
unset KEEP_CLAIM_CONCURRENCY_SCHEMA
```

## 22. 전체 명령어 빠른 실행본

OpenSQL Image가 이미 있는 경우 다음 순서로 실행할 수 있다.

```bash
cd /Users/giminkim/IdeaProjects/backend

open -a Docker

docker run -d \
  --platform linux/amd64 \
  --name docgrid-claim-concurrency-opensql \
  -e POSTGRES_DB=docgrid \
  -e POSTGRES_USER=docgrid \
  -e POSTGRES_PASSWORD=docgrid1234 \
  -p 55433:5432 \
  -v docgrid_claim_concurrency_opensql_data:/var/lib/pgsql \
  -v /Users/giminkim/IdeaProjects/backend/docker/opensql/vars.yml:/tmp/settings/vars/vars.yml:ro \
  backend-postgres:latest

until docker exec docgrid-claim-concurrency-opensql \
  psql -U docgrid -d docgrid -tAc 'SELECT 1' >/dev/null 2>&1; do
  sleep 5
done

export DB_HOST=localhost
export DB_PORT=55433
export DB_NAME=docgrid
export DB_USER=docgrid
export DB_PASSWORD=docgrid1234
export DB_SSLMODE=disable
export JWT_SECRET='claim-concurrency-test-secret-key-must-be-at-least-sixty-four-characters-long-20260723'

./gradlew compileTestJava
./gradlew clean build

for run in 1 2 3 4 5; do
  echo "===== claim concurrency run ${run}/5 ====="
  ./gradlew claimConcurrencyTest --rerun-tasks || break
done

open build/reports/tests/claimConcurrencyTest/index.html
```

테스트가 모두 끝난 뒤 정리:

```bash
docker stop docgrid-claim-concurrency-opensql
docker rm docgrid-claim-concurrency-opensql
docker volume rm docgrid_claim_concurrency_opensql_data
```

## 23. 완료 기준

- Worker 100개가 Job 하나를 동시에 Claim해도 성공은 정확히 한 건이다.
- 나머지 99개 Worker는 예외가 아니라 빈 Queue 결과를 받는다.
- 성공 응답의 Worker, Token, Lease가 최종 DB Row와 일치한다.
- 단일 Job의 LOCKED 이벤트가 정확히 한 건이다.
- Worker 20개가 PENDING Job 1,000개를 모두 Claim한다.
- 응답 Job ID 1,000개가 모두 고유하다.
- 초기 Job ID 집합과 응답 Job ID 집합이 같다.
- 최종 PENDING은 0이고 PROCESSING은 1,000이다.
- 모든 PROCESSING Job에 Worker, Token, Lease가 존재한다.
- 모든 Job에 LOCKED 이벤트가 정확히 한 건 존재한다.
- Thread Timeout과 Executor 미종료가 없다.
- 전용 Task가 5회 연속 통과한다.
- 전체 Build가 통과한다.
- 실제 실행 결과를 아래 기록에 남긴다.

## 24. 실제 검증 결과

검증 일시:

```text
2026-07-23 KST
```

환경:

```text
OpenSQL Image: backend-postgres:latest
PostgreSQL Version: 14.6
pgvector Version: 0.8.0
Database: docgrid
Host Architecture: Apple Silicon arm64
Container Architecture: linux/amd64
Java Version: Temurin 17.0.18
Hikari Maximum Pool Size: 20
Java Worker Thread: 단일 Job 100 / 다중 Job 20
```

단일 Job 경쟁:

```text
PENDING Job: 1
ACTIVE Worker: 100
Claim 성공: 1
빈 결과: 99
예외: 0
LOCKED 이벤트: 1
최종 수동 검증 실행의 동시 Claim 구간: 약 189ms
```

다중 Job Queue 소진:

```text
PENDING Job: 1,000
ACTIVE Worker: 20
성공 Claim: 1,000
고유 Job ID: 1,000
중복 Claim: 0
누락 Job: 0
최종 PENDING: 0
최종 PROCESSING: 1,000
불완전 소유권: 0
LOCKED 이벤트: 1,000
이벤트 중복 또는 누락 Job: 0
최종 수동 검증 실행의 동시 Claim 구간: 약 648ms
```

반복 및 회귀 검증:

```text
claimConcurrencyTest 최초 검증: 통과
claimConcurrencyTest 5회 연속: 5/5 통과
./gradlew clean build: 통과
기존 Test: 199개
기존 Test Failure: 0
기존 Test Error: 0
스키마 보존 후 수동 SQL 검증: 통과
수동 SQL PROCESSING: 1,000
수동 SQL 불완전 소유권: 0
수동 SQL LOCKED 이벤트: 1,000
수동 SQL 이벤트 중복·누락 Job: 0
수동 SQL 중복 Token: 0
```

첫 실행에서는 Hikari `connection-timeout`을 `60s` 문자열로 설정해 ApplicationContext Binding이
실패했다. HikariDataSource의 해당 속성은 Spring `Duration`이 아니라 밀리초 `long`을 받으므로
`60000`으로 수정한 뒤 모든 검증을 통과했다. 이 실패를 통해 설정 단위까지 실제 ApplicationContext에서
검증했다.

위 실행 시간은 성능 기준선이 아니다. Docker와 Host 상태에 따라 달라질 수 있으며 이 PR의 합격 기준은
중복·누락·부분 소유권이 모두 0인지 여부다.
