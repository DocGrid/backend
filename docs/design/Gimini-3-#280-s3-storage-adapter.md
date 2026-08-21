# AWS S3 파일 저장소 Adapter 설계

Closes #280

## 배경

DocGrid의 파일 저장소 Port는 Local Filesystem과 MinIO 구현을 선택할 수 있지만 팀 공용 개발환경이나
AWS 배포환경에서 관리형 S3를 직접 사용할 구현이 없다. 도메인과 Worker가 AWS SDK에 의존하지 않도록
기존 `FileStorageService` 경계를 유지하면서 S3를 선택형 Adapter로 추가한다.

## 범위

- AWS SDK for Java v2 S3 Client 구성
- `STORAGE_TYPE=s3` 조건부 Adapter 등록
- 문서 Object 저장·조회·삭제
- AWS Region, 선택적 Endpoint, Credential 설정
- README의 S3 및 EC2 OpenSQL 공용환경 실행 가이드

Bucket 생성, Bucket Policy, IAM Role 발급, 기존 Object Migration은 애플리케이션 범위에서 제외한다.

## 구조

```text
Document API / Indexing Worker
            |
            v
   FileStorageService Port
            ^
            |
   S3StorageService Adapter
            |
            v
       AWS SDK S3Client
```

Spring은 `storage.type` 값에 따라 Local, MinIO, S3 Adapter 중 정확히 하나만 등록한다. 서비스 계층은
Provider 분기나 AWS SDK 타입을 알지 않는다.

## 설정

| 환경 변수 | 필수 여부 | 설명 |
|---|---|---|
| `STORAGE_TYPE=s3` | 필수 | S3 Adapter 선택 |
| `STORAGE_BUCKET` | 필수 | 미리 생성된 S3 Bucket |
| `AWS_REGION` | 선택 | 기본값 `ap-northeast-2` |
| `AWS_ACCESS_KEY_ID` | 선택 | 정적 Credential 사용 시 Secret Key와 함께 설정 |
| `AWS_SECRET_ACCESS_KEY` | 선택 | 정적 Credential 사용 시 Access Key와 함께 설정 |
| `AWS_SESSION_TOKEN` | 선택 | 임시 Credential에만 설정 |
| `S3_ENDPOINT` | 선택 | LocalStack·VPC Endpoint 등 명시적 Override |
| `S3_PATH_STYLE_ACCESS_ENABLED` | 선택 | Path-style 주소가 필요한 Endpoint에만 사용 |

정적 Credential이 없으면 AWS SDK 기본 Credential Chain을 사용하므로 AWS Profile, Web Identity,
Container Credential, EC2 Instance Role을 사용할 수 있다. Access Key와 Secret Key 중 하나만 있으면
잘못된 Credential로 요청을 보내지 않고 애플리케이션 시작을 거부한다.

## 저장 위치 모델

DB에는 AWS URL이나 Host를 저장하지 않는다.

```text
storage_provider = S3
bucket_name      = docgrid-shared
object_key       = documents/{documentId}/{versionId}/original.pdf
```

S3 Adapter는 업로드 시 공통 `STORAGE_BUCKET`을 사용하고 조회·삭제 시 DB Snapshot의 Bucket과 Object
Key를 사용한다. API와 Worker는 같은 DB Schema를 사용한다면 동일한 S3 환경과 접근 권한을 가져야 한다.

## I/O 계약

### 저장

1. 호출자가 전달한 Object Key와 Content Type으로 `PutObject`를 실행한다.
2. 성공하면 `StoredFile(S3, bucket, objectKey)`를 반환한다.
3. SDK 오류는 `FILE_STORAGE_FAILED`로 변환한다.

애플리케이션은 Bucket을 자동 생성하지 않는다. Bucket 생성 권한 없이 Object 작업에 필요한 최소 IAM
권한만 부여할 수 있도록 인프라에서 Bucket을 먼저 준비한다.

### 조회

1. `StoredFile` Provider가 S3인지 확인한다.
2. Snapshot의 Bucket과 Object Key로 Object 전체 Byte를 읽는다.
3. `NoSuchKey` 또는 HTTP 404는 `FILE_OBJECT_NOT_FOUND`로 변환한다.
4. 인증, Network, S3 서비스 오류는 `FILE_STORAGE_FAILED`로 변환한다.

### 삭제

Snapshot의 Bucket과 Object Key로 `DeleteObject`를 실행한다. SDK 실패는 `FILE_STORAGE_FAILED`로
변환하며 Bucket 자체는 삭제하지 않는다.

## 보안

- 실제 Credential은 Git에 Commit하지 않는다.
- EC2 실행환경은 정적 Access Key보다 IAM Role을 우선한다.
- 오류 응답과 일반 로그에 Credential, Bucket, Object Key를 함께 노출하지 않는다.
- S3 Bucket은 Public Access를 차단하고 API·Worker 실행 주체에 필요한 Object 권한만 부여한다.

## 검증

- 저장 요청의 Bucket, Object Key, Content Type과 반환 Provider 검증
- 조회·삭제 요청이 DB Snapshot 위치를 사용하는지 검증
- Object 없음과 일반 SDK 장애 변환 검증
- 다른 Provider Snapshot 거부 검증
- Region·Endpoint 설정 및 불완전한 정적 Credential 시작 실패 검증
- Local 기본 Spring Context에서 S3 Client가 등록되지 않는지 회귀 검증
