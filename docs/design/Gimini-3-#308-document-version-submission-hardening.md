# 문서 버전 이력과 제출 검증 증빙 보완

Closes #308

## 1. 배경

문서 상세 화면은 현재 버전과 처리 중 버전만 보여 주므로, 정상인 이전 버전과 실패한 새 버전이 함께
남는 복구 상태를 한 화면에서 설명하기 어려웠다. 또한 새 버전 업로드 화면은 계산된 WRITE 권한을
표시하지만 실제 Command는 소유자만 허용해 UI와 API 권한 경계가 달랐다.

제출 전에는 MCP 토큰 원문 노출과 운영 로그의 민감정보 경계를 명확히 하고, OpenSQL 복구 검증의 실행
Commit과 최종 제출 Commit을 구분할 필요도 있다.

## 2. 범위

- 읽기 가능한 문서의 전체 버전과 버전별 최신 인덱싱 Job Snapshot을 조회한다.
- 소유자뿐 아니라 계산된 WRITE 권한 사용자도 새 버전을 업로드할 수 있게 한다.
- 실제 업로드 Transaction에서 WRITE 권한을 다시 확인해 준비 이후 권한 회수를 반영한다.
- MCP 도구의 성공·거부·오류와 소요 시간을 Query·본문·Token 없이 기록한다.
- 발급된 MCP 토큰 원문은 화면에 렌더링하지 않고 성공한 클립보드 복사 뒤 메모리 상태에서 제거한다.
- OpenSQL Single 재연결·Lease 복구 Runbook과 기존 실행 결과의 Commit 경계를 공개한다.

시연 녹화 시나리오, 데모 문서, 계정, DB Snapshot, 촬영 자동화 Script는 이 이슈와 PR 범위에 포함하지
않는다.

## 3. API

### `GET /api/documents/{documentId}/versions`

읽기 권한이 있는 사용자에게 문서 버전을 최신 번호순으로 반환한다. 각 항목은 다음 Snapshot을 포함한다.

- 버전 ID·번호·상태와 현재 검색 버전 여부
- 업로드 당시 파일명·MIME 타입·크기·SHA-256
- 생성 사용자와 생성·인덱싱 완료 시각
- 해당 버전의 최신 인덱싱 Job ID·상태·Worker·오류 코드·재시도 수

저장소 위치, Claim Token과 내부 오류 상세문은 응답하지 않는다.

오류 경계는 기존 문서 상세 조회와 같다.

- 인증 없음: 인증 계층에서 차단
- 문서 없음 또는 삭제됨: `DOCUMENT_NOT_FOUND`
- 읽기 권한 없음: `PERMISSION_DENIED`

### `POST /api/documents/{documentId}/versions`

기존 소유자 전용 판단을 `PermissionQueryService.canWriteDocument`의 계산된 WRITE 권한으로 통일한다.
업로드 준비와 실제 저장 Transaction에서 각각 확인하므로 두 단계 사이에 권한이 회수되면 저장하지 않는다.
파일 형식, 중복 내용, 진행 중 버전과 삭제 상태 검증은 기존 규칙을 유지한다.

## 4. OpenSQL 검증 경계

공개하는 기존 복구 결과는 `8aeedc9`에서 실행했다. 이후 장애 분류와 Sync 정합성 코드 변경이 있으므로
최종 제출 Commit에서 재실행한 결과로 표현하지 않는다. 최종 Commit의 복구 성공을 주장하려면 같은
OpenSQL Single 중단·재연결 시나리오를 다시 실행해 별도 결과를 남긴다.
