package com.opensource.docgrid.domain.dashboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Dashboard debounce push 스케줄러 전용 스케줄링 활성화.
 *
 * <p>{@code WorkerSchedulingConfig}의 {@code @EnableScheduling}은
 * {@code indexing.worker.enabled=true}일 때만 켜지는 조건부 설정이고 기본값은 {@code false}다.
 * 그 설정에 얹혀가면 Worker 기능이 꺼진 기본 상태에서 대시보드 push 스케줄러도 같이 동작하지
 * 않게 된다. 대시보드 push는 자동 Worker On/Off와 무관하게(관리자 수동 재처리만으로도) 항상
 * 동작해야 하므로 조건 없이 별도로 스케줄링을 켠다. {@code @EnableScheduling}을 여러 설정
 * 클래스에서 선언해도 스프링이 안전하게 처리한다.
 */
@Configuration
@EnableScheduling
public class DashboardSchedulingConfig {
}
