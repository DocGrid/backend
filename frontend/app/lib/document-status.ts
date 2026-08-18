import type { DocumentStatus } from "./api-types";

/** 인덱싱 상태 재조회 간격. 인덱싱은 보통 수 초 안에 끝나므로 짧게 두되 요청이 과하지 않은 값으로 잡는다. */
export const DOCUMENT_STATUS_POLL_INTERVAL_MS = 2000;

/**
 * 처리 중 버전이 남아 있으면 인덱싱이 진행 중이므로 상태를 계속 조회해야 한다.
 *
 * <p>백엔드는 UPLOADED·PARSING·CHUNKED·EMBEDDING Version만 processingVersion으로 반환한다.
 * 따라서 INDEXED로 끝나든 FAILED로 끝나든 processingVersion이 사라지면서 조회가 멈춘다.
 * documentStatus 대신 이 값을 기준으로 삼아야 재인덱싱처럼 문서가 INDEXED를 유지하는
 * 경우에도 진행 중임을 인식할 수 있다.
 */
export function isDocumentProcessing(status: DocumentStatus | null): boolean {
  return status !== null && status.processingVersion !== null;
}
