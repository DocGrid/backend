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

## CQRS 패턴
- Service는 `command`(쓰기)와 `query`(읽기)로 분리
- Command: 클래스 레벨 `@Transactional` 필수
- Query: 클래스 레벨 `@Transactional(readOnly = true)`, 변경 메서드만 `@Transactional` 오버라이드

## 예외 처리
항상 이 패턴 사용:
```java
Entity entity = repository.findById(id)
    .orElseThrow(() -> new DocGridException(ErrorCode.ENTITY_NOT_FOUND));
```
- 커스텀 예외: `DocGridException(ErrorCode)` 사용
- `ErrorCode`에 HTTP 상태코드와 메시지 함께 정의
- `GlobalExceptionHandler`에서 일괄 처리

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

## Service 패턴
```java
@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
@Slf4j
public class XxxQueryService {
    private final XxxRepository xxxRepository;
    private final XxxConverter xxxConverter;

    public XxxResponse getXxx(Long id) {
        Xxx xxx = xxxRepository.findById(id)
            .orElseThrow(() -> new DocGridException(ErrorCode.XXX_NOT_FOUND));
        return xxxConverter.toResponse(xxx);
    }
}

@Transactional
@Service
@RequiredArgsConstructor
public class XxxCommandService {
    private final XxxRepository xxxRepository;
    // dirty checking 활용, 명시적 save는 신규 엔티티에만 사용
}
```

## Controller 패턴
```java
@Tag(name = "Xxx", description = "xxx 관련 API")
@RestController
@RequestMapping("/api/xxx")
@RequiredArgsConstructor
public class XxxController {

    @Operation(summary = "xxx 단건 조회")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<XxxResponse>> getXxx(@PathVariable Long id) {
        return ResponseUtils.ok(xxxQueryService.getXxx(id));
    }
}
```

## Swagger 컨벤션
- 클래스: `@Tag(name = "도메인명", description = "한 줄 설명")`
- 메서드: `@Operation(summary = "짧은 요약", description = "상세 설명")` — description은 항상 작성
  - summary: 한 줄 동사+목적어 (예: "부서 목록 조회")
  - description: 호출 시점, 파라미터 조건, 특이사항, 주의사항 등 프론트가 알아야 할 내용 작성
- Response DTO: `@Schema(description = "필드 설명")`

```java
@Operation(
    summary = "약 등록",
    description = "약을 1개 이상 등록합니다. 단건이면 리스트에 1개, 여러 개면 여러 개 담아서 보내세요. 하나라도 실패하면 전체 롤백됩니다."
)
```

## Converter 패턴
- 위치: `domain/{도메인}/converter/XxxConverter.java`
- `@Component`로 Spring Bean 등록, 서비스에서 주입

```java
@Component
public class XxxConverter {
    public XxxResponse toResponse(Xxx xxx) { ... }
}
```

## Lombok / 코딩 컨벤션
- `@RequiredArgsConstructor`, `@Getter`, `@Builder`, `@Slf4j`
- DTO는 `record` 또는 Lombok 클래스
- Entity는 `BaseEntity` 상속 (createdAt, updatedAt 자동 관리)
- 모든 연관관계 `FetchType.LAZY`, 필요 시 `JOIN FETCH`
- Enum 필드는 `@Enumerated(EnumType.STRING)`
- 상수는 `enum` 또는 `static final`, 매직 넘버 금지
