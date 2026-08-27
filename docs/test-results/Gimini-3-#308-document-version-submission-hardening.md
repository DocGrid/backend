# 문서 버전 이력과 제출 검증 증빙 보완 테스트 결과

Closes #308

## 1. 실행 결과

2026-08-27(Asia/Seoul) `feature/308`의 PR 제출 직전 작업 트리에서 실행했다.

| 구분 | 명령 | 결과 |
|---|---|---:|
| Backend 변경 관련 테스트 | `./backend/gradlew -p backend test --tests '*DocumentVersionHistoryConverterTest' --tests '*DocumentVersionUploadServiceTest' --tests '*DocumentQueryServiceTest' --tests '*DocumentQueryControllerTest'` | PASS |
| Backend 전체 테스트 | `./backend/gradlew -p backend --no-daemon test` | 1,120 / 1,120 PASS |
| Frontend Build·테스트 | `npm test` | 39 / 39 PASS |
| OpenSQL 검증 Script 문법 | `bash -n scripts/opensql/verify-single-recovery.sh` | PASS |
| 변경 공백 검사 | `git diff --check` | PASS |

Backend 전체 테스트는 기존 개발 DB와 분리된 PostgreSQL 17·pgvector 0.8.1 임시 Container와 로컬 Redis를
사용했다. 테스트 전용 JWT Secret만 Process 환경 변수로 주입했으며 저장소 파일에는 기록하지 않았다.

## 2. 정상 시나리오

1. 읽기 권한 사용자는 최신 버전부터 전체 이력을 조회한다.
2. 각 버전은 현재 검색 버전 여부와 최신 인덱싱 Job Snapshot을 함께 반환한다.
3. 소유자가 아니어도 계산된 WRITE 권한이 있으면 새 버전 업로드 준비를 통과한다.
4. 문서 상세 화면은 정상 현재 버전과 실패한 새 버전을 한 타임라인에 표시한다.
5. MCP 토큰 발급 화면은 원문 대신 Mask를 표시하고 클립보드 복사 성공 뒤 원문 상태를 제거한다.

## 3. 오류·보안 시나리오

1. 읽기 권한이 없으면 버전 Repository를 조회하지 않고 `PERMISSION_DENIED`를 반환한다.
2. 업로드 준비 뒤 WRITE 권한이 회수되면 실제 저장 Transaction 전에 다시 차단한다.
3. 버전 이력 응답은 저장소 위치, Claim Token과 내부 오류 상세문을 포함하지 않는다.
4. MCP 운영 로그는 Query, 문서 본문, Authorization Header와 Token 원문을 기록하지 않는다.
5. Frontend Source 검증은 발급 Token 원문을 JSX에 직접 렌더링하지 않는지 확인한다.

## 4. OpenSQL 결과 해석

이번 Backend 전체 테스트는 PostgreSQL 17·pgvector 기준 회귀 검증이다. 기존 OpenSQL Single 중단·재연결
실장애 결과는 `8aeedc9`에서 실행한 별도 증빙이며, 이번 PR에서 같은 장애를 다시 주입했다고 주장하지 않는다.
