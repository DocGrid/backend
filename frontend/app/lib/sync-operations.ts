import type { SyncIssueAdmin } from "./api-types";

const issueCopy: Record<string, { title: string; summary: string; manualAction: string }> = {
  MISSING_JOB: {
    title: "처리 작업이 사라짐",
    summary: "처리 중인 문서 버전에 이어서 실행할 인덱싱 작업이 없습니다.",
    manualAction: "문서 버전 상태와 최근 Job을 확인한 뒤 재색인을 요청하세요.",
  },
  MISSING_CHUNKS: {
    title: "검색용 문서 조각이 없음",
    summary: "처리된 문서 버전에 검색과 임베딩의 기준이 되는 Chunk가 없습니다.",
    manualAction: "본문 추출 결과를 확인하고 문서를 다시 인덱싱하세요.",
  },
  MISSING_EMBEDDINGS: {
    title: "검색 벡터가 일부 부족함",
    summary: "현재 문서의 일부 Chunk가 활성 검색 Vector와 연결되지 않았습니다.",
    manualAction: "비활성 Embedding 행의 상태와 실패 원인을 확인한 뒤 재색인하세요.",
  },
  MODEL_MISMATCH: {
    title: "Embedding 모델이 다름",
    summary: "문서 Vector가 현재 활성 모델이 아닌 다른 모델로 생성되어 있습니다.",
    manualAction: "활성 모델 설정을 확인하고 현재 모델로 문서를 다시 인덱싱하세요.",
  },
  INVALID_CURRENT_VERSION: {
    title: "현재 문서 버전 연결이 잘못됨",
    summary: "문서가 가리키는 현재 버전과 실제 인덱싱 완료 버전이 일치하지 않습니다.",
    manualAction: "문서와 버전 상태를 확인한 뒤 currentVersion 연결을 복구하세요.",
  },
  STALLED_VERSION: {
    title: "문서 처리가 오래 멈춰 있음",
    summary: "문서 버전이 처리 중 상태에서 허용 시간을 넘겨 진행되지 않고 있습니다.",
    manualAction: "Worker와 Job Lease를 확인하고 실패 Job이면 재처리하세요.",
  },
  DELETED_DOCUMENT_RESIDUE: {
    title: "삭제 문서의 검색 데이터가 남음",
    summary: "삭제된 문서에 활성 검색 Vector가 남아 있습니다.",
    manualAction: "문서 삭제 Event와 Vector 정리 상태를 확인하세요.",
  },
  ORPHANED_DATA: {
    title: "소유 대상을 잃은 데이터가 있음",
    summary: "문서나 버전과 연결되지 않는 Job·Chunk·Vector 데이터가 발견됐습니다.",
    manualAction: "연결이 끊긴 원인을 확인한 뒤 잔여 데이터를 수동 정리하세요.",
  },
};

const evidenceLabels: Record<string, string> = {
  activeEmbeddingCount: "활성 Vector 수",
  currentModelEmbeddingCount: "현재 모델 Vector 수",
  allModelEmbeddingCount: "전체 모델 Vector 수",
  chunkCount: "Chunk 수",
  liveJob: "실행 중 Job",
  versionStatus: "문서 버전 상태",
  currentVersion: "현재 버전",
  currentVersionStatus: "현재 버전 상태",
  sameDocument: "같은 문서 연결",
  updatedAt: "마지막 변경 시각",
  updatedAfter: "정상 처리 기준 시각",
};

export function syncIssueTitle(issueType: string): string {
  return issueCopy[issueType]?.title ?? issueType;
}

export function syncIssueSummary(issueType: string): string {
  return issueCopy[issueType]?.summary ?? "원장과 검색용 파생 데이터가 일치하지 않습니다.";
}

export function syncIssueAction(issue: SyncIssueAdmin): string {
  if (issue.repairable) return "안전 복구를 요청하면 기존 처리 흐름으로 재색인 Event를 생성합니다.";

  if (issue.issueType === "MISSING_EMBEDDINGS") {
    const actual = parseSyncEvidence(issue.actualJson);
    const active = numberEvidence(actual, "활성 Vector 수");
    const currentModel = numberEvidence(actual, "현재 모델 Vector 수");
    if (active != null && currentModel != null && currentModel > active) {
      return `현재 모델 Vector ${currentModel}개 중 ${active}개만 활성 상태라 자동 재색인하지 않습니다. 비활성 Vector의 원인을 먼저 확인하세요.`;
    }
  }

  return issueCopy[issue.issueType]?.manualAction
    ?? "자동 변경이 안전하지 않아 상세 근거를 확인한 뒤 수동 조치해야 합니다.";
}

export function syncIssueStatusLabel(status: string): string {
  return ({ OPEN: "조치 필요", REPAIRING: "복구 진행 중", RESOLVED: "해결됨", IGNORED: "관리자 무시" } as Record<string, string>)[status] ?? status;
}

export function syncIssueSeverityLabel(severity: string): string {
  return ({ CRITICAL: "검색 영향 큼", ERROR: "오류", WARNING: "확인 필요", INFO: "참고" } as Record<string, string>)[severity] ?? severity;
}

export function syncEventTitle(eventType: string): string {
  return ({
    DOCUMENT_VERSION_CREATED: "새 문서 버전 처리",
    DOCUMENT_REINDEX_REQUESTED: "문서 재색인 요청",
    DOCUMENT_DELETED: "삭제 문서 정리",
    PERMISSION_CACHE_REFRESH_REQUESTED: "문서 권한 갱신",
    EMBEDDING_MODEL_ACTIVATED: "Embedding 모델 전환",
  } as Record<string, string>)[eventType] ?? eventType;
}

export function syncEventStatusLabel(status: string): string {
  return ({ PENDING: "전달 대기", PROCESSING: "처리 중", PROCESSED: "완료", FAILED: "최종 실패" } as Record<string, string>)[status] ?? status;
}

export function parseSyncEvidence(json: string | null): Array<{ label: string; value: string }> {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json) as Record<string, unknown>;
    return Object.entries(parsed).map(([key, value]) => ({
      label: evidenceLabels[key] ?? key,
      value: formatEvidenceValue(value),
    }));
  } catch {
    return [{ label: "원본 값", value: json }];
  }
}

function numberEvidence(evidence: Array<{ label: string; value: string }>, label: string): number | null {
  const value = evidence.find((item) => item.label === label)?.value;
  if (value == null || !/^\d+$/.test(value)) return null;
  return Number(value);
}

function formatEvidenceValue(value: unknown): string {
  if (typeof value === "boolean") return value ? "있음" : "없음";
  if (value === null) return "없음";
  return String(value);
}
