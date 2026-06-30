package com.opensource.docgrid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class DocgridApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocgridApplication.class, args);
	}

}
