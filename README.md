# DocGrid

DocGrid는 문서 업로드·인덱싱·검색과 웹 인터페이스를 하나의 저장소에서 관리하는 모노레포입니다.

## 저장소 구조

```text
.
├── backend/          # Spring Boot API와 임베딩 서버
├── frontend/         # DocGrid 웹 애플리케이션
├── docs/             # 설계 문서와 실행된 테스트 결과
├── docker/           # 로컬 인프라 초기화 파일
├── scripts/          # 프로젝트 공용 검증·보고 스크립트
└── docker-compose.yml
```

## 사전 준비

- Git
- Java 17
- Node.js 22.13.0 이상
- Docker Desktop과 Docker Compose
- OpenSSH Client(`ssh`), netcat(`nc`), curl
- Rocky Linux 9.7 EC2의 OpenSQL 개발 DB에 접속할 SSH Key와 DB 계정

EC2 주소, SSH Key, DB 비밀번호, OpenSQL License는 저장소에 포함하지 않습니다. 팀 구성원은 해당 값을
별도의 안전한 경로로 전달받아야 합니다.

## Clone

```bash
git clone https://github.com/DocGrid/docgrid.git
cd docgrid
```

이후 명령은 별도 안내가 없는 한 저장소 루트에서 실행합니다.

## EC2 OpenSQL을 사용하는 로컬 실행

프론트엔드와 Spring Boot는 로컬에서 실행하고, DB만 SSH Tunnel을 통해 EC2 OpenSQL에 연결합니다.
로컬 PostgreSQL Container는 실행하지 않습니다.

### 1. 환경 변수 준비

```bash
cp .env.example .env
```

루트 `.env`의 DB 항목을 발급받은 개발 전용 DB 정보로 변경합니다. 실제 비밀번호를 README,
`application.yml`, Commit 또는 Issue에 기록하지 마세요.

```dotenv
DB_HOST=127.0.0.1
DB_PORT=55433
DB_NAME=<development-database>
DB_USER=<development-user>
DB_PASSWORD=<development-password>
DB_SCHEMA=public
DB_SSLMODE=disable
SPRING_PROFILES_ACTIVE=local

# 자동 인덱싱 Worker를 로컬에서 실행합니다.
INDEXING_WORKER_ENABLED=true
INDEXING_WORKER_MAX_CONCURRENCY=1

# RAG 답변에 사용할 Ollama 모델입니다.
OLLAMA_MODEL=qwen2.5:3b
```

`DB_SSLMODE=disable`은 DB 자체 TLS가 비활성화되어 있고 아래 SSH Tunnel로 전송 구간을 암호화하는
환경의 설정입니다. OpenSQL 서버가 TLS를 제공하면 서버 정책에 맞는 SSL Mode를 사용합니다.

MinIO 설정은 `.env.example`의 로컬 기본값을 그대로 사용할 수 있습니다. `.env`는 Git에 추가하지
않습니다.

### 2. OpenSQL SSH Tunnel 열기

별도 Terminal에서 다음 명령을 실행하고 Spring Boot를 사용하는 동안 열어 둡니다.

```bash
ssh -i <absolute-path-to-ssh-key.pem> \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -N -L 55433:127.0.0.1:5432 \
  <ssh-user>@<ec2-host>
```

새 Terminal에서 Tunnel이 열렸는지 확인합니다.

```bash
nc -zv 127.0.0.1 55433
```

로컬 Spring Boot는 `127.0.0.1:55433`으로 접속하지만, 실제 요청은 SSH Tunnel을 통해 EC2의
OpenSQL `127.0.0.1:5432`로 전달됩니다.

### 3. MinIO, BGE-M3, Ollama 실행

Docker Desktop을 실행한 뒤 로컬 인프라를 기동합니다. 이 구성에서는 `postgres` Service를 실행하지
않습니다.

```bash
docker compose up -d --build minio embedding-server ollama
```

Ollama API가 준비될 때까지 Compose Health Check를 기다린 뒤, 루트 `.env`의 `OLLAMA_MODEL`에 지정한
모델을 최초 한 번 내려받습니다. 값을 생략하면 Spring Boot 기본값인 `qwen2.5:3b`를 사용합니다.

```bash
docker compose up -d --wait --wait-timeout 120 ollama
OLLAMA_MODEL_NAME=$(sed -n 's/^OLLAMA_MODEL=//p' .env | tail -n 1)
docker compose exec ollama ollama pull "${OLLAMA_MODEL_NAME:-qwen2.5:3b}"
```

BGE-M3는 첫 실행 시 약 3GB 모델을 내려받으므로 준비까지 10~15분 정도 걸릴 수 있습니다. 모델이
준비되기 전에 Spring Boot의 인덱싱 Worker를 실행하지 마세요.

```bash
docker compose logs -f embedding-server
```

모델 준비 Log를 확인한 뒤 `Ctrl+C`로 빠져나오고, 각 Service의 상태를 확인합니다. Container는 계속
실행됩니다.

```bash
curl -f http://localhost:8000/health
curl -f http://localhost:9000/minio/health/live
curl -f http://localhost:11434/api/tags
```

### 4. Spring Boot 실행

```bash
./backend/gradlew -p backend bootRun
```

Spring Boot는 저장소 루트의 `.env`와 `local` Profile을 사용합니다. 빈 개발 DB에 처음 연결하면
Flyway가 Migration과 로컬 Seed를 적용합니다. 기존 데이터가 있는 DB나 운영 DB를 로컬 Profile에
연결하지 마세요.

API 문서가 열리는지 확인합니다.

```bash
curl -f http://localhost:8080/v3/api-docs
```

### 5. 프론트엔드 실행

최초 한 번 Dependency와 환경 변수를 준비합니다.

```bash
npm --prefix frontend install
cp frontend/.env.example frontend/.env.local
```

`frontend/.env.local`의 기본값은 로컬 Spring Boot입니다.

```dotenv
BACKEND_API_URL=http://localhost:8080
```

별도 Terminal에서 프론트엔드를 실행합니다.

```bash
npm --prefix frontend run dev
```

브라우저에서 `http://localhost:3000`에 접속합니다.

### 6. 전체 동작 확인

1. 회원가입 또는 로그인
2. PDF/DOCX 문서 업로드
3. 문서 Version 상태가 `PENDING → PROCESSING → INDEXED`로 바뀌는지 확인
4. 문서 내용으로 검색
5. RAG 질문에 Ollama 답변과 인용 근거가 표시되는지 확인

인덱싱은 BGE-M3와 MinIO가 필요하고, 최종 RAG 답변 생성은 Ollama가 필요합니다.

## 사용 포트

| 포트 | 실행 위치 | 용도 |
|---:|---|---|
| `3000` | 로컬 | 프론트엔드 개발 서버 |
| `8080` | 로컬 | Spring Boot API |
| `55433` | 로컬 | EC2 OpenSQL로 연결되는 SSH Tunnel |
| `5432` | EC2 | OpenSQL 실제 포트 |
| `8000` | 로컬 Docker | BGE-M3 임베딩 서버 |
| `9000` | 로컬 Docker | MinIO API |
| `9001` | 로컬 Docker | MinIO Console |
| `11434` | 로컬 Docker | Ollama RAG LLM 서버 |

## 종료

프론트엔드, Spring Boot, SSH Tunnel Terminal에서 각각 `Ctrl+C`를 누릅니다. EC2 OpenSQL 구성의
Docker Service는 다음 명령으로 중지합니다.

```bash
docker compose stop minio embedding-server ollama
```

로컬 PostgreSQL 구성까지 실행했다면 `postgres`도 함께 중지합니다.

```bash
docker compose stop postgres minio embedding-server ollama
```

위 명령은 Container만 중지하고 데이터를 보존합니다. 반면 `docker compose down -v`는
`postgres17-data`, `minio-data`, `huggingface-cache`, `ollama-data` Volume의 DB·Object·모델 Cache를
삭제할 수 있으므로 일반적인 종료에는 사용하지 마세요.

## EC2 없이 로컬 PostgreSQL 사용

EC2 OpenSQL 접근 권한이 없는 기여자는 PostgreSQL 17 + pgvector 0.8.1을 로컬 기준선으로 사용할 수
있습니다. 이 결과는 Rocky Linux 9.7의 공식 OpenSQL 검증을 대신하지 않습니다.

루트 `.env`의 DB 항목을 `.env.example` 기본값으로 설정한 뒤 실행합니다.

```dotenv
DB_HOST=localhost
DB_PORT=55432
DB_NAME=app
DB_USER=app
DB_PASSWORD=local_password
DB_SCHEMA=public
DB_SSLMODE=disable
```

```bash
docker compose pull postgres
docker compose up -d --wait --wait-timeout 60 postgres
./backend/gradlew -p backend bootRun
```

자세한 구성은 [백엔드 실행 방법](backend/README.md), [프론트엔드 실행 방법](frontend/README.md),
[로컬 DB 실행 문서](docs/local-db.md)를 참고하세요.

## 검증

```bash
./backend/gradlew -p backend test
npm --prefix frontend test
```

## 라이선스

DocGrid의 자체 소스코드와 문서는 [Apache License 2.0](LICENSE)에 따라 배포합니다. 외부
라이브러리, Container Image, AI 모델과 OpenSQL 배포본에는 각 구성요소의 별도 라이선스가 적용됩니다.
