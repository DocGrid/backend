# 파일 저장소 Adapter 선택 설계

## 목적

DocGrid의 문서 업로드·다운로드·인덱싱 흐름이 특정 MinIO 구현이 아니라
`FileStorageService` Port에만 의존하도록 정리한다. Local Filesystem을 기본 구현으로 추가하고,
기존 MinIO는 환경설정으로 선택하는 Adapter로 유지한다.

Closes #278

## 현재 문제

- `FileStorageService`는 이미 존재하지만 `MinioStorageService`와 `MinioClient`가 항상 Bean으로 등록된다.
- `FileObjectRepository`가 `storage_provider`를 `MINIO`로 고정해 다른 구현의 저장 위치를 기록할 수 없다.
- `StoredFile`에 provider가 없어 DB에서 읽은 위치와 현재 Adapter의 불일치를 확인할 수 없다.
- 업로드 보상 삭제와 Worker 원본 읽기 로그·주석에 MinIO가 직접 노출된다.

## 범위

### 포함

- 기존 `FileStorageService`를 저장소 Port로 유지하고 역할·경계 주석을 보강한다.
- `StoredFile`에 `StorageProvider`를 포함한다.
- `LocalFileStorageService`를 추가한다.
- `STORAGE_TYPE=local|minio`로 하나의 Adapter만 등록한다.
- 공통 논리 Namespace는 `STORAGE_BUCKET`, Local 저장 경로는 `STORAGE_LOCAL_ROOT`로 설정한다.
- 선택된 Adapter의 provider를 `file_objects.storage_provider`에 저장한다.
- Local 경로가 설정 Root 밖으로 이탈하지 못하게 검증한다.
- Local/MinIO Adapter와 저장 Metadata 회귀 테스트를 추가한다.
- README와 `.env.example`에 실행 방법과 DB·저장소 환경 일치 규칙을 기록한다.

### 제외

- AWS S3 Adapter와 AWS SDK 의존성
- 기존 MinIO Object의 다른 저장소 이전
- Worker 실패 코드·재시도 정책의 세부 분류
- 여러 저장소를 한 애플리케이션에서 동시에 읽는 Routing

## 구조

```text
Document API / Indexing Worker
              |
              v
     FileStorageService
          /       \
         v         v
 LocalFileStorage  MinioStorage
 STORAGE_TYPE=local|minio
```

Spring은 `storage.type` 조건으로 정확히 하나의 `FileStorageService` 구현만 등록한다. 도메인 Service는
Profile이나 Adapter 종류를 분기하지 않는다.

## 저장 위치 모델

```text
StoredFile
- storageProvider
- bucketName
- objectKey
```

기존 `bucket_name` DB Column은 마이그레이션하지 않는다. MinIO에서는 실제 Bucket, Local에서는 설정된
논리 Namespace를 뜻한다. 실제 Local Root 절대 경로는 DB에 저장하지 않고 실행 환경에서 주입한다.
따라서 저장소를 다른 Host로 옮겨도 DB의 Object Key를 유지할 수 있다.

Local Adapter는 다음 조건을 지킨다.

1. `objectKey`를 정규화한 결과가 반드시 `STORAGE_LOCAL_ROOT` 아래여야 한다.
2. 저장 전 부모 Directory를 생성한다.
3. 읽기 대상이 없으면 `FILE_OBJECT_NOT_FOUND`, 그 외 I/O 오류는 `FILE_STORAGE_FAILED`로 변환한다.
4. 삭제는 MinIO와 동일하게 대상이 없어도 성공한 것으로 처리한다.

## 설정

```yaml
storage:
  type: ${STORAGE_TYPE:local}
  bucket: ${STORAGE_BUCKET:docgrid}
  local:
    root: ${STORAGE_LOCAL_ROOT:./data/docgrid}
```

MinIO endpoint와 Credential은 MinIO Adapter가 선택된 경우에만 사용한다. 현재 팀 개발 방식은 다음처럼
명시적으로 MinIO를 선택한다.

```dotenv
STORAGE_TYPE=minio
STORAGE_BUCKET=docgrid-gimin
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin1234
```

## 환경 일치 규칙

같은 DB Schema를 사용하는 모든 API와 Worker는 같은 `STORAGE_TYPE`, 저장소 Endpoint와 Bucket을
사용해야 한다. EC2 OpenSQL과 개발자별 Local MinIO를 조합할 때는 개발자마다 DB Schema와 Bucket을
분리한다. 공용 Schema의 파일을 여러 실행 인스턴스가 읽어야 한다면 저장소도 공용이어야 한다.

## 검증

- Local Adapter 저장·읽기·삭제와 경로 이탈 차단 단위 테스트
- MinIO Adapter가 provider와 공통 Bucket을 반환하는 단위 테스트
- FileObject Insert가 선택된 provider를 저장하는 Service·통합 테스트
- 기존 문서 업로드·Version 업로드·Chunking 회귀 테스트
- `STORAGE_TYPE=local`에서 MinIO 설정 없이 Spring Context가 시작되는지 확인
- `STORAGE_TYPE=minio` 실제 MinIO 통합 테스트 유지
