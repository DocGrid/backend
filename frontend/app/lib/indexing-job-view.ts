import type { IndexingAttempt, IndexingEvent, IndexingJob } from "./api-types";

const eventCopy: Record<string, { title: string; description: string }> = {
  JOB_CREATED: { title: "인덱싱 작업 생성", description: "문서 버전을 처리할 작업이 대기열에 등록됐습니다." },
  LOCKED: { title: "Worker 작업 시작", description: "Worker가 작업을 가져가 처리 권한을 확보했습니다." },
  PARSE_STARTED: { title: "본문 분석 시작", description: "업로드된 파일에서 검색에 사용할 텍스트를 추출하기 시작했습니다." },
  PARSE_FAILED: { title: "본문 분석 실패", description: "파일에서 텍스트를 추출하는 중 오류가 발생했습니다." },
  CHUNKED: { title: "문서 조각 생성 완료", description: "추출한 본문을 검색에 사용할 Chunk로 나눴습니다." },
  EMBEDDING_STARTED: { title: "검색 Vector 생성 시작", description: "각 Chunk의 의미를 표현하는 검색 Vector 생성을 시작했습니다." },
  EMBEDDING_FAILED: { title: "검색 Vector 생성 실패", description: "검색 Vector를 생성하는 중 오류가 발생했습니다." },
  INDEXED: { title: "인덱싱 완료", description: "문서 버전이 검색 가능한 상태가 됐습니다." },
  LEASE_EXPIRED: { title: "Worker 작업 시간 초과", description: "Worker의 작업 점유 시간이 만료되어 다른 Worker가 이어받을 수 있습니다." },
  FAILED: { title: "인덱싱 최종 실패", description: "허용된 처리 또는 재시도를 마치지 못해 작업이 실패했습니다." },
  RETRY: { title: "자동 재시도 예약", description: "일시적인 오류로 작업을 다시 실행하도록 예약했습니다." },
  MANUAL_RETRY: { title: "관리자 재처리 요청", description: "관리자가 실패 작업을 다시 실행하도록 요청했습니다." },
};

const statusLabels: Record<string, string> = {
  PENDING: "대기",
  PROCESSING: "처리 중",
  UPLOADED: "업로드 완료",
  PARSING: "본문 분석 중",
  CHUNKED: "문서 조각 생성 완료",
  EMBEDDING: "검색 Vector 생성 중",
  INDEXED: "검색 가능",
  SUCCESS: "완료",
  FAILED: "실패",
  CANCELED: "취소됨",
};

export function indexingJobDescription(job: IndexingJob): string {
  if (job.status === "INDEXED") return "검색 데이터 생성을 정상 완료한 작업입니다.";
  if (job.status === "FAILED") return "실패 원인과 재처리 가능 여부를 확인하세요.";
  if (job.status === "PROCESSING") return `${job.workerName ?? "Worker"}가 현재 문서를 처리하고 있습니다.`;
  if (job.status === "PENDING") return "처리 가능한 Worker 배정을 기다리고 있습니다.";
  if (job.status === "CANCELED") return "더 이상 실행되지 않도록 취소된 작업입니다.";
  return "문서 인덱싱의 현재 상태와 실행 기록을 확인하세요.";
}

export function indexingJobResultTitle(status: string): string {
  return ({
    INDEXED: "인덱싱을 정상 완료했습니다.",
    FAILED: "인덱싱에 실패했습니다.",
    PROCESSING: "인덱싱을 처리하고 있습니다.",
    PENDING: "인덱싱 순서를 기다리고 있습니다.",
    CANCELED: "인덱싱 작업이 취소됐습니다.",
  } as Record<string, string>)[status] ?? "인덱싱 상태를 확인하세요.";
}

export function indexingJobStatusLabel(status: string): string {
  return ({ INDEXED: "완료", FAILED: "실패", PROCESSING: "처리 중", PENDING: "대기", CANCELED: "취소됨" } as Record<string, string>)[status]
    ?? status;
}

export function indexingJobTone(status: string): string {
  if (status === "INDEXED") return "success";
  if (status === "FAILED") return "danger";
  if (status === "PROCESSING") return "processing";
  return "neutral";
}

export function indexingEventTitle(eventType: string): string {
  return eventCopy[eventType]?.title ?? eventType;
}

export function indexingEventDescription(event: IndexingEvent): string {
  if (event.eventType === "CHUNKED") {
    const chunkCount = event.message?.match(/chunkCount=(\d+)/)?.[1];
    if (chunkCount) return `추출한 본문을 검색에 사용할 Chunk ${chunkCount}개로 나눴습니다.`;
  }
  return eventCopy[event.eventType]?.description ?? event.message ?? "인덱싱 상태가 변경됐습니다.";
}

export function indexingEventTone(eventType: string): string {
  if (["FAILED", "PARSE_FAILED", "EMBEDDING_FAILED"].includes(eventType)) return "danger";
  if (eventType === "INDEXED") return "success";
  if (["RETRY", "MANUAL_RETRY", "LEASE_EXPIRED"].includes(eventType)) return "warning";
  return "progress";
}

export function indexingStatusLabel(status: string | null): string {
  if (!status) return "시작";
  return statusLabels[status] ?? status;
}

export function indexingAttemptStatusLabel(status: string): string {
  return ({ SUCCESS: "완료", FAILED: "실패", PROCESSING: "진행 중" } as Record<string, string>)[status]
    ?? indexingStatusLabel(status);
}

export function indexingJobDurationMs(job: IndexingJob, attempts: IndexingAttempt[]): number | null {
  const terminalAt = job.completedAt ?? job.failedAt;
  if (job.startedAt && terminalAt) {
    const duration = new Date(terminalAt).getTime() - new Date(job.startedAt).getTime();
    if (Number.isFinite(duration) && duration >= 0) return duration;
  }
  const durations = attempts.map((attempt) => attempt.durationMs).filter((duration): duration is number => duration != null);
  return durations.length ? durations.reduce((total, duration) => total + duration, 0) : null;
}

export function formatIndexingDuration(durationMs: number | null): string {
  if (durationMs == null) return "—";
  if (durationMs < 1000) return `${durationMs}ms`;
  if (durationMs < 60_000) return `${(durationMs / 1000).toFixed(1)}초`;
  const minutes = Math.floor(durationMs / 60_000);
  const seconds = Math.floor((durationMs % 60_000) / 1000);
  return `${minutes}분 ${seconds}초`;
}
