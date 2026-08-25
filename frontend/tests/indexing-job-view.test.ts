import assert from "node:assert/strict";
import test from "node:test";

import type { IndexingAttempt, IndexingEvent, IndexingJob } from "../app/lib/api-types.ts";
import { formatIndexingDuration, indexingEventDescription, indexingEventTitle, indexingJobDescription, indexingJobDurationMs, indexingJobResultTitle, indexingJobStatusLabel, indexingStatusLabel } from "../app/lib/indexing-job-view.ts";

function job(overrides: Partial<IndexingJob> = {}): IndexingJob {
  return {
    jobId: 6,
    status: "INDEXED",
    manualRetryEligibility: "JOB_NOT_FAILED",
    priority: 0,
    retryCount: 0,
    maxRetryCount: 3,
    nextRetryAt: null,
    documentId: 8,
    documentTitle: "6월19일발표 대본",
    documentVersionId: 8,
    documentVersionNo: 1,
    documentVersionStatus: "INDEXED",
    embeddingModelId: 1,
    embeddingModelName: "BAAI/bge-m3",
    embeddingModelVersion: "1.0",
    workerId: 1,
    workerName: "indexing-worker",
    errorCode: null,
    lockedAt: "2026-08-25T18:38:00",
    lockExpiresAt: "2026-08-25T18:43:00",
    createdAt: "2026-08-25T18:37:58",
    startedAt: "2026-08-25T18:38:00",
    completedAt: "2026-08-25T18:38:11.707",
    failedAt: null,
    ...overrides,
  };
}

test("완료 작업은 실패 추적이 아니라 정상 완료 문구로 안내한다", () => {
  assert.equal(indexingJobDescription(job()), "검색 데이터 생성을 정상 완료한 작업입니다.");
  assert.equal(indexingJobResultTitle("INDEXED"), "인덱싱을 정상 완료했습니다.");
  assert.equal(indexingJobStatusLabel("INDEXED"), "완료");
});

test("처리 중 작업은 현재 Worker를 안내한다", () => {
  assert.equal(indexingJobDescription(job({ status: "PROCESSING", workerName: "worker-2" })), "worker-2가 현재 문서를 처리하고 있습니다.");
});

test("작업 처리 시간은 시작과 종료 시각으로 계산하고 읽기 쉽게 표시한다", () => {
  assert.equal(indexingJobDurationMs(job(), []), 11_707);
  assert.equal(formatIndexingDuration(11_707), "11.7초");
});

test("Chunk Event는 원본 메시지에서 생성 개수를 살려 한국어로 설명한다", () => {
  const event: IndexingEvent = {
    eventId: 3,
    eventType: "CHUNKED",
    fromStatus: "PARSING",
    toStatus: "CHUNKED",
    message: "Document Version Chunk 저장을 완료했습니다. versionId=8, chunkCount=9",
    occurredAt: "2026-08-25T18:38:05",
  };
  assert.equal(indexingEventTitle(event.eventType), "문서 조각 생성 완료");
  assert.equal(indexingEventDescription(event), "추출한 본문을 검색에 사용할 Chunk 9개로 나눴습니다.");
  assert.equal(indexingStatusLabel(event.toStatus), "문서 조각 생성 완료");
});

test("시각 정보가 없으면 Attempt 소요 시간을 합산한다", () => {
  const attempts = [{ durationMs: 500 }, { durationMs: 750 }] as IndexingAttempt[];
  assert.equal(indexingJobDurationMs(job({ startedAt: null, completedAt: null }), attempts), 1_250);
});
