package com.opensource.docgrid.domain.sync.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Sync Dispatcher가 활성화된 실행 인스턴스에서만 Polling과 Lease Recovery Scheduler를 켠다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "sync.dispatcher", name = "enabled", havingValue = "true")
public class SyncSchedulingConfig {
}
