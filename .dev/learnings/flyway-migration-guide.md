# Flyway 마이그레이션 가이드 (DocGrid)

## 1. `V`는 무엇의 약자인가

Flyway 마이그레이션 파일명의 접두사는 파일의 "종류"를 나타낸다. `V`는 **Versioned**(버전이 있는 마이그레이션)의 약자다.

| 접두사 | 의미 | 이 프로젝트에서 사용 여부 |
|---|---|---|
| `V{버전}__{설명}.sql` | Versioned migration. 버전 번호 순서대로 **한 번만** 실행되고, 실행 이력이 `flyway_schema_history`에 영구 기록된다. | 사용 (V1~V25) |
| `R__{설명}.sql` | Repeatable migration. 버전이 없고, 파일 내용(checksum)이 바뀔 때마다 매번 다시 실행된다. 주로 뷰/함수/프로시저 재정의에 쓴다. | 미사용 |
| `U{버전}__{설명}.sql` | Undo migration. `V` 마이그레이션을 되돌리는 파일(Flyway Teams 유료 기능). | 미사용 |

즉 우리가 쓰는 `V2__create_departments.sql` 같은 이름은 "버전 2번, departments 테이블 생성"이라는 뜻이며, 파일명의 밑줄 두 개(`__`)가 버전과 설명을 구분하는 구분자다.

## 2. 동작 원리

- Flyway는 `src/main/resources/db/migration` 아래의 `V*.sql` 파일들을 버전 번호(`V1`, `V2`, `V3`, ...) 순서대로 정렬해서 실행한다.
- 각 파일이 실행되면 `flyway_schema_history` 테이블에 버전/체크섬/실행 시각이 기록된다.
- **한 번 적용된(운영/공유 DB에 반영된) 마이그레이션 파일은 절대 내용을 수정하면 안 된다.** 파일을 수정하면 체크섬이 달라져서 다음 실행 시 `FlywayException: Migration checksum mismatch`가 발생한다. 스키마를 고쳐야 하면 새 버전 파일(`V26__...sql`)을 추가해야 한다. (`deploy.md`에도 명시된 이 프로젝트의 규칙이다.)
- 이 원칙 때문에, 만약 실수로 만든 마이그레이션이 **아직 아무 환경에도 적용되지 않았다면**(로컬에서 한 번도 `flywayMigrate`가 안 돌았다면) 파일을 지우고 재배치해도 안전하다. 이번에 `V2__create_collection_documents.sql`을 지우고 의존성 순서에 맞게 `V2~V8`로 재배치한 것이 그 예다. 반대로 이미 팀원 로컬/개발 서버에 적용된 뒤라면 새 버전 파일로 고쳐야 한다.

## 3. 이 프로젝트에서 마이그레이션이 실행되는 방식

- `build.gradle`에는 Flyway **Gradle 플러그인**이 없다. `flyway-core`/`flyway-database-postgresql`은 런타임 의존성으로만 들어있다.
- 즉 `./gradlew flywayMigrate` 같은 별도 태스크는 없고, **Spring Boot 애플리케이션이 기동될 때**(`bootRun`, 또는 IDE에서 `DocgridApplication` 실행) `spring.flyway.enabled=true` 설정에 따라 Hibernate가 스키마를 검증(`ddl-auto: validate`)하기 전에 Flyway가 먼저 자동으로 마이그레이션을 적용한다.
- 로컬 설정(`application-local.yml`)은 `spring.flyway.locations: classpath:db/migration`, PostgreSQL 접속 정보는 환경변수(`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`)로 주입한다.
- 따라서 마이그레이션을 검증하려면: 로컬 PostgreSQL(OpenSQL) 실행 → `local` 프로파일로 앱 기동 → 콘솔에 `Flyway ... Successfully applied N migrations` 로그가 뜨는지 확인하면 된다.

## 4. 새 마이그레이션을 추가하는 절차

1. `src/main/resources/db/migration` 안의 가장 큰 버전 번호를 확인한다(현재 최신: `V25`).
2. `V{다음 번호}__{동사 없이 명사형 설명}.sql` 형식으로 새 파일을 만든다. 예: `V26__add_users_phone_number.sql`.
3. 참조하는 테이블이 이미 존재하는지 확인한다. 없으면 그 테이블을 먼저 만드는 마이그레이션을 앞 번호로 추가해야 한다(의존성 역순으로 만들면 `flyway migrate` 시점에 FK 생성이 실패한다).
4. 엔티티(`@Table`/`@Column`)와 컬럼명·타입·길이·unique/index 이름이 1:1로 일치하는지 대조한다. `ddl-auto: validate`라서 어긋나면 앱 기동 자체가 실패한다.
5. 로컬에서 실행해 성공하는지 확인한 뒤 커밋한다.
6. **이미 적용된 파일은 수정하지 않는다.** 스키마 변경이 필요하면 새 버전 파일을 추가한다.

## 5. 순환 FK(circular FK) 해결 패턴

이 프로젝트에서 `documents`와 `document_versions`는 서로를 참조한다.

```
documents.current_version_id      -> document_versions.id
document_versions.document_id     -> documents.id
```

이런 경우 두 테이블을 동시에 FK 포함해서 만들 수 없으므로(둘 다 아직 없는 상태), 아래 3단계 패턴을 쓴다.

1. `documents` 테이블을 만들되, `current_version_id` 컬럼은 **FK 없이 컬럼(타입)만** 추가한다. (`V5__create_documents.sql`)
2. `document_versions` 테이블을 만든다. 이때 `document_id -> documents.id` FK는 정상적으로 걸 수 있다(`documents`가 이미 존재하므로). (`V6__create_document_versions.sql`)
3. 같은 파일(V6) 마지막에 `ALTER TABLE documents ADD CONSTRAINT fk_documents_current_version_id FOREIGN KEY (current_version_id) REFERENCES document_versions (id);` 로 미뤄뒀던 FK를 마무리한다.

이 패턴은 "테이블 A ↔ 테이블 B가 서로를 가리켜야 하는" 모든 순환 참조 상황에 재사용할 수 있다: 먼저 한쪽을 FK 없이 만들고, 반대쪽을 만든 뒤, `ALTER TABLE`로 되돌아가 마무리한다.

## 6. target_type별 단일 FK CHECK 제약

`collection_permissions`/`document_permissions`는 `target_type`(USER/DEPARTMENT/ROLE)에 따라 `user_id`/`department_id`/`role_id` 중 정확히 하나만 채워져야 한다. JPA/Hibernate 레벨에서는 이 제약을 표현할 수 없어 엔티티 JavaDoc에는 TODO로만 남겨뒀지만, **Flyway 마이그레이션(순수 SQL)에서는 실제 `CHECK` 제약으로 강제할 수 있어 추가했다**:

```sql
CONSTRAINT ck_collection_permissions_target_type_fk CHECK (
    (target_type = 'USER' AND user_id IS NOT NULL AND department_id IS NULL AND role_id IS NULL) OR
    (target_type = 'DEPARTMENT' AND department_id IS NOT NULL AND user_id IS NULL AND role_id IS NULL) OR
    (target_type = 'ROLE' AND role_id IS NOT NULL AND user_id IS NULL AND department_id IS NULL)
)
```

## 7. 이번에 생성한 전체 마이그레이션 체인 (V1~V25)

| 버전 | 테이블 | 의존 테이블(FK) |
|---|---|---|
| V1 | app_health_checks | - |
| V2 | departments | departments(self) |
| V3 | users | departments |
| V4 | file_objects | users |
| V5 | documents | users (current_version_id는 FK 보류) |
| V6 | document_versions | documents, file_objects, users + documents FK 마무리 |
| V7 | collections | users, collections(self) |
| V8 | collection_documents | collections, documents, users |
| V9 | roles | - |
| V10 | user_roles | users, roles |
| V11 | collection_permissions | collections, users, departments, roles |
| V12 | document_permissions | documents, users, departments, roles |
| V13 | user_document_access_cache | users, documents |
| V14 | embedding_models | - |
| V15 | document_chunks | document_versions |
| V16 | embeddings | document_chunks, documents, document_versions, embedding_models |
| V17 | worker_nodes | - |
| V18 | embedding_jobs | document_versions, embedding_models, worker_nodes |
| V19 | embedding_job_attempts | embedding_jobs, worker_nodes |
| V20 | indexing_events | embedding_jobs |
| V21 | search_queries | users, collections, embedding_models |
| V22 | search_results | search_queries, document_chunks, embeddings |
| V23 | rag_responses | search_queries |
| V24 | response_citations | rag_responses, document_chunks, search_results |
| V25 | failover_events | - |

## 8. 자주 겪는 실수와 대처

- **`Migration checksum mismatch`**: 이미 적용된 `V*.sql` 파일을 수정했을 때 발생. 파일을 원상복구하거나, 정말 다시 실행해야 한다면 로컬 한정으로 `flyway_schema_history`에서 해당 행을 지우거나 `flyway repair`(Boot 액추에이터/CLI 필요)를 쓴다. 공유 DB에서는 되돌리지 말고 새 버전 파일을 추가한다.
- **FK 대상 테이블이 없어서 실패**: 새 테이블이 참조하는 테이블이 아직 없는 버전 번호를 받은 경우. 항상 "참조하는 테이블이 먼저 생성되는 순서"로 번호를 매겨야 한다(2절 참고).
- **엔티티와 컬럼이 어긋나서 앱 기동 실패**: `ddl-auto: validate`이므로 컬럼명/타입/길이/nullable이 엔티티와 정확히 일치해야 한다. 엔티티를 수정하면 반드시 대응하는 SQL도 같이 확인한다.
- **PostgreSQL 식별자 63자 제한**: `uk_user_document_access_cache_user_id_document_id_source_type_source_id`처럼 긴 제약 이름은 63바이트를 넘으면 PostgreSQL이 조용히 잘라서 저장한다(에러는 아니지만 이름이 달라짐). 너무 길어질 경우 축약된 이름을 쓰는 것도 고려할 수 있다.
