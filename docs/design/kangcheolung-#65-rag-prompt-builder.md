# #65 RAG 블록 시작 — Ollama 로컬 서버 세팅 + PromptBuilder 구현 (F-RAG-01)

closes #65

---

## 배경

검색 블록(#54, #56)으로 `POST /search`가 완성되어, 권한 필터링을 통과한 chunk 후보(`search_results`)를 유사도 순으로 반환할 수 있게 됐다. 이제 이 chunk 후보들을 근거로 LLM이 자연어 답변을 만들고, 어떤 chunk를 출처로 썼는지 같이 반환하는 **RAG 블록**을 시작한다.

RAG 블록은 명세상 아래 5개 기능(F-RAG-01~05)으로 구성되고, 개발도 이슈 5개로 나눠 진행한다.

| Issue | 범위 | 상태 |
|---|---|---|
| **1 (이 문서)** | Ollama 로컬 세팅 + PromptBuilder (F-RAG-01) | 완료 |
| 2 | OllamaClient 연동 — 실제 LLM 호출 (F-RAG-02) | 예정 |
| 3 | rag_responses 저장 (F-RAG-03) | 예정 |
| 4 | response_citations 저장 (F-RAG-04) | 예정 |
| 5 | 최종 answer + citations 응답 조합 (F-RAG-05) | 예정 |

이번 이슈(1번)의 범위는 딱 두 가지다.

1. RAG 답변 생성에 쓸 로컬 LLM(Ollama + qwen2.5:3b)을 개발 환경에 붙인다.
2. 검색 후보 목록을 LLM에게 줄 프롬프트 문자열로 조립하는 `PromptBuilder`를 만든다.

**이번 이슈에서 하지 않는 것**: 실제로 Ollama에 프롬프트를 HTTP로 보내 답변을 받는 것(Issue 2), `PromptBuilder`를 `SearchFacade`/`SearchController`와 연결하는 것(Issue 5)은 포함하지 않는다. `PromptBuilder`는 이번 이슈에서는 단위 테스트로만 검증되는 독립된 컴포넌트다.

---

## 전체 흐름

### 1) 인프라(로컬 개발 환경) 트랙 — docker-compose

```
docker compose up -d ollama
        │
        ▼
ollama/ollama 공식 이미지 실행 (11434 포트, /root/.ollama 볼륨)
        │
        ▼
docker compose exec ollama ollama pull qwen2.5:3b   ← 최초 1회만, 이후 볼륨에 캐시
        │
        ▼
docker compose exec ollama ollama run qwen2.5:3b "..."  로 응답 확인
```

이 트랙은 Spring Boot 코드와 무관하게, Ollama라는 별도 컨테이너를 로컬에 띄우는 작업이다. `embedding-server`(임베딩 서버, 자체 빌드 이미지) 옆에 나란히 추가된 세 번째 사이드카 서버다.

### 2) 코드 트랙 — PromptBuilder (아직 어디에도 연결되지 않은 독립 컴포넌트)

```
(향후 Issue 5에서 연결될 흐름 — 이번 이슈에는 미포함)
SearchFacade.search() 내부의 List<VectorSearchCandidate>
        │
        ▼
PromptBuilder.build(queryText, candidates)
        │
        ▼
"[1] 문서제목 p.페이지: "청크텍스트"\n[2] ...\n\n질문: ..." 형태의 프롬프트 문자열
        │
        ▼
(Issue 2의 OllamaClient가 이 문자열을 Ollama /api/generate로 전송 — 예정)
```

이번 이슈는 이 화살표 중 `PromptBuilder.build()` 한 칸만 구현하고 단위 테스트로 검증한다. `search_results`/`document_chunks`를 다시 조회하지 않고, `SearchFacade`가 이미 만들어 둔 `List<VectorSearchCandidate>`(chunkId, documentId, chunkText, pageNo, documentTitle, similarityScore)를 그대로 입력으로 받는 것을 전제로 설계했다.

---

## 신규/변경 파일

### 1. `docker-compose.yml` — ollama 서비스 추가

```yaml
  ollama:
    image: ollama/ollama
    container_name: docgrid-ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama-data:/root/.ollama
    healthcheck:
      test: ["CMD-SHELL", "ollama list || exit 1"]
      interval: 30s
      timeout: 10s
      retries: 5
      start_period: 60s
    networks:
      - docgrid-local
```

`volumes:` 최상단 블록에 `ollama-data:`도 함께 추가했다.

- `ollama/ollama`: 공식 이미지를 그대로 사용 — `embedding-server`처럼 별도 Dockerfile 빌드가 필요 없다.
- `ollama-data:/root/.ollama`: pull한 모델이 이 볼륨에 저장된다. 컨테이너를 지웠다 다시 띄워도 볼륨이 남아있으면 모델을 다시 받지 않는다.
- `healthcheck`: `ollama list`(로컬에 받은 모델 목록 조회)가 성공하면 서버가 정상 응답 중이라는 뜻이라 헬스체크로 사용했다.

### 2. `src/main/resources/application.yml` — Ollama 서버 주소 설정값 추가

```yaml
ollama:
  server:
    base-url: ${OLLAMA_SERVER_URL:http://localhost:11434}
```

기존 `embedding.server.base-url` 패턴을 그대로 따랐다. `OLLAMA_SERVER_URL` 환경변수가 없으면 docker-compose 기본 포트(`localhost:11434`)를 그대로 쓴다. 이 값은 아직 아무 코드에서도 참조하지 않는다 — 실제로 이 값을 읽어 `RestClient` Bean을 만드는 건 Issue 2(`OllamaServerConfig`)에서 한다. 이번 이슈에서는 "Ollama가 어디 떠 있는지"를 docker-compose 서비스 등록과 함께 미리 문서화해 두는 차원에서 넣었다.

### 3. `.env.example` — 오버라이드용 주석 옵션 추가

```bash
# 임베딩 서버 - application.yml에 이미 기본값(http://localhost:8000)이 있어 docker-compose 기본 포트를 쓰면 설정 불필요.
# 기본값과 다른 포트/호스트를 쓸 때만 주석 해제
# EMBEDDING_SERVER_URL=http://localhost:8000

# Ollama RAG LLM 서버 - application.yml에 이미 기본값(http://localhost:11434)이 있어 docker-compose 기본 포트를 쓰면 설정 불필요.
# 기본값과 다른 포트/호스트를 쓸 때만 주석 해제
# OLLAMA_SERVER_URL=http://localhost:11434
```

`.env`는 보안 규칙(`security.md`)상 직접 수정 금지 대상이라 `.env.example`(템플릿)만 갱신했다. `application.yml`에 이미 docker-compose 기본 포트와 동일한 기본값이 박혀 있어서, 로컬에서 기본 포트를 그대로 쓰는 한 `.env`에 이 값을 넣을 필요가 없다. 주석 처리해 둔 이유도 "기본값과 다르게 오버라이드하고 싶을 때만 필요하다"는 걸 명시하기 위함이다.

### 4. `README.md` — 임베딩 서버 / Ollama 실행 방법 섹션 추가

기존 README에는 로컬 DB 실행 방법만 있고 `embedding-server`, `ollama`에 대한 안내가 없었다. 두 섹션을 추가했다.

```markdown
## 임베딩 서버

docker compose build embedding-server
docker compose up -d embedding-server

- 첫 실행 시 BAAI/bge-m3 모델 다운로드로 약 10~15분 소요 (약 3GB)
- docker logs -f docgrid-embedding 으로 진행 상태 확인
- 모델 로딩 전까지 GET /health는 503 반환
- 기본 접속 정보 http://localhost:8000, EMBEDDING_SERVER_URL로 오버라이드 가능

## Ollama (RAG LLM 서버)

docker compose up -d ollama
docker compose exec ollama ollama pull qwen2.5:3b
docker compose exec ollama ollama run qwen2.5:3b "안녕"

- pull은 최초 1회만 필요 (약 2GB, ollama-data 볼륨에 캐시)
- 정상 응답이 텍스트로 출력되면 준비 완료
- 기본 접속 정보 http://localhost:11434, OLLAMA_SERVER_URL로 오버라이드 가능
```

### 5. `domain/rag/service/PromptBuilder.java` (신규)

```java
@Component
public class PromptBuilder {

    private static final String INSTRUCTION =
        "다음은 참고 문서입니다. 이 내용만을 근거로 답변하고,\n문서에 없는 내용은 추측하지 마세요.\n\n";

    public String build(String queryText, List<VectorSearchCandidate> candidates) {
        StringBuilder sb = new StringBuilder(INSTRUCTION);
        for (int i = 0; i < candidates.size(); i++) {
            VectorSearchCandidate candidate = candidates.get(i);
            sb.append(citationLine(i + 1, candidate)).append('\n');
        }
        sb.append("\n질문: ").append(queryText);
        return sb.toString();
    }

    private String citationLine(int order, VectorSearchCandidate candidate) {
        String pageSuffix = candidate.pageNo() != null ? " p." + candidate.pageNo() : "";
        return "[%d] %s%s: \"%s\"".formatted(order, candidate.documentTitle(), pageSuffix, candidate.chunkText());
    }
}
```

**한 줄 요약**: 검색 후보 리스트 + 질문 텍스트를 받아, 환각 방지 지시문 + 라벨링된 출처 목록 + 질문으로 이어지는 프롬프트 문자열 하나를 조립한다.

- `INSTRUCTION`: 명세 원문의 지시문("이 내용만을 근거로 답변하고, 문서에 없는 내용은 추측하지 마세요")을 그대로 상수로 뺐다. RAG 구조의 환각 방지 핵심 장치이므로 문구를 임의로 바꾸지 않았다.
- 라벨(`[1]`, `[2]`...)은 `candidates` 리스트의 **인덱스 순서를 그대로 사용**한다. 이 순서는 `SearchFacade`에서 이미 유사도/live check를 거쳐 정렬된 순서(=`SearchResultItem.rank`와 동일)이므로 별도 재정렬이나 라벨 매핑 구조체가 필요 없다. 이 순서는 Issue 4(`response_citations` 저장)에서 `citation_order`/`citation_label`을 매길 때도 그대로 재사용할 계획이다.
- `pageSuffix`: `pageNo`가 `null`(페이지 개념이 없는 문서 포맷)이면 `" p.N"` 부분을 통째로 생략한다.
- `search_results`/`document_chunks`를 다시 SELECT하지 않는다 — `VectorSearchCandidate`가 이미 `chunkText`, `documentTitle`, `pageNo`를 flat하게 갖고 있어서 이 레코드를 그대로 재사용하는 게 더 단순하고, 불필요한 재조회도 없앤다.
- 빈 리스트(`candidates.isEmpty()`)가 들어와도 이 클래스는 특별 취급하지 않는다 — 지시문 + 빈 출처 목록 + 질문으로 이어지는 프롬프트를 그대로 만든다. "검색 결과 0건이면 LLM 호출 자체를 생략한다"는 판단(NO_CONTEXT)은 이 클래스의 책임이 아니라, Issue 5에서 만들 `RagFacade`(오케스트레이션 레이어)의 책임으로 명확히 분리했다.

### 6. `src/test/java/.../rag/service/PromptBuilderTest.java` (신규)

`testing_guide.md` 컨벤션(`@DisplayName` 한국어, Given/When/Then)을 따라 3개 테스트를 작성했다.

| 테스트 | 검증 내용 |
|---|---|
| `build_withCandidates_appendsLabeledCitations` | 후보 2개 → `[1]`, `[2]` 순서대로 라벨이 붙고, 각 문서 제목/페이지/청크 텍스트가 프롬프트에 포함되는지 |
| `build_withNullPageNo_omitsPageSuffix` | `pageNo == null`인 후보는 `p.` 표기가 프롬프트에 없는지 |
| `build_alwaysIncludesInstructionAndQuestion` | 환각 방지 지시문과 `"질문: {queryText}"`가 항상 포함되는지 (빈 후보 리스트로도 검증) |

`PromptBuilder`가 외부 의존성이 없는 순수 컴포넌트라 Mockito 없이 `new PromptBuilder()`로 바로 인스턴스화해서 테스트했다. `VectorSearchCandidate`도 `SearchFacadeTest`와 동일하게 별도 Fixture 클래스 없이 직접 `new`로 생성했다 (재사용처가 아직 이 테스트 하나뿐이라 Fixture를 만들 이유가 없음).

---

## 로컬 검증 (실제 수행 기록)

### 1. docker-compose 문법 검증

```bash
$ docker compose config -q
$ echo $?
0
```
문법 오류 없음.

### 2. Ollama 컨테이너 기동

```bash
$ docker compose up -d ollama
[+] Running 5/5
 ✔ ollama Pulled                                                        79.3s
   ✔ f62942f34b62 Pull complete                                        76.5s
   ✔ 4b987da45db4 Pull complete                                         6.7s
   ✔ 921d3d52bf34 Pull complete                                        17.3s
   ✔ ef14de4afe0b Pull complete                                        17.6s
[+] Running 2/2
 ✔ Volume "docgrid_ollama-data"  Created                                0.0s
 ✔ Container docgrid-ollama      Started                                0.4s

$ docker compose ps ollama
NAME             IMAGE           COMMAND               SERVICE   STATUS
docgrid-ollama   ollama/ollama   "/bin/ollama serve"   ollama    Up 8 seconds (healthy)
```
컨테이너가 `healthy` 상태로 11434 포트에 정상 기동됨을 확인.

### 3. qwen2.5:3b 모델 pull

```bash
$ docker compose exec ollama ollama pull qwen2.5:3b
pulling manifest
pulling 5ee4f07cdb9b: 100% ▕████████████████████████▏ 1.9 GB
verifying sha256 digest
writing manifest
success
```
약 1.9GB, `ollama-data` 볼륨에 캐시되어 이후 컨테이너 재기동 시 재다운로드 불필요.

### 4. 모델 응답 확인

```bash
$ docker compose exec ollama ollama run qwen2.5:3b "안녕"
안녕하세요!如何可以帮助您？
```

**관찰된 현상**: "안녕"이라는 한국어 인사에 대해 한국어("안녕하세요!")와 중국어("如何可以帮助您？")가 섞인 응답이 나왔다.

**원인 분석**: qwen2.5:3b는 중국 Alibaba에서 만든 다국어 모델이라, 짧고 문맥이 거의 없는 프롬프트("안녕" 한 단어)에서는 출력 언어가 안정적으로 고정되지 않는 경향이 있다. 에러나 설정 문제가 아니라 이 규모(3b)의 다국어 모델이 갖는 특성이다.

**판단 및 대응 방향**: 이번 이슈의 완료 기준은 "로컬 Ollama 서버가 정상 응답을 생성한다"는 것이고(마일스톤 표 1번), 이 기준은 충족했다. 다만 실제 RAG 답변 품질을 위해서는 언어를 한국어로 고정하는 지시문이 필요할 수 있다. 지금 `PromptBuilder.INSTRUCTION`에는 언어 지시가 없는데, 이 시점에 미리 추가하기보다 **Issue 2(OllamaClient 연동)에서 실제 검색 문서 기반 프롬프트로 재현 여부를 확인한 뒤 필요 시 `INSTRUCTION`에 "한국어로 답변하세요" 한 줄을 추가하기로 결정**했다. 지금은 이 컴포넌트가 실제 LLM 호출과 연결되어 있지 않아 검증할 방법이 없기 때문이다(검증 안 된 변경을 미리 넣지 않는다는 원칙).

### 5. 단위 테스트 / 빌드

```bash
$ ./gradlew test --tests "*PromptBuilderTest*"
BUILD SUCCESSFUL in 8s

$ ./gradlew build -x test
BUILD SUCCESSFUL in 1s
```

---

## 에러 케이스 정리

`PromptBuilder`는 순수 문자열 조립 로직이라 예외를 던지지 않는다. 이번 이슈 범위에서 발생 가능한 케이스는 아래와 같다.

| 상황 | 처리 |
|---|---|
| `candidates`가 빈 리스트 | 지시문 + 빈 출처 목록 + 질문만 있는 프롬프트를 그대로 생성 (예외 없음). 이 프롬프트를 실제로 Ollama에 보낼지 말지는 Issue 5의 `RagFacade`가 판단 — `PromptBuilder`는 판단하지 않는다 |
| `candidate.pageNo() == null` | `" p.N"` 부분만 생략, 나머지는 정상 조립 |
| Ollama 컨테이너가 안 떠 있음 | 이번 이슈는 `PromptBuilder`와 Ollama 인프라가 코드로 연결되어 있지 않아 영향 없음 (연결은 Issue 2) |

---

## 설계 결정 요약

**`search_results`/`document_chunks` 재조회 없이 `VectorSearchCandidate` 재사용**
검색 블록이 이미 만든 in-memory 객체를 그대로 넘겨받는 구조로 설계했다. DB 재조회 비용을 없애고, `SearchFacade` → (향후) `RagFacade`로 이어지는 흐름에서 동일 트랜잭션/요청 내에 이미 갖고 있는 데이터를 재사용하는 게 자연스럽다.

**인용 라벨 = 리스트 인덱스, 별도 매핑 구조체 없음**
검색 결과 순서가 이미 유사도 기준 정렬 + live check를 거친 최종 순서이므로, 이 순서를 그대로 라벨/citation_order의 근거로 삼는다. Simplicity First 원칙에 따라 별도 DTO(예: `LabeledChunk`)를 만들지 않았다.

**Mock LLM 미구현**
Ollama 설치 자체가 이번 이슈 범위(docker-compose 서비스 등록)에 포함되어 있어 로컬 개발 환경에 실제 LLM을 붙이는 데 추가 비용이 없다. Mock LLM을 넣으면 프로덕션 코드에 "Mock이냐 실제냐" 분기가 생기는데, 이는 불필요한 feature flag이자 나중에 제거해야 할 임시 코드라 판단해 처음부터 만들지 않기로 했다. 단위 테스트는 Mockito로 `OllamaClient`(Issue 2)를 mocking하는 방식으로 충분하다.

**`OllamaServerConfig`(RestClient Bean)는 Issue 2로 미룸**
이 Bean은 `OllamaClient`가 생겨야 실제로 쓰인다. 이번 이슈에서 미리 만들면 아무도 참조하지 않는 Bean이 컨텍스트에 등록되는 셈이라, 사용처가 생기는 Issue 2에서 함께 만들기로 했다.

**언어 지시문("한국어로 답변하세요") 추가는 Issue 2로 연기**
로컬 검증 중 언어가 섞이는 현상을 관찰했지만, 실제 RAG 프롬프트(검색된 한국어 문서 chunk 포함)로 테스트해보지 않은 상태에서 미리 지시문을 추가하는 것은 검증되지 않은 변경이다. Issue 2에서 `OllamaClient`로 실제 호출해보고 문제가 재현되면 그때 `PromptBuilder.INSTRUCTION`에 한 줄 추가하기로 결정했다.

---

## 남은 이슈 / TODO

### 코드
- `PromptBuilder`는 아직 어디에서도 호출되지 않는 독립 컴포넌트 — Issue 5에서 `RagFacade`가 실제로 연결한다.
- 언어 지시문 추가 여부는 Issue 2에서 실제 Ollama 호출 결과를 보고 판단.

### 문서
- README의 기존 "Local DB" 섹션이 참조하는 `docs/local-db.md` 링크는 이번 작업 이전부터 실제 파일이 없는 broken 링크였다 — 이번 이슈 범위 밖이라 별도로 손대지 않음.

### 다음 단계
Issue 2 — `OllamaServerConfig`(RestClient Bean) + `OllamaClient` 구현. `PromptBuilder.build()`로 만든 프롬프트를 `POST /api/generate`로 실제 전송하고, 타임아웃/장애 시 503 `SERVICE_UNAVAILABLE`로 처리하는 예외 처리까지 포함한다.
