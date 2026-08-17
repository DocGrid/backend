# #224 관리자 사용자 부서 변경 API

closes #224

---

## 배경

QA 시나리오 1(회원가입/로그인 플로우) 진행 중 발견: 회원가입 시 `departmentId`는 `@NotNull` 필수값이라 부서 등록은 가입 시점에 자동으로 되지만, **가입 이후 부서를 변경하는 API가 백엔드·프론트 어디에도 없었다.** DB에 직접 삽입한 계정(예: 초기 admin 계정)처럼 `department`가 `null`인 사용자는 앱 내에서 영구히 고칠 방법이 없는 상태였다.

역할(role) 부여가 이미 관리자 전용(`POST /admin/users/{userId}/roles`)이고, `department`는 부서 기반 권한 검색(live predicate)의 기준값이라 사용자 본인이 셀프로 바꾸면 권한 상승 위험이 있다. 그래서 동일한 관리자 전용 패턴으로 부서 변경 API를 추가했다.

```text
관리자 → PATCH /admin/users/{userId}/department (JWT, ADMIN role) → AdminUserController → UserCommandService
```

---

## 설계 결정

**새 응답 DTO를 만들지 않고 기존 `AdminUserResponse`/`AdminUserConverter` 재사용**
부서 변경 결과로 필요한 필드(사용자 정보 + 변경된 부서 + 역할 목록)가 `GET /admin/users` 목록 조회에 이미 쓰이는 `AdminUserResponse`와 완전히 동일해서, 별도 Response DTO를 만들지 않고 `AdminUserConverter.toResponse(user, roles)`를 그대로 재사용했다.

**부서 검증 로직은 회원가입과 동일한 조건**
`AuthCommandService.signup()`이 이미 `departmentRepository.findById(...).filter(d -> d.getStatus() == CommonStatus.ACTIVE)` 패턴으로 "존재 + ACTIVE 상태"를 검증하고 있어, `UserCommandService.changeDepartment()`도 동일한 조건을 그대로 따랐다. 비활성 부서로는 신규 가입도, 부서 변경도 불가능하다.

**`User` 엔티티에 `changeDepartment()` 도메인 메서드 추가**
기존 `recordLogin()`, `markDeleted()`와 같은 위치·패턴으로 추가했다. Command Service에서 필드를 직접 대입하지 않고 엔티티 메서드를 통해서만 상태를 바꾸는 기존 컨벤션을 따랐다. 저장은 JPA dirty checking에 맡기고 명시적 `save()` 호출은 하지 않는다.

**감사(audit) 필드는 추가하지 않음**
`UserRole`에는 `assignedBy`/`assignedAt`이 있지만, `User.department`는 단일 값 필드라 "누가 언제 바꿨는지"를 남기려면 별도 이력 테이블이 필요하다. 이번 이슈 범위에서는 요구되지 않아 추가하지 않았다 — 필요해지면 별도 이슈로 분리한다.

---

## API 명세

### PATCH /admin/users/{userId}/department — 사용자 부서 변경

```http
Authorization: Bearer {JWT, ADMIN role}
Content-Type: application/json

{ "departmentId": 5 }
```

```json
// 200 OK
{
  "success": true,
  "status": 200,
  "data": {
    "userId": 10,
    "name": "홍길동",
    "email": "hong@example.com",
    "nickname": "hong",
    "departmentId": 5,
    "departmentName": "영업팀",
    "status": "ACTIVE",
    "roles": ["USER"],
    "lastLoginAt": "2026-08-10 10:00:00",
    "createdAt": "2026-01-01 10:00:00"
  }
}
```

### 에러 케이스

| 상황 | 코드 | HTTP |
| --- | --- | --- |
| ADMIN 권한 없이 호출 | 없음 (Spring Security 필터에서 차단) | 403 |
| 존재하지 않는 `userId` | `USER-001` (`USER_NOT_FOUND`) | 404 |
| 존재하지 않거나 비활성 상태인 `departmentId` | `DEPT-001` (`DEPARTMENT_NOT_FOUND`) | 400 |
| `departmentId` 누락 (`@NotNull` 검증 실패) | `COMMON-002` (`INVALID_PARAMETER`) | 400 |

---

## 프론트엔드

`/admin/users` 관리자 페이지에 "부서 변경" 폼을 추가했다. 기존 "역할 부여" 폼과 동일한 `selectedUserId`(목록에서 "역할 관리" 버튼으로 선택하거나 직접 입력)를 공유하고, 이미 로드돼 있던 `departments` 상태로 부서 `<select>`를 구성한다. 성공 시 `notify()`로 토스트를 띄우고 `load()`로 목록을 새로고침해 변경된 부서가 즉시 반영되도록 했다.

---

## 검증

- `./backend/gradlew -p backend test --tests "com.opensource.docgrid.domain.user.*"`: 신규 테스트(`UserCommandServiceTest` 3건, `AdminUserControllerTest` 추가 3건) 전부 통과
  - `UserRepositoryTest`의 기존 실패 1건은 이번 변경과 무관 — 변경 전 코드에서도 동일하게 재현되는 기존 결함으로 확인(테스트 DB 데이터 격리 이슈로 추정), 이번 PR 범위 밖이라 손대지 않음
- `./backend/gradlew -p backend build -x test`: 컴파일 성공
- 프론트: `npx tsc --noEmit`로 타입체크 — `AdminPages.tsx` 관련 에러 없음 (남은 에러는 Cloudflare Worker 타입 관련 기존 이슈로 무관)

---

## 남은 이슈 / TODO

- 본인(비관리자)이 자신의 부서를 확인만 하고 변경은 못 하는 현재 UX는 의도된 동작 — 필요 시 별도 논의
- 부서 변경 이력(누가/언제 바꿨는지) 감사 로그는 이번 범위에 포함하지 않음
