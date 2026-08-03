# #88 인덱싱 실패 및 지연 재시도 검증 결과

## 1. 검증 정보

- 실행일: 2026-08-03
- 실행 환경: macOS Docker Desktop의 일회용 `docgrid-postgres:latest` 컨테이너
- 데이터베이스: PostgreSQL/OpenSQL, 테스트별 격리 Schema, Flyway V1~V35 적용
- 애플리케이션: Spring Boot 3.5.16, Java 17
- 브랜치: `codex/feature-88-indexing-failure-retry`

기존 로컬 DB 컨테이너와 영구 Volume은 변경하지 않았다. 일회용 컨테이너에는 호스트 테스트 접속을
위해 이미지가 생성한 `pg_hba.conf`를 다시 로드했으며, 운영 Secret은 사용하지 않았다.

## 2. 신규 PostgreSQL 통합 검증

실행 명령의 비밀 값은 placeholder로 대체한다.

```bash
DB_HOST=localhost \
DB_PORT=55432 \
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

## 3. 전체 회귀 검증

```bash
DB_HOST=localhost \
DB_PORT=55432 \
DB_NAME=docgrid \
DB_USER=docgrid \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<64-char-test-secret>' \
./gradlew test
```

결과: `BUILD SUCCESSFUL`, 511개 테스트 통과, 실패 0, Skip 0.

프로젝트 설정에 따라 `benchmark`, `minio-integration`, `claim-concurrency` Tag는 기본 `test`에서
제외됐다. 이번 변경과 직접 관련된 SKIP LOCKED 동시성은 아래 전용 Task로 추가 검증했다.

```bash
DB_HOST=localhost \
DB_PORT=55432 \
DB_NAME=docgrid \
DB_USER=docgrid \
DB_PASSWORD='<local-test-password>' \
DB_SSLMODE=disable \
JWT_SECRET='<64-char-test-secret>' \
./gradlew claimConcurrencyTest
```

결과: `BUILD SUCCESSFUL`, 2개 테스트 통과, 실패 0, Skip 0.

## 4. 확인된 불변식

- Retry 가능 여부는 요청 Boolean이 아니라 `IndexingFailureType` 서버 정책으로 결정된다.
- `max_retry_count`는 최초 실행 이후 허용할 Retry 횟수로 동작한다.
- Retry 예약은 Version과 Document 상태를 되돌리지 않고 현재 재개 지점을 보존한다.
- Retry 예약 시 이전 Worker, Claim Token과 Lease가 제거된다.
- 동일 실패 요청은 Retry 횟수와 이벤트를 중복 생성하지 않는다.
- 영구 실패 또는 Retry 소진 시 대상 Version의 ACTIVE Embedding은 STALE이 된다.
- 이전 INDEXED Version이 있으면 Document 상태와 현재 검색 포인터는 유지된다.
- 완료와 실패는 Job 행 잠금에서 직렬화되며 한쪽 상태만 커밋된다.
- 이벤트 저장 실패는 Attempt, Job, Version과 Document 변경을 모두 Rollback한다.
