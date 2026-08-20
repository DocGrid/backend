package com.opensource.docgrid.domain.embedding.enums;

/**
 * 관리자 수동 재처리 가능 여부와 차단 사유를 표현한다.
 *
 * <p>관리자 조회 화면은 이 값을 버튼 노출 기준으로 사용하지만, 실제 상태 변경 시점에는 잠금 안에서
 * 같은 정책을 다시 평가해야 한다. 조회 결과는 동시 변경 전의 Snapshot일 뿐 Command 권한이 아니다.
 */
public enum EmbeddingJobManualRetryEligibility {
    ELIGIBLE,
    JOB_NOT_FAILED,
    VERSION_NOT_FAILED,
    DOCUMENT_DELETED,
    DOCUMENT_STATUS_INVALID,
    CURRENT_VERSION_INCONSISTENT,
    SUPERSEDED_VERSION,
    LIVE_JOB_EXISTS,
    DATA_INCONSISTENT
}
