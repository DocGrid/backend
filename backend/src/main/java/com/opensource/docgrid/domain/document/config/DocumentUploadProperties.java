package com.opensource.docgrid.domain.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import lombok.Getter;
import lombok.Setter;

/**
 * {@code document.upload} 설정에서 도메인 업로드 검증에 사용할 최대 파일 크기를 제공한다.
 *
 * <p>HTTP Multipart 수신 한도와 같은 기본값을 사용해 전송은 성공했지만 도메인 검증에서 예상과 다르게
 * 거부되는 구성 차이를 줄인다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "document.upload")
public class DocumentUploadProperties {

    // Spring multipart의 파일 제한과 같은 기본값을 사용해 수신 경계와 도메인 검증이 어긋나지 않게 한다.
    private DataSize maxFileSize = DataSize.ofMegabytes(50);
}
