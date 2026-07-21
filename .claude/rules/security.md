---
globs: "**/SecurityConfig.java, **/config/**/*.java, **/*Controller.java, .env, .env.*, **/application*.yml"
---

# 보안 규칙

## Spring Security
- 인증이 필요한 API는 SecurityConfig에서 명시적으로 관리
- `permitAll()`은 최소한으로, 이유 명확히 파악 후 추가
- 새 API 추가 시 SecurityConfig 반드시 확인

## 민감 정보
- 비밀번호 등 민감 정보는 절대 응답 DTO에 포함 금지
- 시크릿은 `application.yml` 하드코딩 금지 — 환경변수로 주입
- `.env` 파일 직접 수정 금지

## 입력 검증
- `@Valid`, `@NotBlank` 등으로 Controller 계층에서 검증
- 외부 입력 신뢰 금지 — 시스템 경계에서만 검증

## 트러블슈팅
- Spring Security 403: SecurityConfig의 requestMatchers 경로 확인
- Spring Security 401: 인증 토큰 또는 세션 상태 확인
