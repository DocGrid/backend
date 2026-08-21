package com.opensource.docgrid;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.opensource.docgrid.domain.document.storage.FileStorageService;
import com.opensource.docgrid.domain.document.storage.LocalFileStorageService;

import io.minio.MinioClient;

@ActiveProfiles("test")
@SpringBootTest
class DocgridApplicationTests {

	@Autowired private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
		assertThat(applicationContext.getBean(FileStorageService.class))
			.isInstanceOf(LocalFileStorageService.class);
		assertThat(applicationContext.getBeansOfType(MinioClient.class)).isEmpty();
	}

}
