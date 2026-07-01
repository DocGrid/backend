# DocGrid Backend

## Local DB

로컬 개발 DB는 Docker Compose로 실행합니다. 현재 OpenSQL 공식 Docker 이미지 또는 공식 docker-compose 예제를 확인하지 못해 **OpenSQL 대체 개발용 PostgreSQL fallback**을 사용합니다.

```bash
cp .env.example .env
docker compose up -d
docker compose ps
./gradlew bootRun --args='--spring.profiles.active=local'
```

DB 기본 접속 정보는 `localhost:55432`, database `app`, user `app`, password `local_password`입니다.

자세한 내용은 [docs/local-db.md](docs/local-db.md)를 참고하세요.
