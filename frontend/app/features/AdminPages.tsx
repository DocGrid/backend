"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import { apiRequest, errorMessage, toQuery } from "../lib/api";
import type { AdminUser, DashboardSummary, Department, IndexingAttempt, IndexingEvent, IndexingJob, ManualRetryEligibility, PageResponse, RetryAllJobsResult, SyncAdminSummary, SyncEventAdmin, SyncIssueAdmin, UserRoleResponse, Worker } from "../lib/api-types";
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

  async function runSyncCommand(busyKey: string, path: string, successMessage: string, body?: Record<string, unknown>) {
    setSyncBusyKey(busyKey);
    try {
      await apiRequest(path, { method: "POST", ...(body === undefined ? {} : { body }) });
      notify(successMessage);
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setSyncBusyKey(""); }
  }

  function ignoreSyncIssue(issue: SyncIssueAdmin) {
    const reason = window.prompt("이 Issue를 자동 조치하지 않는 이유를 입력하세요.");
    if (!reason?.trim()) return;
    void runSyncCommand(`ignore-${issue.issueId}`, `/admin/sync/issues/${issue.issueId}/ignore`, `Issue #${issue.issueId}를 감사 사유와 함께 무시했습니다.`, { reason: reason.trim() });
  }

  // vinext serves these operational links as full route requests.
  /* eslint-disable @next/next/no-html-link-for-pages */
  return <section className="content page-view sync-dashboard">
    <PageHeading kicker="RAGOPS · SYNC CONTROL PLANE" title="운영 현황" description="문서 인덱싱과 Outbox 원장, Vector 정합성을 한 화면에서 확인하세요." actions={<div className="sync-page-actions"><div className={`live-indicator connection-${connection.toLowerCase()}`}><span /> {connection === "LIVE" ? "실시간 연결" : connection === "CONNECTING" ? "연결 중" : "30초 자동 갱신"}</div><button className="secondary-button" disabled={loading} onClick={() => void load()}>새로고침</button><button className="primary-button" disabled={Boolean(syncBusyKey)} onClick={() => void runSyncCommand("reconcile", "/admin/sync/reconcile", "정합성 검사와 안전한 복구 요청을 완료했습니다.", { mode: "REPAIR", cursor: 0 })}>{syncBusyKey === "reconcile" ? "검사 중…" : "정합성 검사"}</button></div>} />
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading && !summary ? <LoadingState /> : null}
    {summary ? <><div className="metric-grid"><Metric label="전체 문서" value={summary.documents.total} note="soft-delete 제외" /><Metric label="검색 가능" value={summary.documents.searchable} note="INDEXED" tone="success-metric" /><Metric label="인덱싱 대기" value={summary.documents.pendingIndex} note="UPLOADED · INDEXING" /><Metric label="최근 24시간 검색" value={summary.search.recent24hCount} note="search.recent24hCount" /></div><div className="metric-grid job-metrics"><Metric label="대기" value={summary.jobs.pending} note="PENDING" /><Metric label="처리 중" value={summary.jobs.processing} note="PROCESSING" /><div className="metric-card danger-metric"><span>누적 실패</span><b>{summary.jobs.failed.toLocaleString()}</b><button disabled={busy || summary.jobs.failed === 0} onClick={() => void retryAll()}>재처리 가능 Job 요청</button></div><Metric label="평균 처리 시간" value={summary.jobs.avgProcessMs ?? "—"} note="ms · Queue 대기 제외" /><Metric label="정상 Worker" value={`${summary.workers.activeCount} / ${summary.workers.totalCount}`} note="ACTIVE · IDLE" /></div><div className="dashboard-grid"><div className="panel-card"><div className="panel-heading"><div><h2>최근 실패 Job</h2><p>클릭하면 Attempt와 Event를 추적할 수 있습니다.</p></div><a href="/admin/indexing-jobs" target="_top">전체 보기 →</a></div>{failedJobs.length ? <div className="mini-table failed-jobs">{failedJobs.map((job) => <div key={job.jobId}><a href={`/admin/indexing-jobs/${job.jobId}`} target="_top">#{job.jobId}</a><span>{job.documentTitle}</span><code>{job.errorCode ?? "—"}</code><span>{job.retryCount}/{job.maxRetryCount}</span><ManualRetryAction job={job} busy={busy} onRetry={() => void retry(job.jobId)} /></div>)}</div> : <EmptyState symbol="✓" title="실패한 Job이 없습니다" description="현재 재처리할 작업이 없습니다." />}</div><div className="panel-card"><div className="panel-heading"><div><h2>운영 경계</h2><p>대시보드 API가 제공하는 현재 Snapshot</p></div></div><div className="tool-list"><div><code>Documents</code><span>전체, 검색 가능, 인덱싱 대기</span></div><div><code>Jobs</code><span>대기, 처리 중, 누적 실패, 평균 처리 시간</span></div><div><code>Workers</code><span>정상 Worker와 전체 Worker 수</span></div></div></div></div></> : null}
    {syncSummary ? <>
      <div className="sync-section-heading"><div><span className="page-kicker">TRANSACTIONAL OUTBOX</span><h2>동기화 원장과 정합성</h2></div><p>마지막 갱신 {formatDate(syncSummary.capturedAt)} · 마지막 처리 Event {syncSummary.events.lastProcessedEventId?.slice(0, 8) ?? "—"}</p></div>
      <div className="metric-grid sync-metrics">
        <div className="metric-card"><span>Outbox 대기</span><b>{formatMetric(syncSummary.events.pendingCount)}</b><small>최대 지연 {formatAge(syncSummary.events.oldestPendingAgeSeconds)}</small></div>
        <div className="metric-card"><span>Dispatcher 처리 중</span><b>{formatMetric(syncSummary.events.processingCount)}</b><small>Lease 소유 Event</small></div>
        <div className={`metric-card ${syncSummary.events.failedCount > 0 ? "danger-metric" : "success-metric"}`}><span>Event 최종 실패</span><b>{formatMetric(syncSummary.events.failedCount)}</b><small>최근 24시간 {formatMetric(syncSummary.events.failedLast24hCount)}</small></div>
        <div className="metric-card success-metric"><span>24시간 처리 성공률</span><b>{syncSummary.events.successRateLast24h}%</b><small>성공 {formatMetric(syncSummary.events.processedLast24hCount)} · 재시도 {formatMetric(syncSummary.events.retriedLast24hCount)}</small></div>
        <div className={`metric-card ${syncSummary.issues.openCount > 0 ? "danger-metric" : "success-metric"}`}><span>미해결 정합성 Issue</span><b>{formatMetric(syncSummary.issues.openCount)}</b><small>복구 중 {formatMetric(syncSummary.issues.repairingCount)}</small></div>
        <div className="metric-card success-metric"><span>24시간 자동 복구</span><b>{formatMetric(syncSummary.issues.autoResolvedLast24hCount)}</b><small>실패 {formatMetric(syncSummary.issues.failedRepairCount)}</small></div>
        <div className="metric-card"><span>마지막 Reconciliation</span><b className="metric-status">{syncSummary.reconciliation?.status ?? "미실행"}</b><small>{syncSummary.reconciliation ? `${syncSummary.reconciliation.scannedCount}개 검사 · ${syncSummary.reconciliation.detectedCount}개 탐지` : "실행 이력 없음"}</small></div>
        <div className="metric-card"><span>복구 요청</span><b>{formatMetric(syncSummary.reconciliation?.repairRequestedCount)}</b><small>{syncSummary.reconciliation?.mode ?? "DRY_RUN / REPAIR"}</small></div>
      </div>
      {(syncSummary.events.failedCount > 0 || syncSummary.issues.failedRepairCount > 0) && <div className="alert-row">
        {syncSummary.events.failedCount > 0 && <div className="danger-alert">▣ <strong>최종 실패 Sync Event가 {syncSummary.events.failedCount}건 있습니다.</strong> 원인을 확인한 뒤 개별 재시도하세요.</div>}
        {syncSummary.issues.failedRepairCount > 0 && <div className="warning-alert">⚠ <strong>자동 복구에 실패한 Issue가 {syncSummary.issues.failedRepairCount}건 있습니다.</strong></div>}
      </div>}
      <div className="dashboard-grid sync-operations-grid">
        <div className="panel-card">
          <div className="panel-heading"><div><h2>최근 Sync Event</h2><p>Event ID로 장애 전후 처리 지점을 추적합니다.</p></div><code>GET /admin/sync/events</code></div>
          <div className="mini-table sync-event-table">
            {syncEvents.length === 0 ? <EmptyState symbol="✓" title="표시할 Event가 없습니다" description="Outbox가 비어 있거나 API 연결을 기다리는 중입니다." /> : syncEvents.map((event) => <div key={event.eventId}>
              <code>{event.eventId.slice(0, 8)}</code>
              <span><strong>{event.eventType}</strong><small>{event.aggregateType} #{event.aggregateId ?? "—"}</small></span>
              <StatusPill value={event.status} />
              <span>{event.retryCount} / {event.maxRetryCount}</span>
              <span>{event.lastErrorCode ?? formatDate(event.occurredAt)}</span>
              {event.status === "FAILED" ? <button className="retry-button" disabled={Boolean(syncBusyKey)} onClick={() => void runSyncCommand(`event-${event.eventId}`, `/admin/sync/events/${event.eventId}/retry`, `${event.eventId.slice(0, 8)} Event를 재시도 대기열에 추가했습니다.`)}>{syncBusyKey === `event-${event.eventId}` ? "처리 중" : "재시도"}</button> : <span />}
            </div>)}
          </div>
        </div>
        <div className="panel-card">
          <div className="panel-heading"><div><h2>정합성 Issue</h2><p>위험한 변경은 보고만 하고 관리자 판단을 기다립니다.</p></div><code>GET /admin/sync/issues</code></div>
          <div className="mini-table sync-issue-table">
            {syncIssues.length === 0 ? <EmptyState symbol="✓" title="열린 Issue가 없습니다" description="최근 검사에서 원장과 Vector 상태가 일치합니다." /> : syncIssues.map((issue) => <div key={issue.issueId}>
              <span><strong>#{issue.issueId} · {issue.issueType}</strong><small>document {issue.documentId ?? "GLOBAL"} · {formatDate(issue.lastDetectedAt)}</small></span>
              <StatusPill value={issue.severity} />
              <StatusPill value={issue.status} />
              <span className="sync-issue-actions">
                {issue.status === "OPEN" && issue.repairable && <button className="retry-button" disabled={Boolean(syncBusyKey)} onClick={() => void runSyncCommand(`repair-${issue.issueId}`, `/admin/sync/issues/${issue.issueId}/repair`, `Issue #${issue.issueId} 복구 Event를 생성했습니다.`)}>복구</button>}
                {issue.status === "OPEN" && <button className="danger-text" disabled={Boolean(syncBusyKey)} onClick={() => ignoreSyncIssue(issue)}>무시</button>}
              </span>
            </div>)}
          </div>
        </div>
      </div>
    </> : null}
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

  // vinext serves these operational links as full route requests.
  // eslint-disable-next-line @next/next/no-html-link-for-pages
  return <section className="content page-view wide-page"><div className="detail-back"><a href="/admin/indexing-jobs" target="_top">← 인덱싱 Job 목록</a><span>jobId {jobId}</span></div><PageHeading kicker="JOB TRACE" title={`Job #${jobId}`} description="실패 원인과 실행 시도를 Event 타임라인으로 추적하세요." actions={job ? <><StatusPill value={job.status} /><ManualRetryAction job={job} busy={busy} className="primary-button" label="이 Job 재처리" onRetry={() => void retry()} /></> : null} />{error ? <ErrorState message={error} onRetry={() => void load()} /> : null}{loading ? <LoadingState /> : null}{job ? <div className="job-detail-grid"><div className="panel-card job-info"><div className="panel-heading"><h2>Job 정보</h2><a href={`/documents/${job.documentId}`} target="_top">문서 보기 →</a></div><dl><div><dt>문서</dt><dd>{job.documentTitle} #{job.documentId}</dd></div><div><dt>버전</dt><dd>v{job.documentVersionNo} · {job.documentVersionStatus}</dd></div><div><dt>모델</dt><dd>{job.embeddingModelName} · {job.embeddingModelVersion}</dd></div><div><dt>priority</dt><dd>{job.priority}</dd></div><div><dt>재시도</dt><dd>{job.retryCount}/{job.maxRetryCount}</dd></div><div><dt>소유 Worker</dt><dd>{job.workerName ?? "—"}</dd></div><div><dt>Lease 만료</dt><dd>{formatDate(job.lockExpiresAt)}</dd></div><div><dt>errorCode</dt><dd><code>{job.errorCode ?? "—"}</code></dd></div></dl></div><div className="panel-card attempts"><div className="panel-heading"><div><h2>실행 시도</h2><p>/attempts</p></div></div>{attempts.length ? <div className="mini-table attempt-table"><div className="table-labels"><span>#</span><span>상태</span><span>WORKER</span><span>소요</span><span>오류 코드</span></div>{attempts.map((attempt) => <div key={attempt.attemptId}><b>{attempt.attemptNo}</b><StatusPill value={attempt.status} /><span>{attempt.workerName ?? `#${attempt.workerId ?? "—"}`}</span><span>{attempt.durationMs ? `${attempt.durationMs.toLocaleString()}ms` : "—"}</span><code>{attempt.errorCode ?? "—"}</code></div>)}</div> : <EmptyState symbol="○" title="Attempt가 없습니다" description="아직 실행 이력이 없습니다." />}</div><div className="panel-card timeline-card"><div className="panel-heading"><h2>이벤트 타임라인</h2><code>/events</code></div>{events.length ? <div className="event-timeline">{events.map((event) => <div className={event.toStatus === "FAILED" ? "danger" : event.toStatus === "INDEXED" ? "success" : "purple"} key={event.eventId}><i /><span><strong>{event.eventType} <small>{formatDate(event.occurredAt)}</small></strong><p>{event.fromStatus ?? "—"} → {event.toStatus ?? "—"}{event.message ? ` · ${event.message}` : ""}</p></span></div>)}</div> : <EmptyState symbol="○" title="Event가 없습니다" description="아직 상태 전이가 없습니다." />}</div></div> : null}</section>;
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
