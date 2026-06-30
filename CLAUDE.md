# CLAUDE.md — DocGrid

## Engineering Guidelines

Don't assume. Don't hide confusion. Surface tradeoffs.

**Think Before Coding**
- State assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- For non-trivial tasks, start in Plan Mode and don't implement until approved.

**Simplicity First**
- Minimum code that solves the problem. Nothing speculative.
- No features beyond what was asked. No abstractions for single-use code.

**Surgical Changes**
- Touch only what you must. Don't "improve" adjacent code or formatting.
- Match existing style, even if you'd do it differently.
- Every changed line should trace directly to the user's request.

**Goal-Driven Execution**
- Define success criteria before starting.
- For multi-step tasks, state a brief plan and verify each step.

---

## 어디서 무엇을 읽을지

### 🔵 작업 직전 항상
- 프로젝트 구조 → 이 파일 (CLAUDE.md)
- 도메인 목록 → `src/main/java/com/opensource/docgrid/domain/`

### 🟢 상황별 룰 (`.claude/rules/`) — 자동 로드됨
- Java 코드 작성 시 → `code_style.md`
- 테스트 작성/수정 시 → `testing_guide.md`
- Security/Config 만질 때 → `security.md`
- 배포/Docker/GitHub Actions 관련 → `deploy.md`

### 🟣 AI 작업 흔적 (`.dev/`)
- 새로 알게 된 패턴·주의점·오류 기록 → `learnings/`
- 작업 중 임시 메모 (작업 종료 후 삭제 — 비어있는 게 정상) → `scratchpad/`

---

## 프로젝트 개요

- **Framework**: Spring Boot 3.5.16
- **Language**: Java 17
- **Build**: Gradle
- **DB**: PostgreSQL + Flyway
- **Package**: `com.opensource.docgrid`

## 프로젝트 구조

```
src/main/java/com/opensource/docgrid/
├── global/
│   ├── common/       # 공통 응답 (ApiResponse, ErrorResponse, BaseEntity)
│   ├── config/       # 설정 (SecurityConfig, CorsConfig, SwaggerConfig)
│   └── exception/    # 전역 예외 (DocGridException, ErrorCode, GlobalExceptionHandler)
└── domain/
    └── {도메인}/
        ├── entity/
        ├── repository/
        ├── service/
        │   ├── command/    # 상태 변경
        │   └── query/      # 조회 전용
        ├── controller/
        ├── dto/
        │   ├── request/
        │   └── response/
        ├── converter/      # Entity ↔ DTO 변환
        └── enums/
```

## 주요 명령어

```bash
./gradlew build
./gradlew clean build
./gradlew build -x test
./gradlew test
```

---

## 영구 금지

- `git add -A` / `git add .` (민감 파일 우회 위험)
- `git push --force` / `--no-verify` / `--amend` (안전장치 우회)
- `main` 브랜치 직접 push — PR + 리뷰 후 merge만 허용
- 시크릿을 `application.yml`에 하드코딩
- Entity를 Controller 계층에 직접 노출
