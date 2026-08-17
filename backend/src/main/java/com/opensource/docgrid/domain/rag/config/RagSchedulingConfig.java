package com.opensource.docgrid.domain.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code WorkerSchedulingConfig} 등 다른 도메인의 {@code @EnableScheduling}과 별개로 켠다 —
 * 그쪽은 {@code indexing.worker.enabled} 조건부라 꺼질 수 있지만, {@link
 * com.opensource.docgrid.domain.rag.service.RagJobWorker}는 검색 API의 핵심 경로라 조건 없이
 * 항상 돌아야 한다. {@code @EnableScheduling}을 여러 설정 클래스에 중복 선언해도 Spring이
 * 안전하게 병합하므로 문제없다.
 */
@Configuration
@EnableScheduling
public class RagSchedulingConfig {
}
