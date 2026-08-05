package com.opensource.docgrid.domain.worker.service;

import com.opensource.docgrid.domain.embedding.enums.IndexingFailureType;

/**
 * Worker 실행 예외를 DB 실패 보고에 사용할 제한된 유형과 안전한 진단 정보로 표현한다.
 *
 * <p>소유권을 이미 잃은 예외는 {@code reportable=false}이며 실패 유형이 없다. 진단 코드와 메시지는
 * 자유 형식 원인이나 Claim Token을 포함하지 않고 로그와 실패 전이 경계에서만 사용한다.
 */
public record WorkerIndexingFailure(
    boolean reportable,
    IndexingFailureType failureType,
    String diagnosticCode,
    String safeMessage
) {

    static WorkerIndexingFailure reportable(
        IndexingFailureType failureType,
        String diagnosticCode,
        String safeMessage
    ) {
        return new WorkerIndexingFailure(true, failureType, diagnosticCode, safeMessage);
    }

    static WorkerIndexingFailure ownershipLost(String diagnosticCode) {
        return new WorkerIndexingFailure(
            false,
            null,
            diagnosticCode,
            "현재 Worker 실행이 Job 소유권을 더 이상 보유하지 않습니다."
        );
    }
}
