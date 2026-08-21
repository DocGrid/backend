# 파일 저장소 설정 불일치와 Object 누락 오류 분류 설계

Closes #282

## 배경

API와 Worker가 같은 DB를 사용하면서 다른 파일 저장소를 바라보면 DB의 Object Key는 정상이어도 실제
파일을 읽을 수 없다. 기존 구현은 Adapter Provider 불일치를 일반 저장소 장애로 반환하고, Worker는
Object 누락을 내부 오류로 분류해 자동 재시도했다. 운영자가 설정 오류, 실제 Object 누락, 일시적
저장소 장애를 구분할 수 있도록 오류 계약과 Retry 정책을 분리한다.

## 범위

- Local Filesystem, MinIO, S3의 Provider·Bucket 일치 검증
- 저장소 설정 불일치 ErrorCode 추가
- Worker 저장소 실패 유형 및 Retry 정책 세분화
- API 오류와 Worker 안전 메시지 검증
- README의 동일 저장소 환경 규칙과 진단표

Endpoint 식별자를 DB에 저장하거나 기존 파일을 Migration하는 기능은 범위에서 제외한다.

## 불변 조건

같은 DB Schema를 사용하는 모든 프로세스는 하나의 저장소 환경을 사용한다.

```text
API    ─┬─ STORAGE_TYPE
        ├─ STORAGE_BUCKET
Worker ─┴─ Provider Endpoint 또는 Local Root
```

`StoredFile`의 Provider와 Bucket은 업로드 시점 저장 위치의 불변 Snapshot이다. 읽기와 삭제 전에 현재
Adapter의 Provider·Bucket과 비교하고 하나라도 다르면 SDK 또는 Filesystem I/O를 실행하지 않는다.

Endpoint는 DB에 저장하지 않으므로 같은 Provider·Bucket에서 Endpoint만 다른 경우를 사전에 비교할 수
없다. 이 경우 조회 대상 저장소가 Object를 반환하지 않으면 Object 누락으로 분류하고, 운영자가 각
프로세스의 Endpoint를 함께 점검한다.

## API 오류 계약

| 상황 | ErrorCode | HTTP | 의미 |
|---|---|---:|---|
| Network·인증·Provider 장애 | `FILE_STORAGE_FAILED` | 503 | 일시적 저장소 비가용 가능 |
| Object 없음 | `FILE_OBJECT_NOT_FOUND` | 404 | DB Metadata와 실제 Object 불일치 |
| Provider 또는 Bucket 불일치 | `FILE_STORAGE_CONFIGURATION_MISMATCH` | 500 | 배포 설정 또는 Migration 오류 |

오류 응답과 Worker 실패 메시지에는 Endpoint, Credential, Bucket, Object Key 원문을 포함하지 않는다.
세부 예외는 서버 로그의 Stack Trace로만 확인한다.

## Worker 분류 계약

| ErrorCode | `IndexingFailureType` | Retry | 안전 메시지 목적 |
|---|---|---|---|
| `FILE_STORAGE_FAILED` | `STORAGE_UNAVAILABLE` | 가능 | 저장소를 일시적으로 사용할 수 없음 |
| `FILE_OBJECT_NOT_FOUND` | `STORAGE_OBJECT_MISSING` | 불가 | Metadata가 가리키는 원본 누락 |
| `FILE_STORAGE_CONFIGURATION_MISMATCH` | `STORAGE_CONFIGURATION_INVALID` | 불가 | Worker 설정과 저장 위치 불일치 |

설정 불일치와 Object 누락은 같은 요청을 반복해도 복구되지 않으므로 자동 Retry를 중단한다. 설정 또는
Object를 복구한 뒤에는 운영자가 명시적으로 새 인덱싱 실행을 시작해야 한다.

## Adapter 동작

### Local Filesystem

`LOCAL` Provider와 논리 Bucket이 모두 일치할 때만 설정 Root 아래 경로를 해석한다. 일치하지 않으면
Filesystem 접근 전에 설정 불일치 오류를 반환한다.

### MinIO와 S3

각 Adapter Provider와 `STORAGE_BUCKET`이 DB Snapshot과 일치할 때만 Get/Delete 요청을 만든다. Object
없음 응답은 기존 파일 누락 오류로 유지하고, 그 밖의 SDK 오류는 저장소 비가용으로 유지한다.

## 검증

- 세 Adapter의 Provider 불일치 오류 검증
- 세 Adapter의 Bucket 불일치 오류 검증
- 세 Adapter의 Object 누락 오류 회귀 검증
- 저장소 SDK·I/O 장애가 일반 저장소 비가용으로 유지되는지 검증
- Worker 세 분류의 실패 유형, 진단 코드, 안전 메시지, Retry 가능 여부 검증
- 영향 범위 서비스와 전체 백엔드 회귀 테스트
