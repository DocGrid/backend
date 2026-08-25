package com.opensource.docgrid.domain.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Spring multipart 수신 제한과 문서 도메인 파일 제한의 기본값이 함께 유지되는지 검증한다.
 */
@DisplayName("문서 업로드 제한 설정 테스트")
class DocumentUploadLimitConfigurationTest {

    @Test
    @DisplayName("파일은 50MB, multipart 요청은 60MB로 제한한다")
    void applicationDefaults_alignMultipartAndDocumentLimits() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"));

        assertThat(sources).hasSize(1);
        PropertySource<?> source = sources.get(0);
        assertThat(source.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("50MB");
        assertThat(source.getProperty("spring.servlet.multipart.max-request-size")).isEqualTo("60MB");
        assertThat(source.getProperty("document.upload.max-file-size")).isEqualTo("50MB");
    }
}
