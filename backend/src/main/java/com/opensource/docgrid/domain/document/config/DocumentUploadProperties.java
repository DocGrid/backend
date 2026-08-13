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

    private DataSize maxFileSize = DataSize.ofMegabytes(10);
}
