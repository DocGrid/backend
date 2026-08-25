package com.opensource.docgrid.domain.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "document.upload")
public class DocumentUploadProperties {

    // Spring multipart의 파일 제한과 같은 기본값을 사용해 수신 경계와 도메인 검증이 어긋나지 않게 한다.
    private DataSize maxFileSize = DataSize.ofMegabytes(50);
}
