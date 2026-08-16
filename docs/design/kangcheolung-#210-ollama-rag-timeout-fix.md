# #210 Ollama RAG 응답 지연/타임아웃 수정

## 배경

RAG 답변 생성(Ollama 호출)이 자주 타임아웃되거나, 답변이 중간에 끊기거나, 엉뚱한 언어(중국어)로
새거나, 무관한 검색 결과를 근거 문서인 것처럼 보여주는 문제가 QA 중 다수 발견됐다. 원래 버그
리포트는 Docker로 띄운 Ollama가 GPU 가속을 못 받아 CPU 전용 추론으로 50초 이상 걸려 항상
타임아웃 나는 것이 출발점이었다.

## 0단계 — 세션 시작 전 상태 (원래 버그 리포트)

- Ollama를 Docker 컨테이너로 실행 → Docker Desktop for Mac은 컨테이너에 Metal GPU를 못 넘김 →
  CPU 전용 추론
- `read-timeout: 18s`, 실제 RAG 프롬프트 기준 50초 이상 소요 → 항상 타임아웃
- 모델 cold load에만 8.98~10.78초, 18.026초 지점에 강제 취소되는 사례 확인됨

## 1단계 — Ollama Docker → macOS 네이티브 전환

Docker Desktop for Mac은 컨테이너에 GPU(Metal)를 넘길 방법이 없어 CPU로만 추론하게 되므로,
Ollama를 macOS에 네이티브로 설치(`brew install ollama`)해 Apple Silicon Metal 가속을 받도록
전환했다. `docker-compose.yml`에서 `ollama` 서비스/볼륨을 제거했다.

### 벤치마크 — 원본 명령어와 결과

**Docker(CPU) — 명령어**
```bash
docker run -d --name docgrid-ollama-benchmark \
  -p 127.0.0.1:11435:11434 \
  -v docgrid_ollama-data:/root/.ollama \
  ollama/ollama

curl -s http://localhost:11435/api/generate -d @/tmp/test_req_docker.json \
  -o /tmp/docker_resp.json \
  -w "HTTP_CODE:%{http_code} TIME:%{time_total}s\n"
```

**Docker(CPU) — 결과 (터미널 출력 그대로)**
```
HTTP_CODE:200 TIME:204.825280s
done: True done_reason: stop
prompt_eval_count: 676 eval_count: 236
total_duration(ns): 204796233095
prompt_eval_duration(ns): 28970897000
eval_duration(ns): 127378650000
```

**네이티브(Metal) — 명령어**
```bash
# 1) 요청 전송
curl -s http://localhost:11434/api/generate -d @/tmp/test_req_raw.json | python3 -c "
import json,sys
d = json.load(sys.stdin)
print(d.get('response'))
print('done:', d.get('done'))
"

# 2) 서버 자체 로그에서 시간 통계 확인
grep -an "task.n_tokens\|n_decoded\|prompt eval time\|eval time\|GIN.*api/generate" \
  /opt/homebrew/var/log/ollama.log | tail -10
```

**네이티브(Metal) — 결과 (로그 파일에 찍혀있던 그대로)**
```
task.n_tokens = 676
prompt eval time = 3817.31 ms / 676 tokens (177.09 tokens per second)
eval time = 12169.35 ms / 216 tokens (17.75 tokens per second)
total time = 15986.66 ms / 892 tokens
[GIN] 2026/08/15 - 23:53:00 | 200 | 19.85289775s | POST "/api/generate"
```

두 테스트 모두 동일한 프롬프트(676 prompt 토큰), 동일한 옵션(`raw:true`, `num_predict` 계열)으로
실행했다.

### 벤치마크 — 정리(계산)

| | Docker(CPU) | 네이티브(Metal) | 계산 |
|---|---|---|---|
| 총 소요 시간 | 204.825280s | 19.85289775s | 204.83 ÷ 19.85 ≈ **10.3배** |
| decode 속도 | eval_duration(127.378650s) ÷ eval_count(236) ≈ 1.85 토큰/초 | 17.75 토큰/초 (로그에 이미 계산되어 있음) | 17.75 ÷ 1.85 ≈ **9.6배** |
| 절대 시간 차이 | - | - | 204.83 − 19.85 ≈ **약 185초(3분 5초) 단축** |

네이티브 전환 없이는 25~29초대 프론트/워커 타임아웃 제한 자체가 애초에 충족 불가능한 수준이었다.

## 2단계 — 프롬프트 내용 버그 3건

| 문제 | 원인 | 해결 |
|---|---|---|
| 중국어로 답이 샘 | 한국어 강제 지시 없음 (qwen2.5는 Alibaba 모델) | `PromptBuilder` 지시문 앞부분 + 질문 바로 뒤 2곳에 "반드시 한국어로만" 추가 |
| 구체적 질문("ls 관련 명령어 찾아줘")에도 뭉뚱그린 답만 나옴 | "요약해줘/찾아줘/소개해줘" 요청을 전부 "주제만 설명"하라는 지시 하나로 처리해 구체적 대상 지정 질문까지 함께 걸림 | 지시문을 2갈래로 분리 — 구체적 키워드 지정 시 항목 빠짐없이 나열 / 대상 없는 요청은 3~4문장 요약 |
| 답변이 특정 지점에서 `done:false`로 끊김 | `/api/generate`가 기본으로 태우는 채팅 템플릿 + tool-call PEG 파서가 답변 속 백틱 코드 표기(`` `ls` ``)를 tool-call 시도로 오인 | 요청에 `raw: true` 추가해 템플릿/파서 자체를 우회 |

## 3단계 — 시간 예산 튜닝

`topK`는 호출자가 1~20까지 정할 수 있는 값인데(`SearchRequest`), 검색된 후보를 그대로 프롬프트에
다 넣고 있어 후보 수에 따라 prefill 시간이 들쭉날쭉했다. 화면 표시용 `topK`와 별개로,
`RagFacade`에서 LLM에 넘기는 후보 수를 `MAX_PROMPT_CANDIDATES = 3`으로 고정했다.

디코드 속도 자체도 세션 중 초당 12~18토큰으로 흔들리는 것이 로그로 확인됐다(원인 미확정, 열
스로틀링 등 추정). 이 때문에 `num_predict`/`read-timeout` 값을 여러 차례 조정했다.

| 시점 | read-timeout | num_predict |
|---|---|---|
| 세션 시작 | 18s | (옵션 없음) |
| raw:true 적용 전 | 25s | 300 |
| ls 컷오프 대응(오진단 — 실제 원인은 raw 파서 버그) | 25s | 300 → 500 |
| 500이 자체적으로 25s 예산 초과 확인 후 | 25s | 500 → 300 |
| 후보 캡(3개) 이후에도 timeout 재현, 디코드 속도 변동 확인 | 25s → **27s** | 300 → 220 |
| 답변이 너무 짧아진다는 피드백 반영 | **27s** | 220 → **250 (최종)** |

## 4단계 — 실패 시 사용자 경험 개선

시간 튜닝만으로는 100% 무타임아웃을 보장할 수 없다는 결론에 따라, 실패했을 때의 경험을
개선하는 쪽으로 방향을 잡았다.

- **근거 문서 숨김 버그**: LLM이 "관련 문서를 찾지 못했습니다"라고 답해도 검색 후보가 그대로
  citations로 내려가 화면에 표시되던 문제. `RagFacade`에서 해당 문구 포함 시 citations를 빈
  배열로 반환(DB에는 그대로 저장)하도록 수정. 프론트 `search-sources.ts`의 "citations 비면
  results로 대체" fallback도 제거(백엔드 수정이 프론트에서 무력화되고 있었음).
- **Extractive fallback**: Ollama 호출 실패/타임아웃 시 정적 안내 문구 대신, 최상위 검색 후보
  원문을 최대 300자까지 인용해서 보여주도록 `RagFacade` 수정.
- **나열형 답변 압축 시도 2건 → 모두 철회**:
  - "항목명: 짧은 설명 형식으로 압축" 지시 → 모델이 무시하고 기존처럼 완전한 문장으로 답함
  - "최대 8개까지만, 넘으면 '외 N개 더 있음'" 지시 → 한 케이스에서는 정확히 동작했지만, 다른
    케이스에서 모델이 지시문을 답변에 그대로 베껴 쓰거나 스스로 없는 규칙("3개만 나열")을
    지어내는 오작동을 유발함
  - **최종 대안**: Ollama 응답의 `eval_count`(실제 생성 토큰 수)가 `num_predict`에 도달했는지를
    코드로 확정 판별해, 잘렸을 때만 `OllamaClient`가 답변 끝에
    `"※ 답변이 길어 일부 내용이 생략됐을 수 있습니다. 자세한 내용은 문서를 확인해주세요."`를
    자동 첨부. LLM의 자기 판단에 의존하지 않는 방식.

## 5단계 — KV 캐시 정밀도 문제 해결

~~**글자 깨짐(한자·가타카나가 한글 자리에 섞임)**: `~/Library/LaunchAgents/homebrew.mxcl.ollama.plist`에
설정된 `OLLAMA_KV_CACHE_TYPE=q8_0`(KV 캐시 정밀도를 낮추는 옵션, 이번 세션 코드와 무관한 기존
설정)이 유력 원인으로 지목됨. 제거하기로 합의했으나 아직 미적용.~~ → **해결됨**: plist에서
`OLLAMA_KV_CACHE_TYPE` 항목을 제거하고 `launchctl unload`/`load`로 Ollama 서비스를 재로드했다
(`brew services restart`는 plist를 원본으로 재생성해 수동 설정을 지우므로 쓰지 않았다).
`OLLAMA_FLASH_ATTENTION=1`은 그대로 유지. 이후 QA에서 한자/가타카나 원문이 그대로 노출되는
사례는 재현되지 않았다 — 다만 아래 6단계에서 별도의 언어 혼입 코드 가드도 함께 추가해 이중으로
방어한다.

## 6단계 — 근본 원인 재규명: raw:true만으로는 부족했다

2단계에서 `raw:true`로 컷오프 버그를 해결했다고 판단했으나, 세션을 계속 진행하며 **같은 문서의
같은 질문("ls 관련 명령어 찾아줘")이 매번 정확히 같은 지점("...부모")에서 반복적으로 끊기는
현상**이 재발했다. `rag_responses.output_token_count`가 해당 응답들에서 전부 `NULL`인 것을
확인하고, DB에 저장된 프롬프트를 그대로 재요청해 Ollama 서버 로그를 직접 대조했다.

```
common_chat_peg_parse: unparsed Content-only output: ... `ls ../test2`: 부모 �렉
srv stop: cancel task
```

`�렉`이 결정적 단서였다 — "디렉토리"의 "디"가 토큰 경계에서 UTF-8 바이트 단위로 쪼개졌고,
`raw:true`로도 여전히 동작하던 tool-call PEG 파서가 이 불완전한 바이트열 파싱에 실패하자
**생성 자체를 취소**했다. 이건 llama.cpp의 PEG 기반 파서가 파싱 실패 시 스트림을 통째로
중단하는 알려진 버그 계열이며(llama.cpp #24807, #24863), Ollama 0.32.13(당시 최신)에서도
재현됨 — 업그레이드로 해결 불가능한 서버 측 결함이다. `raw:true`는 "백틱 코드로 인한 tool-call
오인식"이라는 한 가지 촉발 경로만 막았을 뿐, "토큰 경계에서 한글이 쪼개지는" 별개의 촉발
경로는 막지 못했던 것이다.

이 재규명을 계기로 시간 관리와 언어 안정성을 프롬프트가 아니라 **코드 레벨에서 결정적으로
방어**하는 쪽으로 구조를 다시 잡았다.

### 6-1. 스트리밍 + 애플리케이션 레벨 데드라인으로 전환

기존에는 `stream:false`로 완성된 응답을 한 번에 기다렸고, `read-timeout`이 초과하면 **이미 생성된
내용까지 통째로 버리고** 503(→ extractive fallback)으로 처리했다. `OllamaClient.generate()`를
`stream:true` + NDJSON 청크 누적 방식으로 바꾸고, 청크를 하나 읽을 때마다 `ollama.generate-deadline`
(기본 25s) 초과 여부를 확인해서, 초과 시 스트림을 끊고 **그때까지 모인 부분 답변**을 문장 경계
트리밍 + 잘림 안내로 반환한다. 한 청크도 못 받았을 때만 예외를 던져 기존 extractive fallback으로
넘어간다.

```java
private StreamChunks readStream(InputStream body, long deadline) throws IOException {
    StringBuilder answer = new StringBuilder();
    OllamaGenerateResponse last = null;
    boolean deadlineExceeded = false;
    BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
    String line;
    while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
            continue;
        }
        OllamaGenerateResponse chunk = CHUNK_MAPPER.readValue(line, OllamaGenerateResponse.class);
        if (chunk.response() != null) {
            answer.append(chunk.response());
        }
        last = chunk;
        if (chunk.done()) {
            break;
        }
        if (System.currentTimeMillis() >= deadline) {
            deadlineExceeded = true;
            break;
        }
    }
    return new StreamChunks(answer.toString(), last, deadlineExceeded);
}
```

`read-timeout`(27s)의 역할도 바뀌었다 — 이제 "전체 응답 완료까지"가 아니라 "스트리밍 헤더가
시작될 때까지"만 책임지고, 생성 전체 시간의 실질적 상한은 `generate-deadline`(25s)이 담당한다.
`num_predict`는 시간 상한을 `generate-deadline`이 넘겨받은 만큼 250 → **400**으로 완화했다
(디코드가 빠른 세션에서는 더 긴 답변을 허용).

**PEG 파서 버그와의 관계**: 이 버그가 발생하면 최종 청크에 `done:true`가 오지 않는다
(`common_chat_peg_parse` 실패 후 `cancel task`). 스트리밍 전환으로 이 경우도 "정상 완료가
아닌 조기 종료"로 명시적으로 판별할 수 있게 됐다(아래 6-3).

### 6-2. 샘플링 파라미터 추가 (temperature / top_p / repeat_penalty / repeat_last_n)

요청 옵션에 `num_predict`만 있어 나머지는 Ollama 기본값(`temperature≈0.7`, `repeat_penalty=1.0`,
`repeat_last_n=64`)으로 돌고 있었다. RAG는 문서 내용을 그대로 답하는 용도라 창의성이 필요 없다는
점에 착안해 4개를 추가했다.

```java
public record OllamaGenerateOptions(
    @JsonProperty("num_predict") int numPredict,
    double temperature,
    @JsonProperty("top_p") double topP,
    @JsonProperty("repeat_penalty") double repeatPenalty,
    @JsonProperty("repeat_last_n") int repeatLastN
) {
}
```

- `temperature: 0.3`, `top_p: 0.8` — 확률 꼬리에 있는 한자/가나 토큰이 뽑힐 확률 자체를 낮춰
  code-switching을 완화한다.
- `repeat_penalty: 1.1` — Ollama 기본값이 1.0(반복 억제 없음)임을 로그로 확인. 모델이 답을 끝내고도
  "한국어로만 답변했습니다… 번역 없음… 감사합니다…" 같은 잡담을 반복하며 토큰 상한까지 채우던
  현상을 억제.
- `repeat_last_n: 256` — `repeat_penalty`가 되돌아보는 토큰 창. 기본 64로는 64토큰보다 긴 블록이
  통째로 반복되는 것(디렉토리 요약이 처음부터 한 번 더 반복되는 현상)을 못 잡아서 넓혔다. 목록형
  답변의 정당한 반복 표현(각 항목이 "~를 출력합니다"로 끝나는 등)이 어색해지면 128로 낮출 것 —
  `OLLAMA_REPEAT_LAST_N` 환경변수로 코드 수정 없이 조절 가능.

### 6-3. 언어 혼입 코드 가드 (프롬프트 지시 대신 확정적 처리)

```java
private static SanitizedAnswer sanitizeAnswer(String text) {
    // 전각 구두점은 문장 부호 역할을 유지해야 하므로 삭제하지 않고 반각으로 치환한다.
    String normalized = text
        .replace('。', '.').replace('、', ',').replace('：', ':')
        .replace('，', ',').replace('！', '!').replace('？', '?');
    Matcher matcher = FOREIGN_CJK_PATTERN.matcher(normalized);
    if (!matcher.find()) {
        return new SanitizedAnswer(normalized, false);
    }
    int firstMixIndex = matcher.start();
    int mixedCount = matcher.group().length();
    while (matcher.find()) {
        mixedCount += matcher.group().length();
    }
    if (mixedCount > FOREIGN_CJK_CUT_THRESHOLD) {
        log.warn("답변에 한자/가나 대량 혼입({}자) 감지, 혼입 시작 지점에서 잘라냄", mixedCount);
        return new SanitizedAnswer(normalized.substring(0, firstMixIndex), true);
    }
    log.warn("답변에 한자/가나 혼입({}자) 감지, 제거함", mixedCount);
    return new SanitizedAnswer(FOREIGN_CJK_PATTERN.matcher(normalized).replaceAll(""), false);
}
```

한국어 RAG 답변에 한자·히라가나·가타카나가 나올 일은 없다는 전제로, 정규식(`\p{IsHan}`,
`\p{IsHiragana}`, `\p{IsKatakana}`)으로 감지해 처리한다. 두 갈래로 나눈 이유는 실제 QA에서
드러났다 — 짧은 낱자 혼입(8자 이하)은 단순히 지워도 문장이 자연스럽지만, **모델이 아예 중국어
반복 루프로 넘어간 대량 혼입**을 그냥 지워버리면 "Git ： 1. **Git **: - git branch: ." 같은
구두점 뼈대만 남아 더 지저분해졌다. 그래서 8자를 넘으면 문자만 지우지 않고 **혼입이 시작된
지점에서 답변 자체를 자르고** 문장 경계 트리밍 + 잘림 안내를 붙인다. 전각 구두점(。、：，！？)은
삭제 대신 반각으로 치환한다 — 삭제하면 문장 부호 자체가 사라지기 때문이다.

**`done:false` 조기 종료도 잘림으로 처리**: PEG 파서 버그(6단계 도입부)로 `done` 없이 스트림이
끝나는 경우, 데드라인 초과와 구분해서 `log.warn`으로 빈도만 추적하고 동일하게 문장 경계 트리밍 +
잘림 안내를 적용한다. 재시도는 하지 않기로 했다 — 확률적으로 재시도하면 우회될 가능성이 높지만
(temperature 0.3), 응답이 그만큼 느려지고 코드 분기가 늘어나는 트레이드오프가 있어 이번 라운드는
빈도 관찰(로그)까지만 하고 보류.

### 6-4. 문장 경계 트리밍 정교화

```java
private static boolean isSentenceEndDot(String text, int i) {
    boolean precededOk = i == 0
        || (text.charAt(i - 1) != '.' && !Character.isDigit(text.charAt(i - 1)));
    boolean followedOk = i == text.length() - 1 || text.charAt(i + 1) != '.';
    return precededOk && followedOk;
}
```

숫자 목록 마커("6.")와 경로 표기("..")의 마침표를 문장 끝으로 오인하지 않도록, 앞뒤에 숫자나
마침표가 연이어 있으면 후보에서 제외한다.

**알려진 잔여 결함(미해결)**: `` `ls .` `` 처럼 **백틱 코드 스팬 안에 있는 명령어 인자로서의
마침표**는 이 조건을 통과해버려 잘못 문장 끝으로 오인된다 — 최신 QA에서 `` "(`ls . (※ 답변이...)" `` 처럼
백틱·괄호가 안 닫힌 채 잘리는 사례로 재현됨. 해결 방향은 파악됨(마침표 앞의 백틱 개수 홀짝으로
코드 스팬 내부 여부 판별) — 아직 미적용.

**추가 관찰(원인 미확정)**: 내용상 완결된 것으로 보이는 답변(예: mv 명령어 요약)에도 잘림 안내가
붙는 사례가 있었다. `hitTokenLimit`/`prematureEnd` 중 어느 쪽이 오탐인지는 실제 응답의
`eval_count`/`done` 로그를 봐야 확정할 수 있어 조사 예정.

## 7단계 — 남은 미해결 항목

- 위 6-4의 백틱 코드 스팬 마침표 오인식, 완결 답변에 잘림 안내가 붙는 오탐 케이스
- 스프링부트(한글 음역) vs springboot(영문) 임베딩 매칭 — 조사만 하고 미해결. 현재 쿼리
  전처리/동의어 매칭/하이브리드(BM25) 검색이 전혀 없고 순수 벡터(BGE-M3 코사인 유사도, 임계값
  0.30)만 사용 중이라는 것까지만 확인함.
- 서버 시작 시 모델 warm-up, 더 작고 빠른 한국어 모델 비교, cold/warm p95 정식 계측(10회씩),
  임베딩 서버와의 메모리 경합 조사 — 원래 버그 리포트에 명시됐으나 이번 세션에서 미착수.
- 원래 버그 리포트의 실제 재현 케이스("코테이토 13기 회비는 얼마인가요?") 재테스트 — 미실행.
- PEG 파서 조기 종료 시 재시도(6-3에서 검토만 하고 보류) — 필요성은 빈도 로그를 보고 재판단.

## 최종 설정값 (이 문서 작성 시점)

- `ollama.server.connect-timeout`: 3s / `read-timeout`: 27s (스트리밍 헤더 수신까지만 책임)
- `ollama.generate-deadline`: 25s (생성 전체 시간의 실질적 상한)
- `ollama.num-predict`: 400
- `ollama.temperature`: 0.3 / `top-p`: 0.8
- `ollama.repeat-penalty`: 1.1 / `repeat-last-n`: 256
- `ollama.keep-alive`: 30m
- `OllamaGenerateRequest`: `stream:true`, `raw:true`
- `RagFacade.MAX_PROMPT_CANDIDATES`: 3
- Ollama 서비스: `OLLAMA_KV_CACHE_TYPE` 제거(정밀도 기본값 f16), `OLLAMA_FLASH_ATTENTION=1` 유지

closes #210
