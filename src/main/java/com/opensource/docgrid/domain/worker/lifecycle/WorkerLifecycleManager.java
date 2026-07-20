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
import org.springframework.stereotype.Component;

import com.opensource.docgrid.domain.worker.config.IndexingWorkerProperties;
import com.opensource.docgrid.domain.worker.service.command.WorkerNodeCommandService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    @EventListener(ApplicationReadyEvent.class)
    public void registerWorker() {
        if (workerId.get() != null) {
            return;
        }

        HostInfo hostInfo = resolveHostInfo();
        Long registeredWorkerId = workerNodeCommandService.register(
            indexingWorkerProperties.getName(),
            instanceId,
            hostInfo.hostName(),
            hostInfo.ipAddress()
        );

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

    @EventListener(ContextClosedEvent.class)
    public void stopWorker() {
        Long registeredWorkerId = workerId.getAndSet(null);
        if (registeredWorkerId == null) {
            return;
        }

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

    public Optional<Long> getWorkerId() {
        return Optional.ofNullable(workerId.get());
    }

    public String getInstanceId() {
        return instanceId;
    }

    private HostInfo resolveHostInfo() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            return new HostInfo(localHost.getHostName(), localHost.getHostAddress());
        } catch (UnknownHostException exception) {
            log.warn("Worker의 호스트 정보를 확인하지 못했습니다.", exception);
            return new HostInfo(UNKNOWN_HOST, null);
        }
    }

    private record HostInfo(String hostName, String ipAddress) {
    }
}
