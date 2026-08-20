# #248 회원가입 비밀번호 최소 길이·재사용 검증 누락 수정

closes #248

---

## 배경

QA 과정에서 `/signup`에 비밀번호 1글자만 입력해도 브라우저 검증을 통과하는 문제가 발견됐다. 원인을 추적해보니 백엔드 `SignupRequest.password`에는 `@NotBlank`만 적용돼 있었다 — `null`/빈 문자열/공백만 막을 뿐 길이 제한은 없다. 프런트(`AuthPage.tsx`)도 `required`만 걸려 있고 `minLength`가 없어 클라이언트·서버 모두 약한 비밀번호를 그대로 받아들이는 상태였다.

실제 계정 생성은 테스트 데이터 변경을 피하기 위해 수행하지 않았고, DTO·서비스 코드 확인으로 원인을 검증했다.

## 설계 결정

- **최소 길이 12자, 최대 64자.** Bean Validation `@Size(min = 12, max = 64)`를 `SignupRequest.password`에 그대로 추가했다. `@Valid`(`AuthController`)와 `MethodArgumentNotValidException` 처리(`GlobalExceptionHandler`)가 이미 있어 별도 배선 없이 400 응답이 나간다.
- **이메일·이름과 동일한 비밀번호 차단은 서비스 계층에서.** 필드 간 비교는 단일 필드용 Bean Validation 애노테이션으로 표현할 수 없어, 커스텀 `ConstraintValidator`를 새로 만드는 대신 기존 `EMAIL_ALREADY_EXISTS` 체크와 동일한 패턴으로 `AuthCommandService.signup()`에 조건문을 추가했다. 이 프로젝트에 커스텀 validator 선례가 없어 새 패턴을 도입하기보다 기존 서비스 계층 검증 패턴을 재사용하는 쪽을 택했다.
- **신규 `ErrorCode.WEAK_PASSWORD` (USER-005, 400)** — 이메일/이름 재사용 케이스 전용. 단순 유효성 실패라 `log.error` 없이 예외만 던진다 (Not Found류와 동일하게 로깅 규칙상 생략).
- **스코프에서 제외한 것**: 흔한 비밀번호 목록 차단, 유출 비밀번호 DB 연동. 둘 다 별도 데이터 소스와 유지보수가 필요한 기능이라 이번 QA 수정 범위를 벗어난다고 판단해 뺐다. 필요하면 별도 이슈로 분리한다.
- **프런트는 signup 모드에서만 `minLength={12}`.** `AuthPage.tsx`는 로그인/회원가입이 같은 컴포넌트를 공유하는데, 로그인 모드에 `minLength`를 걸면 (정책 도입 이전에 만들어진) 기존 계정의 로그인이 막힐 수 있어 signup 모드에서만 적용했다.

## API 명세

```http
POST /auth/signup
Content-Type: application/json

{
  "email": "user@company.com",
  "password": "string (12~64자)",
  "name": "string",
  "departmentId": number
}
```

**응답**: `201 Created` — `SignupResponse` (기존과 동일)

**에러 케이스**

| 상황 | 응답 |
| --- | --- |
| 비밀번호 12자 미만 / 64자 초과 | `400 BAD_REQUEST` — Bean Validation, `MethodArgumentNotValidException` |
| 비밀번호가 공백/빈 문자열 | `400 BAD_REQUEST` — 기존 `@NotBlank` (변경 없음) |
| 비밀번호가 이메일과 동일 | `400 WEAK_PASSWORD` (USER-005) |
| 비밀번호가 이름과 동일 | `400 WEAK_PASSWORD` (USER-005) |
| 이메일 중복 | `409 EMAIL_ALREADY_EXISTS` (기존, 변경 없음) — 이메일 중복 체크가 비밀번호 정책 체크보다 먼저 실행됨 |

## 변경 파일

- `SignupRequest.java` — `password`에 `@Size(min = 12, max = 64)` 추가
- `ErrorCode.java` — `WEAK_PASSWORD` (USER-005) 추가
- `AuthCommandService.java` — `signup()`에 이메일/이름 재사용 비밀번호 차단 로직 추가
- `frontend/app/components/AuthPage.tsx` — signup 모드 비밀번호 입력에 `minLength={12}` + 안내 placeholder 추가

## 테스트

- `AuthCommandServiceTest` — 비밀번호가 이메일과 동일한 경우 / 이름과 동일한 경우 `WEAK_PASSWORD` 예외 케이스 2개 추가
- 신규 `SignupRequestTest` — `@Size` 경계값 검증 (정상, 12자 미만, 64자 초과) 3케이스
- `./backend/gradlew -p backend test --tests "*AuthCommandServiceTest*" --tests "*SignupRequestTest*"` 통과 (17개, 전부 성공)
- 프런트: `AuthPage.tsx`만 대상 `eslint` 통과, `tsc --noEmit`에서 이 파일 관련 에러 없음 (남은 에러는 워커 타입 설정 관련 기존 이슈)
