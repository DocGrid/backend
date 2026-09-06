package com.opensource.docgrid.domain.worker.lifecycle;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 애플리케이션 실행 인스턴스를 Worker Node로 등록하고 Heartbeat·Job 실행에 현재 Worker ID를 제공한다.
 *
 * <p>종료 시 활성 Job Executor와 Lease 갱신이 먼저 정리된 뒤 Worker를 STOPPED로 기록한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "indexing.worker", name = "enabled", havingValue = "true")
public class WorkerLifecycleManager {

    private static final String UNKNOWN_HOST = "unknown";

    private final WorkerNodeCommandService workerNodeCommandService;
    private final IndexingWorkerProperties indexingWorkerProperties;
    private final AtomicReference<Long> workerId = new AtomicReference<>();
    private final String instanceId = UUID.randomUUID().toString();

    /**
     * 애플리케이션 준비 완료 시 이 프로세스를 고유한 Worker 인스턴스로 등록한다.
     *
     * <p>이벤트가 중복 전달돼도 AtomicReference로 한 번만 채택하며, 동시에 생성된 불필요한 등록은
     * 즉시 STOPPED 처리해 활성 Worker가 중복 노출되지 않게 한다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerWorker() {
        // 1. 이미 등록된 인스턴스에는 중복 Worker Row를 만들지 않는다.
        if (workerId.get() != null) {
            return;
        }

        HostInfo hostInfo = resolveHostInfo();

        // 2. 설정된 Worker 이름과 프로세스별 instanceId, 가능한 호스트 정보를 DB에 등록한다.
        Long registeredWorkerId = workerNodeCommandService.register(
            indexingWorkerProperties.getName(),
            instanceId,
            hostInfo.hostName(),
            hostInfo.ipAddress()
        );

        // 3. 다른 등록 호출이 먼저 ID를 게시했다면 이번 중복 Row를 즉시 종료한다.
        if (!workerId.compareAndSet(null, registeredWorkerId)) {
            workerNodeCommandService.stop(registeredWorkerId, instanceId);
            return;
        }

        log.info(
            "인덱싱 Worker를 등록했습니다. workerId={}, workerName={}, instanceId={}",
            registeredWorkerId,
            indexingWorkerProperties.getName(),
            instanceId
        );
    }

    /**
     * 애플리케이션 종료 시 현재 프로세스의 Worker를 더 이상 실행하지 않는 상태로 기록한다.
     *
     * <p>낮은 이벤트 우선순위로 실행되어 Polling과 Lease Scheduler가 먼저 정리된 뒤 호출된다.
     * 종료 중 DB 오류는 프로세스 종료를 막지 않고 Lease 만료 복구가 남은 상태를 회수하게 한다.
     */
    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(ContextClosedEvent.class)
    public void stopWorker() {
        // 1. Worker ID를 먼저 비워 다른 Lifecycle 호출이 같은 Worker를 다시 종료하지 않게 한다.
        Long registeredWorkerId = workerId.getAndSet(null);
        if (registeredWorkerId == null) {
            return;
        }

        // 2. instanceId까지 대조해 재등록된 다른 프로세스의 Worker 상태를 잘못 바꾸지 않는다.
        try {
            boolean stopped = workerNodeCommandService.stop(registeredWorkerId, instanceId);
            if (!stopped) {
                log.warn(
                    "종료할 인덱싱 Worker를 찾지 못했습니다. workerId={}, instanceId={}",
                    registeredWorkerId,
                    instanceId
                );
            }
        } catch (RuntimeException exception) {
            log.error(
                "인덱싱 Worker 종료 상태를 기록하지 못했습니다. workerId={}, instanceId={}",
                registeredWorkerId,
                instanceId,
                exception
            );
        }
    }

    /**
     * 등록 완료된 현재 Worker ID를 반환하며 시작 전이나 종료 후에는 빈 값을 반환한다.
     */
    public Optional<Long> getWorkerId() {
        return Optional.ofNullable(workerId.get());
    }

    /**
     * 이 애플리케이션 프로세스 수명 동안 유지되는 Worker 인스턴스 식별자를 반환한다.
     */
    public String getInstanceId() {
        return instanceId;
    }

    /**
     * 운영 진단용 호스트 이름과 IP를 조회하되 실패해도 Worker 등록을 계속할 수 있는 값으로 대체한다.
     */
    private HostInfo resolveHostInfo() {
        // 1. 가능한 환경에서는 실제 호스트 이름과 IP를 함께 남겨 운영자가 Worker 인스턴스를 식별하게 한다.
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            return new HostInfo(localHost.getHostName(), localHost.getHostAddress());
        } catch (UnknownHostException exception) {
            // 2. DNS/호스트 조회 실패는 인덱싱 기능 기동을 막지 않고 안전한 대체 이름으로 낮춘다.
            log.warn("Worker의 호스트 정보를 확인하지 못했습니다.", exception);
            return new HostInfo(UNKNOWN_HOST, null);
        }
    }

    /**
     * Worker 등록 시 함께 저장할 호스트 이름과 선택적 IP 주소를 묶는다.
     */
    private record HostInfo(String hostName, String ipAddress) {
    }
}
