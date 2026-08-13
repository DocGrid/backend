# Sync 장애 주입·복구 검증 결과

- 관련 이슈: #168
- 실행 일시: 2026-08-13 (Asia/Seoul)
- 환경: Java 17, Spring Boot test profile, PostgreSQL 17.8 + pgvector
- 격리 스키마:
  - `docgrid_sync_dispatch_failure_test`
  - `docgrid_embedding_failure_recovery_test`

## 성공 기준

- 장애 뒤 Event, Job, Attempt, Version, Document가 유효한 상태로 수렴한다.
- Job, Chunk, Vector 중복이 없다.
- Handler 부작용과 Event 완료 사이의 부분 Commit이 없다.
- 실패 원인과 복구 실행 이력이 남는다.
- Reconciler 재검사 결과 활성 불일치가 없다.

## 장애 지점별 결과

| 장애 지점 | 주입 방법 | 장애 직후 상태 | 복구 경로 | 최종 결과 |
|---|---|---|---|---|
| Event Claim 직후 | Claim Commit 뒤 테스트 프로세스 예외 | Event `PROCESSING`, Job 0건 | Sync Lease Recovery → 재Claim | Event `PROCESSED`, Job 1건 |
| Handler 시작 직후 | 필수 payload 제거 | Event 완료 안 됨, Job 0건 | 실패 기록 → payload 복원 → Retry | Event `PROCESSED`, Job 1건 |
| Job 생성 직후 | `embedding_jobs` AFTER INSERT Trigger 예외 | Job INSERT와 Dispatch Transaction Rollback | 실패 기록 → Retry | Job 1건, 중복 0건 |
| Embedding 저장 도중 | 두 번째 `embeddings` INSERT Trigger 예외 | Vector 0건, Version `EMBEDDING` | Worker Lease Recovery → 새 Attempt | Vector 3건, 중복 0건 |
| Event 완료 직전 | `PROCESSED` UPDATE Trigger 예외 | Handler Job과 Attempt 성공 전이 Rollback | 실패 기록 → Retry | Event `PROCESSED`, Job 1건 |

## 복구 이력 검증

- Sync Event Claim마다 `sync_event_delivery_attempts`에 별도 실행 이력을 남겼다.
- Lease 만료 Attempt는 `FAILED / SYNC_LEASE_EXPIRED`로 보존됐다.
- Handler·Job·완료 장애는 각각 주입 지점별 오류 코드와 완료 시각을 보존했다.
- 복구 Claim은 별도 Attempt로 생성되어 `SUCCEEDED`로 종결됐다.
- Embedding 첫 Attempt는 `FAILED / WORKER_LEASE_EXPIRED`, 두 번째 Attempt는 `SUCCESS`로 종결됐다.
- `indexing_events`에 `LEASE_EXPIRED`, 단계 실패, `RETRY`, 최종 `INDEXED` 흐름이 남았다.

## Reconciliation 검증

1. 완료 Event만 있고 Job이 없는 손상 상태를 만들었다.
2. `REPAIR` 검사에서 `MISSING_JOB` 한 건을 탐지하고 Repair Event 한 건을 생성했다.
3. Dispatcher가 Repair Event를 처리해 Job 한 건을 복원했다.
4. `DRY_RUN` 재검사에서 탐지 0건, 복구 요청 0건을 확인했다.
5. 기존 `MISSING_JOB` Issue는 `RESOLVED`로 종결됐다.
6. Embedding 장애 복구 완료 후에도 탐지 0건, `current_version_id` 일치, Vector 중복 0건을 확인했다.

이 과정에서 실제 Reconciler 실행이 `document_chunks`에 존재하지 않는 `document_id`를 직접 참조하는
고아 검사 오류를 발견했다. Document 원장은 `document_versions.document_id`를 통해 조인하도록 수정했고,
장애 복구 후 실제 Reconciliation 통합 테스트로 재검증했다.

## 실행 명령과 결과

```bash
DB_PORT=5432 \
JWT_SECRET=test-only-secret-key-with-at-least-32-characters \
./gradlew test \
  --tests '*SyncDispatchFailureRecoveryIntegrationTest' \
  --tests '*EmbeddingFailureRecoveryIntegrationTest' \
  --tests '*SyncEventClaimServiceTest' \
  --tests '*SyncEventDispatchServiceTest'
```

- 결과: `BUILD SUCCESSFUL`

```bash
DB_PORT=5432 \
JWT_SECRET=test-only-secret-key-with-at-least-32-characters \
./gradlew test
```

- 결과: `BUILD SUCCESSFUL in 34s`
- PostgreSQL 장애 Trigger는 테스트별 격리 스키마에만 만들고 각 테스트와 클래스 종료 시 제거했다.
