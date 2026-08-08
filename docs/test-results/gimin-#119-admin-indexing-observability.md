# #119 관리자 인덱싱 Job·Attempt·Event 조회 검증 결과

## 1. 검증 정보

- 실행일: 2026-08-08 (Asia/Seoul)
- 대상 브랜치: `feature/119`
- 애플리케이션: Spring Boot 3.5.16, Java 17
- 데이터베이스: 로컬 PostgreSQL 17.8 + pgvector 0.8.1 컨테이너
- 검증 범위: 관리자 조회 Service·Converter·Controller·PostgreSQL Query와 전체 회귀 Test
- 최종 결과: 706개 통과, 실패·오류·Skip 0개

각 PostgreSQL 통합 Test는 독립 Schema와 전체 Flyway Migration을 사용한다. DB 접속 정보와 인증 값은
실행 Process의 Test 전용 환경변수로만 주입했으며 실제 운영 값은 사용하거나 기록하지 않았다.

## 2. 전체 회귀 검증

실행 명령의 값은 공개 가능한 Test 전용 Placeholder로 대체한다.

```bash
DB_SSLMODE=disable \
JWT_SECRET='<test-only-secret>' \
MINIO_ENDPOINT='<local-test-endpoint>' \
MINIO_ACCESS_KEY='<test-only-value>' \
MINIO_SECRET_KEY='<test-only-value>' \
MINIO_BUCKET='<test-bucket>' \
./gradlew test
```

결과:

```text
BUILD SUCCESSFUL
tests=706 failures=0 errors=0 skipped=0
```

기존 Command API Controller Test도 새 Query Service 의존성을 Mock으로 보강해 전체 Controller Context가
정상 기동하는지 함께 검증했다.

## 3. 단위 검증

| Test Class | Test 수 | 검증 범위 | 결과 |
|---|---:|---|---|
| `IndexingJobAdminQueryServiceTest` | 6 | 필터·Pagination 전달, 상세 Not Found, Attempt·Event 조회 | 통과 |
| `IndexingJobAdminConverterTest` | 4 | Job·Attempt·Event 공개 DTO 변환, 민감 필드 계약 제외 | 통과 |
| `IndexingJobAdminQueryControllerTest` | 17 | 네 API, Validation, ADMIN 권한, 오류·JSON 응답 | 통과 |

## 4. PostgreSQL 통합 검증

실행:

```bash
DB_SSLMODE=disable \
./gradlew test \
  --tests 'com.opensource.docgrid.domain.embedding.integration.IndexingJobAdminQueryIntegrationTest'
```

결과:

```text
tests=5 failures=0 errors=0 skipped=0
```

| 시나리오 | 확인 항목 | 결과 |
|---|---|---|
| Job 복합 필터 | 상태·문서·현재 소유 Worker 조건이 같은 한 건으로 수렴 | 통과 |
| Job 목록 정렬·Page | `created_at DESC, id DESC`, Page 경계와 전체 건수 보존 | 통과 |
| Attempt 이력 | `attempt_no DESC, id DESC`, 내부 Claim Token·오류 메시지 미노출 | 통과 |
| Event 타임라인 | `occurred_at DESC, id DESC`, Metadata JSON 미노출 | 통과 |
| 종료 Job 상세 | Worker와 Lease가 없는 종료 상태를 Null로 안전하게 반환 | 통과 |

## 5. 보안·읽기 전용 경계

- `/admin/**`의 기존 `ADMIN` 권한 정책을 그대로 적용했다.
- Job·Attempt의 Claim Token과 내부 Error Message를 응답 DTO에 정의하지 않았다.
- Event의 `metadata_json`을 응답 DTO에 정의하지 않았다.
- Query Service는 `@Transactional(readOnly = true)`로만 동작한다.
- 조회 테스트 전후에 Job·Attempt·Event 상태 전이가 발생하지 않는다.

## 6. 결론

- 관리자는 Job 목록·상세와 Attempt·Event 이력을 고정된 Pagination 계약으로 조회할 수 있다.
- 상태·문서·현재 Worker 필터와 재현 가능한 역순 정렬이 실제 PostgreSQL에서 동작한다.
- 운영에 필요한 제한된 Error Code는 제공하면서 소유권 증명 값과 내부 진단 원문은 노출하지 않는다.
- 기존 Worker 조회 및 인덱싱 Command API를 포함한 전체 706개 Test가 실패 없이 통과한다.
