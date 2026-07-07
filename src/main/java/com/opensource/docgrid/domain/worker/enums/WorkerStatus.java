package com.opensource.docgrid.domain.worker.enums;

/**
 * 인덱싱 Worker 노드 상태.
 * ACTIVE: 정상 동작, IDLE: 대기, DEAD: heartbeat 유실로 사망 판정, STOPPED: 정상 종료.
 */
public enum WorkerStatus {
    ACTIVE,
    IDLE,
    DEAD,
    STOPPED
}
