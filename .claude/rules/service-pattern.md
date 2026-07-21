---
globs: "**/service/**/*.java"
---

# Service 패턴

## CQRS
- Service는 `command`(쓰기)와 `query`(읽기)로 분리
- Command: 클래스 레벨 `@Transactional` 필수
- Query: 클래스 레벨 `@Transactional(readOnly = true)`, 변경 메서드만 `@Transactional` 오버라이드

## 코드 패턴
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
