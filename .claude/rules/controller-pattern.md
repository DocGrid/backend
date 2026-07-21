---
globs: "**/controller/**/*.java"
---

# Controller 패턴

## 코드 패턴
```java
@Tag(name = "Xxx", description = "xxx 관련 API")
@RestController
@RequestMapping("/api/xxx")
@RequiredArgsConstructor
public class XxxController {

    @Operation(summary = "xxx 단건 조회", description = "상세 설명 작성")
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
