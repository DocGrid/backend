---
name: 문서 새 버전 업로드
about: 기존 문서에 수정 파일을 새 버전으로 등록합니다.
title: "[Feature] 문서 새 버전 업로드"
labels: "✨ Feature"
assignees: ""
---

## 📌 Description

기존 논리 문서에 수정된 파일을 새 버전으로 등록합니다.

```http
POST /api/documents/{documentId}/versions
```

- 기존 `Document`를 유지하고 `version_no`를 증가시킵니다.
- 동일 파일은 `file_hash + file_size` 기준으로 재사용합니다.
- 새 버전이 `INDEXED`되기 전까지 기존 `current_version_id`를 유지합니다.
- 한 문서에는 처리 중인 버전을 하나만 허용합니다.

## ✅ To-do

- [ ] 버전 업로드 API 및 소유자 검증 구현
- [ ] 현재 버전과 동일 파일, 처리 중 버전, 잘못된 문서 상태 검증
- [ ] `version_no` 증가 및 동시성 Lock 적용
- [ ] `document_versions`에 nullable 파일명·Content-Type 컬럼 추가 및 백필
- [ ] 신규/후속 Version의 파일 메타데이터 저장
- [ ] FileObject 재사용 및 MinIO 후보 Object 보상 처리
- [ ] 새 Version과 `PENDING` EmbeddingJob 생성
- [ ] 단위·통합·동시성 테스트 작성

## ✅ 완료 기준

- [ ] 같은 문서에 Version이 중복 없이 증가한다.
- [ ] 현재 Version과 동일한 내용은 `409`로 거절한다.
- [ ] 처리 중 기존 검색 가능 Version이 유지된다.
- [ ] 실제 파일과 MinIO 후보 Object가 중복 저장되지 않는다.
- [ ] PostgreSQL 통합 테스트가 통과한다.

## 📒 기타

- 상세 설계: `docs/pr-2.1-document-version-upload.md`
- 후속 PR 3/9/10/17/18은 문서의 버전 상태 조회·완료·실패·재처리 계약을 따릅니다.
