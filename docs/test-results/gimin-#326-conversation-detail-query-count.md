# 대화 상세 N+1 개선 검증 결과

- 관련 이슈: #326
- 실행 일시: 2026-09-12 (Asia/Seoul)
- 수정 전 기준 Commit: `cb23c95` (`develop`)
- 수정 후 기준: `fix/326`
- 환경: Java 17, Spring Boot 3.5.16, Hibernate 6.6.53, PostgreSQL 17.8
- 격리 Schema: `docgrid_test`

## 검증 목적

- 대화 상세 조회의 SELECT 수가 완료 Turn 수에 비례해 증가하는 현상을 실제 DB에서 재현한다.
- 질문·검색 결과·RAG 응답·citation을 목적별 DTO Projection으로 일괄 조회한 뒤에도 기존 응답
  필드와 순서가 유지되는지 확인한다.
- 최대 50 Turn에서도 SELECT 수가 상수로 유지되는지 검증한다.

## 수정 전 원인

`SearchConversationQueryService.getConversation()`은 대화 소유권과 최근 질문 목록을 조회한 뒤,
각 질문마다 `SearchAnswerQueryService.getAnswer()`를 호출했다. 완료 Turn 한 건마다 다음 조회가
반복됐다.

1. query ID와 user ID를 사용한 질문 소유권 조회
2. 해당 질문의 검색 결과 조회
3. 해당 질문의 RAG 응답 조회
4. 확정 RAG 응답의 citation 조회

따라서 완료 Turn이 `N`개일 때 SELECT 수는 `2 + 4N`이었다. #318에서 검색 결과의
`chunk → documentVersion → document` 경로를 Fetch Join하도록 수정했기 때문에, 검색 결과 개수에
따른 추가 LAZY SELECT는 발생하지 않았다. 이번 계측은 Turn별 명시적 단건 조회가 반복되는 서비스
계층 N+1을 대상으로 했다.

## 측정 방법

각 대화에 완료된 Turn을 1개, 5개, 10개, 50개 생성했다. 모든 Turn에는 검색 결과 2건과 같은
근거를 가리키는 citation 2건을 저장했다. 저장 데이터를 flush하고 영속성 Context를 비운 뒤
Hibernate `Statistics.getPrepareStatementCount()`로 대화 상세 응답 한 번을 조립하는 동안 실행된
Prepared Statement 수를 기록했다.

수정 전은 변경되지 않은 `develop`의 별도 복제본에서 같은 픽스처와 계측 지점으로 실행했다.
수정 후는 `SearchConversationQueryCountIntegrationTest`로 동일 조건을 검증했다.

## 수정 내용

대화 상세 조회에서 최근 50개 질문 ID를 먼저 얻고 다음 세 종류의 표시 필드를 각각 한 번의
`IN (:queryIds)` 쿼리로 읽는다.

1. 검색 결과: query ID, 정렬 순위, 문서·Chunk 표시 필드, 유사도
2. RAG 응답: query ID, 상태, 답변 본문
3. Citation: query ID, 라벨, 문서·Chunk 표시 필드, 인용문

조회 결과는 애플리케이션에서 query ID로 그룹화한다. 질문·검색 결과·citation의 1:N 관계를 한
Fetch Join으로 합치지 않아 행의 곱 증가와 최근 50개 제한의 왜곡을 피했다.

## 수정 전·후 결과

| 완료 Turn | 수정 전 SELECT | 수정 후 SELECT | 감소 |
|---:|---:|---:|---:|
| 1 | 6 | 5 | 1 |
| 5 | 22 | 5 | 17 |
| 10 | 42 | 5 | 37 |
| 50 | 202 | 5 | 197 |

최대 50 Turn에서 SELECT가 202회에서 5회로 줄어 197회, 약 97.5% 감소했다. 수정 후 다섯
SELECT는 대화 소유권, 최근 질문 Projection, 검색 결과 Projection, RAG Projection, citation
Projection에 각각 한 번씩 사용된다.

## 응답 계약 검증

- 질문은 DB의 최근순 제한 결과를 화면용 과거→현재 순서로 반환한다.
- 검색 결과는 rank 순서대로 반환하고 화면 순위는 기존처럼 1부터 빈틈없이 다시 매긴다.
- Citation은 `citation_order` 순서를 유지한다.
- RAG 응답이 없거나 PROCESSING이면 상태는 PROCESSING, 답변은 null, citation은 빈 목록이다.
- 확정 응답은 저장된 상태·답변·citation을 반환한다.
- 질문이 없는 대화는 관련 테이블 세 종류의 일괄 조회를 생략한다.
- 대화 소유권은 기존처럼 conversation ID와 user ID를 함께 조건으로 검증한다.

## 수치 해석

이 결과는 한 요청에서 실행된 SQL 왕복 횟수다. 실제 HTTP 지연 시간이나 초당 처리량을 측정한
부하 테스트 결과는 아니다. 데이터베이스·네트워크 지연에 따른 응답 시간 개선은 별도 부하 테스트
없이 추정하지 않는다.

## 실행 명령과 결과

```bash
DB_PORT=55433 \
JWT_SECRET=test-only-query-count-secret-key-at-least-thirty-two-bytes \
./backend/gradlew -p backend test \
  --tests 'com.opensource.docgrid.domain.search.service.query.SearchConversationQueryServiceTest' \
  --tests 'com.opensource.docgrid.domain.search.service.query.SearchConversationQueryCountIntegrationTest'
```

- 결과: 5 tests, 0 failed, 0 errors, 0 skipped
- 수정 후 SQL 계측: `[5, 5, 5, 5]`

```bash
DB_PORT=55433 \
JWT_SECRET=test-only-query-count-secret-key-at-least-thirty-two-bytes \
./backend/gradlew -p backend build
```

- 결과: 1,148 tests, 0 failed, 0 errors, 0 skipped
- Gradle 결과: `BUILD SUCCESSFUL`
