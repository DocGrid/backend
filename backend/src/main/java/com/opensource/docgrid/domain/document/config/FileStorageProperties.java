package com.opensource.docgrid.domain.document.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.document.enums.StorageProvider;

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

    private StorageProvider type = StorageProvider.LOCAL;
    private String bucket = "docgrid";
    private Local local = new Local();

    /**
     * Local Filesystem Adapter가 Object Key를 해석할 기준 Root를 제공한다.
     */
    @Getter
    @Setter
    public static class Local {

        private Path root = Path.of("./data/docgrid");
    }
}
