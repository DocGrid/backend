package com.opensource.docgrid.domain.worker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 인덱싱 Worker가 활성화된 실행에서만 Heartbeat·Polling·복구 Scheduler 처리를 켠다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerSchedulingConfig {
}
