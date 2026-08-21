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

## 파일 저장소 선택

DocGrid의 API와 인덱싱 Worker는 특정 Cloud SDK가 아니라 공통 파일 저장소 Port를 사용합니다. 현재
Local Filesystem, MinIO, AWS S3 Adapter를 지원하며 `STORAGE_TYPE`으로 하나만 선택합니다.

| `STORAGE_TYPE` | 용도 | 추가 설정 |
|---|---|---|
| `local` | 별도 Object Storage 없이 실행하는 기본값 | `STORAGE_LOCAL_ROOT`, `STORAGE_BUCKET` |
| `minio` | Docker 또는 외부 MinIO 사용 | `STORAGE_BUCKET`, `MINIO_ENDPOINT`, Credential |
| `s3` | AWS S3를 사용하는 공용 개발·배포 환경 | `STORAGE_BUCKET`, `AWS_REGION`, AWS Credential |

Local Filesystem은 `STORAGE_TYPE`을 설정하지 않았을 때의 애플리케이션 기본값입니다. 실제 절대 경로는
DB에 저장하지 않고, DB에는 `LOCAL` Provider와 논리 Bucket·Object Key만 저장합니다.

```dotenv
STORAGE_TYPE=local
STORAGE_BUCKET=docgrid
STORAGE_LOCAL_ROOT=./data/docgrid
```

현재 `.env.example`은 팀의 Docker MinIO 개발 방식을 바로 실행할 수 있도록 `minio`를 명시합니다.

```dotenv
STORAGE_TYPE=minio
STORAGE_BUCKET=docgrid
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin1234
```

AWS S3는 미리 생성된 Bucket을 사용하며 애플리케이션이 Bucket을 생성하거나 삭제하지 않습니다. 로컬에서
실행할 때는 AWS Profile 또는 환경 변수를 사용하고, EC2에서 실행할 때는 Access Key보다 IAM Role을
권장합니다.

```dotenv
STORAGE_TYPE=s3
STORAGE_BUCKET=<s3-bucket-name>
AWS_REGION=ap-northeast-2

# IAM Role이나 ~/.aws/credentials를 사용하지 않을 때만 설정합니다.
AWS_ACCESS_KEY_ID=<access-key>
AWS_SECRET_ACCESS_KEY=<secret-key>
# 임시 Credential인 경우에만 설정합니다.
AWS_SESSION_TOKEN=<session-token>
```

일반 AWS S3에서는 `S3_ENDPOINT`를 설정하지 않습니다. LocalStack이나 별도 VPC Endpoint를 명시적으로
사용할 때만 `S3_ENDPOINT`를 설정하고, Path-style 주소가 필요한 호환 Endpoint에서만
`S3_PATH_STYLE_ACCESS_ENABLED=true`를 사용합니다.

API와 Worker는 반드시 동일한 `STORAGE_TYPE`과 저장소 Endpoint·Bucket을 사용해야 합니다. 같은 DB
Schema를 사용하면서 서로 다른 Local Directory나 개발자별 MinIO를 바라보면 DB의 Object Key는
존재하지만 실제 파일을 찾지 못합니다.

같은 DB Schema를 사용하는 모든 API와 Worker는 아래 설정을 하나의 환경 단위로 배포합니다.

| 저장소 | 반드시 같은 값 |
|---|---|
| Local Filesystem | `STORAGE_TYPE`, `STORAGE_BUCKET`, 공유 가능한 `STORAGE_LOCAL_ROOT` |
| MinIO | `STORAGE_TYPE`, `STORAGE_BUCKET`, `MINIO_ENDPOINT` |
| AWS S3 | `STORAGE_TYPE`, `STORAGE_BUCKET`, `AWS_REGION`, 동일 Object 권한 |

DB에 저장된 Provider 또는 Bucket이 현재 설정과 다르면 요청을 원격 저장소로 보내기 전에
`DOCUMENT-STORAGE-003` 설정 불일치로 중단합니다. Provider·Bucket은 같지만 Endpoint가 다른 경우는
DB만으로 구분할 수 없으므로 Object 조회 시 `DOCUMENT-STORAGE-002` 파일 누락으로 보일 수 있습니다.
이때 실제 파일 삭제 여부와 API·Worker의 Endpoint를 함께 확인합니다.

| 진단 코드 | 의미 | Worker 자동 Retry |
|---|---|---|
| `DOCUMENT-STORAGE-001` | Network·인증·저장소 서비스 장애 | 대상 |
| `DOCUMENT-STORAGE-002` | Metadata가 가리키는 Object 누락 | 대상 아님 |
| `DOCUMENT-STORAGE-003` | 현재 Provider 또는 Bucket 설정 불일치 | 대상 아님 |

`STORAGE_TYPE` 변경은 기존 파일을 자동으로 옮기지 않습니다. 파일이 들어 있는 DB Schema의 Provider를
바꾸려면 Object와 DB Metadata를 함께 이전하는 별도 Migration이 필요합니다.

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
DB_SCHEMA=<development-schema>
DB_SSLMODE=disable
SPRING_PROFILES_ACTIVE=local

# EC2 OpenSQL의 개발자별 Schema와 한 환경으로 묶을 Local Docker MinIO입니다.
STORAGE_TYPE=minio
STORAGE_BUCKET=docgrid-<developer>
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin1234

# 자동 인덱싱 Worker를 로컬에서 실행합니다.
INDEXING_WORKER_ENABLED=true
INDEXING_WORKER_MAX_CONCURRENCY=1

# RAG 답변에 사용할 Ollama 모델입니다.
OLLAMA_MODEL=qwen2.5:7b
```

`DB_SSLMODE=disable`은 DB 자체 TLS가 비활성화되어 있고 아래 SSH Tunnel로 전송 구간을 암호화하는
환경의 설정입니다. OpenSQL 서버가 TLS를 제공하면 서버 정책에 맞는 SSL Mode를 사용합니다.

EC2 OpenSQL과 개발자별 Local MinIO를 함께 사용할 때는 개발자마다 DB Schema와 Bucket을 분리해야
합니다. 팀 공용 Schema를 사용하려면 모든 API와 Worker가 접근할 수 있는 공용 저장소가 필요합니다.
`.env`는 Git에 추가하지 않습니다.

팀 공용 Schema와 AWS S3를 한 환경으로 사용할 때는 위 MinIO 항목 대신 다음처럼 설정합니다. OpenSQL은
SSH Tunnel을 사용하지만 S3 요청은 AWS Endpoint로 직접 전송하므로 S3용 SSH Port Forwarding은 만들지
않습니다. API와 Worker에는 동일한 Bucket·Region·Credential 권한이 필요합니다.

```dotenv
STORAGE_TYPE=s3
STORAGE_BUCKET=<shared-s3-bucket>
AWS_REGION=ap-northeast-2
```

EC2에서 API와 Worker를 실행한다면 인스턴스 IAM Role에 해당 Bucket의 Object 읽기·쓰기·삭제 권한을
부여합니다. 로컬 실행에서는 AWS Profile 또는 환경 변수를 사용하며 실제 Key는 README, Issue,
Commit에 기록하지 않습니다.

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

### 3. MinIO, BGE-M3 실행 + Ollama 네이티브 설치

Docker Desktop을 실행한 뒤 로컬 인프라를 기동합니다. 이 구성에서는 `postgres` Service를 실행하지
않습니다.

```bash
docker compose up -d --build minio embedding-server
```

`STORAGE_TYPE=local`을 선택했다면 MinIO는 실행하지 않고 BGE-M3만 기동합니다.

```bash
docker compose up -d --build embedding-server
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
# STORAGE_TYPE=minio인 경우에만 확인합니다.
curl -f http://localhost:9000/minio/health/live
```

Ollama는 **Docker가 아니라 macOS에 네이티브로 설치**합니다. Docker Desktop for Mac은 컨테이너에
GPU(Metal)를 넘길 방법이 없어 CPU로만 추론하게 되고, 실제 RAG 프롬프트 기준 50초 이상 걸려 항상
타임아웃됩니다. GPU(Metal) 가속은 **Apple Silicon Mac 기준**이며, Intel Mac은 네이티브로 설치해도
CPU로만 추론하므로 동일한 타임아웃 문제가 있습니다.

```bash
brew install ollama
brew services start ollama
ollama pull qwen2.5:7b
curl -f http://localhost:11434/api/tags
```

`ollama pull`은 모델을 다운로드만 하고 메모리에 올리지는 않습니다. 아래처럼 모델을 한 번 실행해
로드한 뒤, `ollama ps`의 `PROCESSOR`가 `100% GPU`로 나오는지 확인하세요.

```bash
ollama run qwen2.5:7b "안녕"
ollama ps
```

자세한 내용은 [백엔드 README의 Ollama 절](backend/README.md#ollama-rag-llm-서버)을 참고하세요.

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

인덱싱은 BGE-M3와 선택한 파일 저장소가 필요하고, 최종 RAG 답변 생성은 Ollama가 필요합니다.

## 사용 포트

| 포트 | 실행 위치 | 용도 |
|---:|---|---|
| `3000` | 로컬 | 프론트엔드 개발 서버 |
| `8080` | 로컬 | Spring Boot API |
| `55433` | 로컬 | EC2 OpenSQL로 연결되는 SSH Tunnel |
| `5432` | EC2 | OpenSQL 실제 포트 |
| `8000` | 로컬 Docker | BGE-M3 임베딩 서버 |
| `9000` | 로컬 Docker | MinIO API(`STORAGE_TYPE=minio`) |
| `9001` | 로컬 Docker | MinIO Console(`STORAGE_TYPE=minio`) |
| `11434` | 로컬 (네이티브) | Ollama RAG LLM 서버 |

## 종료

프론트엔드, Spring Boot, SSH Tunnel Terminal에서 각각 `Ctrl+C`를 누릅니다. EC2 OpenSQL 구성의
Docker Service는 다음 명령으로 중지합니다.

```bash
docker compose stop minio embedding-server
```

Local Filesystem을 사용했다면 `embedding-server`만 중지합니다. `STORAGE_LOCAL_ROOT`의 원본 파일은
애플리케이션 종료 후에도 유지되며 Git에 포함되지 않습니다.

```bash
docker compose stop embedding-server
```

로컬 PostgreSQL 구성까지 실행했다면 `postgres`도 함께 중지합니다.

```bash
docker compose stop postgres minio embedding-server
```

위 명령은 Container만 중지하고 데이터를 보존합니다. 반면 `docker compose down -v`는
`postgres17-data`, `minio-data`, `huggingface-cache` Volume의 DB·Object·모델 Cache를
삭제할 수 있으므로 일반적인 종료에는 사용하지 마세요.

Ollama는 `brew services stop ollama`로 중지합니다.

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

Local Filesystem·MinIO·S3 Adapter와 실제 자동 Worker 전체 흐름은 일반 테스트와 분리해 실행합니다.
PostgreSQL, MinIO, BGE-M3가 준비되어 있어야 하며 S3 Adapter는 로컬 MinIO의 S3-compatible API를
사용하므로 AWS 계정이나 실제 Credential이 필요하지 않습니다.

```bash
docker compose up -d --wait postgres minio embedding-server

# Docker Compose의 PostgreSQL Host Port가 다르면 DB_PORT를 맞춰 변경합니다.
DB_HOST=127.0.0.1 DB_PORT=55432 \
  ./backend/gradlew -p backend storageWorkerE2eTest
```

이 검증은 실행마다 별도 DB Schema·Bucket·Local Root를 사용하고 종료 시 Test가 만든 위치만 정리합니다.
실행 결과는 [파일 저장소·Worker E2E 결과](docs/test-results/Gimini-3-%23284-file-storage-worker-e2e.md)를
참고하세요.

## 라이선스

DocGrid의 자체 소스코드와 문서는 [Apache License 2.0](LICENSE)에 따라 배포합니다. 외부
라이브러리, Container Image, AI 모델과 OpenSQL 배포본에는 각 구성요소의 별도 라이선스가 적용됩니다.
