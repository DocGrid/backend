# 벡터 검색 인프라 구축 (#35)

## 1. OpenSQL + pgvector 구성

### 배경

벡터 검색 기능 구현에 pgvector 확장이 필요하다.
공식 `pgvector/pgvector` Docker 이미지가 있지만, 대회 요구사항이 **OpenSQL(tmaxopensql) 기반**이라 교체할 수 없었다.
`tmaxopensql/postgres:14.6` 이미지에는 pgvector가 포함되어 있지 않아서 직접 컴파일해서 설치하는 방식을 택했다.

### 해결 방법

`docker/opensql/Dockerfile`을 새로 만들어 `tmaxopensql/postgres:14.6` 위에 pgvector 0.8.0을 소스에서 빌드해 설치했다.
기존 이미지 안에 `gcc`, `make`, `pg_config`가 모두 있어서 별도 도구 설치 없이 컴파일이 가능했다.

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

### init-and-start.sh

OpenSQL entrypoint는 Ansible을 실행해 PostgreSQL을 초기화한다.
Ansible 완료 후 CMD로 지정한 `init-and-start.sh`가 실행되며 아래 작업을 수행한다.

1. postgres를 로컬 소켓으로 임시 시작
2. `docgrid` DB 생성 (없으면)
3. `docgrid` DB에 `vector` 확장 활성화
4. 외부 TCP 접속 허용을 위해 `pg_hba.conf`에 항목 추가
5. postgres 정지 후 foreground로 재시작

```bash
pg_ctl start -D "$PGDATA" -l /tmp/pg_init.log -o "-h ''" -w

psql -d postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'docgrid'" | grep -q 1 \
    || psql -d postgres -c "CREATE DATABASE docgrid OWNER docgrid;"

psql -d docgrid -c "CREATE EXTENSION IF NOT EXISTS vector;"

grep -qxF "host all all 0.0.0.0/0 trust" "$PGDATA/pg_hba.conf" \
    || echo "host all all 0.0.0.0/0 trust" >> "$PGDATA/pg_hba.conf"

pg_ctl stop -D "$PGDATA" -m fast -w
exec postgres
```

### 트레이드오프 — pg_hba.conf 규칙 범위

현재 `host all all 0.0.0.0/0 trust`로 설정되어 있다.
로컬 개발 환경에서는 문제없지만, 포트가 외부 네트워크에 노출되는 상황(공용 와이파이, 시연 환경 등)에서는 인증 없이 접속이 가능해질 수 있다.
시연 전에는 `192.168.65.1/32` 또는 실제 Docker 브리지 대역으로 범위를 좁히는 것을 권장한다.

### 참고 — Ansible 재실행 동작

OpenSQL entrypoint는 컨테이너가 새로 생성될 때마다 Ansible을 실행한다 (볼륨 유지 여부와 무관).
이미 초기화된 경우 대부분의 태스크가 skip되지만 전체 플레이가 돌기 때문에 약 5분이 소요된다.
`docker compose build` 후 재시작 시에도 동일하게 발생하므로, Dockerfile 변경은 확실한 사항만 모아서 한 번에 반영하는 것이 좋다.

---

## 2. 임베딩 서버 구축

### 설계 배경

pgvector 기반 벡터 검색을 위해 텍스트를 1024차원 벡터로 변환하는 임베딩 서버가 필요했다.
Spring Boot에서 직접 모델을 실행하기 어렵기 때문에 Python 서버를 별도 서비스로 분리하고 HTTP로 통신하는 구조를 채택했다.

모델은 **BAAI/bge-m3**를 사용한다. 다국어(한국어 포함) 지원, 1024차원 dense vector 출력, pgvector HNSW 인덱스와의 궁합이 선택 이유다.

### 구현 구조

```
embedding-server/
├── main.py          # FastAPI 앱
├── requirements.txt # 의존성 (버전 고정)
└── Dockerfile       # python:3.11-slim 기반
```

Spring Boot와 같은 `docgrid-local` Docker 네트워크에 올라가며, `http://docgrid-embedding:8000`으로 통신한다.

### API 명세

#### GET /health

서버 및 모델 로드 상태 확인.

**Response 200**
```json
{ "status": "ok" }
```

**Response 503** — 모델 미로드 시
```json
{ "detail": "Model not loaded" }
```

#### POST /embed

텍스트를 1024차원 벡터로 변환.

**Request**
```json
{ "text": "검색할 텍스트" }
```

**Response 200**
```json
{ "vector": [0.012, -0.034, ..., 0.087] }
```

**Response 503** — 모델 미로드 시
```json
{ "detail": "Model not loaded" }
```

### 주요 설계 결정

#### FastAPI 선택

async 기반으로 Spring Boot에서 동시 요청이 들어올 때 안정적으로 처리한다.
Swagger UI가 자동 생성되어 별도 설정 없이 `http://localhost:8000/docs`에서 확인 가능하다.

#### 모델 런타임 다운로드 + 볼륨 캐시

빌드 시 모델을 이미지에 포함하면 이미지 크기가 3GB 이상 커진다.
대신 첫 컨테이너 시작 시 HuggingFace에서 다운로드하고 `huggingface-cache` Docker 볼륨에 캐시한다.
두 번째 실행부터는 볼륨에서 로드하므로 다운로드 없이 빠르게 뜬다.

```yaml
volumes:
  - huggingface-cache:/root/.cache/huggingface
```

#### 의존성 버전 고정

`FlagEmbedding==1.2.11`이 최신 `transformers`(4.47+)를 끌어오면 `torch 2.4.1`과 DTensor import 충돌이 발생한다.
`transformers==4.44.2`로 고정해서 해결했다.

`peft` 패키지는 `FlagEmbedding`의 reranker 모듈이 의존하지만 자동 설치되지 않아 명시적으로 추가했다.

```
FlagEmbedding==1.2.11
torch==2.4.1
transformers==4.44.2
peft==0.12.0
```

#### healthcheck — curl 대신 python3 urllib

`python:3.11-slim`에는 `curl`이 없다.
별도 패키지 설치 없이 Python 표준 라이브러리로 healthcheck를 구현했다.

```yaml
test: ["CMD", "python3", "-c", "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"]
```

### 로컬 실행 방법

```bash
docker compose build embedding-server
docker compose up -d embedding-server
```

- 첫 실행 시 bge-m3 모델 다운로드로 약 10~15분 소요 (약 3GB)
- `docker logs -f docgrid-embedding` 으로 진행 상태 확인
- `Uvicorn running on http://0.0.0.0:8000` 로그가 뜨면 준비 완료
- 이후 재시작은 볼륨 캐시에서 로드하므로 빠름
