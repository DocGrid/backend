package com.opensource.docgrid.domain.worker.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker의 시각 계산을 주입 가능한 Clock으로 제공하는 공통 구성이다.
 *
 * <p>테스트가 고정 Clock을 등록하면 해당 Bean을 우선 사용하고 운영 환경에서만 시스템 기본 시간대를 쓴다.
 */
@Configuration
public class WorkerConfig {

    /** 별도 Clock Bean이 없는 환경에 운영용 시스템 Clock을 제공한다. */
    @Bean
    @ConditionalOnMissingBean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
