package com.opensource.docgrid.domain.document.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.Getter;
import lombok.Setter;

/**
 * 파일 저장소 Adapter 선택과 모든 구현이 공유하는 논리적 저장 위치 설정을 제공한다.
 * 실제 Provider Client 생성과 I/O는 각 Adapter가 담당하며 이 설정은 도메인 분기를 만들지 않는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "storage")
public class FileStorageProperties {

    private FileStorageType type = FileStorageType.LOCAL;
    private String bucket;
    private Local local = new Local();

    public String getBucket() {
        if (StringUtils.hasText(bucket)) {
            return bucket;
        }
        // 외부 Bucket은 명시해야 하지만 기본 Local 실행에는 논리 Namespace를 자동 제공한다.
        return type == FileStorageType.LOCAL ? "docgrid" : bucket;
    }

    /**
     * Local Filesystem Adapter가 Object Key를 해석할 기준 Root를 제공한다.
     */
    @Getter
    @Setter
    public static class Local {

        private Path root = Path.of("./data/docgrid");
    }
}
