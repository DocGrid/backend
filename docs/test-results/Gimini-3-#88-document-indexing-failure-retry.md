# #88 인덱싱 실패 및 지연 재시도 검증 결과

## 1. 검증 정보

- 실행일: 2026-08-03
- 실행 환경: macOS Docker Desktop의 일회용 `docgrid-postgres:latest` 컨테이너
- 데이터베이스: PostgreSQL 14.6(OpenSQL-PG 호환), 테스트별 격리 Schema, Flyway V1~V35 적용
- 애플리케이션: Spring Boot 3.5.16, Java 17
- 브랜치: `codex/feature-88-indexing-failure-retry`

기존 로컬 DB 컨테이너와 영구 Volume은 변경하지 않았다. 일회용 컨테이너에는 호스트 테스트 접속을
위해 이미지가 생성한 `pg_hba.conf`를 다시 로드했으며, 운영 Secret은 사용하지 않았다.

## 2. Swagger/OpenAPI 실제 HTTP 수동 검증

- 서버: Test Profile, `http://localhost:18089`
- Schema: `docgrid_pr89_swagger`
- 인증: Seed ADMIN 로그인 후 발급한 Bearer Token 사용, Token 값은 기록하지 않음
- Swagger 계약: `GET /v3/api-docs`가 `200 OK`이고 실패 Endpoint의 POST Operation이 존재함

### 2.1 최초 실패와 멱등 재생

최초 요청과 같은 요청을 한 번 더 전송했다.

```http
POST /admin/indexing-jobs/8911/attempts/8911/fail
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "workerId": 8901,
  "claimToken": "44444444-4444-4444-8444-444444444444",
  "failureType": "STORAGE_UNAVAILABLE",
  "errorMessage": "Storage timeout"
}
```

두 요청 모두 `200 OK`였고 전체 응답 JSON이 같았다.

```json
{
  "success": true,
  "status": 200,
  "data": {
    "jobId": 8911,
    "attemptId": 8911,
    "attemptNo": 1,
    "attemptStatus": "FAILED",
    "failureType": "STORAGE_UNAVAILABLE",
    "failedAt": "2026-08-03T11:01:33.1175",
    "durationMs": 25068
  },
  "timestamp": "2026-08-03 11:01:33"
}
```

### 2.2 요청 검증 실패

```http
POST /admin/indexing-jobs/8901/attempts/8901/fail
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "workerId": 8901,
  "claimToken": "11111111-1111-4111-8111-111111111111",
  "failureType": "STORAGE_UNAVAILABLE",
  "errorMessage": ""
}
```

결과: `400 Bad Request`, `COMMON-002`, `errorMessage: 공백일 수 없습니다`.

### 2.3 소유권 충돌

Job을 소유한 Worker `8901` 대신 `workerId=8902`로 요청했다.

```http
POST /admin/indexing-jobs/8902/attempts/8902/fail
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "workerId": 8902,
  "claimToken": "22222222-2222-4222-8222-222222222222",
  "failureType": "STORAGE_UNAVAILABLE",
  "errorMessage": "Storage timeout"
}
```

결과: `409 Conflict`, `EMBEDDING-JOB-003`,
`현재 Embedding Job 소유권과 요청이 일치하지 않습니다.`

### 2.4 상태 충돌

`PENDING` Job에 남겨 둔 `STARTED` Attempt로 실패를 요청했다.

```http
POST /admin/indexing-jobs/8903/attempts/8903/fail
Authorization: Bearer <admin-token>
Content-Type: application/json

{
  "workerId": 8901,
  "claimToken": "33333333-3333-4333-8333-333333333333",
  "failureType": "STORAGE_UNAVAILABLE",
  "errorMessage": "Storage timeout"
}
```

결과: `409 Conflict`, `EMBEDDING-JOB-002`,
`현재 상태에서는 Embedding Job Attempt를 시작할 수 없습니다.`

## 3. 신규 PostgreSQL 통합 검증

실행 명령의 비밀 값은 placeholder로 대체한다.

```bash
DB_HOST=localhost \
DB_PORT=55433 \
DB_NAME=docgrid \
DB_USER=docgrid \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<64-char-test-secret>' \
./gradlew test \
  --tests 'com.opensource.docgrid.domain.embedding.integration.DocumentIndexingFailureIntegrationTest'
```

결과: `BUILD SUCCESSFUL`, 5개 테스트 통과.

| 검증 항목 | 결과 |
| --- | --- |
| `next_retry_at` 이전 Queue 선택 제외 | 통과 |
| 정확한 예약 시각의 Queue 선택 허용 | 통과 |
| 동일 실패 동시 요청의 단일 Retry·동일 응답 수렴 | 통과 |
| 새 Version 최종 실패 시 이전 INDEXED 검색 Set 보존 | 통과 |
| 완료와 실패 동시 요청의 단일 상태 전이 | 통과 |
| RETRY 이벤트 Insert 실패 시 전체 Transaction Rollback | 통과 |

## 4. 전체 회귀 검증

```bash
DB_HOST=localhost \
DB_PORT=55433 \
DB_NAME=docgrid \
DB_USER=docgrid \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<64-char-test-secret>' \
./gradlew test
```

결과: `BUILD SUCCESSFUL`, 514개 테스트 통과, 실패 0, Skip 0.

프로젝트 설정에 따라 `benchmark`, `minio-integration`, `claim-concurrency` Tag는 기본 `test`에서
제외됐다. 이번 변경과 직접 관련된 SKIP LOCKED 동시성은 아래 전용 Task로 추가 검증했다.

```bash
DB_HOST=localhost \
DB_PORT=55433 \
DB_NAME=docgrid \
DB_USER=docgrid \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<64-char-test-secret>' \
./gradlew claimConcurrencyTest
```

결과: `BUILD SUCCESSFUL`, 2개 테스트 통과, 실패 0, Skip 0.

## 5. 확인된 불변식

- Retry 가능 여부는 요청 Boolean이 아니라 `IndexingFailureType` 서버 정책으로 결정된다.
- `max_retry_count`는 최초 실행 이후 허용할 Retry 횟수로 동작한다.
- Retry 예약은 Version과 Document 상태를 되돌리지 않고 현재 재개 지점을 보존한다.
- Retry 예약 시 이전 Worker, Claim Token과 Lease가 제거된다.
- 동일 실패 요청은 Retry 횟수와 이벤트를 중복 생성하지 않는다.
- 영구 실패 또는 Retry 소진 시 대상 Version의 ACTIVE Embedding은 STALE이 된다.
- 이전 INDEXED Version이 있으면 Document 상태와 현재 검색 포인터는 유지된다.
- 완료와 실패는 Job 행 잠금에서 직렬화되며 한쪽 상태만 커밋된다.
- 이벤트 저장 실패는 Attempt, Job, Version과 Document 변경을 모두 Rollback한다.
