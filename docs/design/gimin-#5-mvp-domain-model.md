# Issue #5 1단계 MVP 도메인 모델과 Flyway 스키마 설계

## 1. 목적

문서 업로드부터 권한 기반 검색과 출처 반환까지 이어지는 1단계 MVP의 데이터 구조를 먼저 정의한다.

```text
사용자·조직
→ 문서·버전·원본 파일
→ 컬렉션·권한
→ 청크·임베딩 작업
→ 검색·RAG 응답·출처
→ Worker·장애 복구
```

기능 API보다 Entity와 DB 계약을 먼저 고정해 이후 작업이 같은 관계와 상태 모델을 사용하도록 하는 것이 핵심이다.

이슈를 처음 등록할 때는 Entity와 Enum만 범위로 잡았지만, 실제 완료된 변경에서는 이 모델을 실행 가능한 DB 스키마로 검증하기 위해 Flyway V2~V25까지 함께 반영했다. 이 문서는 최초 계획이 아니라 최종 병합된 작업 범위를 기준으로 정리한다.

## 2. 구현 범위

- JPA Entity 24종
- 도메인 Enum 24종
- Flyway V2~V25 마이그레이션
- 주요 Unique Constraint와 조회용 Index
- Entity와 마이그레이션의 테이블·제약 이름 일치
- Flyway 작성 및 의존성 관리 가이드

Repository, Service, Controller와 실제 업로드·검색 기능은 포함하지 않았다.

## 3. 도메인 구성

| 도메인 | 주요 Entity |
|---|---|
| 사용자 | `User`, `Department`, `Role`, `UserRole` |
| 문서 | `Document`, `DocumentVersion`, `FileObject`, `DocumentChunk` |
| 컬렉션 | `DocumentCollection`, `CollectionDocument` |
| 권한 | `CollectionPermission`, `DocumentPermission`, `UserDocumentAccessCache` |
| 임베딩 | `EmbeddingModel`, `EmbeddingJob`, `Embedding` |
| 검색·RAG | `SearchQuery`, `SearchResult`, `RagResponse`, `ResponseCitation` |
| Worker·복구 | `WorkerNode`, `EmbeddingJobAttempt`, `IndexingEvent`, `FailoverEvent` |

`Collection`은 Java 표준 타입과 이름이 충돌하므로 `DocumentCollection`으로 명명했다.

## 4. 연관관계 원칙

연관관계는 단방향 `LAZY ManyToOne`을 기본으로 사용했다.

```text
@ManyToMany 사용하지 않음
users ↔ roles → UserRole로 해소
collections ↔ documents → CollectionDocument로 해소
```

양방향 컬렉션을 두지 않아 Entity 그래프가 불필요하게 커지는 것을 막고, 필요한 조회는 Repository에서 명시적으로 작성하도록 했다.

모든 Entity는 전체 `@Setter`를 노출하지 않고 `markIndexed()`, `lock()`, `updateHeartbeat()`처럼 의미가 드러나는 상태 변경 메서드를 사용한다.

## 5. Document와 DocumentVersion 순환 FK

문서는 현재 검색 가능한 버전을 가리키고, 버전은 소속 문서를 가리킨다.

```text
documents.current_version_id → document_versions.id
document_versions.document_id → documents.id
```

두 테이블을 동시에 만들 수 없으므로 다음 순서로 해결했다.

```text
V5: documents 생성, current_version_id는 FK 없이 nullable 컬럼으로 생성
V6: document_versions 생성
V6: ALTER TABLE로 documents.current_version_id FK 추가
```

새 문서는 최초 저장 과정에서 `currentVersion = null`일 수 있으며, 첫 Version 생성 후 연결한다.

## 6. Flyway 마이그레이션 순서

```text
V2~V4   사용자와 FileObject
V5~V8   Document, Version, Collection
V9~V13  Role과 권한
V14~V16 EmbeddingModel, Chunk, Embedding
V17~V20 Worker와 인덱싱 이벤트
V21~V25 검색, RAG, 인용, 장애 복구
```

공유된 기존 마이그레이션은 수정하지 않고 새 변경은 다음 버전 파일로 추가하는 방식을 전제로 한다.

## 7. DB 제약 설계

대표적인 불변식은 DB 제약으로 보호했다.

- 사용자 이메일과 부서·역할 코드의 단일성
- 문서별 `version_no` 단일성
- 저장소의 `bucket_name + object_key` 단일성
- 컬렉션과 문서 연결 중복 방지
- 청크 순서와 임베딩 결과 중복 방지
- 검색 결과와 인용 관계 중복 방지

권한 대상은 `USER`, `DEPARTMENT`, `ROLE` 중 하나만 선택돼야 한다. 이 규칙은 JPA만으로 정확히 표현하기 어려워 Flyway의 `CHECK` 제약으로 적용했다.

## 8. 의도적으로 남긴 후속 과제

- Vector 필드는 우선 문자열·TEXT로 매핑하고 실제 OpenSQL Vector 타입 적용을 후속으로 분리
- JSON 성격 필드는 우선 문자열로 두고 JSON 타입 매핑을 후속 검토
- `user_document_access_cache`는 권한 원본이 아니라 검색 가속 캐시로 사용
- ROLE·DEPARTMENT·PUBLIC 권한은 검색 시점의 Live Predicate로 판단
- 기본 임베딩 모델 단일성은 별도 Partial Unique Index로 보강

## 9. 결과

이 작업으로 이후 기능이 공통으로 사용할 24개 테이블과 JPA 모델의 기준이 마련됐다. 특히 문서 버전, 파일 재사용, 임베딩 작업 큐, 권한 캐시와 검색 출처 관계를 기능 구현 전에 명시해 후속 API가 임의의 스키마를 만들지 않도록 했다.
