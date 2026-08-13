# PostgreSQL 17 + pgvector 0.8.1 로컬 DB 실행 Runbook

## 1. 목적

로컬 개발과 자동화된 회귀 검증은 PostgreSQL 17 + pgvector 0.8.1을 사용한다. 공식 OpenSQL 17.8은
Rocky Linux 9.7 x86-64 원격 환경에서 별도로 검증한다.

이 Runbook은 다음 경계를 유지한다.

- 로컬 Docker 실행에 공급사 설치 파일이나 라이선스를 사용하지 않는다.
- PostgreSQL 14의 기존 `opensql_data` 볼륨을 PostgreSQL 17에서 재사용하지 않는다.
- 실제 비밀번호, 다운로드 URL, 압축 해제 정보와 라이선스 내용은 문서와 Git에 기록하지 않는다.

## 2. 사전 요구사항

- Docker Desktop 또는 Docker Engine + Compose Plugin
- Java 17
- Repository Root에 `.env.example`을 복사해 만든 `.env`

```bash
cp .env.example .env
```

`.env`는 Git 추적 대상이 아니다. 기본 개발값을 바꿔야 할 때만 로컬 파일에서 수정하고, 실제 운영
비밀정보를 `.env.example`에 기록하지 않는다.

## 3. 기본 환경

| 항목 | 기본값 |
| --- | --- |
| Image | `pgvector/pgvector:0.8.1-pg17` |
| Host | `localhost` |
| Port | `55432` |
| Database | `app` |
| User | `app` |
| SSL Mode | `disable` |
| Data Volume | `docgrid_postgres17_data` |

Compose는 Host Architecture를 강제하지 않는다. 공식 Image Manifest가 macOS arm64와 x86-64에 맞는
Image를 선택한다.

## 4. 최초 기동

```bash
docker compose pull postgres
docker compose up -d postgres
docker compose ps postgres
docker compose logs postgres
```

PostgreSQL의 공식 Entry Point가 새 Data Volume을 초기화할 때
`docker/postgres/init/001-enable-vector.sql`을 실행한다. 이 SQL은 Flyway보다 먼저
`vector` Extension을 만든다.

Health 상태가 `healthy`가 된 뒤 Application을 기동한다.

```bash
./backend/gradlew -p backend bootRun --args='--spring.profiles.active=local'
```

Spring Boot 기동 과정에서 Flyway 전체 Migration과 Hibernate `ddl-auto=validate`가 통과해야 한다.

## 5. 버전과 Schema 확인

기본 Database/User를 사용할 때 다음 명령으로 실제 버전을 확인한다.

```bash
docker compose exec postgres psql -U app -d app -c "SHOW server_version;"
docker compose exec postgres psql -U app -d app -c "SELECT extversion FROM pg_extension WHERE extname = 'vector';"
```

기대 결과:

- `server_version`: 17 계열
- `extversion`: `0.8.1`

Flyway 적용 후 Vector Column과 HNSW Index를 확인한다.

```bash
docker compose exec postgres psql -U app -d app -c "SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid WHERE c.relname = 'embeddings' AND a.attname = 'vector';"
docker compose exec postgres psql -U app -d app -c "SELECT indexdef FROM pg_indexes WHERE tablename = 'embeddings' AND indexdef ILIKE '%USING hnsw%';"
```

기대 결과:

- `embeddings.vector`: `vector(1024)`
- `vector_cosine_ops`를 사용하는 HNSW Index 존재

## 6. 테스트

전체 단위 테스트:

```bash
./backend/gradlew -p backend test
```

실제 PostgreSQL 통합 테스트를 실행할 때는 Test Profile이 격리 Schema와 `public` Search Path를
사용한다.

```bash
DB_HOST=localhost \
DB_PORT=55432 \
DB_NAME=app \
DB_USER=app \
DB_PASSWORD=local_password \
DB_SSLMODE=disable \
./backend/gradlew -p backend test -Dgroups=integration
```

로컬 `.env`에서 접속값을 바꿨다면 명령의 값도 동일하게 맞춘다. 실제 비밀번호가 포함된 명령 출력은
Test Result 문서에 복사하지 않는다.

## 7. 정지와 재기동

Data Volume을 유지한 채 정지:

```bash
docker compose stop postgres
```

재기동:

```bash
docker compose up -d postgres
```

`docker compose down`도 기본적으로 Named Volume을 삭제하지 않는다. `docker compose down -v`는
PostgreSQL뿐 아니라 다른 Service Volume까지 삭제할 수 있으므로 이 Runbook의 정상 정리 명령으로
사용하지 않는다.

## 8. PostgreSQL 14 볼륨과 Rollback

- 기존 `opensql_data`는 Compose에서 더 이상 참조하지 않지만 자동 삭제하지 않는다.
- PostgreSQL 17은 `docgrid_postgres17_data`만 사용한다.
- PostgreSQL Major Version이 다른 Data Directory를 직접 Mount하지 않는다.
- 데이터 이전이 필요하면 별도 이슈에서 `pg_dump`와 `pg_restore` 절차를 검증한다.

Rollback이 필요한 경우:

1. Application과 PostgreSQL 17 Service를 중지한다.
2. PostgreSQL 17 전환 Commit을 Revert한다.
3. 이전 Compose 구성에서 기존 `opensql_data`를 다시 Mount한다.
4. 어떤 Named Volume도 사용자 확인 없이 삭제하지 않는다.

## 9. 공식 OpenSQL 17.8 원격 검증

공식 OpenSQL 검증 환경:

| 항목 | 조건 |
| --- | --- |
| OS | Rocky Linux 9.7 |
| Architecture | x86-64 |
| 구성 | Single |
| DBMS | OpenSQL 17.8 |
| Vector Extension | pgvector 0.8.1 |

공급사 설치 Archive와 라이선스는 Repository 외부의 접근 제한 위치에서만 관리한다. 다운로드 URL,
압축 해제 비밀번호, 라이선스 XML과 원격 서버 인증정보를 Commit, Issue, Log 또는 Test Result에
기록하지 않는다.

원격 설치가 완료되면 다음 순서로 검증한다.

1. `SHOW server_version`과 `SELECT version()` 확인
2. `vector` Extension 0.8.1 확인
3. 격리 Database에서 Flyway 전체 Migration 적용
4. Hibernate Schema Validation 확인
5. `vector(1024)` 저장·조회 확인
6. HNSW Cosine 검색 확인
7. Claim 동시성, 인덱싱 완료·실패·Lease 복구·Worker Polling 회귀 테스트
8. 로컬 PostgreSQL 결과와 차이가 있으면 독립된 호환성 이슈로 기록

원격 접속정보는 환경변수로만 주입한다.

```bash
DB_HOST=<remote-host> \
DB_PORT=<remote-port> \
DB_NAME=<database> \
DB_USER=<user> \
DB_PASSWORD=<secret> \
DB_SSLMODE=<server-policy> \
./backend/gradlew -p backend test -Dgroups=integration
```

위 Placeholder를 실제 값으로 바꾼 명령은 Shell History, 문서와 CI Log에 남지 않도록 실행 환경의
Secret 주입 기능을 사용한다.

## 10. 문제 해결

### Port 충돌

`55432`가 사용 중이면 `.env`의 `DB_PORT`를 바꾸고 Application과 테스트 명령에도 같은 값을
적용한다.

### vector Extension 없음

`docker-entrypoint-initdb.d` Script는 새 Data Directory에서만 자동 실행된다. 새 PostgreSQL 17
Volume인데 Extension이 없다면 초기화 Log를 확인한다. 기존 PostgreSQL 14 Volume을 Mount해 해결하려
하지 않는다.

### Flyway V32 실패

`vector` Extension Version과 Test Profile의 `currentSchema={test-schema},public` Search Path를
먼저 확인한다. 이미 적용된 Migration 파일은 수정하지 않는다.

### SSL 연결 실패

로컬 기본값은 `DB_SSLMODE=disable`이다. 공식 원격 환경에서는 서버 정책에 맞는 SSL Mode와 인증서를
환경변수로 주입한다.

## 11. 관련 문서

- [상세 설계](design/Gimini-3-%2397-postgresql17-pgvector-local-environment.md)
- [GitHub Issue #97](https://github.com/DocGrid/backend/issues/97)
