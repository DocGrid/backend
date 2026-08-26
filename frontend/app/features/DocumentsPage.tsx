"use client";

// vinext production navigation uses full requests because its client router does not complete catch-all route transitions.
/* eslint-disable @next/next/no-html-link-for-pages */

import { useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, downloadBackendFile, errorMessage, previewBackendFile, toQuery } from "../lib/api";
import type { DocumentContent, DocumentDetail, DocumentStatus, DocumentSummary, IndexingJob, PageResponse, PermissionSummary, UpdateDocumentMetadataRequest } from "../lib/api-types";
import { DOCUMENT_STATUS_POLL_INTERVAL_MS, isDocumentProcessing } from "../lib/document-status";
import { EmptyState, ErrorState, LoadingState, Notice, PageHeading, StatusPill, formatBytes, formatDate } from "../components/ui";
import { useAuth } from "../components/AuthProvider";

const statuses = ["", "DRAFT", "UPLOADED", "INDEXING", "INDEXED", "FAILED", "ARCHIVED"];

export function DocumentsPage({ onUpload, refreshKey }: { onUpload: () => void; refreshKey: number }) {
  const [data, setData] = useState<PageResponse<DocumentSummary> | null>(null);
  const [status, setStatus] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setData(await apiRequest<PageResponse<DocumentSummary>>(`/api/documents${toQuery({ status, page, size: 20 })}`));
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setLoading(false);
    }
  }, [page, status]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  // 업로드는 Modal에서 끝나므로 접수 신호가 바뀔 때만 목록을 다시 읽는다.
  const loadedRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (loadedRefreshKey.current === refreshKey) return;
    loadedRefreshKey.current = refreshKey;
    void load();
  }, [load, refreshKey]);
  const visible = data?.content.filter((document) => document.title.toLowerCase().includes(search.toLowerCase())) ?? [];

  return <section className="content page-view">
    <PageHeading kicker="KNOWLEDGE BASE" title="문서" description="내가 읽을 수 있는 문서와 현재 인덱싱 상태를 확인하세요." actions={<button className="primary-button" onClick={onUpload}>＋ 문서 업로드</button>} />
    <div className="toolbar"><div className="toolbar-search"><span>⌕</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="현재 페이지 문서 검색" /></div><select aria-label="문서 상태" value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>{statuses.map((item) => <option key={item || "ALL"} value={item}>{item || "모든 상태"}</option>)}</select><span className="toolbar-count">총 {data?.totalElements ?? 0}개 문서</span></div>
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading ? <LoadingState label="문서 목록을 불러오는 중입니다." /> : null}
    {!loading && !error && !visible.length ? <EmptyState symbol="▤" title="표시할 문서가 없습니다" description="필터를 바꾸거나 새 문서를 업로드해 보세요." /> : null}
    {!loading && visible.length ? <div className="data-table documents-table"><div className="data-row data-head"><span>문서</span><span>소유자</span><span>버전</span><span>상태</span><span>공개 범위</span><span>최근 수정</span><span /></div>{visible.map((document) => <div className="data-row" key={document.documentId}><div className="doc-name"><span className={`file-square ${fileTone(document.documentType)}`}>▤</span><div><a href={`/documents/${document.documentId}`} target="_top">{document.title}</a><small>{document.documentType} · documentId {document.documentId}</small></div></div><span>{document.ownerName ? `${document.ownerName} (#${document.ownerUserId})` : `#${document.ownerUserId}`}</span><span>{document.currentVersionNo ? `v${document.currentVersionNo}` : "—"}</span><span><StatusPill value={document.status} /></span><span><StatusPill value={document.visibility} /></span><span>{formatDate(document.updatedAt, false)}</span><a className="row-link" href={`/documents/${document.documentId}`} target="_top">→</a></div>)}<div className="pagination"><span>{data?.totalElements ?? 0}건 · {data?.size ?? 20}건씩</span><div><button disabled={data?.first} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button><button className="active">{page + 1}</button><button disabled={data?.last} onClick={() => setPage((current) => current + 1)}>다음</button></div></div></div> : null}
  </section>;
}

export function DocumentDetailPage({ documentId, onVersionUpload, notify, refreshKey }: { documentId: number; onVersionUpload: () => void; notify: (message: string) => void; refreshKey: number }) {
  const { user } = useAuth();
  const isAdmin = user?.roles.includes("ADMIN") ?? false;
  const [document, setDocument] = useState<DocumentDetail | null>(null);
  const [content, setContent] = useState<DocumentContent | null>(null);
  const [status, setStatus] = useState<DocumentStatus | null>(null);
  const [permission, setPermission] = useState<PermissionSummary | null>(null);
  const [failedJob, setFailedJob] = useState<IndexingJob | null>(null);
  const [loading, setLoading] = useState(true);
  const [fileAction, setFileAction] = useState<"preview" | "download" | null>(null);
  const [editing, setEditing] = useState(false);
  const [mutation, setMutation] = useState<"update" | "delete" | null>(null);
  const [mutationError, setMutationError] = useState("");
  const [error, setError] = useState("");
  const [contentError, setContentError] = useState("");

  // silent 재조회는 인덱싱 완료 반영처럼 화면이 이미 떠 있는 상태에서 쓰므로 Loading 표시를 건너뛴다.
  const load = useCallback(async (options?: { silent?: boolean }) => {
    if (!options?.silent) setLoading(true);
    setError("");
    setContentError("");
    // 1. One unstable detail contract must not hide independently available status and permission data.
    const [detailResult, statusResult, permissionResult] = await Promise.allSettled([
      apiRequest<DocumentDetail>(`/api/documents/${documentId}`),
      apiRequest<DocumentStatus>(`/api/documents/${documentId}/status`),
      apiRequest<PermissionSummary>(`/permissions/documents/${documentId}/me`),
    ]);
    const failures: string[] = [];
    let detail = detailResult.status === "fulfilled" ? detailResult.value : null;

    // 2. Older backend deployments can still supply enough list metadata for a useful detail screen.
    if (!detail) {
      try {
        const page = await apiRequest<PageResponse<DocumentSummary>>("/api/documents?page=0&size=100");
        const summary = page.content.find((item) => item.documentId === documentId);
        if (summary) detail = summaryToFallbackDetail(summary);
      } catch {
        // The original detail failure below remains the actionable error when list fallback is also unavailable.
      }
    }

    setDocument(detail);
    setStatus(statusResult.status === "fulfilled" ? statusResult.value : null);
    setPermission(permissionResult.status === "fulfilled" ? permissionResult.value : null);
    if (detailResult.status === "rejected") failures.push(detail
      ? "상세 메타데이터 일부를 불러오지 못해 문서 목록과 처리 상태 정보를 대신 표시합니다."
      : `문서 정보: ${errorMessage(detailResult.reason)}`);
    if (statusResult.status === "rejected") failures.push(`처리 상태: ${errorMessage(statusResult.reason)}`);
    if (permissionResult.status === "rejected") failures.push(`내 권한: ${errorMessage(permissionResult.reason)}`);

    // 3. Extracted content is optional until a readable current version finishes indexing.
    if (detail?.contentAvailable) {
      try {
        setContent(await apiRequest<DocumentContent>(`/api/documents/${documentId}/content`));
      } catch (reason) {
        setContent(null);
        setContentError(errorMessage(reason));
      }
    } else {
      setContent(null);
    }

    // 4. 실패 원인은 ADMIN 전용 Job 조회라, 권한이 있고 문서가 최종 실패한 경우에만 가져온다.
    if (detail?.status === "FAILED" && isAdmin) {
      try {
        const jobs = await apiRequest<PageResponse<IndexingJob>>(
          `/admin/indexing-jobs?documentId=${documentId}&status=FAILED&page=0&size=1`
        );
        setFailedJob(jobs.content[0] ?? null);
      } catch {
        setFailedJob(null);
      }
    } else {
      setFailedJob(null);
    }

    setError(failures.join(" · "));
    setLoading(false);
  }, [documentId, isAdmin]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  // 새 버전 접수 신호가 바뀌면 처리 중 버전이 상세에 바로 드러나도록 다시 읽는다.
  const loadedRefreshKey = useRef(refreshKey);
  useEffect(() => {
    if (loadedRefreshKey.current === refreshKey) return;
    loadedRefreshKey.current = refreshKey;
    void load();
  }, [load, refreshKey]);

  // 인덱싱은 비동기로 끝나므로, 처리 중 버전이 남아 있는 동안만 상태를 다시 읽어 완료 시점을 반영한다.
  useEffect(() => {
    if (!isDocumentProcessing(status)) return;

    let active = true;
    const timer = window.setInterval(async () => {
      try {
        const next = await apiRequest<DocumentStatus>(`/api/documents/${documentId}/status`);
        if (!active) return;
        setStatus(next);
        // 처리가 끝난 시점에만 현재 버전과 추출 본문까지 최신 값으로 맞춘다.
        if (!isDocumentProcessing(next)) await load({ silent: true });
      } catch {
        // 일시적인 조회 실패는 다음 주기에 다시 시도한다.
      }
    }, DOCUMENT_STATUS_POLL_INTERVAL_MS);

    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [documentId, load, status]);

  async function accessFile(action: "preview" | "download") {
    setFileAction(action);
    setContentError("");
    try {
      if (action === "preview") await previewBackendFile(`/api/documents/${documentId}/file?disposition=inline`);
      else await downloadBackendFile(`/api/documents/${documentId}/file?disposition=attachment`);
    } catch (reason) {
      setContentError(errorMessage(reason));
    } finally {
      setFileAction(null);
    }
  }

  async function updateMetadata(request: UpdateDocumentMetadataRequest) {
    setMutation("update");
    setMutationError("");
    try {
      await apiRequest<void>(`/api/documents/${documentId}`, { method: "PATCH", body: request });
      setEditing(false);
      await load();
      notify("문서 정보를 수정했습니다.");
    } catch (reason) {
      setMutationError(errorMessage(reason));
    } finally {
      setMutation(null);
    }
  }

  async function updateVisibility(next: "PRIVATE" | "PUBLIC") {
    if (next === "PUBLIC" && !window.confirm("이 문서를 조직 전체에 공개할까요?")) return;

    setMutation("update");
    setMutationError("");
    try {
      await apiRequest<void>(`/api/documents/${documentId}/visibility`, { method: "PATCH", body: { visibility: next } });
      await load();
      notify(next === "PUBLIC" ? "문서를 전체공개로 전환했습니다." : "문서를 비공개로 전환했습니다.");
    } catch (reason) {
      setMutationError(errorMessage(reason));
    } finally {
      setMutation(null);
    }
  }

  async function deleteDocument() {
    const confirmed = window.confirm(`“${document?.title ?? `문서 #${documentId}`}” 문서를 삭제할까요?\n원본 파일과 버전 이력은 보존됩니다.`);
    if (!confirmed) return;

    setMutation("delete");
    setMutationError("");
    try {
      await apiRequest<void>(`/api/documents/${documentId}`, { method: "DELETE" });
      notify("문서를 삭제했습니다.");
      window.location.assign("/documents");
    } catch (reason) {
      setMutationError(errorMessage(reason));
      setMutation(null);
    }
  }

  const isOwner = document != null && user != null && user.userId === document.ownerUserId;

  return <section className="content page-view">
    <div className="detail-back"><a href="/documents" target="_top">← 문서 목록</a><span>documentId {documentId}</span></div>
    <PageHeading kicker="DOCUMENT DETAIL" title={document?.title ?? `문서 #${documentId}`} description={document?.description ?? "검색 가능한 버전과 현재 처리 중인 버전을 분리해서 확인합니다."} actions={<><StatusPill value={status?.documentStatus ?? document?.status ?? "LOADING"} />{document?.currentVersion ? <><button className="secondary-button" disabled={fileAction !== null || mutation !== null} onClick={() => void accessFile("preview")}>{fileAction === "preview" ? "여는 중…" : "↗ 원본 미리보기"}</button><button className="secondary-button" disabled={fileAction !== null || mutation !== null} onClick={() => void accessFile("download")}>{fileAction === "download" ? "다운로드 중…" : "↓ 다운로드"}</button></> : null}{permission?.canWrite ? <button className="secondary-button" disabled={mutation !== null} onClick={() => { setMutationError(""); setEditing(true); }}>문서 정보 수정</button> : null}{permission?.canWrite ? <button className="primary-button" disabled={mutation !== null} onClick={onVersionUpload}>＋ 새 버전 업로드</button> : null}{isOwner && document ? <button className="secondary-button" disabled={mutation !== null} onClick={() => void updateVisibility(document.visibility === "PUBLIC" ? "PRIVATE" : "PUBLIC")}>{document.visibility === "PUBLIC" ? "비공개로 전환" : "전체공개로 전환"}</button> : null}{permission?.canAdmin ? <button className="danger-button" disabled={mutation !== null} onClick={() => void deleteDocument()}>{mutation === "delete" ? "삭제 중…" : "문서 삭제"}</button> : null}</>} />
    {error ? document || status || permission ? <Notice>{error}</Notice> : <ErrorState message={error} onRetry={() => void load()} /> : null}
    {mutationError && !editing ? <Notice>{mutationError}</Notice> : null}
    {loading ? <LoadingState label="문서 상태를 확인하는 중입니다." /> : null}
    {!loading ? <>
      <div className="progress-card"><div className="panel-heading"><div><h2>인덱싱 진행 상태</h2><p>백엔드가 반환한 현재 버전과 처리 중 버전입니다.</p></div><span>실시간 조회</span></div><div className="status-flow"><div><span>현재 문서</span><strong>{status?.documentStatus ?? "—"}</strong></div><b>→</b><div><span>검색 가능 버전</span><strong>{status?.currentVersion ? `v${status.currentVersion.versionNo} · ${status.currentVersion.status}` : "없음"}</strong></div><b>→</b><div><span>처리 중 버전</span><strong>{status?.processingVersion ? `v${status.processingVersion.versionNo} · ${status.processingVersion.jobStatus}` : "없음"}</strong></div></div></div>
      {contentError ? <Notice>{contentError}</Notice> : null}
      <div className="detail-grid three"><div className="panel-card"><div className="panel-heading"><h2>문서 정보</h2></div><dl><div><dt>형식</dt><dd>{document?.documentType ?? "—"}</dd></div><div><dt>출처</dt><dd>{document?.sourceType ?? "—"}</dd></div><div><dt>공개 범위</dt><dd>{document?.visibility ?? "—"}</dd></div><div><dt>소유자</dt><dd>{document ? document.ownerName ? `${document.ownerName} (#${document.ownerUserId})` : `user #${document.ownerUserId}` : "—"}</dd></div><div><dt>최근 수정</dt><dd>{formatDate(document?.updatedAt)}</dd></div></dl></div><div className="panel-card"><div className="panel-heading"><h2>내 권한</h2></div><div className="permission-checks"><StatusPill value={`READ ${permission?.canRead ? "✓" : "✕"}`} /><StatusPill value={`WRITE ${permission?.canWrite ? "✓" : "✕"}`} /><StatusPill value={`ADMIN ${permission?.canAdmin ? "✓" : "✕"}`} /></div><span className="field-label">권한 경로</span><div className="source-chips">{permission?.sources.length ? permission.sources.map((source) => <b key={source}>{source}</b>) : <span>없음</span>}</div></div><div className="panel-card"><div className="panel-heading"><h2>현재 버전</h2></div><dl><div><dt>버전</dt><dd>{document?.currentVersion ? `v${document.currentVersion.versionNo}` : status?.currentVersion ? `v${status.currentVersion.versionNo}` : "—"}</dd></div><div><dt>원본 파일</dt><dd>{document?.currentVersion?.originalFilename ?? "—"}</dd></div><div><dt>파일 크기</dt><dd>{formatBytes(document?.currentVersion?.fileSize)}</dd></div><div><dt>청크 수</dt><dd>{content?.chunkCount ?? "—"}</dd></div><div><dt>인덱싱 완료</dt><dd>{formatDate(document?.currentVersion?.indexedAt)}</dd></div></dl></div></div>
      {document?.status === "FAILED" && isAdmin ? <div className="panel-card"><div className="panel-heading"><div><h2>실패 원인</h2><p>관리자 전용 — 최근 실패 Job 정보입니다.</p></div>{failedJob ? <a href={`/admin/indexing-jobs/${failedJob.jobId}`} target="_top">Job 상세 보기 →</a> : null}</div><dl><div><dt>Job</dt><dd>{failedJob ? `#${failedJob.jobId}` : "—"}</dd></div><div><dt>오류 코드</dt><dd><code>{failedJob?.errorCode ?? "—"}</code></dd></div><div><dt>실패 시각</dt><dd>{formatDate(failedJob?.failedAt)}</dd></div><div><dt>재시도</dt><dd>{failedJob ? `${failedJob.retryCount}/${failedJob.maxRetryCount}` : "—"}</dd></div></dl></div> : null}
      <div className="panel-card document-content"><div className="panel-heading"><div><h2>추출 본문</h2><p>{content ? `v${content.versionNo} · ${content.chunkCount}개 청크로 복원` : "인덱싱 완료 후 조회할 수 있습니다."}</p></div></div>{content ? <pre>{content.content}</pre> : <EmptyState symbol="≡" title="조회 가능한 본문이 없습니다" description="현재 버전의 인덱싱 상태를 확인해 주세요." />}</div>
    </> : null}
    {editing && document ? <DocumentMetadataModal document={document} busy={mutation === "update"} error={mutationError} onClose={() => { if (mutation === null) setEditing(false); }} onSubmit={updateMetadata} /> : null}
  </section>;
}

function DocumentMetadataModal({ document, busy, error, onClose, onSubmit }: {
  document: DocumentDetail;
  busy: boolean;
  error: string;
  onClose: () => void;
  onSubmit: (request: UpdateDocumentMetadataRequest) => Promise<void>;
}) {
  const [title, setTitle] = useState(document.title);
  const [description, setDescription] = useState(document.description ?? "");

  return <div className="modal-layer"><form className="modal compact-modal" onSubmit={(event) => { event.preventDefault(); void onSubmit({ title, description }); }}>
    <div className="modal-header"><div><span className="modal-symbol">✎</span><div><h2>문서 정보 수정</h2><p>검색 결과와 문서 화면에 표시되는 제목과 설명을 변경합니다.</p></div></div><button type="button" disabled={busy} aria-label="닫기" onClick={onClose}>×</button></div>
    <label className="form-field">문서 제목<input value={title} onChange={(event) => setTitle(event.target.value)} maxLength={500} required /></label>
    <label className="form-field">문서 설명<textarea value={description} onChange={(event) => setDescription(event.target.value)} rows={5} placeholder="문서가 해결하는 문제나 목적을 입력하세요." /></label>
    {error ? <div className="form-error" role="alert">{error}</div> : null}
    <div className="modal-footer"><button type="button" className="secondary-button" disabled={busy} onClick={onClose}>취소</button><button className="primary-button" disabled={busy || !title.trim()}>{busy ? "저장 중…" : "변경사항 저장"}</button></div>
  </form></div>;
}

function summaryToFallbackDetail(summary: DocumentSummary): DocumentDetail {
  return {
    documentId: summary.documentId,
    title: summary.title,
    description: summary.description,
    documentType: summary.documentType,
    sourceType: "—",
    status: summary.status,
    visibility: summary.visibility,
    ownerUserId: summary.ownerUserId,
    ownerName: "",
    currentVersion: null,
    contentAvailable: false,
    createdAt: summary.createdAt,
    updatedAt: summary.updatedAt,
  };
}

function fileTone(type: string) {
  if (type === "PDF") return "red";
  if (type === "DOCX") return "blue";
  if (type === "MARKDOWN" || type === "MD") return "violet";
  return "green";
}
