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
