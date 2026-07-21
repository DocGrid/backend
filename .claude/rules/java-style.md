---
globs: "**/*.java"
---

# Java 코드 스타일

## 레이어 규칙
- Controller → Service → Repository 단방향
- Entity를 Controller 계층에 노출 금지 — 반드시 DTO 변환
- 순환 참조 금지 — 도메인 간 의존은 단방향으로만
- 외부 API 연동은 별도 `client/` 패키지로 분리
- 공통 응답은 `ResponseUtils`로 래핑

## Lombok / 코딩 컨벤션
- `@RequiredArgsConstructor`, `@Getter`, `@Builder`, `@Slf4j`
- DTO는 `record` 또는 Lombok 클래스
- Entity는 `BaseEntity` 상속 (createdAt, updatedAt 자동 관리)
- 모든 연관관계 `FetchType.LAZY`, 필요 시 `JOIN FETCH`
- Enum 필드는 `@Enumerated(EnumType.STRING)`
- 상수는 `enum` 또는 `static final`, 매직 넘버 금지

## 예외 처리
항상 이 패턴 사용:
```java
Entity entity = repository.findById(id)
    .orElseThrow(() -> new DocGridException(ErrorCode.ENTITY_NOT_FOUND));
```
- 커스텀 예외: `DocGridException(ErrorCode)` 사용
- `ErrorCode`에 HTTP 상태코드와 메시지 함께 정의
- `GlobalExceptionHandler`에서 일괄 처리
- 민감한 에러 정보(스택트레이스 등)는 응답 본문에 포함 금지
- 운영 환경에서 내부 오류는 `INTERNAL_SERVER_ERROR` 공통 메시지만 반환

## 로깅 규칙
- 단순 Not Found는 로그 생략 (GlobalExceptionHandler에서 처리됨)
- 중요한 비즈니스 로직 실패, 시스템 설정 오류, 외부 연동 실패는 예외 던지기 전에 `log.error()` 필수

```java
// 중요한 예외 — log.error 추가
if (activeModels.isEmpty()) {
    log.error("사용 가능한 임베딩 모델이 설정되지 않았습니다.");
    throw new DocGridException(ErrorCode.EMBEDDING_MODEL_NOT_CONFIGURED);
}

// 단순 Not Found — log 생략
User user = userRepository.findById(id)
    .orElseThrow(() -> new DocGridException(ErrorCode.USER_NOT_FOUND));
```
