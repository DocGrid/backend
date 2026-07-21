# 도메인 생성 커맨드

주어진 도메인 이름($ARGUMENTS)으로 DocGrid 프로젝트의 표준 도메인 구조를 생성합니다.

## 선택적 패키지

명령 실행 전 아래 항목이 필요한지 사용자에게 먼저 확인합니다:

| 패키지 | 생성 파일 | 사용 시점 |
|--------|-----------|----------|
| `scheduler/` | `{Domain}Scheduler.java` | 주기적 배치 작업이 필요할 때 |
| `client/` | `{Domain}Client.java` | 외부 API 연동이 필요할 때 |
| `event/` | `{Domain}Event.java` | 도메인 이벤트 발행이 필요할 때 |
| `enums/` | `{Domain}Status.java` | 상태값 enum이 필요할 때 |

확인 후 필요하다고 한 패키지만 추가로 생성합니다.

## 생성 규칙

- 베이스 패키지: `com.opensource.docgrid.domain`
- 도메인명은 camelCase로 변환 (예: `document-grid` → `DocumentGrid`)
- 모든 파일은 아래 패턴과 컨벤션을 따라 생성

## 생성할 파일 목록

### 1. Entity
`src/main/java/com/opensource/docgrid/domain/{domain}/entity/{Domain}.java`

```java
package com.opensource.docgrid.domain.{domain}.entity;

import com.opensource.docgrid.global.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "{domain}s")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class {Domain} extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
```

### 2. Repository
`src/main/java/com/opensource/docgrid/domain/{domain}/repository/{Domain}Repository.java`

```java
package com.opensource.docgrid.domain.{domain}.repository;

import com.opensource.docgrid.domain.{domain}.entity.{Domain};
import org.springframework.data.jpa.repository.JpaRepository;

public interface {Domain}Repository extends JpaRepository<{Domain}, Long> {
}
```

### 3. QueryService
`src/main/java/com/opensource/docgrid/domain/{domain}/service/query/{Domain}QueryService.java`

```java
package com.opensource.docgrid.domain.{domain}.service.query;

import com.opensource.docgrid.domain.{domain}.converter.{Domain}Converter;
import com.opensource.docgrid.domain.{domain}.entity.{Domain};
import com.opensource.docgrid.domain.{domain}.repository.{Domain}Repository;
import com.opensource.docgrid.global.exception.DocGridException;
import com.opensource.docgrid.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
@Service
@RequiredArgsConstructor
public class {Domain}QueryService {

    private final {Domain}Repository {domain}Repository;
    private final {Domain}Converter {domain}Converter;

    public {Domain} get{Domain}(Long id) {
        return {domain}Repository.findById(id)
            .orElseThrow(() -> new DocGridException(ErrorCode.NOT_FOUND));
    }
}
```

### 4. CommandService
`src/main/java/com/opensource/docgrid/domain/{domain}/service/command/{Domain}CommandService.java`

```java
package com.opensource.docgrid.domain.{domain}.service.command;

import com.opensource.docgrid.domain.{domain}.repository.{Domain}Repository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@Service
@RequiredArgsConstructor
public class {Domain}CommandService {

    private final {Domain}Repository {domain}Repository;
}
```

### 5. Controller
`src/main/java/com/opensource/docgrid/domain/{domain}/controller/{Domain}Controller.java`

```java
package com.opensource.docgrid.domain.{domain}.controller;

import com.opensource.docgrid.domain.{domain}.service.command.{Domain}CommandService;
import com.opensource.docgrid.domain.{domain}.service.query.{Domain}QueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "{Domain}", description = "{domain} 관련 API")
@RestController
@RequestMapping("/api/{domain}s")
@RequiredArgsConstructor
public class {Domain}Controller {

    private final {Domain}QueryService {domain}QueryService;
    private final {Domain}CommandService {domain}CommandService;
}
```

### 6. Request DTO
`src/main/java/com/opensource/docgrid/domain/{domain}/dto/request/{Domain}Request.java`

```java
package com.opensource.docgrid.domain.{domain}.dto.request;

public record {Domain}Request() {
}
```

### 7. Response DTO
`src/main/java/com/opensource/docgrid/domain/{domain}/dto/response/{Domain}Response.java`

```java
package com.opensource.docgrid.domain.{domain}.dto.response;

public record {Domain}Response() {
}
```

### 8. Converter
`src/main/java/com/opensource/docgrid/domain/{domain}/converter/{Domain}Converter.java`

```java
package com.opensource.docgrid.domain.{domain}.converter;

import com.opensource.docgrid.domain.{domain}.dto.response.{Domain}Response;
import com.opensource.docgrid.domain.{domain}.entity.{Domain};
import org.springframework.stereotype.Component;

@Component
public class {Domain}Converter {

    public {Domain}Response toResponse({Domain} {domain}) {
        return new {Domain}Response();
    }
}
```

### Scheduler (선택)
```java
package com.opensource.docgrid.domain.{domain}.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class {Domain}Scheduler {

    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        log.info("{Domain}Scheduler 실행");
    }
}
```

### Client (선택)
```java
package com.opensource.docgrid.domain.{domain}.client;

import org.springframework.stereotype.Component;

@Component
public class {Domain}Client {
}
```

### Event (선택)
```java
package com.opensource.docgrid.domain.{domain}.event;

public record {Domain}Event(Long {domain}Id) {
}
```

### Enum (선택)
```java
package com.opensource.docgrid.domain.{domain}.enums;

public enum {Domain}Status {
}
```

## 완료 후 안내

생성 완료 후 아래 사항을 안내합니다:
1. Entity 필드 추가 필요
2. Request/Response DTO 필드 추가 필요
3. ErrorCode에 도메인 전용 에러코드 추가 필요
4. Flyway 마이그레이션 파일 작성 필요 (`V{버전}__{설명}.sql`)
5. Scheduler 생성 시 메인 클래스에 `@EnableScheduling` 추가 필요
6. `docs/design/{github아이디}-#{이슈번호}-{설명}.md` 설계 문서 작성 필요 (배경·API명세·에러케이스·이슈번호 필수)
