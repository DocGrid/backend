# #35 검색 블록 인프라 구축 — Python 임베딩 서버 + OpenSQL pgvector

closes #35

---

## 배경

벡터 검색 기능을 만들려면 두 가지 인프라가 먼저 갖춰져야 한다.

1. **텍스트 → 벡터 변환 서버**: 사용자 질문을 숫자 배열(1024차원)로 바꿔주는 Python 서버
2. **벡터 저장/검색 가능한 DB**: pgvector 확장이 설치된 PostgreSQL

두 인프라 모두 "Spring Boot 안에서 직접 처리"가 불가능하거나 비현실적이라 외부 컴포넌트로 분리했다.

---

## 1. Python 임베딩 서버

### 1.1 임베딩 모델 선택 — `BAAI/bge-m3`

| 후보 | 기각/채택 이유 |
|---|---|
| OpenAI `text-embedding-3` | Closed API — 대회 규정([별표2] "Closed API 사용 불가") 위반 |
| Cohere `embed-multilingual` | 동일하게 Closed API |
| `jhgan/ko-sroberta-multitask` (기존 사용) | STS(문장 유사도)용으로 학습됨 — "짧은 질문 vs 긴 문서" 비대칭 매칭에 약함. 최대 128토큰이라 청크(256~512토큰)가 잘려 임베딩에 반영 안 됨. retrieval 벤치마크에서 bge-m3 계열에 밀림 |
| `intfloat/multilingual-e5-large` | 대안으로 유효했으나 한국어 커뮤니티 자료가 bge-m3 대비 적어 기각 |
| **`BAAI/bge-m3`** | 채택 — retrieval 특화, 다국어(100+), 8192토큰, dense/sparse/multi-vector 지원(2단계 하이브리드 확장 여지) |

모델 변경 요약:

| 항목 | 기존 | 변경 |
|---|---|---|
| 모델 | ko-sroberta-multitask | BAAI/bge-m3 |
| 차원 | 768 | 1024 |
| 최대 입력 | 128토큰 | 8192토큰 |
| 언어 | 한국어 전용 | 다국어(100+) |
| 모델 크기 | 약 768MB | 약 2.2GB |

트레이드오프: 모델 크기가 3배로 커져 최초 다운로드/로딩 시간 증가. Docker 볼륨으로 캐시하면 재시작 시에는 비용 없음.

### 1.2 서빙 방식 — FastAPI 사이드카

| 방식 | 기각/채택 이유 |
|---|---|
| Java 내부 실행(DJL, ONNX Runtime) | bge-m3의 Python 생태계(FlagEmbedding) 의존도가 높아 Java 포팅 시 구현/검증 리스크 큼 |
| Hugging Face TEI | 프로덕션급이지만 "우리가 직접 구현한 컴포넌트"가 아니라 포트폴리오 목적에 안 맞음 |
| Flask | FastAPI 대비 비동기 처리·자동 Swagger 문서화 이점이 없어 기각 |
| **FastAPI 사이드카** | 채택 |

사이드카 구조를 택한 이유: Spring Boot와 언어/런타임이 완전히 다르므로 관심사를 분리하고, Spring 입장에서 임베딩 서버를 PostgreSQL과 마찬가지로 "외부 인프라"로 취급해 장애 시 503으로 격리하는 설계와 자연스럽게 맞음.

### 1.3 파일 구조

```text
embedding-server/
├── main.py           # FastAPI 앱: POST /embed, GET /health
├── requirements.txt  # 버전 고정 의존성
└── Dockerfile        # python:3.11-slim 기반
```

### 1.4 Dockerfile

```dockerfile
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

- `requirements.txt`를 `main.py`보다 먼저 COPY: Docker 레이어 캐싱 — 코드만 바뀔 때 `pip install` 레이어 재사용되어 빌드 빠름.
- `--host 0.0.0.0`: 컨테이너 외부(Spring Boot)에서 접근 가능하게 하는 필수 설정.

### 1.5 main.py

```python
def _load_model():
    global model
    model = BGEM3FlagModel("BAAI/bge-m3", use_fp16=True)

@asynccontextmanager
async def lifespan(app: FastAPI):
    thread = threading.Thread(target=_load_model, daemon=True)
    thread.start()
    yield
    global model
    model = None

app = FastAPI(lifespan=lifespan)

@app.get("/health")
def health():
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    return {"status": "ok"}

@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    result = model.encode([req.text], batch_size=1, max_length=8192)
    vector = result["dense_vecs"][0].tolist()
    return EmbedResponse(vector=vector)
```

- 모델 로딩을 별도 스레드에서 백그라운드 실행: FastAPI 서버 자체는 즉시 기동되고, 모델 준비 전엔 `/health`가 503 반환. "떠 있음"과 "실제로 쓸 수 있음"을 구분.
- `model.encode([req.text], ...)`: 리스트로 감싸는 이유 — bge-m3가 배치 처리 기본(현재는 batch_size=1로 1개씩).
- `max_length=8192`: bge-m3 최대 토큰 길이 그대로 지정.
- `result["dense_vecs"][0]`: bge-m3는 dense/sparse/multi-vector 모두 낼 수 있는데 MVP는 dense만 사용.

### 1.6 requirements.txt

```text
fastapi==0.115.0
uvicorn==0.30.6
FlagEmbedding==1.2.11
torch==2.4.1
transformers==4.44.2
peft==0.12.0
numpy==1.26.4
```

### 1.7 API 명세

#### GET /health

```json
// 200
{ "status": "ok" }

// 503 — 모델 미로드
{ "detail": "Model not loaded" }
```

#### POST /embed

```json
// 요청
{ "text": "검색할 텍스트" }

// 200
{ "vector": [0.012, -0.034, ..., 0.087] }  // 1024개

// 503 — 모델 미로드
{ "detail": "Model not loaded" }
```

### 1.8 트러블슈팅

#### torch / transformers 버전 충돌

증상: 컨테이너 기동 시 ImportError traceback.  
원인: `torch==2.4.1`만 고정하고 `transformers`는 미지정 → 최신 버전이 설치되며 비호환.  
해결: `transformers==4.44.2`로 명시적 고정.

#### FlagEmbedding 임포트 실패 (peft 누락)

증상: `ModuleNotFoundError: No module named 'peft'`.  
원인: `FlagEmbedding`이 내부적으로 `peft`(LoRA 파인튜닝 라이브러리)에 의존하는데 자동 설치 안 됨.  
해결: `peft==0.12.0` 추가.

#### healthcheck가 계속 unhealthy

증상: 컨테이너는 정상 응답하는데 `docker ps`가 unhealthy 표시.  
원인: `python:3.11-slim`엔 `curl`이 없는데 healthcheck가 curl 기반이었음.  
해결: Python 표준 라이브러리(urllib.request)로 교체.

```yaml
test: ["CMD", "python3", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"]
```

### 1.9 모델 런타임 다운로드 + 볼륨 캐시

빌드 시 모델을 이미지에 포함하면 이미지 크기 3GB 이상 증가. 대신 첫 컨테이너 시작 시 HuggingFace에서 다운로드 후 `huggingface-cache` Docker 볼륨에 캐시.

```yaml
volumes:
  - huggingface-cache:/root/.cache/huggingface
```

두 번째 실행부터는 볼륨에서 로드 — 재시작 시 즉시 사용 가능.

### 1.10 최종 검증

```bash
curl -s http://localhost:8000/health
# {"status": "ok"}

curl -s -X POST http://localhost:8000/embed \
  -H "Content-Type: application/json" \
  -d '{"text": "연차 규정"}'
# {"vector": [0.02143, -0.44201, ...]}  // 총 1024개
```

모델 총 다운로드 용량 약 3.28GB, Docker volume 캐시로 재시작 시 즉시 로드.

---

## 2. OpenSQL + pgvector 커스텀 이미지

### 2.1 문제 발견

대회 지정 DB인 `tmaxopensql/postgres:14.6` 이미지에 pgvector 확장이 없었음.

```text
ERROR: could not open extension control file
"/usr/pgsql-14/share/extension/vector.control": No such file or directory
```

### 2.2 검토한 대안

| 옵션 | 채택 여부 |
|---|---|
| OpenSQL 유지 + pgvector 소스 빌드 설치 | **채택** |
| pgvector/pgvector:pg16으로 완전 교체 | 기각 — 과제명이 "OpenSQL 기반 AI 검색 및 벡터 데이터 플랫폼 개발"이라 OpenSQL 교체 시 과제 요건 위반 위험 |

채택 근거: 공식 문서에서 OpenSQL 확장도 `make + pg_config`로 소스 빌드해서 설치하는 걸 정식 절차로 안내 — 확장을 직접 컴파일해서 얹는 방식이 OpenSQL의 정상적인 사용 패턴.

### 2.3 docker/opensql/Dockerfile

```dockerfile
FROM tmaxopensql/postgres:14.6

USER root

RUN curl -L https://github.com/pgvector/pgvector/archive/refs/tags/v0.8.0.tar.gz \
        -o /tmp/pgvector.tar.gz \
    && tar -xzf /tmp/pgvector.tar.gz -C /tmp \
    && cd /tmp/pgvector-0.8.0 \
    && make PG_CONFIG=/usr/pgsql-14/bin/pg_config \
    && make install PG_CONFIG=/usr/pgsql-14/bin/pg_config \
    && rm -rf /tmp/pgvector.tar.gz /tmp/pgvector-0.8.0

COPY init-and-start.sh /usr/local/bin/init-and-start.sh
RUN chmod +x /usr/local/bin/init-and-start.sh

CMD ["/usr/local/bin/init-and-start.sh"]
```

`tmaxopensql/postgres:14.6`을 베이스로 그대로 유지하고 그 위에 pgvector 0.8.0을 소스 컴파일해서 얹는 구조. OpenSQL 자체를 바꾼 게 아니라 OpenSQL + pgvector 커스텀 이미지를 새로 만든 것.

### 2.4 docker/opensql/init-and-start.sh

OpenSQL entrypoint는 컨테이너 기동 시 Ansible을 실행해 PostgreSQL을 초기화한다. Ansible 완료 후 이 스크립트가 실행된다.

```bash
#!/bin/bash
set -e
PGDATA="${PGDATA:-/var/lib/pgsql/14/data}"

# 초기화 작업 중엔 로컬 소켓 전용으로 임시 기동 (TCP 미오픈)
pg_ctl start -D "$PGDATA" -l /tmp/pg_init.log -o "-h ''" -w

# ansible이 docgrid DB 자동 생성을 안 해줘서 직접 처리
psql -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'docgrid'" | grep -q 1 \
    || psql -d postgres -c "CREATE DATABASE docgrid OWNER docgrid;"

# pgvector 확장 활성화
psql -d docgrid -c "CREATE EXTENSION IF NOT EXISTS vector;"

# 호스트 머신(Spring Boot)에서 TCP 접속 허용
grep -qxF "host all all 0.0.0.0/0 scram-sha-256" "$PGDATA/pg_hba.conf" \
    || echo "host all all 0.0.0.0/0 scram-sha-256" >> "$PGDATA/pg_hba.conf"

pg_ctl stop -D "$PGDATA" -m fast -w
exec postgres  # PID 1을 postgres 프로세스로 교체 — exec 없으면 스크립트 종료 시 컨테이너도 종료됨
```

- `pg_ctl start ... -o "-h ''"`: 초기화 작업 중엔 TCP를 안 열고 로컬 소켓으로만 안전하게 작업.
- `exec postgres`: Docker가 컨테이너를 "살아있음"으로 인식하려면 PID 1이 postgres여야 함.
- `scram-sha-256`: 초기 버전은 `trust`(비밀번호 없이 누구나 접속 가능)였다가, 비밀번호 해시 인증으로 보안 강화.

### 2.5 트러블슈팅

#### 재시작 시 컨테이너 크래시

증상: `su: user docgrid does not exist` 에러.  
원인: `.env`(DB_USER=docgrid)와 `vars.yml`(pg_owner: app)의 유저명 불일치.  
해결: `vars.yml`을 `.env`에 맞춰 통일.

#### docgrid DB 자동 생성 안 됨

원인: `vars.yml`의 `pg_databases` 목록 설정이 OpenSQL ansible 롤에서 인식 안 됨 (Docker 컨텍스트에서 해당 태스크가 스킵되는 것으로 추정).  
해결: `init-and-start.sh`에서 ansible 완료 후 직접 `CREATE DATABASE` 실행.

#### Spring Boot → DB 연결 실패

증상: `no pg_hba.conf entry for host "192.168.65.1"...`  
원인: Docker Desktop 브리지 게이트웨이 IP에서 오는 TCP 연결 허용 규칙 없음.  
해결: `pg_hba.conf`에 `host all all 0.0.0.0/0 scram-sha-256` 추가.

### 2.6 트레이드오프 — pg_hba.conf IP 대역

현재 `0.0.0.0/0`으로 설정되어 있어 IP 대역이 넓음. 인증 방식은 `trust`에서 `scram-sha-256`으로 개선됐으나, 포트가 외부 네트워크에 노출되는 상황(공용 와이파이, 시연 환경 등)에서는 주의 필요. 시연 전에는 Docker 브리지 대역으로 좁히는 것 권장.

### 2.7 Ansible 재실행 동작 주의

OpenSQL entrypoint는 컨테이너가 새로 생성될 때마다 Ansible을 실행한다(볼륨 유지 여부와 무관). 이미 초기화된 경우 대부분의 태스크가 skip되지만 전체 플레이가 돌기 때문에 약 5분 소요. Dockerfile 변경은 확실한 사항만 모아서 한 번에 반영하는 것이 좋다.

---

## 로컬 실행 방법

```bash
# PostgreSQL (커스텀 이미지)
docker compose build opensql
docker compose up -d opensql
# Ansible 초기화로 약 5분 소요

# 임베딩 서버
docker compose build embedding-server
docker compose up -d embedding-server
# 첫 실행 시 bge-m3 다운로드로 약 10~15분 소요 (약 3GB)
# docker logs -f docgrid-embedding 으로 진행 상태 확인
# Uvicorn 시작 로그는 서버 프로세스만 뜬 것 — 모델 로딩 전까지 /health가 503 반환
# GET /health 응답이 200이 될 때까지 대기 후 사용
```

---

## 이후 변경 이력 (원 설계 이후 팀 작업으로 확장·전환된 부분)

위 내용은 #35 시점의 설계·구현 기록으로 그대로 보존한다. 이후 팀 작업으로 아래가 추가·전환되었으며, **원 설계의 핵심 계약 — BAAI/bge-m3 모델, 1024차원 dense vector, `/embed` API, `/health`의 "떠 있음 vs 쓸 수 있음" 구분, HuggingFace 볼륨 캐시 — 은 현재까지 그대로 유지되고 있다.**

### 임베딩 서버 API 확장 — `/embed/batch` (팀원, 문서 인덱싱 파이프라인)

문서 인덱싱 파이프라인(A담당) 구축 과정에서 여러 청크를 한 번에 임베딩하는 `POST /embed/batch`가 추가되었다. 검색은 기존 `/embed`(단건), 문서 인덱싱은 `/embed/batch`(배치)로 용도가 나뉜다. 응답에 `model`명과 요청 순서를 보존한 `embeddings[{index, vector}]`를 포함한다.

### 부하·메모리 보호 계층 (#213, #216 — 김기민)

- **#213**: 실제 PDF 3건 실측 벤치마크로 문서 배치 기본값을 `batch-size=4`로 결정, 문서 배치 전용 read timeout 30s 신설 (검색 5s는 원 설계대로 유지)
- **#216**: 서버에 Admission Controller 추가 — 모델 `encode` 동시 실행 1개 + 대기 1건 제한, 초과 요청은 `429 Too Many Requests` + `Retry-After` 헤더로 즉시 거절 (`EMBEDDING_PROVIDER_OVERLOADED`). docker-compose에 `EMBEDDING_PROVIDER_MAX_CONCURRENCY`, `EMBEDDING_PROVIDER_MAX_QUEUE_SIZE`, `EMBEDDING_PROVIDER_QUEUE_WAIT_TIMEOUT_SECONDS` 환경변수 추가

상세는 `gimin-#213-real-pdf-embedding-safety.md`, `gimin-#216-embedding-provider-load-protection.md` 참조.

### OpenSQL 커스텀 이미지 → 표준 이미지 + 호환성 검증으로 전환 (#97, #124 — 김기민)

§2의 OpenSQL 14.6 + pgvector 커스텀 이미지는 대회 지정 환경이 OpenSQL 17.8로 상향되면서 전략이 바뀌었다.

- **#97**: 로컬 개발 DB를 표준 이미지 `pgvector/pgvector:0.8.1-pg17`로 전환, `docker/opensql/` 제거
- **#124**: 공식 OpenSQL 17.8 환경(Rocky Linux 9.7) 대응은 커스텀 이미지 대신 호환성 검증 테스트·Runbook 방식으로 이관

§2의 원본 코드는 git 이력에 보존되어 있다: `git show 3e500e4:docker/opensql/Dockerfile`, `git show f4a6cd5:docker/opensql/init-and-start.sh`

### 현재 로컬 실행 방법

```bash
# PostgreSQL (표준 pgvector 이미지 — #97 이후)
docker compose up -d postgres

# 임베딩 서버 (기동 절차는 원 설계와 동일)
docker compose up -d embedding-server
```
