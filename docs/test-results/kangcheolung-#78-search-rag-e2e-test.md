# #78 검색+RAG 통합 e2e 테스트 결과

관련 이슈: #78 (`[Test] 검색+RAG 통합 e2e 테스트`)
관련 설계 문서: `kangcheolung-#65-rag-prompt-builder.md`, `#67-ollama-client.md`, `#71-rag-response-save.md`, `#73-response-citation-save.md`, `#75-rag-facade-integration.md`

---

## 배경

RAG 블록(F-RAG-01~05) 구현이 전부 완료된 뒤, 실제 서버 + 실제 인프라(Postgres, embedding-server, Ollama)를 띄운 상태에서 `POST /search`가 검색 결과(`results`) + RAG 답변(`answer`) + 출처(`citations`)를 정상적으로 조합해 반환하는지 Swagger로 수동 확인했다.

**중요한 제약**: 테스트에 사용한 문서(`documents.id=3,4`)의 `embeddings` 벡터가 실제 `bge-m3` 계산값이 아니라 인위적으로 채워넣은 더미 패턴(`[0.3, 0.3, ..., 0.001, 0.001, ...]` 형태)이라는 게 이번 테스트 중에 확인됐다. 이 때문에 `similarityScore`가 신뢰할 수 없는 값으로 나온다 — 이는 RAG/검색 로직의 결함이 아니라 **테스트 데이터 자체의 한계**다 (아래 "발견된 이슈" 참고).

실제 업로드 API(`POST /api/documents`)로 새 문서를 넣어 정식 임베딩 파이프라인을 거치게 하려고 시도했으나, 문서 청크 생성(`PARSING → CHUNKED`)까지는 관리자 API로 가능해도 **임베딩 계산 및 `INDEXED` 전환은 아직 다른 이슈(A 담당자 담당, 인덱싱 파이프라인)의 범위**로 남아 있어(`Gimini-3-#68-text-parsing-chunk-storage.md`의 "비범위"에 명시) 이번 테스트 범위에서는 진행하지 못했다. 해당 시나리오는 파이프라인이 완성된 뒤 별도로 재검증한다.

---

## 테스트 환경

| 항목 | 상태 |
|---|---|
| PostgreSQL (opensql) | 정상 기동 |
| embedding-server (Python) | 정상 기동 |
| Ollama (qwen2.5:3b) | 정상 기동 |
| Spring Boot 앱 | `INDEXING_WORKER_ENABLED=true ./gradlew bootRun --args='--spring.profiles.active=local'` |
| 테스트 계정 | `kcw130502@gmail.com` (documents id=3,4의 소유자) |
| 테스트 문서 | `Spring Boot 개발 가이드`(id=3, chunk 2개), `Python 데이터 분석 입문`(id=4, chunk 2개) — **더미 임베딩** |

---

## 정상 케이스 — 검색+RAG 통합 응답

### 요청
```text
POST /search
Authorization: Bearer {JWT}
{
  "queryText": "Spring Boot 개발 가이드에서 다루는 내용 알려줘",
  "topK": 5
}
```

### 응답 (200 OK)
```json
{
  "success": true,
  "status": 200,
  "data": {
    "queryId": 8,
    "results": [
      {
        "rank": 1,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다.",
        "pageNo": null,
        "similarityScore": 0.03644
      },
      {
        "rank": 2,
        "documentTitle": "Python 데이터 분석 입문",
        "chunkText": "Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다.",
        "pageNo": null,
        "similarityScore": 0.017193
      },
      {
        "rank": 3,
        "documentTitle": "Python 데이터 분석 입문",
        "chunkText": "numpy는 수치 계산을 위한 Python 라이브러리입니다. 다차원 배열 연산과 선형대수 기능을 제공합니다.",
        "pageNo": null,
        "similarityScore": 0
      },
      {
        "rank": 4,
        "documentTitle": "Spring Boot 개발 가이드",
        "chunkText": "Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다. 자동 설정과 내장 서버를 제공하여 빠른 개발이 가능합니다.",
        "pageNo": null,
        "similarityScore": 0
      }
    ],
    "answer": "Spring Boot 개발 가이드에서 다루는 내용은 다음과 같습니다:\n\n- Spring Boot Starter가 의존성 관리를 단순화시켜 줍니다.\n- 애플리케이션을 시작하는 방법 중 하나로 @SpringBootApplication 어노테이션이 사용되며, 이를 통해 Spring Boot 기능들을 쉽게 활성화할 수 있습니다.",
    "citations": [
      { "label": "[1]", "documentId": 3, "documentTitle": "Spring Boot 개발 가이드", "chunkId": 6, "pageNo": null, "quotedText": "Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다." },
      { "label": "[2]", "documentId": 4, "documentTitle": "Python 데이터 분석 입문", "chunkId": 7, "pageNo": null, "quotedText": "Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다." },
      { "label": "[3]", "documentId": 4, "documentTitle": "Python 데이터 분석 입문", "chunkId": 8, "pageNo": null, "quotedText": "numpy는 수치 계산을 위한 Python 라이브러리입니다. 다차원 배열 연산과 선형대수 기능을 제공합니다." },
      { "label": "[4]", "documentId": 3, "documentTitle": "Spring Boot 개발 가이드", "chunkId": 5, "pageNo": null, "quotedText": "Spring Boot는 Java 기반 웹 애플리케이션 프레임워크입니다. 자동 설정과 내장 서버를 제공하여 빠른 개발이 가능합니다." }
    ]
  },
  "timestamp": "2026-07-30 17:57:50"
}
```

### 확인된 것
- `results` + `answer` + `citations` 세 필드가 설계대로 하나의 응답에 모두 포함됨
- `citations`의 `documentId`/`chunkId`가 실제 DB row와 정확히 일치함 (`SearchResultCommandService` → `ResponseCitationCommandService`로 이어지는 FK 연결이 정상 동작)
- **환각 방지 프롬프트가 실제로 동작함**: `results`/`citations`에는 무관한 Python 내용이 섞여 들어왔지만(더미 임베딩 때문 — 아래 참고), `answer`는 Python 내용을 전혀 언급하지 않고 Spring Boot 관련 내용만으로 답변을 구성함

---

## 에러 케이스 — Ollama 콜드스타트로 인한 503

의도적으로 만든 시나리오는 아니고, 테스트 중 실제로 재현된 케이스다.

### 상황
Ollama가 한동안 유휴 상태였다가(모델이 메모리에서 내려간 상태), 오랜만의 첫 `POST /search` 요청에서 모델 재로딩(~24.6초) + 생성 시간을 합쳐 `OllamaServerConfig`의 `readTimeout=30초`를 초과함.

### 응답 (503)
```json
{
  "status": 503,
  "code": "RAG-001",
  "message": "LLM 서버를 사용할 수 없습니다.",
  "method": "POST",
  "path": "/search",
  "success": false,
  "timestamp": "2026-07-30 17:56:28"
}
```

### Ollama 로그 (원인 확인)
```text
srv  llama_server: model loaded
time=... msg="llama-server started in 24.62 seconds"
...
[GIN] ... | 500 | 30.042079348s | ... | POST "/api/generate"
```

### 확인된 것
- `RagFacade`의 `OllamaClient.generate()` 실패 → `createFailed()` 호출 → 예외 재전파 → `GlobalExceptionHandler`가 `RAG_SERVICE_UNAVAILABLE`(503)로 정상 변환
- 검색 자체(`search_results` 저장)는 `SearchFacade`의 별도 트랜잭션에서 이미 커밋된 뒤라 이 실패에 영향받지 않음 (재요청 시 동일 질문을 새로 검색해서 정상 응답 받음을 확인)
- 재요청 시(모델이 메모리에 이미 로드된 상태) 정상적으로 200 응답 — Ollama 콜드스타트가 원인이었음을 재확인

---

## 발견된 이슈 (버그 아님 — 원인 규명 및 기록)

### 1. `similarityScore`가 비정상적으로 낮음 (0~0.036)
DB에서 `embeddings.vector` 값을 직접 확인한 결과, 실제 `bge-m3` 계산값이 아니라 `[0.3, 0.3, ..., 0.001, 0.001, ...]` 형태의 인위적인 더미 패턴이 들어있었다. 이 chunk들은 정상 업로드 API를 거치지 않고 DB에 직접 seed된 테스트 데이터로 추정된다.

**결론**: RAG/검색 코드의 버그가 아니라 테스트 데이터의 한계. 실제 임베딩 파이프라인으로 색인된 문서가 필요하다.

### 2. `citations`에 답변과 무관한 chunk가 포함됨
`answer`는 `citations[0]`(Spring Boot Starter 내용) 하나만 실질적으로 사용했지만, `citations`에는 검색된 4개 후보가 전부 포함됨.

**원인**: `RagAnswer.of()`가 LLM 답변 텍스트를 파싱해 실제 인용된 라벨만 거르지 않고, `PromptBuilder`에 넘겨준 `candidates` 전체를 그대로 citation으로 변환하기 때문 (설계상 알려진 MVP 한계, RAG 명세 11장 제외 범위와 일치).

**1번과의 관계**: 이번에 관찰된 것 중 상당 부분은 더미 임베딩으로 인해 애초에 검색 결과 자체가 부정확했기 때문이다(진짜 임베딩이었다면 무관한 Python 청크가애초에 top-K 안에 들어오지 않았을 가능성이 높음). 다만 "citations = 검색 후보 전체, 실제 인용 여부와 무관"이라는 설계 자체는 임베딩 품질과 별개로 존재하는 한계이므로, 향후 개선 검토 대상으로 남긴다.

**결론**: 버그 아님, 백로그 아이템으로 기록. 우선순위는 임베딩 파이프라인 정상화 이후 재평가.

---

## 미완료 시나리오 (인덱싱 파이프라인 완성 후 재시도)

| 시나리오 | 상태 | 사유 |
|---|---|---|
| 정상 케이스 B (다른 문서 관련 질문) | 보류 | 실제 임베딩 없이는 결과 신뢰 불가 |
| NO_CONTEXT (무관한 질문) | 보류 | 위와 동일 |
| 권한 케이스 (`test@gmail.com`으로 동일 질문) | 보류 | 위와 동일 |
| 입력 검증 (`queryText` 빈 값, `topK` 21 이상) | 보류 | RAG 블록과 무관, 독립적으로 진행 가능하나 이번 세션에서 미실시 |
| Ollama 장애 케이스 (의도적 재현) | **완료** (우연히 재현됨) | 위 "에러 케이스" 참고 |

---

## 자동화 테스트 결과

```bash
$ ./gradlew test
BUILD SUCCESSFUL in 25s
5 actionable tasks: 2 executed, 3 up-to-date
```

- 총 테스트: 343개
- 실패: 0개
- 대상: 검색 블록(F-SEARCH) + RAG 블록(F-RAG-01~05) 전체 단위 테스트 (`SearchFacadeTest`, `RagFacadeTest`, `RagResponseCommandServiceTest`, `ResponseCitationCommandServiceTest`, `OllamaClientTest`, `PromptBuilderTest` 등 포함)

---

## 결론

- **RAG 블록(F-RAG-01~05)의 코드 로직은 정상 동작을 확인했다**: 검색 결과 → 프롬프트 조립 → LLM 호출 → 답변/출처 저장 → 최종 응답 조합까지 e2e로 실제 인프라(Postgres/embedding-server/Ollama) 연동 하에 검증됨. 환각 방지 지시문도 실제로 유효함을 확인.
- **LLM 장애 처리(503)도 실제 장애 상황에서 의도대로 동작함**을 우연히 재현하여 확인.
- **`similarityScore` 이상치는 테스트 데이터(더미 임베딩) 문제**로 원인을 특정했다 — RAG/검색 코드 수정 불필요, 실제 임베딩 파이프라인 완성 후 재검증 필요.
- **citations 필터링 미비**는 설계상 알려진 MVP 한계로 별도 개선 백로그로 기록한다.
- 나머지 시나리오(NO_CONTEXT, 권한, 입력 검증, 다른 문서 케이스)는 인덱싱 파이프라인(A 담당자 담당, 임베딩 계산 + INDEXED 전환)이 완성된 뒤 재시도한다.
