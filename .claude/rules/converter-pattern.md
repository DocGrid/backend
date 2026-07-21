---
globs: "**/converter/**/*.java"
---

# Converter 패턴

- 위치: `domain/{도메인}/converter/XxxConverter.java`
- `@Component`로 Spring Bean 등록, 서비스에서 주입

```java
@Component
public class XxxConverter {
    public XxxResponse toResponse(Xxx xxx) { ... }
}
```
