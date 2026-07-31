# 문서 인덱싱 완료 구현 검증 결과 (#84)

## 1. 검증 개요

- 실행일: 2026-07-31 (Asia/Seoul)
- 대상 브랜치: `feature/84`
- 데이터베이스: 격리된 OpenSQL/PostgreSQL 14 + pgvector
- Vector Schema: `vector(1024)`
- 결과: 전체 빌드와 432개 테스트 성공, 실패 0건

기존 개발 데이터 볼륨은 사용하거나 변경하지 않았다. 검증 전용 컨테이너와 전용 볼륨에서 Flyway
Migration 및 Seed를 적용하고, 테스트 Class별 격리 Schema를 사용했다.

## 2. 실행 결과

### 2.1 Domain 전이와 완료 Service 단위 테스트

다음 계약의 성공·거부 경로가 통과했다.

- `EmbeddingJob`: `PROCESSING → INDEXED`
- `EmbeddingJobAttempt`: `STARTED → SUCCESS`
- `DocumentVersion`: `EMBEDDING → INDEXED`
- `Document`: 같은 Document의 완료 Version 활성화
- 최초 완료의 최신 Version, 전체 Embedding Set, Model, 이벤트 사전 상태 검증
- 완료 재생의 Worker·Token·Attempt 및 저장 완료 상태 검증
- Lease 만료와 후속 Version 활성화 뒤에도 최초 완료 결과 재생
- 관리자 API의 요청 Validation, ADMIN 권한, 오류 응답과 민감 필드 비노출

실행한 대표 Test Class:

```text
DocumentTest
DocumentVersionTest
EmbeddingJobTest
EmbeddingJobAttemptTest
DocumentIndexingCompletionServiceTest
IndexingJobAdminControllerTest
```

### 2.2 Repository와 OpenSQL 검색 전환

실제 OpenSQL에서 다음 Test Class가 통과했다.

```text
IndexingCompletionRepositoryTest
DocumentIndexingCompletionIntegrationTest
```

검증한 내용:

1. Version 전체·Model별·ACTIVE Embedding 개수 집계
2. Vector를 JVM으로 읽지 않는 `vector_dims`·관계·Hash 불일치 집계
3. 이전 Version ACTIVE Embedding의 STALE bulk update
4. 최초 Version 완료 전 권한 pre-filter·Vector Search 제외
5. 최초 Version 완료 후 current ACTIVE Version 검색 노출
6. 새 Version 완료 전 이전 본문만 검색
7. 새 Version 완료 후 이전 STALE·새 ACTIVE 및 새 본문만 검색
8. Attempt·Job·Version·INDEXED 이벤트의 동일 완료 시각

### 2.3 동시 완료와 실패 Rollback

- 같은 Job·Attempt·Worker·Token의 두 Thread를 Barrier로 동시에 시작했다.
- 두 요청은 Job Pessimistic Lock으로 직렬화돼 같은 완료 응답으로 수렴했다.
- `INDEXED` 이벤트는 한 건만 저장됐다.
- PostgreSQL `TIMESTAMP` 정밀도에 맞춰 완료 시각을 microsecond로 고정해 최초 응답과 재생 응답을
  동일하게 유지했다.

Rollback 검증은 실제 OpenSQL의 테스트용 Trigger가 마지막 `INDEXED` 이벤트 Insert를 실패시키도록
구성했다. 실패 뒤 새 조회에서 다음 원상태를 확인했다.

- 이전 current Version Embedding: `ACTIVE`
- 대상 Version Embedding: `ACTIVE`
- 대상 Version: `EMBEDDING`
- Document current Version: 이전 Version
- Attempt: `STARTED`
- Job: `PROCESSING`
- `INDEXED` 이벤트: 0건

테스트용 Trigger와 Function은 검증 직후 제거했다.

## 3. 전체 회귀

실행:

```bash
./gradlew clean build
git diff --check
```

결과:

```text
BUILD SUCCESSFUL
tests=432 failures=0 errors=0
git diff --check: 통과
```

Gradle 기본 `test` 설정이 제외하는 `benchmark`, `minio-integration`, `claim-concurrency` 태그는 이번
전체 빌드 범위에도 포함되지 않았다. 이번 변경 전용 OpenSQL 검색 전환·동시 완료·Rollback 테스트는
기본 `test` 범위에 포함되어 실행됐다.
