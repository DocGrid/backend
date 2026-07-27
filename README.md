# DocGrid Backend

## Local DB

로컬 개발 DB는 Docker Compose로 실행합니다. 기본 DB 이미지는 OpenSQL-PG 호환 이미지인 `tmaxopensql/postgres:14.6`입니다.

```bash
cp .env.example .env
docker pull tmaxopensql/postgres:14.6
docker compose up -d
docker compose ps
./gradlew bootRun --args='--spring.profiles.active=local'
```

DB 기본 접속 정보는 `localhost:55432`, database `app`, user `app`, password `local_password`입니다.

자세한 내용은 [docs/local-db.md](docs/local-db.md)를 참고하세요.

## 임베딩 서버

문서 청크/질의를 벡터로 변환하는 FastAPI + sentence-transformers 서버입니다. 이미지가 자체 빌드 대상이라 최초 1회 build가 필요합니다.

```bash
docker compose build embedding-server
docker compose up -d embedding-server
```

- 첫 실행 시 `BAAI/bge-m3` 모델 다운로드로 약 10~15분 소요됩니다 (약 3GB).
- `docker logs -f docgrid-embedding`으로 진행 상태를 확인할 수 있습니다.
- 모델 로딩이 끝나기 전까지 `GET /health`는 503을 반환합니다. 200이 될 때까지 대기 후 사용하세요.
- 기본 접속 정보는 `http://localhost:8000`이며, Spring Boot에서는 `EMBEDDING_SERVER_URL` 환경변수로 오버라이드할 수 있습니다.

## Ollama (RAG LLM 서버)

RAG 답변 생성에 사용하는 로컬 LLM(`qwen2.5:3b`) 서버입니다. 공식 이미지를 그대로 사용하므로 별도 build 없이 실행만 하면 됩니다.

```bash
docker compose up -d ollama
docker compose exec ollama ollama pull qwen2.5:3b
docker compose exec ollama ollama run qwen2.5:3b "안녕"
```

- `pull`은 최초 1회만 필요합니다 (약 2GB, `ollama-data` 볼륨에 캐시되어 이후 재구동 시 재다운로드하지 않습니다).
- 정상 응답이 텍스트로 출력되면 준비 완료입니다.
- 기본 접속 정보는 `http://localhost:11434`이며, Spring Boot에서는 `OLLAMA_SERVER_URL` 환경변수로 오버라이드할 수 있습니다.
