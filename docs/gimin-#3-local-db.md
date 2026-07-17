# Issue #3 로컬 DB 개발 환경 구성

이 문서는 로컬 개발 환경에서 OpenSQL-PG 기반 DB 컨테이너를 띄우고 Spring Boot `local` profile로 Flyway migration 실행을 확인하는 최소 가이드입니다.

로컬 DB 기본 이미지는 `tmaxopensql/postgres:14.6`입니다. Spring Boot datasource URL은 OpenSQL-PG의 PostgreSQL 호환 연결을 기준으로 `jdbc:postgresql://...` 형태를 유지합니다.

OpenSQL-PG 컨테이너는 `docker/opensql/vars.yml`을 사용해 로컬 개발용 `app` DB, `app` user, `local_password` password를 초기화합니다.

## 필요 조건

- Docker Desktop 또는 Docker Engine
- Docker Compose v2
- Java 17

## 환경변수 준비

```bash
cp .env.example .env
```

실제 `.env` 파일은 커밋하지 않습니다. 로컬 기본값은 다음과 같습니다.

```text
DB_HOST=localhost
DB_PORT=55432
DB_NAME=app
DB_USER=app
DB_PASSWORD=local_password
DB_SCHEMA=public
SPRING_PROFILES_ACTIVE=local
```

## DB 컨테이너 실행

이미지를 먼저 내려받습니다.

```bash
docker pull tmaxopensql/postgres:14.6
```

```bash
docker compose up -d
```

상태를 확인합니다.

```bash
docker compose ps
```

`local-opensql`이 `healthy` 상태면 정상입니다.

## DB 접속 정보

- Host: `localhost`
- Port: `55432`
- Database: `app`
- User: `app`
- Password: `local_password`
- Schema: `public`
- JDBC URL: `jdbc:postgresql://localhost:55432/app?currentSchema=public&sslmode=require`

## Spring Boot local profile 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

`application.yml`에서 `.env`를 optional import하므로, `.env.example`을 `.env`로 복사한 뒤 실행하면 동일한 값으로 연결됩니다.

## Flyway migration 확인

애플리케이션을 `local` profile로 실행하면 OpenSQL-PG 컨테이너에 연결한 뒤 Flyway가 `src/main/resources/db/migration` 아래 migration을 적용합니다.

현재 로컬 DB 연결 확인용 migration은 `V1__init_schema.sql`이며, 도메인 테이블이 아닌 `app_health_checks` 테이블만 생성합니다.

컨테이너에서 직접 확인하려면 다음 명령어를 사용할 수 있습니다.

```bash
docker compose exec postgres /usr/pgsql-14/bin/psql -U app -d app -c "select * from app_health_checks;"
```

## PostgreSQL fallback

OpenSQL-PG 이미지 `tmaxopensql/postgres:14.6`을 실행할 수 없는 환경에서만 예비 옵션으로 일반 PostgreSQL 이미지를 사용할 수 있습니다.

```yaml
image: postgres:16
```

fallback을 사용할 때도 Spring Boot datasource URL은 `jdbc:postgresql://...` 형태를 유지하고, `.env`의 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SCHEMA` 값으로 연결 정보를 조정합니다.

## DB 컨테이너 중지

```bash
docker compose down
```

볼륨까지 삭제해 DB 데이터를 초기화하려면 다음 명령어를 사용합니다.

```bash
docker compose down -v
```

## 자주 발생하는 오류

### `port is already allocated`

호스트의 `55432` 포트를 다른 프로세스가 사용 중입니다. `.env`의 `DB_PORT`를 다른 값으로 바꾼 뒤 `docker compose up -d`를 다시 실행합니다.

### DB가 `healthy`가 되지 않음

컨테이너 로그를 확인합니다.

```bash
docker compose logs postgres
```

환경변수를 바꾼 뒤 기존 볼륨에 이전 DB 계정 정보가 남아 있다면 `docker compose down -v`로 초기화한 뒤 다시 실행합니다.

### Flyway migration 실패

이미 같은 DB 볼륨에 다른 V1 migration 이력이 남아 있을 수 있습니다. 로컬 개발 DB라면 `docker compose down -v` 후 다시 실행합니다.

### Spring Boot가 DB에 연결하지 못함

`docker compose ps`에서 DB가 `healthy`인지 확인하고, `.env`의 `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DB_SCHEMA` 값이 `application-local.yml`과 맞는지 확인합니다.
