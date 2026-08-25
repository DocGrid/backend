"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { apiRequest, errorMessage, toQuery } from "../lib/api";
import type { AdminUser, DashboardSummary, Department, IndexingAttempt, IndexingEvent, IndexingJob, ManualRetryEligibility, PageResponse, RetryAllJobsResult, SyncAdminSummary, SyncEventAdmin, SyncIssueAdmin, SyncReconciliationAdmin, UserRoleResponse, Worker } from "../lib/api-types";
import { formatIndexingDuration, indexingAttemptStatusLabel, indexingEventDescription, indexingEventTitle, indexingEventTone, indexingJobDescription, indexingJobDurationMs, indexingJobResultTitle, indexingJobStatusLabel, indexingJobTone, indexingStatusLabel } from "../lib/indexing-job-view";
import { parseSyncEvidence, syncEventStatusLabel, syncEventTitle, syncIssueAction, syncIssueSeverityLabel, syncIssueStatusLabel, syncIssueSummary, syncIssueTitle } from "../lib/sync-operations";
import { useDashboardSocket } from "../lib/useDashboardSocket";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusPill, formatDate } from "../components/ui";

const manualRetryBlockedLabels: Record<Exclude<ManualRetryEligibility, "ELIGIBLE">, string> = {
  JOB_NOT_FAILED: "재처리 대상 아님",
  VERSION_NOT_FAILED: "버전 상태 확인 필요",
  DOCUMENT_DELETED: "삭제된 문서",
  DOCUMENT_STATUS_INVALID: "문서 상태 확인 필요",
  CURRENT_VERSION_INCONSISTENT: "현재 버전 확인 필요",
  SUPERSEDED_VERSION: "과거 버전",
  LIVE_JOB_EXISTS: "이미 처리 중",
  DATA_INCONSISTENT: "데이터 확인 필요",
};

function ManualRetryAction({ job, busy, onRetry, label = "재처리", className }: { job: IndexingJob; busy: boolean; onRetry: () => void; label?: string; className?: string }) {
  if (job.status !== "FAILED") return null;
  if (job.manualRetryEligibility !== "ELIGIBLE") {
    return <span className="retry-blocked" title="현재 상태에서는 이 Job을 재처리할 수 없습니다.">{manualRetryBlockedLabels[job.manualRetryEligibility]}</span>;
  }
  return <button className={className} disabled={busy} onClick={onRetry}>{label}</button>;
}

export function DashboardPage({ notify }: { notify: (message: string) => void }) {
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [failedJobs, setFailedJobs] = useState<IndexingJob[]>([]);
  const [syncSummary, setSyncSummary] = useState<SyncAdminSummary | null>(null);
  const [syncEvents, setSyncEvents] = useState<SyncEventAdmin[]>([]);
  const [syncIssues, setSyncIssues] = useState<SyncIssueAdmin[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [syncBusyKey, setSyncBusyKey] = useState("");
  const [selectedSyncIssue, setSelectedSyncIssue] = useState<SyncIssueAdmin | null>(null);
  const [ignoreTarget, setIgnoreTarget] = useState<SyncIssueAdmin | null>(null);
  const [ignoreReason, setIgnoreReason] = useState("");
  const [reconciliationResult, setReconciliationResult] = useState<SyncReconciliationAdmin | null>(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [metrics, jobs, sync, events, issues] = await Promise.all([
        apiRequest<DashboardSummary>("/admin/dashboard/summary"),
        apiRequest<PageResponse<IndexingJob>>("/admin/indexing-jobs?status=FAILED&page=0&size=5"),
        apiRequest<SyncAdminSummary>("/admin/sync/summary"),
        apiRequest<PageResponse<SyncEventAdmin>>("/admin/sync/events?size=8"),
        apiRequest<PageResponse<SyncIssueAdmin>>("/admin/sync/issues?size=8"),
      ]);
      setSummary(metrics);
      setFailedJobs(jobs.content);
      setSyncSummary(sync);
      setSyncEvents(events.content);
      setSyncIssues(issues.content);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const initialTimer = window.setTimeout(() => void load(), 0);
    const timer = window.setInterval(() => void load(), 30_000);
    return () => {
      window.clearTimeout(initialTimer);
      window.clearInterval(timer);
    };
  }, [load]);

  // /topic/dashboard push가 오면 30초를 기다리지 않고 즉시 재조회한다. 연결이 끊기면 위 폴링이 fallback 역할을 한다.
  const connection = useDashboardSocket(load);

  async function retryAll() {
    setBusy(true);
    try {
      const result = await apiRequest<RetryAllJobsResult>("/admin/embedding-jobs/retry-all", { method: "POST" });
      notify(result.message || `${result.retriedCount}개 Job을 재처리했습니다.`);
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function retry(jobId: number) {
    setBusy(true);
    try { await apiRequest(`/admin/embedding-jobs/${jobId}/retry`, { method: "POST" }); notify(`Job #${jobId}을 재처리 대기열에 넣었습니다.`); await load(); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function runSyncCommand(busyKey: string, path: string, successMessage: string, body?: Record<string, unknown>): Promise<boolean> {
    setSyncBusyKey(busyKey);
    setError("");
    try {
      await apiRequest(path, { method: "POST", ...(body === undefined ? {} : { body }) });
      notify(successMessage);
      await load();
      return true;
    } catch (reason) {
      setError(errorMessage(reason));
      return false;
    }
    finally { setSyncBusyKey(""); }
  }

  async function runReconciliation(cursor: number) {
    setSyncBusyKey("reconcile");
    setError("");
    try {
      // 1. 한 번의 검사가 처리한 범위를 보존해 다음 Cursor를 운영자가 이어서 실행할 수 있게 한다.
      const result = await apiRequest<SyncReconciliationAdmin>("/admin/sync/reconcile", {
        method: "POST",
        body: { mode: "REPAIR", cursor },
      });
      setReconciliationResult(result);
      notify(`${result.scannedCount}개 문서 버전을 검사하고 ${result.detectedCount}개 이상을 찾았습니다.`);
      // 2. 검사와 안전 복구 요청으로 변경된 요약·Event·Issue를 같은 화면에 즉시 반영한다.
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setSyncBusyKey(""); }
  }

  function openIgnoreSyncIssue(issue: SyncIssueAdmin) {
    setIgnoreTarget(issue);
    setIgnoreReason("");
  }

  async function confirmIgnoreSyncIssue(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!ignoreTarget || !ignoreReason.trim()) return;
    const succeeded = await runSyncCommand(
      `ignore-${ignoreTarget.issueId}`,
      `/admin/sync/issues/${ignoreTarget.issueId}/ignore`,
      `이상 #${ignoreTarget.issueId}를 사유와 함께 무시 처리했습니다.`,
      { reason: ignoreReason.trim() },
    );
    if (succeeded) {
      setIgnoreTarget(null);
      setSelectedSyncIssue(null);
      setIgnoreReason("");
    }
  }

  async function repairSyncIssue(issue: SyncIssueAdmin) {
    const succeeded = await runSyncCommand(
      `repair-${issue.issueId}`,
      `/admin/sync/issues/${issue.issueId}/repair`,
      `이상 #${issue.issueId}의 재색인 Event를 생성했습니다.`,
    );
    if (succeeded) setSelectedSyncIssue(null);
  }

  const expectedEvidence = selectedSyncIssue ? parseSyncEvidence(selectedSyncIssue.expectedJson) : [];
  const actualEvidence = selectedSyncIssue ? parseSyncEvidence(selectedSyncIssue.actualJson) : [];
  const openIssueCount = syncIssues.filter((issue) => issue.status === "OPEN").length;

  // vinext serves these operational links as full route requests.
  /* eslint-disable @next/next/no-html-link-for-pages */
  return <section className="content page-view sync-dashboard">
    <PageHeading kicker="RAGOPS · SYNC CONTROL PLANE" title="운영 현황" description="문서 변경 전달과 검색 데이터 상태를 확인하고 필요한 조치를 실행하세요." actions={<div className="sync-page-actions"><div className={`live-indicator connection-${connection.toLowerCase()}`}><span /> {connection === "LIVE" ? "실시간 연결" : connection === "CONNECTING" ? "연결 중" : "30초 자동 갱신"}</div><button className="secondary-button" disabled={loading} onClick={() => void load()}>새로고침</button><button className="primary-button" disabled={Boolean(syncBusyKey)} onClick={() => void runReconciliation(0)}>{syncBusyKey === "reconcile" ? "검사 중…" : "상태 검사 실행"}</button></div>} />
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading && !summary ? <LoadingState /> : null}
    {summary ? <><div className="metric-grid"><Metric label="전체 문서" value={summary.documents.total} note="soft-delete 제외" /><Metric label="검색 가능" value={summary.documents.searchable} note="INDEXED" tone="success-metric" /><Metric label="인덱싱 대기" value={summary.documents.pendingIndex} note="UPLOADED · INDEXING" /><Metric label="최근 24시간 검색" value={summary.search.recent24hCount} note="search.recent24hCount" /></div><div className="metric-grid job-metrics"><Metric label="대기" value={summary.jobs.pending} note="PENDING" /><Metric label="처리 중" value={summary.jobs.processing} note="PROCESSING" /><div className="metric-card danger-metric"><span>누적 실패</span><b>{summary.jobs.failed.toLocaleString()}</b><button disabled={busy || summary.jobs.failed === 0} onClick={() => void retryAll()}>재처리 가능 Job 요청</button></div><Metric label="평균 처리 시간" value={summary.jobs.avgProcessMs ?? "—"} note="ms · Queue 대기 제외" /><Metric label="정상 Worker" value={`${summary.workers.activeCount} / ${summary.workers.totalCount}`} note="ACTIVE · IDLE" /></div><div className="dashboard-grid"><div className="panel-card"><div className="panel-heading"><div><h2>최근 실패 Job</h2><p>클릭하면 Attempt와 Event를 추적할 수 있습니다.</p></div><a href="/admin/indexing-jobs" target="_top">전체 보기 →</a></div>{failedJobs.length ? <div className="mini-table failed-jobs">{failedJobs.map((job) => <div key={job.jobId}><a href={`/admin/indexing-jobs/${job.jobId}`} target="_top">#{job.jobId}</a><span>{job.documentTitle}</span><code>{job.errorCode ?? "—"}</code><span>{job.retryCount}/{job.maxRetryCount}</span><ManualRetryAction job={job} busy={busy} onRetry={() => void retry(job.jobId)} /></div>)}</div> : <EmptyState symbol="✓" title="실패한 Job이 없습니다" description="현재 재처리할 작업이 없습니다." />}</div><div className="panel-card"><div className="panel-heading"><div><h2>운영 경계</h2><p>대시보드 API가 제공하는 현재 Snapshot</p></div></div><div className="tool-list"><div><code>Documents</code><span>전체, 검색 가능, 인덱싱 대기</span></div><div><code>Jobs</code><span>대기, 처리 중, 누적 실패, 평균 처리 시간</span></div><div><code>Workers</code><span>정상 Worker와 전체 Worker 수</span></div></div></div></div></> : null}
    {syncSummary ? <>
      <div className="sync-section-heading"><div><span className="page-kicker">DOCUMENT SEARCH PIPELINE</span><h2>문서 변경 전달과 검색 데이터 상태</h2></div><p>마지막 갱신 {formatDate(syncSummary.capturedAt)} · 마지막 처리 Event {syncSummary.events.lastProcessedEventId?.slice(0, 8) ?? "—"}</p></div>
      <div className="sync-operator-guide" aria-label="운영 순서 안내">
        <div><b>1</b><span><strong>변경 전달 확인</strong><small>문서 변경이 Worker로 정상 전달되는지 확인합니다.</small></span></div>
        <div><b>2</b><span><strong>검색 데이터 검사</strong><small>문서 원장과 Chunk·Vector가 일치하는지 검사합니다.</small></span></div>
        <div><b>3</b><span><strong>근거 확인 후 조치</strong><small>안전 복구만 실행하고 나머지는 원인을 먼저 확인합니다.</small></span></div>
      </div>
      <div className={`sync-current-action ${(syncSummary.events.failedCount > 0 || syncSummary.issues.openCount > 0) ? "needs-action" : "healthy"}`}>
        <span>{syncSummary.events.failedCount > 0 ? "!" : syncSummary.issues.openCount > 0 ? "?" : "✓"}</span>
        <div>
          <strong>{syncSummary.events.failedCount > 0 ? `먼저 변경 전달 실패 ${syncSummary.events.failedCount}건을 확인하세요.` : syncSummary.issues.openCount > 0 ? `검색 데이터 이상 ${syncSummary.issues.openCount}건의 근거를 확인하세요.` : "지금 바로 조치할 문제가 없습니다."}</strong>
          <p>{syncSummary.events.failedCount > 0 ? "아래 변경 전달 기록에서 실패 원인을 확인한 뒤 다시 전달할 수 있습니다." : syncSummary.issues.openCount > 0 ? "아래 이상 항목의 상세 보기에서 자동 복구 가능 여부와 다음 조치를 확인할 수 있습니다." : "실시간 연결 또는 30초 자동 갱신으로 상태를 계속 확인합니다."}</p>
        </div>
      </div>
      <div className="metric-grid sync-metrics">
        <div className="metric-card"><span>변경 전달 대기</span><b>{formatMetric(syncSummary.events.pendingCount)}</b><small>가장 오래 기다린 시간 {formatAge(syncSummary.events.oldestPendingAgeSeconds)}</small></div>
        <div className="metric-card"><span>변경 전달 중</span><b>{formatMetric(syncSummary.events.processingCount)}</b><small>현재 Worker가 처리 중</small></div>
        <div className={`metric-card ${syncSummary.events.failedCount > 0 ? "danger-metric" : "success-metric"}`}><span>변경 전달 실패</span><b>{formatMetric(syncSummary.events.failedCount)}</b><small>최근 24시간 {formatMetric(syncSummary.events.failedLast24hCount)}건</small></div>
        <div className="metric-card success-metric"><span>24시간 전달 성공률</span><b>{syncSummary.events.successRateLast24h == null ? "—" : `${syncSummary.events.successRateLast24h}%`}</b><small>성공 {formatMetric(syncSummary.events.processedLast24hCount)} · 재시도 {formatMetric(syncSummary.events.retriedLast24hCount)}</small></div>
        <div className={`metric-card ${syncSummary.issues.openCount > 0 ? "danger-metric" : "success-metric"}`}><span>검색 데이터 이상</span><b>{formatMetric(syncSummary.issues.openCount)}</b><small>복구 진행 중 {formatMetric(syncSummary.issues.repairingCount)}</small></div>
        <div className="metric-card success-metric"><span>24시간 자동 복구</span><b>{formatMetric(syncSummary.issues.autoResolvedLast24hCount)}</b><small>실패 {formatMetric(syncSummary.issues.failedRepairCount)}</small></div>
        <div className="metric-card"><span>마지막 상태 검사</span><b className="metric-status">{syncSummary.reconciliation?.status === "COMPLETED" ? "완료" : syncSummary.reconciliation?.status === "FAILED" ? "실패" : syncSummary.reconciliation?.status ?? "미실행"}</b><small>{syncSummary.reconciliation ? `${syncSummary.reconciliation.scannedCount}개 확인 · ${syncSummary.reconciliation.detectedCount}개 발견` : "실행 이력 없음"}</small></div>
        <div className="metric-card"><span>재색인 요청</span><b>{formatMetric(syncSummary.reconciliation?.repairRequestedCount)}</b><small>마지막 검사에서 안전 복구 요청</small></div>
      </div>
      {reconciliationResult ? <div className="sync-reconcile-result" role="status">
        <span className="sync-result-symbol">✓</span>
        <div><strong>상태 검사 결과</strong><p>{reconciliationResult.scannedCount}개 문서 버전을 확인해 {reconciliationResult.detectedCount}개 이상을 발견했고, {reconciliationResult.repairRequestedCount}개 재색인을 요청했습니다.</p></div>
        {reconciliationResult.hasMore ? <button className="secondary-button" disabled={Boolean(syncBusyKey)} onClick={() => void runReconciliation(reconciliationResult.endCursor)}>{syncBusyKey === "reconcile" ? "검사 중…" : "다음 묶음 검사"}</button> : <span className="sync-result-complete">전체 범위 확인 완료</span>}
      </div> : null}
      {(syncSummary.events.failedCount > 0 || syncSummary.issues.failedRepairCount > 0) && <div className="alert-row">
        {syncSummary.events.failedCount > 0 && <div className="danger-alert">▣ <strong>Worker로 전달하지 못한 변경이 {syncSummary.events.failedCount}건 있습니다.</strong> 아래 기록에서 원인을 확인한 뒤 다시 전달하세요.</div>}
        {syncSummary.issues.failedRepairCount > 0 && <div className="warning-alert">⚠ <strong>자동 복구에 실패한 검색 데이터 이상이 {syncSummary.issues.failedRepairCount}건 있습니다.</strong> 상세 근거와 관련 Event를 확인하세요.</div>}
      </div>}
      <div className="dashboard-grid sync-operations-grid">
        <div className="panel-card">
          <div className="panel-heading"><div><h2>최근 변경 전달 기록</h2><p>문서 변경이 Worker까지 전달됐는지 확인합니다.</p></div><span className="panel-count">최근 {syncEvents.length}건</span></div>
          <div className="mini-table sync-event-table">
            {syncEvents.length === 0 ? <EmptyState symbol="✓" title="표시할 변경 기록이 없습니다" description="전달할 문서 변경이 없거나 기록을 기다리는 중입니다." /> : syncEvents.map((event) => <div key={event.eventId}>
              <code>{event.eventId.slice(0, 8)}</code>
              <span><strong>{syncEventTitle(event.eventType)}</strong><small>{event.eventType} · {event.aggregateType} #{event.aggregateId ?? "—"}</small></span>
              <span className={`sync-readable-status status-${event.status.toLowerCase()}`}><strong>{syncEventStatusLabel(event.status)}</strong><small>{event.status}</small></span>
              <span className="sync-retry-count"><strong>{event.retryCount} / {event.maxRetryCount}</strong><small>시도 / 최대</small></span>
              <span>{event.lastErrorCode ?? formatDate(event.occurredAt)}</span>
              {event.status === "FAILED" ? <button className="retry-button" disabled={Boolean(syncBusyKey)} onClick={() => void runSyncCommand(`event-${event.eventId}`, `/admin/sync/events/${event.eventId}/retry`, `${event.eventId.slice(0, 8)} 변경을 다시 전달하도록 요청했습니다.`)}>{syncBusyKey === `event-${event.eventId}` ? "요청 중" : "다시 전달"}</button> : <span />}
            </div>)}
          </div>
        </div>
        <div className="panel-card sync-issue-panel">
          <div className="panel-heading"><div><h2>검색 데이터 이상</h2><p>문서 원장과 검색용 Chunk·Vector의 차이를 보여줍니다.</p></div><span className={`panel-count ${openIssueCount > 0 ? "danger" : ""}`}>조치 필요 {openIssueCount}건</span></div>
          <div className="sync-issue-list">
            {syncIssues.length === 0 ? <EmptyState symbol="✓" title="검색 데이터 이상이 없습니다" description="최근 검사에서 문서 원장과 검색 데이터가 일치합니다." /> : syncIssues.map((issue) => <article className={`sync-issue-card severity-${issue.severity.toLowerCase()}`} key={issue.issueId}>
              <div className="sync-issue-card-head">
                <span className="sync-issue-number">#{issue.issueId}</span>
                <div><strong>{syncIssueTitle(issue.issueType)}</strong><small>{issue.issueType} · 문서 {issue.documentId ?? "전체"} · {formatDate(issue.lastDetectedAt)}</small></div>
                <span className={`sync-label severity-${issue.severity.toLowerCase()}`}>{syncIssueSeverityLabel(issue.severity)}</span>
                <span className={`sync-label status-${issue.status.toLowerCase()}`}>{syncIssueStatusLabel(issue.status)}</span>
              </div>
              <p className="sync-issue-summary">{syncIssueSummary(issue.issueType)}</p>
              <div className={`sync-issue-guidance ${issue.repairable ? "repairable" : "manual"}`}>
                <strong>{issue.repairable ? "안전 복구 가능" : "자동 복구 불가"}</strong>
                <span>{syncIssueAction(issue)}</span>
              </div>
              <div className="sync-issue-card-actions">
                <button className="secondary-button" onClick={() => setSelectedSyncIssue(issue)}>상세 근거 보기</button>
                {issue.documentId ? <a className="secondary-button" href={`/documents/${issue.documentId}`} target="_top">문서 상태 보기</a> : null}
                {issue.status === "OPEN" && issue.repairable ? <button className="primary-button" disabled={Boolean(syncBusyKey)} onClick={() => void repairSyncIssue(issue)}>{syncBusyKey === `repair-${issue.issueId}` ? "요청 중…" : "안전 복구 요청"}</button> : null}
                {issue.status === "OPEN" ? <button className="danger-text-button" disabled={Boolean(syncBusyKey)} onClick={() => openIgnoreSyncIssue(issue)}>무시 처리</button> : null}
              </div>
            </article>)}
          </div>
        </div>
      </div>
    </> : null}
    {selectedSyncIssue && !ignoreTarget ? <div className="modal-layer" role="presentation">
      <div className="modal sync-issue-modal" role="dialog" aria-modal="true" aria-labelledby="sync-issue-detail-title">
        <div className="modal-header"><div><span className="modal-symbol">!</span><div><h2 id="sync-issue-detail-title">{syncIssueTitle(selectedSyncIssue.issueType)}</h2><p>이상 #{selectedSyncIssue.issueId} · {selectedSyncIssue.issueType}</p></div></div><button type="button" aria-label="상세 닫기" onClick={() => setSelectedSyncIssue(null)}>×</button></div>
        <p className="sync-modal-summary">{syncIssueSummary(selectedSyncIssue.issueType)}</p>
        <div className="sync-resource-grid">
          <div><span>문서 ID</span><strong>{selectedSyncIssue.documentId ?? "전체"}</strong></div>
          <div><span>문서 버전 ID</span><strong>{selectedSyncIssue.documentVersionId ?? "—"}</strong></div>
          <div><span>Embedding 모델 ID</span><strong>{selectedSyncIssue.embeddingModelId ?? "—"}</strong></div>
          <div><span>처음 발견</span><strong>{formatDate(selectedSyncIssue.detectedAt)}</strong></div>
          <div><span>마지막 확인</span><strong>{formatDate(selectedSyncIssue.lastDetectedAt)}</strong></div>
          <div><span>복구 시도</span><strong>{selectedSyncIssue.repairAttemptCount}회</strong></div>
        </div>
        <div className="sync-evidence-grid">
          <div><h3>정상이어야 하는 값</h3>{expectedEvidence.length ? <dl>{expectedEvidence.map((item) => <div key={item.label}><dt>{item.label}</dt><dd>{item.value}</dd></div>)}</dl> : <p>기대값 정보가 없습니다.</p>}</div>
          <div><h3>현재 확인된 값</h3>{actualEvidence.length ? <dl>{actualEvidence.map((item) => <div key={item.label}><dt>{item.label}</dt><dd>{item.value}</dd></div>)}</dl> : <p>현재값 정보가 없습니다.</p>}</div>
        </div>
        <div className={`sync-modal-guidance ${selectedSyncIssue.repairable ? "repairable" : "manual"}`}><strong>{selectedSyncIssue.repairable ? "이 화면에서 안전 복구할 수 있습니다." : "자동 복구가 안전하지 않습니다."}</strong><p>{syncIssueAction(selectedSyncIssue)}</p></div>
        <div className="modal-footer">
          <button type="button" className="secondary-button" onClick={() => setSelectedSyncIssue(null)}>닫기</button>
          {selectedSyncIssue.documentId ? <a className="secondary-button" href={`/documents/${selectedSyncIssue.documentId}`} target="_top">문서 상태 보기</a> : null}
          {selectedSyncIssue.status === "OPEN" ? <button type="button" className="danger-text-button" disabled={Boolean(syncBusyKey)} onClick={() => openIgnoreSyncIssue(selectedSyncIssue)}>무시 처리</button> : null}
          {selectedSyncIssue.status === "OPEN" && selectedSyncIssue.repairable ? <button type="button" className="primary-button" disabled={Boolean(syncBusyKey)} onClick={() => void repairSyncIssue(selectedSyncIssue)}>{syncBusyKey === `repair-${selectedSyncIssue.issueId}` ? "요청 중…" : "안전 복구 요청"}</button> : null}
        </div>
      </div>
    </div> : null}
    {ignoreTarget ? <div className="modal-layer" role="presentation">
      <form className="modal compact-modal sync-ignore-modal" role="dialog" aria-modal="true" aria-labelledby="sync-ignore-title" onSubmit={confirmIgnoreSyncIssue}>
        <div className="modal-header"><div><span className="modal-symbol sync-ignore-symbol">!</span><div><h2 id="sync-ignore-title">이 이상을 무시 처리할까요?</h2><p>#{ignoreTarget.issueId} · {syncIssueTitle(ignoreTarget.issueType)}</p></div></div><button type="button" aria-label="무시 처리 취소" onClick={() => setIgnoreTarget(null)}>×</button></div>
        <div className="sync-ignore-warning"><strong>데이터는 고쳐지지 않습니다.</strong><p>경고만 ‘관리자 무시’ 상태로 종료되며 자동 조치 대상에서 제외됩니다. 조사 결과와 무시해도 되는 이유를 남겨 주세요.</p></div>
        <label className="form-field">무시 사유<textarea rows={4} value={ignoreReason} onChange={(event) => setIgnoreReason(event.target.value)} placeholder="예: 이전 모델의 비활성 Vector가 남아 있으나 현재 검색 결과에는 영향이 없음을 확인함" required /></label>
        <div className="sync-ignore-meta"><span>문서 {ignoreTarget.documentId ?? "전체"}</span><span>마지막 확인 {formatDate(ignoreTarget.lastDetectedAt)}</span></div>
        <div className="modal-footer"><button type="button" className="secondary-button" onClick={() => setIgnoreTarget(null)}>취소</button><button className="danger-button" disabled={!ignoreReason.trim() || Boolean(syncBusyKey)}>{syncBusyKey === `ignore-${ignoreTarget.issueId}` ? "기록 중…" : "이유를 기록하고 무시"}</button></div>
      </form>
    </div> : null}
  </section>;
  /* eslint-enable @next/next/no-html-link-for-pages */
}

export function IndexingJobsPage({ notify }: { notify: (message: string) => void }) {
  const [data, setData] = useState<PageResponse<IndexingJob> | null>(null);
  const [status, setStatus] = useState("");
  const [documentId, setDocumentId] = useState("");
  const [workerId, setWorkerId] = useState("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try { setData(await apiRequest<PageResponse<IndexingJob>>(`/admin/indexing-jobs${toQuery({ status, documentId, workerId, page, size: 20 })}`)); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, [documentId, page, status, workerId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function retry(jobId: number) {
    setBusy(true);
    try { await apiRequest(`/admin/indexing-jobs/${jobId}/retry`, { method: "POST" }); notify(`Job #${jobId}을 재처리했습니다.`); await load(); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  return <section className="content page-view wide-page"><PageHeading kicker="INDEXING OPERATIONS" title="인덱싱 Job" description="상태·문서·Worker 기준으로 필터링하고 실패 작업을 재처리하세요." /><div className="toolbar job-filters"><span className="filter-label">필터</span><select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="">status: 전체</option>{["PENDING", "PROCESSING", "INDEXED", "FAILED", "CANCELED"].map((item) => <option key={item}>{item}</option>)}</select><input value={documentId} onChange={(event) => setDocumentId(event.target.value)} placeholder="documentId" type="number" /><input value={workerId} onChange={(event) => setWorkerId(event.target.value)} placeholder="workerId" type="number" /><span /><button onClick={() => { setStatus(""); setDocumentId(""); setWorkerId(""); setPage(0); }}>초기화</button><button className="filter-submit" onClick={() => void load()}>조회</button></div>{error ? <ErrorState message={error} onRetry={() => void load()} /> : null}{loading ? <LoadingState label="인덱싱 Job을 불러오는 중입니다." /> : null}{!loading && data && !data.content.length ? <EmptyState symbol="⚙" title="조건에 맞는 Job이 없습니다" description="필터를 바꾸어 다시 조회해 보세요." /> : null}{!loading && data?.content.length ? <div className="data-table jobs-table"><div className="data-row data-head"><span>JOB</span><span>상태</span><span>문서</span><span>버전</span><span>모델</span><span>WORKER</span><span>재시도</span><span>오류 코드</span><span>생성</span><span /></div>{data.content.map((job) => <div className="data-row" key={job.jobId}><a href={`/admin/indexing-jobs/${job.jobId}`} target="_top">#{job.jobId}</a><span><StatusPill value={job.status} /></span><span>{job.documentTitle}</span><span>v{job.documentVersionNo} · {job.documentVersionStatus}</span><span>{job.embeddingModelName}</span><span>{job.workerName ?? "—"}</span><span>{job.retryCount}/{job.maxRetryCount}</span><code>{job.errorCode ?? "—"}</code><span>{formatDate(job.createdAt)}</span>{job.status === "FAILED" ? <ManualRetryAction job={job} busy={busy} className="retry-button" onRetry={() => void retry(job.jobId)} /> : <a className="row-link" href={`/admin/indexing-jobs/${job.jobId}`} target="_top">→</a>}</div>)}<div className="pagination"><span>{data.totalElements}건 · {data.size}건씩</span><div><button disabled={data.first} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button><button className="active">{data.page + 1}</button><button disabled={data.last} onClick={() => setPage((current) => current + 1)}>다음</button></div></div></div> : null}</section>;
}

export function IndexingJobDetailPage({ jobId, notify }: { jobId: number; notify: (message: string) => void }) {
  const [job, setJob] = useState<IndexingJob | null>(null);
  const [attempts, setAttempts] = useState<IndexingAttempt[]>([]);
  const [events, setEvents] = useState<IndexingEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [detail, attemptPage, eventPage] = await Promise.all([apiRequest<IndexingJob>(`/admin/indexing-jobs/${jobId}`), apiRequest<PageResponse<IndexingAttempt>>(`/admin/indexing-jobs/${jobId}/attempts?page=0&size=20`), apiRequest<PageResponse<IndexingEvent>>(`/admin/indexing-jobs/${jobId}/events?page=0&size=20`)]);
      setJob(detail); setAttempts(attemptPage.content); setEvents(eventPage.content);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, [jobId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function retry() {
    setBusy(true);
    try { await apiRequest(`/admin/indexing-jobs/${jobId}/retry`, { method: "POST" }); notify(`Job #${jobId}을 재처리했습니다.`); await load(); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  const durationMs = job ? indexingJobDurationMs(job, attempts) : null;
  const tone = job ? indexingJobTone(job.status) : "neutral";
  const resultDetail = job?.status === "INDEXED"
    ? `${formatDate(job.completedAt)}에 검색 가능한 상태로 전환됐습니다.`
    : job?.status === "FAILED"
      ? `${formatDate(job.failedAt)}에 실패했습니다. ${job.errorCode ? `오류 코드 ${job.errorCode}` : "상세 이벤트를 확인하세요."}`
      : job?.status === "PROCESSING"
        ? `${formatDate(job.startedAt)}부터 ${job.workerName ?? "Worker"}가 처리하고 있습니다.`
        : job?.status === "PENDING"
          ? `${formatDate(job.createdAt)}에 생성되어 Worker 배정을 기다리고 있습니다.`
          : job?.status === "CANCELED"
            ? "취소된 작업은 다시 실행되지 않습니다."
            : "현재 작업 상태와 실행 기록을 확인하세요.";

  // vinext serves these operational links as full route requests.
  /* eslint-disable @next/next/no-html-link-for-pages */
  return <section className="content page-view wide-page indexing-job-detail">
    <div className="detail-back"><a href="/admin/indexing-jobs" target="_top">← 인덱싱 작업 목록</a></div>
    <PageHeading
      kicker="INDEXING JOB TRACE"
      title={`인덱싱 작업 #${jobId}`}
      description={job ? indexingJobDescription(job) : "문서 인덱싱의 현재 상태와 실행 기록을 확인하세요."}
      actions={job ? <><span className={`job-page-status ${tone}`}><strong>{indexingJobStatusLabel(job.status)}</strong><small>{job.status}</small></span><ManualRetryAction job={job} busy={busy} className="primary-button" label="이 작업 재처리" onRetry={() => void retry()} /></> : null}
    />
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading ? <LoadingState /> : null}
    {job ? <>
      <div className={`job-result-summary ${tone}`}>
        <span className="job-result-symbol">{job.status === "INDEXED" ? "✓" : job.status === "FAILED" ? "!" : job.status === "PROCESSING" ? "…" : "○"}</span>
        <div className="job-result-copy"><strong>{indexingJobResultTitle(job.status)}</strong><p>{resultDetail}</p></div>
        <dl>
          <div><dt>처리 시간</dt><dd>{formatIndexingDuration(durationMs)}</dd></div>
          <div><dt>실행 시도</dt><dd>{attempts.length}회</dd></div>
          <div><dt>자동 재시도</dt><dd>{job.retryCount}회</dd></div>
          <div><dt>담당 Worker</dt><dd>{job.workerName ?? "미배정"}</dd></div>
        </dl>
      </div>
      <div className="job-detail-grid">
        <div className="panel-card job-info">
          <div className="panel-heading"><div><h2>작업 정보</h2><p>문서·모델과 처리 시각을 확인합니다.</p></div><a href={`/documents/${job.documentId}`} target="_top">문서 상태 보기 →</a></div>
          <dl>
            <div><dt>문서</dt><dd>{job.documentTitle} <small>#{job.documentId}</small></dd></div>
            <div><dt>문서 버전</dt><dd>v{job.documentVersionNo} · {indexingStatusLabel(job.documentVersionStatus)}</dd></div>
            <div><dt>Embedding 모델</dt><dd>{job.embeddingModelName} · {job.embeddingModelVersion}</dd></div>
            <div><dt>우선순위</dt><dd>{job.priority}</dd></div>
            <div><dt>자동 재시도</dt><dd>{job.retryCount}회 / 최대 {job.maxRetryCount}회</dd></div>
            <div><dt>담당 Worker</dt><dd>{job.workerName ?? "아직 배정되지 않음"}</dd></div>
            <div><dt>작업 생성</dt><dd>{formatDate(job.createdAt)}</dd></div>
            <div><dt>처리 시작</dt><dd>{formatDate(job.startedAt)}</dd></div>
            {job.status === "PROCESSING" ? <div><dt>작업 점유 만료</dt><dd>{formatDate(job.lockExpiresAt)}</dd></div> : null}
            {job.status === "PENDING" && job.nextRetryAt ? <div><dt>다음 재시도</dt><dd>{formatDate(job.nextRetryAt)}</dd></div> : null}
            {job.completedAt ? <div><dt>처리 완료</dt><dd>{formatDate(job.completedAt)}</dd></div> : null}
            {job.failedAt ? <div><dt>최종 실패</dt><dd>{formatDate(job.failedAt)}</dd></div> : null}
            <div><dt>최근 오류</dt><dd className={job.errorCode ? "job-error-code" : "job-no-error"}>{job.errorCode ?? "오류 없음"}</dd></div>
          </dl>
        </div>
        <div className="panel-card attempts">
          <div className="panel-heading"><div><h2>실행 시도</h2><p>{attempts.length ? `${attempts.length}회 실행 기록` : "아직 실행되지 않았습니다."}</p></div></div>
          {attempts.length ? <div className="mini-table attempt-table">
            <div className="table-labels"><span>#</span><span>결과</span><span>Worker</span><span>시작 시각</span><span>소요 시간</span><span>오류</span></div>
            {attempts.map((attempt) => <div key={attempt.attemptId}>
              <b>{attempt.attemptNo}</b>
              <span className={`attempt-status status-${attempt.status.toLowerCase()}`}><strong>{indexingAttemptStatusLabel(attempt.status)}</strong><small>{attempt.status}</small></span>
              <span>{attempt.workerName ?? `Worker #${attempt.workerId ?? "미배정"}`}</span>
              <span>{formatDate(attempt.startedAt)}</span>
              <strong>{formatIndexingDuration(attempt.durationMs)}</strong>
              {attempt.errorCode ? <code className="job-error-code">{attempt.errorCode}</code> : <span className="job-no-error">없음</span>}
            </div>)}
          </div> : <EmptyState symbol="○" title="실행 시도가 없습니다" description="Worker가 작업을 시작하면 실행 기록이 여기에 표시됩니다." />}
        </div>
        <div className="panel-card timeline-card">
          <div className="panel-heading"><div><h2>처리 과정</h2><p>최신순 · {events.length}개 이벤트</p></div></div>
          {events.length ? <div className="event-timeline">{events.map((event) => <div className={indexingEventTone(event.eventType)} key={event.eventId}>
            <i />
            <div>
              <div className="event-timeline-head"><span><strong>{indexingEventTitle(event.eventType)}</strong><code>{event.eventType}</code></span><time>{formatDate(event.occurredAt)}</time></div>
              <p>{indexingEventDescription(event)}</p>
              <small className="event-transition"><b>{indexingStatusLabel(event.fromStatus)}</b><span>→</span><b>{indexingStatusLabel(event.toStatus)}</b></small>
            </div>
          </div>)}</div> : <EmptyState symbol="○" title="처리 기록이 없습니다" description="상태가 변경되면 처리 과정이 여기에 표시됩니다." />}
        </div>
      </div>
    </> : null}
  </section>;
  /* eslint-enable @next/next/no-html-link-for-pages */
}

export function WorkersPage() {
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true); setError("");
    try { setWorkers(await apiRequest<Worker[]>("/admin/workers")); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const initialTimer = window.setTimeout(() => void load(), 0);
    const timer = window.setInterval(() => void load(), 30_000);
    return () => {
      window.clearTimeout(initialTimer);
      window.clearInterval(timer);
    };
  }, [load]);
  const counts = useMemo(() => workers.reduce<Record<string, number>>((result, worker) => ({ ...result, [worker.status]: (result[worker.status] ?? 0) + 1 }), {}), [workers]);

  return <section className="content page-view wide-page"><PageHeading kicker="INFRASTRUCTURE" title="Worker 모니터" description="Heartbeat 기준 Worker 상태와 실행 인스턴스를 확인하세요." actions={<div className="refresh-note">30초마다 자동 새로고침</div>} />{error ? <ErrorState message={error} onRetry={() => void load()} /> : null}{loading ? <LoadingState /> : null}{!loading ? <><div className="worker-metrics">{["ACTIVE", "IDLE", "DEAD", "STOPPED"].map((status) => <div className={status === "DEAD" ? "danger" : ""} key={status}><span>{status}</span><b>{counts[status] ?? 0}</b><small>{status === "DEAD" ? "heartbeat 끊김" : "현재 상태"}</small></div>)}</div>{workers.length ? <div className="data-table worker-table"><div className="data-row data-head"><span>WORKER</span><span>상태</span><span>호스트</span><span>IP</span><span>INSTANCE ID</span><span>마지막 HEARTBEAT</span><span>시작</span></div>{workers.map((worker) => <div className={`data-row ${worker.status === "DEAD" ? "danger-row" : ""}`} key={worker.workerId}><span><strong>{worker.workerName}</strong> #{worker.workerId}</span><span><StatusPill value={worker.status} /></span><span>{worker.hostName}</span><code>{worker.ipAddress}</code><code>{worker.instanceId}</code><span>{formatDate(worker.lastHeartbeatAt)}</span><span>{formatDate(worker.startedAt)}</span></div>)}</div> : <EmptyState symbol="▰" title="등록된 Worker가 없습니다" description="Worker가 등록되면 여기에 표시됩니다." />}</> : null}</section>;
}

export function AdminUsersPage({ notify }: { notify: (message: string) => void }) {
  const [data, setData] = useState<PageResponse<AdminUser> | null>(null);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [departmentId, setDepartmentId] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [selectedUserId, setSelectedUserId] = useState("");
  const [currentDepartmentId, setCurrentDepartmentId] = useState("");
  const [selectedDepartmentId, setSelectedDepartmentId] = useState("");
  const [result, setResult] = useState<UserRoleResponse | null>(null);
  const [deptResult, setDeptResult] = useState<AdminUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [deptBusy, setDeptBusy] = useState(false);
  const [revokingKey, setRevokingKey] = useState("");
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setData(await apiRequest<PageResponse<AdminUser>>(`/admin/users${toQuery({ keyword, departmentId, status, page, size: 20 })}`));
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, [departmentId, keyword, page, status]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  useEffect(() => {
    // Department options share the public signup contract and keep admin filters aligned with active departments.
    apiRequest<Department[]>("/departments", { auth: false }).then(setDepartments).catch(() => setDepartments([]));
  }, []);

  async function assign(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true); setError("");
    const form = new FormData(event.currentTarget);
    const userId = Number(selectedUserId);
    try {
      const response = await apiRequest<UserRoleResponse>(`/admin/users/${userId}/roles`, { method: "POST", body: { roleCode: String(form.get("roleCode")) } });
      setResult(response);
      notify(`${response.name} 사용자에게 역할을 부여했습니다.`);
      // Refresh role chips in the list after the command succeeds.
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function revokeRole(userId: number, roleCode: string) {
    if (!window.confirm(`이 사용자의 ${roleCode} 역할을 회수할까요?`)) return;
    const key = `${userId}:${roleCode}`;
    setRevokingKey(key); setError("");
    try {
      const response = await apiRequest<UserRoleResponse>(`/admin/users/${userId}/roles/${roleCode}`, { method: "DELETE" });
      notify(`${response.name} 사용자의 ${roleCode} 역할을 회수했습니다.`);
      // Refresh role chips in the list after the command succeeds.
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setRevokingKey(""); }
  }

  async function changeDepartment(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setDeptBusy(true); setError("");
    const userId = Number(selectedUserId);
    try {
      const response = await apiRequest<AdminUser>(`/admin/users/${userId}/department`, { method: "PATCH", body: { departmentId: Number(selectedDepartmentId) } });
      setDeptResult(response);
      setCurrentDepartmentId(response.departmentId != null ? String(response.departmentId) : "");
      notify(`${response.name} 사용자의 부서를 변경했습니다.`);
      // Refresh department column in the list after the command succeeds.
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setDeptBusy(false); }
  }

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nextKeyword = keywordInput.trim();
    setPage(0);
    if (nextKeyword === keyword && page === 0) void load();
    else setKeyword(nextKeyword);
  }

  function resetFilters() {
    setKeywordInput("");
    setKeyword("");
    setDepartmentId("");
    setStatus("");
    setPage(0);
  }

  function selectUser(userId: string, departmentId: number | null = null) {
    setSelectedUserId(userId);
    setCurrentDepartmentId(departmentId != null ? String(departmentId) : "");
    setSelectedDepartmentId(departmentId != null ? String(departmentId) : "");
  }

  return <section className="content page-view wide-page">
    <PageHeading kicker="ADMINISTRATION" title="사용자·역할" description="사용자를 검색·필터링하고 현재 역할을 확인한 뒤 새 역할을 부여하세요." />
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    <div className="users-layout">
      <div className="panel-card user-list">
        <form className="toolbar" onSubmit={search}><label className="toolbar-search"><span>⌕</span><input value={keywordInput} onChange={(event) => setKeywordInput(event.target.value)} placeholder="이름 또는 이메일 검색" /></label><select value={departmentId} onChange={(event) => { setDepartmentId(event.target.value); setPage(0); }}><option value="">부서 전체</option>{departments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}</select><select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}><option value="">상태 전체</option>{["ACTIVE", "INACTIVE", "LOCKED", "DELETED"].map((item) => <option key={item}>{item}</option>)}</select><span className="toolbar-count">{data?.totalElements ?? 0}명</span><button type="button" onClick={resetFilters}>초기화</button><button className="filter-submit">검색</button></form>
        {loading ? <LoadingState label="사용자 목록을 불러오는 중입니다." /> : null}
        {!loading && data && !data.content.length ? <EmptyState symbol="◎" title="조건에 맞는 사용자가 없습니다" description="검색어나 필터를 바꾸어 다시 조회해 보세요." /> : null}
        {!loading && data?.content.length ? <>
          <div className="mini-table users-table"><div className="table-labels"><span>사용자</span><span>부서</span><span>역할</span><span>상태</span><span /></div>{data.content.map((user) => <div key={user.userId}><span><strong>{user.name}</strong><small>#{user.userId} · {user.email}<br />가입 {formatDate(user.createdAt, false)}</small></span><span>{user.departmentName ?? "미지정"}</span><span className="role-chips">{user.roles.map((role) => <span key={role} className="role-chip-revoke"><StatusPill value={role} /><button type="button" title={`${role} 역할 회수`} disabled={revokingKey === `${user.userId}:${role}`} onClick={() => void revokeRole(user.userId, role)}>×</button></span>)}</span><StatusPill value={user.status} /><button onClick={() => selectUser(String(user.userId), user.departmentId)}>역할 관리</button></div>)}</div>
          <div className="pagination"><span>{data.totalElements}명 · {data.size}명씩</span><div><button disabled={data.first} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button><button className="active">{data.page + 1}</button><button disabled={data.last} onClick={() => setPage((current) => current + 1)}>다음</button></div></div>
        </> : null}
      </div>
      <div className="user-side">
        <form className="panel-card" onSubmit={assign}><div className="panel-heading"><div><h2>역할 부여</h2><p>목록에서 사용자를 선택하거나 ID를 입력하세요.</p></div></div><label className="form-field">사용자 ID<input name="userId" type="number" min="1" value={selectedUserId} onChange={(event) => selectUser(event.target.value)} required /></label><label className="form-field">역할<select name="roleCode" defaultValue="DOCUMENT_MANAGER"><option value="USER">USER</option><option value="DOCUMENT_MANAGER">DOCUMENT_MANAGER</option><option value="ADMIN">ADMIN</option></select></label><button className="primary-button full-button action-submit" disabled={!selectedUserId || busy}>{busy ? "부여 중…" : "역할 부여"}</button></form>
        {result ? <div className="panel-card result-card"><h2>부여 결과</h2><dl><div><dt>userId</dt><dd>{result.userId}</dd></div><div><dt>이름</dt><dd>{result.name}</dd></div><div><dt>이메일</dt><dd>{result.email}</dd></div><div><dt>역할</dt><dd className="role-chips">{result.roles.map((role) => <StatusPill value={role} key={role} />)}</dd></div></dl></div> : null}
        <form className="panel-card" onSubmit={changeDepartment}><div className="panel-heading"><div><h2>부서 변경</h2><p>왼쪽 목록에서 선택한 사용자 ID의 소속 부서를 변경하세요.</p></div></div><label className="form-field">사용자 ID<input name="userId" type="number" min="1" value={selectedUserId} onChange={(event) => selectUser(event.target.value)} required /></label><label className="form-field">부서<select name="departmentId" required value={selectedDepartmentId} onChange={(event) => setSelectedDepartmentId(event.target.value)}><option value="" disabled>부서를 선택하세요</option>{departments.map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label><button className="primary-button full-button action-submit" disabled={!selectedUserId || !selectedDepartmentId || selectedDepartmentId === currentDepartmentId || deptBusy}>{deptBusy ? "변경 중…" : currentDepartmentId && selectedDepartmentId && selectedDepartmentId !== currentDepartmentId ? `${departments.find((department) => String(department.id) === currentDepartmentId)?.name ?? "?"} → ${departments.find((department) => String(department.id) === selectedDepartmentId)?.name ?? "?"}로 변경` : "부서 변경"}</button></form>
        {deptResult ? <div className="panel-card result-card"><h2>변경 결과</h2><dl><div><dt>userId</dt><dd>{deptResult.userId}</dd></div><div><dt>이름</dt><dd>{deptResult.name}</dd></div><div><dt>부서</dt><dd>{deptResult.departmentName ?? "미지정"}</dd></div></dl></div> : null}
      </div>
    </div>
  </section>;
}

function Metric({ label, value, note, tone = "" }: { label: string; value: number | string; note: string; tone?: string }) {
  return <div className={`metric-card ${tone}`}><span>{label}</span><b>{typeof value === "number" ? value.toLocaleString() : value}</b><small>{note}</small></div>;
}

function formatMetric(value: number | null | undefined) {
  return value == null ? "—" : value.toLocaleString("ko-KR");
}

function formatAge(seconds: number | null) {
  if (seconds == null) return "대기 없음";
  if (seconds < 60) return `${seconds}초`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}분`;
  return `${Math.floor(seconds / 3600)}시간 ${Math.floor((seconds % 3600) / 60)}분`;
}
