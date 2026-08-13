package com.opensource.docgrid.domain.sync.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reconciliation이 활성화된 실행 인스턴스에서 Cursor Scheduler를 켠다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "sync.reconciliation", name = "enabled", havingValue = "true")
public class SyncReconciliationSchedulingConfig {
}
