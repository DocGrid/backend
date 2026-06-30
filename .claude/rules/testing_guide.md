---
globs: "src/test/**/*.java"
---

# 테스트 컨벤션

## 테스트 종류 선택
| 종류 | 기반 | 사용 시점 |
|------|------|----------|
| 단위 | `@ExtendWith(MockitoExtension.class)` | Service 비즈니스 로직 검증 |
| Repository | `@DataJpaTest` | 실제 쿼리 검증이 필요할 때 |
| 통합 | `@SpringBootTest` + `@Tag("integration")` | API 전체 흐름 검증 |

기본 원칙: 단위 테스트 먼저, DB 연동이 꼭 필요한 경우에만 통합 테스트 추가.

## 단위 테스트 구조
```java
@ExtendWith(MockitoExtension.class)
@DisplayName("XxxService 단위 테스트")
class XxxServiceTest {

    @InjectMocks private XxxQueryService xxxQueryService;
    @Mock private XxxRepository xxxRepository;
    @Mock private XxxConverter xxxConverter;

    @Test
    @DisplayName("정상 케이스: 한국어로 명확하게 작성")
    void methodName_scenario() {
        // Given
        given(xxxRepository.findById(anyLong())).willReturn(Optional.of(expected));
        // When
        XxxResponse result = xxxQueryService.getXxx(1L);
        // Then
        assertThat(result).isEqualTo(expected);
        then(xxxRepository).should(times(1)).findById(1L);
    }

    @Test
    @DisplayName("예외 케이스: ~하면 예외가 발생한다")
    void methodName_throws_when_condition() {
        given(xxxRepository.findById(anyLong())).willReturn(Optional.empty());

        assertThatThrownBy(() -> xxxQueryService.getXxx(1L))
            .isInstanceOf(DocGridException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.XXX_NOT_FOUND);
    }
}
```

## Fixture 클래스
- 위치: `src/test/java/com/opensource/docgrid/{domain}/fixture/`
- 상수: `public static final`
- 팩토리 메서드: `public static`

```java
public class XxxFixture {
    public static final Long ID = 1L;
    public static final String NAME = "테스트이름";

    public static Xxx createXxx() {
        return Xxx.builder().name(NAME).build();
    }
}
```

## 통합 테스트
```java
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class XxxIntegrationTest { }
```
- 반드시 `@Tag("integration")` 추가
- `./gradlew test -Dgroups=integration` 으로 분리 실행
