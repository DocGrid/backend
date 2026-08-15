# #67 OllamaClient 연동 — Ollama HTTP 클라이언트 구현 (F-RAG-02)

closes #67

---

## 배경

Issue 1(#65)에서 검색 후보를 출처 라벨과 함께 프롬프트 문자열로 조립하는 `PromptBuilder`를 만들었다. 하지만 그건 문자열을 만드는 것까지였고, 실제로 LLM에게 그 프롬프트를 전달해서 답변을 받아오는 부분은 없었다. 이번 Issue 2는 그 다음 단계 — `PromptBuilder`가 만든 프롬프트를 실제로 Ollama(`qwen2.5:3b`)에 HTTP로 전송해서 답변 텍스트를 받아오는 `OllamaClient`를 구현한다.

RAG 블록 5개 이슈 중 "실제로 LLM이 응답을 생성한다"는 게 처음으로 체감되는 지점이다.

| Issue | 범위 | 상태 |
|---|---|---|
| 1 | Ollama 로컬 세팅 + PromptBuilder (F-RAG-01) | 완료 |
| **2 (이 문서)** | OllamaClient 연동 (F-RAG-02) | 완료 |
| 3 | rag_responses 저장 (F-RAG-03) | 예정 |
| 4 | response_citations 저장 (F-RAG-04) | 예정 |
| 5 | 최종 answer + citations 응답 조합 (F-RAG-05) | 예정 |

**이번 이슈에서 하지 않는 것**: `rag_responses` 저장(Issue 3), `response_citations` 저장(Issue 4), `SearchFacade`/`SearchController`와의 연결(Issue 5)은 포함하지 않는다. "검색 결과가 0건이면 LLM 호출을 생략한다"(NO_CONTEXT) 판단도 포함하지 않는다 — `OllamaClient`는 항상 주어진 프롬프트를 그대로 전송하는 순수 HTTP 클라이언트이고, 호출 여부 판단은 Issue 5에서 만들 `RagFacade`의 책임이다. `OllamaClient`는 `PromptBuilder`처럼 이번 이슈에서는 단위 테스트로만 검증되고, 아직 다른 코드와 연결되지 않은 독립 컴포넌트다.

---

## 왜 기존 `QueryEmbeddingService` 패턴을 그대로 가져왔나

검색 블록에서 이미 Python 임베딩 사이드카를 호출하는 동일한 구조(`RestClient` Bean 분리 + 얇은 서비스가 예외를 변환)가 검증되어 있었다. RAG 블록도 "로컬에 떠 있는 사이드카 서버를 HTTP로 호출하고, 실패하면 DocGridException으로 바꾼다"는 문제의 모양이 완전히 같아서, 새로운 패턴을 고안하지 않고 그대로 재사용했다.

| | 임베딩 서버 (기존) | Ollama (이번 이슈) |
|---|---|---|
| Config | `EmbeddingServerConfig` | `OllamaServerConfig` |
| Bean 이름 | `embeddingRestClient` | `ollamaRestClient` |
| 서비스 | `QueryEmbeddingService` | `OllamaClient` |
| 호출 경로 | `POST /embed` | `POST /api/generate` |
| 실패 시 에러코드 | `EMBEDDING_SERVER_UNAVAILABLE` | `RAG_SERVICE_UNAVAILABLE` |
| 응답 검증 | 벡터 null/차원 체크 | 응답/response 필드 null 체크 (코드리뷰로 추가) |

---

## 전체 흐름

```text
(아직 어디에도 연결되지 않음 — 이번 이슈에는 미포함, Issue 5에서 연결 예정)
PromptBuilder.build(queryText, candidates)  ← Issue 1
        │
        ▼
"다음은 참고 문서입니다...[1] ...[2] ...\n질문: ..." 프롬프트 문자열
        │
        ▼
OllamaClient.generate(prompt)  ← 이 문서
        │
        ├─ OllamaGenerateRequest(model, prompt, stream=false) 조립
        ├─ RestClient로 POST /api/generate 전송
        ├─ 실패(RestClientException) → RAG_SERVICE_UNAVAILABLE(503)
        ├─ 빈 응답(response==null 또는 response.response()==null) → RAG_SERVICE_UNAVAILABLE(503)
        └─ 성공 → OllamaGenerateResult(answerText, inputTokenCount, outputTokenCount, latencyMs)
        │
        ▼
(Issue 3의 RagResponseCommandService가 이 결과를 rag_responses에 저장 — 예정)
```

---

## 신규/변경 파일

### 1. `global/exception/ErrorCode.java` — RAG 에러코드 추가

```java
// RAG
RAG_SERVICE_UNAVAILABLE(
    HttpStatus.SERVICE_UNAVAILABLE,
    "RAG-001",
    "LLM 서버를 사용할 수 없습니다."
);
```

`EMBEDDING_SERVER_UNAVAILABLE`(`SEARCH-001`)과 동일한 역할이다 — Ollama 호출이 실패했을 때 던지는 예외가 `GlobalExceptionHandler`를 거쳐 503 + 이 메시지로 응답된다. 도메인 접두사를 `SEARCH`가 아니라 `RAG`로 새로 연 이유는, 검색 블록과 RAG 블록이 서로 다른 실패 지점(벡터 검색 vs LLM 생성)이라 에러코드로도 구분되는 게 원인 추적에 유리하기 때문이다.

### 2. `src/main/resources/application.yml` — 모델명 설정 추가

```yaml
ollama:
  server:
    base-url: ${OLLAMA_SERVER_URL:http://localhost:11434}
  model: ${OLLAMA_MODEL:qwen2.5:7b}
```

`base-url`(Issue 1에서 추가)은 "어디로 요청을 보낼지"이고, `model`(이번 이슈)은 "그 서버한테 어떤 모델로 답변을 생성해달라고 할지"다. 같은 Ollama 서버에 여러 모델이 동시에 올라가 있을 수 있어서 요청마다 모델명을 명시해야 한다. 명세 0.3의 "qwen2.5:3b → 7b로 재임베딩 없이 교체 가능해야 함" 요구를 만족하기 위해 코드에 하드코딩하지 않고 설정값으로 뺐다 — 나중에 모델을 바꿀 때 `OLLAMA_MODEL` 환경변수만 바꾸면 되고 코드/재배포가 필요 없다.

> **업데이트(#184)**: 실제로 이 교체가 일어났다. Qwen2.5 시리즈 중 `3b`와 `72b`만 예외적으로 "Qwen Research License"(비상업 연구용 한정)가 적용되고, 나머지(`0.5b`/`1.5b`/`7b`/`14b`/`32b`)는 Apache 2.0이라는 사실이 확인됐다(Alibaba 공식 블로그, HuggingFace 모델 카드). 본 프로젝트가 오픈소스 개발자대회 출품작이라 사용 모델까지 완전 오픈소스(OSI 승인 라이선스)여야 한다는 판단 하에, 기본값을 `qwen2.5:3b` → `qwen2.5:7b`(Apache 2.0)로 교체했다. 검토했던 대안은 두 가지였다 — ① `1.5b`로 다운그레이드(라이선스는 해결되지만 RAG 응답 품질 저하 우려), ② `3b` 유지 + 비상업 용도 고지(라이선스 리스크가 완전히 사라지지 않음). LLM 추론이 서버가 아니라 로컬(docker-compose `ollama` 서비스)에서만 도는 구조로 결정되어 서버 리소스 제약(t3.large, 2vCPU)이 무관해졌고, 로컬 검증 환경(MacBook Air M2, 16GB RAM)에서 `qwen2.5:7b` 기본 quant(Q4_K_M, ~4.7GB)를 감당할 수 있는 것도 확인해서 `7b`로 결정했다. 코드 변경은 이 설정값 한 줄뿐이었고, 위에서 설명한 "모델명을 설정값으로 외부화" 설계가 의도대로 재배포 없이 교체 가능함을 실제로 증명했다.

### 3. `global/config/OllamaServerConfig.java` (신규)

```java
@Configuration
public class OllamaServerConfig {

    @Value("${ollama.server.base-url}")
    private String baseUrl;

    @Bean("ollamaRestClient")
    public RestClient ollamaRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(30));

        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();
    }
}
```

**한 줄 요약**: `EmbeddingServerConfig`와 완전히 동일한 구조로, Ollama 전용 `RestClient` Bean을 하나 등록한다.

- `connectTimeout(5초)`: 로컬 docker 컨테이너라 연결 자체는 임베딩 서버와 마찬가지로 빨리 되거나 안 되거나이므로 5초로 동일하게 뒀다.
- `readTimeout(30초)`: 임베딩 서버(5초)보다 6배 길게 잡았다. 벡터 변환은 순간적으로 끝나지만, LLM이 텍스트를 토큰 단위로 하나씩 생성하는 건 본질적으로 훨씬 오래 걸린다. 명세의 NFR("전체 응답 5초 이내")은 목표치이지 하드 타임아웃이 아니라서, 너무 짧게 잡아 정상적으로 생성 중인 요청을 조기에 503으로 끊어버리는 걸 피하고자 여유 있게 잡았다.
- `@Bean("ollamaRestClient")`: 임베딩용 `RestClient`와 이름으로 구분해서, `OllamaClient`가 `@Qualifier`로 정확히 이 Bean만 주입받게 한다.

### 4. DTO 3종 (신규)

**`domain/rag/dto/request/OllamaGenerateRequest.java`** — 우리가 Ollama에 보내는 요청
```java
public record OllamaGenerateRequest(String model, String prompt, boolean stream) {
}
```
Ollama `/api/generate`가 요구하는 요청 body 그대로다. `stream`은 항상 `false`로 고정해서 호출한다 — 답변을 토큰 단위로 실시간 스트리밍 받는 대신, 완성된 답변을 한 번에 받는다. RAG 명세 11장("실시간 스트리밍 응답은 1단계 제외 범위")과 일치하는 선택이고, 스트리밍을 받으면 우리 쪽에서 조각난 응답을 다시 이어붙이는 로직이 추가로 필요해지는데 지금 필요 없는 복잡도다.

**`domain/rag/dto/response/OllamaGenerateResponse.java`** — Ollama가 주는 원본 응답
```java
public record OllamaGenerateResponse(
    String response,
    boolean done,
    @JsonProperty("prompt_eval_count") Integer promptEvalCount,
    @JsonProperty("eval_count") Integer evalCount
) {
}
```
Ollama는 실제로는 `total_duration`, `context`(토큰 ID 배열) 등 훨씬 많은 필드를 돌려주는데, 우리가 실제로 쓰는 4개만 뽑아서 받는다. `@JsonProperty("prompt_eval_count")`는 "JSON 필드명은 snake_case(`prompt_eval_count`)로 오지만 자바 필드는 camelCase(`promptEvalCount`)로 매핑해라"는 Jackson 지시다. 로컬 Ollama에 curl로 실제 호출해서 이 필드명들이 정확히 일치하는 것을 확인했다(아래 "로컬 검증" 참고).

**`domain/rag/dto/OllamaGenerateResult.java`** — `OllamaClient`가 최종적으로 반환하는 결과
```java
public record OllamaGenerateResult(
    String answerText,          // Ollama가 생성한 답변 문장 (Ollama 응답의 response 필드)
    Integer inputTokenCount,    // 프롬프트(지시문+출처+질문)가 소비한 토큰 수 (Ollama 응답의 prompt_eval_count)
    Integer outputTokenCount,   // 생성된 답변이 소비한 토큰 수 (Ollama 응답의 eval_count)
    int latencyMs               // 호출 시작~응답 수신까지 네트워크 왕복 포함 체감 시간(ms). Ollama의 total_duration(순수 연산 시간, 나노초)과는 다름
) {
}
```
`OllamaGenerateResponse`(Ollama 응답 형식에 종속)와 `OllamaGenerateResult`(우리 서비스가 실제로 쓰는 값)를 굳이 두 단계로 나눈 이유는, `QueryEmbeddingService`가 `EmbedResult`를 반환하는 것과 같은 이유다 — 나중에 Ollama 응답 형식이 바뀌거나 다른 LLM 서버로 갈아타도, `OllamaClient`를 호출하는 쪽(Issue 3, 5)은 `OllamaGenerateResult`만 알면 되고 영향을 안 받는다. 또한 `RagResponse` 엔티티(Issue 1 이전부터 존재)에 이미 `inputTokenCount`/`outputTokenCount`/`latencyMs` 컬럼이 있어서, Ollama 응답에 마침 포함돼 있던 토큰 수(`prompt_eval_count`, `eval_count`)를 버리지 않고 여기 담아 Issue 3이 그대로 저장할 수 있게 했다. `latencyMs`는 Ollama가 주는 값을 쓰지 않고 `OllamaClient`가 호출 앞뒤로 직접 `System.currentTimeMillis()`를 재서 계산한다 — 순수 모델 연산 시간이 아니라 네트워크 왕복까지 포함한 "사용자가 실제로 기다린 시간"이 명세 NFR의 의도와 맞기 때문이다.

### 5. `domain/rag/service/OllamaClient.java` (신규 + 코드리뷰 반영)

```java
@Slf4j
@Service
public class OllamaClient {

    private final String model;
    private final RestClient restClient;

    public OllamaClient(
        @Value("${ollama.model}") String model,
        @Qualifier("ollamaRestClient") RestClient restClient
    ) {
        this.model = model;
        this.restClient = restClient;
    }

    public OllamaGenerateResult generate(String prompt) {
        long start = System.currentTimeMillis();

        OllamaGenerateResponse response;
        try {
            response = restClient.post()
                .uri("/api/generate")
                .body(new OllamaGenerateRequest(model, prompt, false))
                .retrieve()
                .body(OllamaGenerateResponse.class);
        } catch (RestClientException e) {
            log.error("Ollama 서버 호출 실패: {}", e.getMessage());
            throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
        }

        if (response == null || response.response() == null) {
            log.error("Ollama 응답이 비어있음: response={}", response);
            throw new DocGridException(ErrorCode.RAG_SERVICE_UNAVAILABLE);
        }

        int latencyMs = (int) (System.currentTimeMillis() - start);
        return new OllamaGenerateResult(
            response.response(), response.promptEvalCount(), response.evalCount(), latencyMs
        );
    }
}
```

**한 줄 요약**: 프롬프트 문자열 하나를 받아 Ollama에 전송하고, 성공하면 답변/토큰수/latency를 담은 결과를, 실패하면 예외를 던지는 얇은 HTTP 클라이언트.

- **①시작 시각 기록 → ②요청 조립·전송 → ③실패 시 503 변환 → ④빈 응답 방어 → ⑤latency 계산 후 결과 포장** 순서로 진행된다.
- `model`/`restClient`는 생성자 주입이다. `model`은 `@Value("${ollama.model}")`로 설정값을, `restClient`는 `@Qualifier("ollamaRestClient")`로 3번에서 만든 그 Bean만 정확히 받는다.
- `catch (RestClientException e)`: 타임아웃, 연결 거부(Ollama 컨테이너가 안 떠 있는 경우) 등 HTTP 레벨 실패를 전부 포괄한다. `QueryEmbeddingService.embed()`의 catch 블록과 동일한 패턴.
- **`if (response == null || response.response() == null)` — 코드리뷰(CodeRabbit)로 추가된 방어 로직.** 처음 구현했을 때는 이 체크가 없었는데, `RestClient`가 빈 body를 받으면 `retrieve().body(...)`가 예외를 던지지 않고 `null`을 반환할 수 있고, 그러면 바로 다음 줄의 `response.response()`에서 `NullPointerException`이 그대로 튀어나가 503이 아니라 처리되지 않은 500으로 응답될 위험이 있었다. `QueryEmbeddingService`가 임베딩 벡터에 대해 이미 하고 있는 null 체크(`response == null || response.vector() == null`)와 동일한 패턴을 그대로 가져왔다. 자세한 내용은 "코드리뷰 반영" 절 참고.
- NO_CONTEXT(검색 결과 0건일 때 호출 생략) 판단 로직은 여기 없다 — 이 메서드는 항상 받은 프롬프트를 그대로 보낸다.

### 6. `src/test/java/.../rag/service/OllamaClientTest.java` (신규)

`QueryEmbeddingServiceTest`와 동일한 Mockito 패턴 — `RestClient.post()` → `RequestBodyUriSpec`(`Answers.RETURNS_SELF`로 메서드 체이닝을 그대로 흉내) → `retrieve()` → `ResponseSpec.body(...)`를 mocking한다.

| 테스트 | 검증 내용 |
|---|---|
| `generate_success` | 정상 응답 시 `answerText`/`inputTokenCount`/`outputTokenCount`가 그대로 담기고 `latencyMs >= 0`인지 |
| `generate_serverUnavailable_throwsException` | `ResourceAccessException`(RestClientException의 하위 타입) 발생 시 `RAG_SERVICE_UNAVAILABLE` 예외로 변환되는지 |
| `generate_nullResponse_throwsException` | `body(...)`가 `null`을 반환할 때 `RAG_SERVICE_UNAVAILABLE` 예외로 변환되는지 (코드리뷰 반영) |
| `generate_nullAnswerText_throwsException` | 응답 객체는 있지만 `response` 필드가 `null`일 때 `RAG_SERVICE_UNAVAILABLE` 예외로 변환되는지 (코드리뷰 반영) |

---

## 로컬 검증 (실제 수행 기록)

### 1. 실제 Ollama 응답 필드 구조 확인 (DTO 설계 전)

로컬에 떠 있는 Ollama 컨테이너(Issue 1에서 기동)에 curl로 직접 `/api/generate`를 호출해, 실제 JSON 응답 필드명이 DTO 설계와 일치하는지 먼저 확인했다.

```bash
$ curl -s http://localhost:11434/api/generate -d '{"model":"qwen2.5:3b","prompt":"한국어로 짧게 답해줘: 1+1은?","stream":false}'
{
  "model": "qwen2.5:3b",
  "response": "1+1은 2입니다.",
  "done": true,
  "done_reason": "stop",
  "total_duration": 895677250,
  "load_duration": 199424041,
  "prompt_eval_count": 46,
  "prompt_eval_duration": 270123000,
  "eval_count": 9,
  "eval_duration": 424287000
}
```
`response`, `prompt_eval_count`, `eval_count` 필드명이 `OllamaGenerateResponse`의 `@JsonProperty` 매핑과 정확히 일치함을 확인했다.

### 2. `PromptBuilder`가 만드는 것과 동일한 형태의 실제 RAG 프롬프트로 재현 테스트

Issue 1에서 미뤄뒀던 "언어가 섞여 나오는 문제"(`"안녕"` 한 단어만 물었을 때 한국어+중국어가 섞였던 현상)를, 실제 RAG 프롬프트 형태(지시문 + 출처 chunk 2개 + 질문)로 재현해봤다.

```bash
$ curl -s http://localhost:11434/api/generate -d '{"model":"qwen2.5:3b","stream":false,"prompt":"다음은 참고 문서입니다. 이 내용만을 근거로 답변하고,\n문서에 없는 내용은 추측하지 마세요.\n\n[1] 인사규정.pdf p.12: \"연차는 입사 1년 기준 15일이 부여되며, 사내 포털에서 신청서를 작성한 뒤 팀장 승인을 받아야 합니다.\"\n[2] 복지정책.pdf p.3: \"연차 사용 시 부서장에게 사전 통보가 권장됩니다.\"\n\n질문: 연차 규정 알려줘"}'
```
응답(요약):
```text
근무 규정에 따르면, 인사규정.pdf의 내용으로는 다음과 같이 연차 관련 정보를 제공할 수 있습니다:
1. 연차 획득 기준은 입사 1년입니다.
2. 1년 동안 15일의 기본 연차가 부여됩니다.
3. 신청이 필요한 절차로, 사내 포털에서 연차 사용을 위한 신청서를 작성한 뒤 팀장 승인을 받아야 합니다.
...
```
**결과**: 언어 지시문("한국어로 답변하세요") 없이도 100% 한국어로만 답변이 나왔다. Issue 1에서 관찰했던 언어 섞임은 `"안녕"`처럼 극단적으로 짧고 맥락 없는 프롬프트에서만 나타나는 현상이었고, 실제 RAG 프롬프트처럼 한국어 문서 내용과 질문이 충분히 포함되면 재현되지 않았다. **결론: `PromptBuilder`에 언어 지시문을 추가할 필요는 없다** — Issue 1에서 "검증 전까지는 추가하지 않는다"고 미뤘던 판단이 맞았다.

같은 프롬프트를 다른 시각에 다시 호출했을 때는 아래처럼 문구가 조금 다르게 나오기도 했다(LLM 생성이 확률적이라 매번 100% 동일하지 않음, 정상):
```text
인사규정에 따르면, 연차는 입사 1년 기준으로 15일이 부여되며, 이는 사내 포털에서 신청서를 작성한 뒤 팀장 승인이 필요합니다.
연차 사용 시에는 사전 통보가 권장됩니다. 하지만, 문서에서는 직접 "권장"이라는 표현을 사용하지 않았습니다. 따라서 이것은 제도적으로 의무가 아니라 추천사항으로 볼 수 있습니다.
```

> **주의**: 이 두 curl 테스트는 검색 블록(F-SEARCH) → `PromptBuilder` → `OllamaClient` 코드를 거친 게 아니라, "검색 결과가 이런 chunk를 줬다고 가정하고" 손으로 지어낸 문서 내용을 프롬프트에 직접 넣어 Ollama만 단독으로 호출해본 것이다. 실제 DB에 문서가 업로드/인덱싱된 상태에서 `POST /search`로 진짜 검색 결과를 받아 전체 파이프라인을 확인하는 e2e 테스트는 Issue 5 이후에나 가능하다.

### 3. Ollama 모델 컨텍스트 한도 확인

```bash
$ curl -s http://localhost:11434/api/show -d '{"model":"qwen2.5:3b"}'
qwen2.context_length = 32768
```
`qwen2.5:3b`가 한 번의 요청에서 처리 가능한 최대 토큰(프롬프트+답변 합산)은 32,768개다. 검색 Top-K가 기본 5, 최대 20(`SearchRequest.topK`)이라 지금 구조에서 이 한도를 넘을 가능성은 낮지만, 나중에 topK를 크게 늘리거나 chunk 텍스트가 매우 길어지는 경우가 생기면 점검이 필요할 수 있다.

### 4. 단위 테스트 / 빌드

```bash
$ ./gradlew test --tests "*OllamaClientTest*"
BUILD SUCCESSFUL

$ ./gradlew build -x test
BUILD SUCCESSFUL
```
4개 테스트(정상/서버장애/빈응답/빈response필드) 모두 통과.

### 5. (#184) 모델 교체 후 RAG E2E 재검증

`qwen2.5:3b` → `7b` 교체(기본값만 변경, 코드 무변경) 후 실제로 전체 파이프라인이 `7b`로 정상 동작하는지 로컬에서 확인했다. Issue 5(#75) 이후에나 가능하다고 위 "주의"에 적어뒀던 진짜 e2e(`POST /search`, 실제 DB 데이터 기반)를 이 시점에 처음 수행했다.

```bash
$ docker exec docgrid-ollama ollama pull qwen2.5:7b
# ... 4.7GB pull 완료

$ docker exec docgrid-ollama ollama list
NAME          ID              SIZE      MODIFIED
qwen2.5:7b    845dbda0ea48    4.7 GB    ...
qwen2.5:3b    357c53fb659c    1.9 GB    ...
```

```bash
$ curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"kcw130502@gmail.com","password":"<local-test-password>"}'
{"success":true,"status":200,"data":{"accessToken":"...(생략)","tokenType":"Bearer",...},"timestamp":"2026-08-15 12:45:25"}

$ curl -s -X POST http://localhost:8080/search \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"queryText":"Spring Boot가 뭐야?","topK":3}'
{"success":true,"status":200,"data":{"queryId":90,"results":[{"rank":1,"documentId":1,"documentTitle":"Spring Boot 개발 가이드","chunkText":"Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다.","pageNo":null,"similarityScore":0.036697},{"rank":2,"documentId":2,"documentTitle":"Python 데이터 분석 입문","chunkText":"Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다.","pageNo":null,"similarityScore":0.000000},{"rank":3,"documentId":2,"documentTitle":"Python 데이터 분석 입문","chunkText":"numpy는 수치 계산을 위한 Python 라이브러리입니다. 다차원 배열 연산과 선형대수 기능을 제공합니다.","pageNo":null,"similarityScore":0.000000}],"answer":"Spring Boot는 의존성 관리를 단순화하기 위해 사용되는 프레임워크로, @SpringBootApplication 어노테이션으로 애플리케이션을 시작할 수 있습니다.","citations":[{"label":"[1]","documentId":1,"documentTitle":"Spring Boot 개발 가이드","chunkId":2,"pageNo":null,"quotedText":"Spring Boot Starter는 의존성 관리를 단순화합니다. @SpringBootApplication 어노테이션으로 애플리케이션을 시작합니다."},{"label":"[2]","documentId":2,"documentTitle":"Python 데이터 분석 입문","chunkId":3,"pageNo":null,"quotedText":"Python은 데이터 분석에 널리 사용되는 프로그래밍 언어입니다. pandas 라이브러리로 데이터를 효율적으로 처리합니다."},{"label":"[3]","documentId":2,"documentTitle":"Python 데이터 분석 입문","chunkId":4,"pageNo":null,"quotedText":"numpy는 수치 계산을 위한 Python 라이브러리입니다. 다차원 배열 연산과 선형대수 기능을 제공합니다."}]},"timestamp":"2026-08-15 12:45:55"}
```

`R__seed_test_fixtures.sql`로 시드된 "Spring Boot 개발 가이드" 문서를 근거로 정상 답변과 citation `[1]`이 반환됨을 확인했다.

```bash
$ docker exec docgrid-postgres17 psql -U docgrid -d docgrid -c \
  "SELECT query_id, llm_provider, llm_model_name FROM rag_responses WHERE query_id = 90;"
 query_id | llm_provider | llm_model_name
----------+--------------+----------------
       90 | Ollama       | qwen2.5:7b
(1 row)
```

**결론**: `rag_responses.llm_model_name`에 `qwen2.5:7b`가 그대로 기록됨을 확인해, 답변이 실제로 교체된 `7b` 모델로 생성됐음을 검증했다. `application.yml` 설정값 변경만으로 재배포 없이 모델이 바뀐다는 설계(위 "설계 결정 요약" 참고)가 실전에서도 그대로 작동했다.

---

## 코드리뷰 반영 (CodeRabbit)

PR에 자동 코드리뷰 코멘트 2건이 달렸고, 각각 다음과 같이 처리했다.

| # | 코멘트 요지 | 처리 | 근거 |
|---|---|---|---|
| 1 | `OllamaGenerateRequest`(요청 DTO)에 `model`/`prompt` 유효성 검증이 없다 | **반영 안 함** | `model`은 설정값(항상 값 있음), `prompt`는 `PromptBuilder.build()`가 항상 지시문+질문을 붙여 반환하므로 빈 문자열이 될 수 없고, 그 근원인 `SearchRequest.queryText`에도 이미 `@NotBlank`가 걸려 있다. NO_CONTEXT 케이스는 `RagFacade`(Issue 5)가 아예 이 메서드를 호출하지 않기로 설계했다. 즉 현재 호출 경로상 도달 불가능한 시나리오라, 방어 코드를 추가하지 않았다(CLAUDE.md "발생할 수 없는 시나리오에 대한 검증/에러 처리 금지" 원칙) |
| 2 | `RestClient` 응답이 `null`이거나 `response` 필드가 없을 때 `NullPointerException`이 그대로 전파될 수 있다 | **반영함** | `QueryEmbeddingService.embed()`가 이미 동일한 상황(임베딩 벡터 null)에 대해 방어 체크를 하고 있는 기존 패턴과 일관성을 맞추기 위해 `OllamaClient`에도 동일한 null 체크를 추가했다. 실제 버그였다 — 코드리뷰가 아니었으면 놓칠 뻔한 부분 |

---

## 에러 케이스 정리

| 상황 | 처리 |
|---|---|
| Ollama 서버 연결 거부/타임아웃(30초 초과) | `RestClientException` catch → `RAG_SERVICE_UNAVAILABLE`(503) |
| Ollama가 빈 body 또는 `response` 필드 없는 JSON 반환 | null 체크 → `RAG_SERVICE_UNAVAILABLE`(503) |
| 정상 응답 | `OllamaGenerateResult` 반환 (예외 없음) |

---

## 설계 결정 요약

**기존 임베딩 서버 연동 패턴을 그대로 재사용**
`RestClient` Bean 분리 + 얇은 서비스가 `RestClientException`을 도메인 예외로 변환하는 구조를, 새로 고안하지 않고 `EmbeddingServerConfig`/`QueryEmbeddingService`에서 그대로 가져왔다. 같은 유형의 문제(로컬 사이드카 HTTP 호출)에 다른 해법을 쓸 이유가 없었다.

**readTimeout을 임베딩 서버보다 6배 길게(5초 → 30초)**
LLM 텍스트 생성은 벡터 변환과 걸리는 시간의 성격이 다르다. NFR의 "5초 이내"는 목표치이지 하드 타임아웃이 아니므로, 짧은 타임아웃으로 정상 생성 중인 요청을 조기에 끊는 것을 피했다.

**모델명을 설정값으로 외부화**
`qwen2.5:3b` → `7b` 같은 향후 교체 시나리오(명세 0.3)에 대비해, `OllamaClient` 코드에는 모델명을 전혀 하드코딩하지 않았다. `application.yml`의 `ollama.model` 값만 바꾸면 재배포 없이(환경변수 재주입만으로) 교체 가능하다.

**(#184 추가) 라이선스 문제로 기본 모델을 `3b` → `7b`로 교체**
Qwen2.5 `3b`가 Apache 2.0이 아니라 비상업 연구용 "Qwen Research License"임이 확인되어, 오픈소스 대회 출품 요건을 맞추려고 Apache 2.0인 `7b`로 기본값을 바꿨다. `1.5b` 다운그레이드(품질 저하 우려)와 `3b` 유지+고지(라이선스 리스크 잔존) 대안을 검토했으나, LLM을 서버가 아닌 로컬에서만 구동하기로 해 리소스 제약이 사라진 점을 고려해 `7b`를 선택했다. 자세한 내용은 위 "2. `application.yml`" 절의 업데이트 노트 참고.

**`OllamaGenerateResponse` → `OllamaGenerateResult` 2단계 변환**
외부 서버(Ollama)의 응답 형식에 종속된 그릇과, 우리 서비스가 실제로 쓰는 값만 담은 그릇을 분리했다. `EmbedResult`와 동일한 패턴이며, Ollama 응답 형식이 바뀌거나 다른 LLM으로 교체해도 `OllamaClient` 호출부(Issue 3, 5)는 영향을 받지 않는다.

**토큰 수를 Ollama 응답에서 그대로 캡처**
`RagResponse` 엔티티에 이미 `inputTokenCount`/`outputTokenCount` 컬럼이 있고, Ollama 응답에 마침 그 값(`prompt_eval_count`, `eval_count`)이 포함돼 있어서 버리지 않고 `OllamaGenerateResult`에 담았다. Issue 3에서 다시 이 값을 구하기 위해 `OllamaClient`를 수정할 필요가 없다.

**`stream: false` 고정**
실시간 스트리밍 응답은 명세상 1단계 제외 범위(11장)다. 완성된 답변을 한 번에 받는 게 지금 구조(저장 후 citation과 함께 응답 조합)에 맞고, 스트리밍 조각을 이어붙이는 불필요한 복잡도를 피했다.

**NO_CONTEXT 판단 미포함**
`OllamaClient`는 "호출해도 되는 상황인지" 판단하지 않는다. 이 판단(검색 결과 0건이면 호출 생략)은 Issue 5의 `RagFacade`가 한다 — 클라이언트는 순수하게 "주면 보낸다"만 한다.

---

## 남은 이슈 / TODO

### 코드
- `OllamaClient`는 아직 어디에서도 호출되지 않는 독립 컴포넌트 — Issue 5에서 `RagFacade`가 실제로 연결한다.
- `OllamaGenerateRequest`에 대한 명시적 유효성 검증은 현재 호출 경로상 불필요하다고 판단해 추가하지 않았다(위 "코드리뷰 반영" 표 참고). 향후 `OllamaClient.generate()`를 다른 곳에서도 직접 호출하게 되는 상황이 생기면 재검토가 필요하다.
- ~~`qwen2.5:3b`의 컨텍스트 한도(32,768 토큰)에 대한 명시적 방어(예: 프롬프트가 너무 길면 사전에 잘라내기)는 아직 없다. 지금 topK 범위(1~20)에서는 실질적 위험이 낮아 보류.~~ → 기본 모델이 `qwen2.5:7b`로 바뀌었으나(#184) `qwen2.context_length`는 동일하게 32,768로 확인되어(로컬 `ollama show` 검증) 이 판단은 그대로 유효하다. 방어 로직 자체는 여전히 미구현 상태.

### 다음 단계
Issue 3 — `RagResponseRepository` + `RagResponseCommandService` 구현. 이번 이슈에서 만든 `OllamaGenerateResult`를 받아 `rag_responses`에 SUCCESS/FAILED 상태로 저장한다. FAILED 기록은 `SearchQueryCommandService.markFailed()`와 동일하게 `@Transactional(propagation = REQUIRES_NEW)` 패턴을 검토한다.
