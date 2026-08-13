"use client";

// vinext production navigation uses full requests because its client router does not complete catch-all route transitions.
/* eslint-disable @next/next/no-html-link-for-pages */

import { useCallback, useEffect, useState } from "react";
import { apiRequest, downloadBackendFile, errorMessage, previewBackendFile, toQuery } from "../lib/api";
import type { DocumentContent, DocumentDetail, DocumentStatus, DocumentSummary, PageResponse, PermissionSummary } from "../lib/api-types";
import { EmptyState, ErrorState, LoadingState, Notice, PageHeading, StatusPill, formatDate } from "../components/ui";

const statuses = ["", "DRAFT", "UPLOADED", "INDEXING", "INDEXED", "FAILED", "ARCHIVED"];

export function DocumentsPage({ onUpload }: { onUpload: () => void }) {
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
  const visible = data?.content.filter((document) => document.title.toLowerCase().includes(search.toLowerCase())) ?? [];

  return <section className="content page-view">
    <PageHeading kicker="KNOWLEDGE BASE" title="문서" description="내가 읽을 수 있는 문서와 현재 인덱싱 상태를 확인하세요." actions={<button className="primary-button" onClick={onUpload}>＋ 문서 업로드</button>} />
    <div className="toolbar"><div className="toolbar-search"><span>⌕</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="현재 페이지 문서 검색" /></div><select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); }}>{statuses.map((item) => <option key={item || "ALL"} value={item}>{item || "모든 상태"}</option>)}</select><span className="toolbar-count">총 {data?.totalElements ?? 0}개 문서</span></div>
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading ? <LoadingState label="문서 목록을 불러오는 중입니다." /> : null}
    {!loading && !error && !visible.length ? <EmptyState symbol="▤" title="표시할 문서가 없습니다" description="필터를 바꾸거나 새 문서를 업로드해 보세요." /> : null}
    {!loading && visible.length ? <div className="data-table documents-table"><div className="data-row data-head"><span>문서</span><span>소유자</span><span>버전</span><span>상태</span><span>공개 범위</span><span>최근 수정</span><span /></div>{visible.map((document) => <div className="data-row" key={document.documentId}><div className="doc-name"><span className={`file-square ${fileTone(document.documentType)}`}>▤</span><div><a href={`/documents/${document.documentId}`} target="_top">{document.title}</a><small>{document.documentType} · documentId {document.documentId}</small></div></div><span>user #{document.ownerUserId}</span><span>{document.currentVersionNo ? `v${document.currentVersionNo}` : "—"}</span><span><StatusPill value={document.status} /></span><span><StatusPill value={document.visibility} /></span><span>{formatDate(document.updatedAt, false)}</span><a className="row-link" href={`/documents/${document.documentId}`} target="_top">→</a></div>)}<div className="pagination"><span>{data?.totalElements ?? 0}건 · {data?.size ?? 20}건씩</span><div><button disabled={data?.first} onClick={() => setPage((current) => Math.max(0, current - 1))}>이전</button><button className="active">{page + 1}</button><button disabled={data?.last} onClick={() => setPage((current) => current + 1)}>다음</button></div></div></div> : null}
  </section>;
}

export function DocumentDetailPage({ documentId, onVersionUpload }: { documentId: number; onVersionUpload: () => void }) {
  const [document, setDocument] = useState<DocumentDetail | null>(null);
  const [content, setContent] = useState<DocumentContent | null>(null);
  const [status, setStatus] = useState<DocumentStatus | null>(null);
  const [permission, setPermission] = useState<PermissionSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [fileAction, setFileAction] = useState<"preview" | "download" | null>(null);
  const [error, setError] = useState("");
  const [contentError, setContentError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
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
    setError(failures.join(" · "));
    setLoading(false);
  }, [documentId]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

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

  return <section className="content page-view">
    <div className="detail-back"><a href="/documents" target="_top">← 문서 목록</a><span>documentId {documentId}</span></div>
    <PageHeading kicker="DOCUMENT DETAIL" title={document?.title ?? `문서 #${documentId}`} description={document?.description ?? "검색 가능한 버전과 현재 처리 중인 버전을 분리해서 확인합니다."} actions={<><StatusPill value={status?.documentStatus ?? document?.status ?? "LOADING"} />{document?.currentVersion ? <><button className="secondary-button" disabled={fileAction !== null} onClick={() => void accessFile("preview")}>{fileAction === "preview" ? "여는 중…" : "↗ 원본 미리보기"}</button><button className="secondary-button" disabled={fileAction !== null} onClick={() => void accessFile("download")}>{fileAction === "download" ? "다운로드 중…" : "↓ 다운로드"}</button></> : null}{permission?.canWrite ? <button className="primary-button" onClick={onVersionUpload}>＋ 새 버전 업로드</button> : null}</>} />
    {error ? document || status || permission ? <Notice>{error}</Notice> : <ErrorState message={error} onRetry={() => void load()} /> : null}
    {loading ? <LoadingState label="문서 상태를 확인하는 중입니다." /> : null}
    {!loading ? <>
      <div className="progress-card"><div className="panel-heading"><div><h2>인덱싱 진행 상태</h2><p>백엔드가 반환한 현재 버전과 처리 중 버전입니다.</p></div><span>실시간 조회</span></div><div className="status-flow"><div><span>현재 문서</span><strong>{status?.documentStatus ?? "—"}</strong></div><b>→</b><div><span>검색 가능 버전</span><strong>{status?.currentVersion ? `v${status.currentVersion.versionNo} · ${status.currentVersion.status}` : "없음"}</strong></div><b>→</b><div><span>처리 중 버전</span><strong>{status?.processingVersion ? `v${status.processingVersion.versionNo} · ${status.processingVersion.jobStatus}` : "없음"}</strong></div></div></div>
      {contentError ? <Notice>{contentError}</Notice> : null}
      <div className="detail-grid three"><div className="panel-card"><div className="panel-heading"><h2>문서 정보</h2></div><dl><div><dt>형식</dt><dd>{document?.documentType ?? "—"}</dd></div><div><dt>출처</dt><dd>{document?.sourceType ?? "—"}</dd></div><div><dt>공개 범위</dt><dd>{document?.visibility ?? "—"}</dd></div><div><dt>소유자</dt><dd>{document ? document.ownerName ? `${document.ownerName} (#${document.ownerUserId})` : `user #${document.ownerUserId}` : "—"}</dd></div><div><dt>최근 수정</dt><dd>{formatDate(document?.updatedAt)}</dd></div></dl></div><div className="panel-card"><div className="panel-heading"><h2>내 권한</h2></div><div className="permission-checks"><StatusPill value={`READ ${permission?.canRead ? "✓" : "✕"}`} /><StatusPill value={`WRITE ${permission?.canWrite ? "✓" : "✕"}`} /><StatusPill value={`ADMIN ${permission?.canAdmin ? "✓" : "✕"}`} /></div><span className="field-label">권한 경로</span><div className="source-chips">{permission?.sources.length ? permission.sources.map((source) => <b key={source}>{source}</b>) : <span>없음</span>}</div></div><div className="panel-card"><div className="panel-heading"><h2>현재 버전</h2></div><dl><div><dt>버전</dt><dd>{document?.currentVersion ? `v${document.currentVersion.versionNo}` : status?.currentVersion ? `v${status.currentVersion.versionNo}` : "—"}</dd></div><div><dt>원본 파일</dt><dd>{document?.currentVersion?.originalFilename ?? "—"}</dd></div><div><dt>파일 크기</dt><dd>{formatBytes(document?.currentVersion?.fileSize)}</dd></div><div><dt>청크 수</dt><dd>{content?.chunkCount ?? "—"}</dd></div><div><dt>인덱싱 완료</dt><dd>{formatDate(document?.currentVersion?.indexedAt)}</dd></div></dl></div></div>
      <div className="panel-card document-content"><div className="panel-heading"><div><h2>추출 본문</h2><p>{content ? `v${content.versionNo} · ${content.chunkCount}개 청크로 복원` : "인덱싱 완료 후 조회할 수 있습니다."}</p></div></div>{content ? <pre>{content.content}</pre> : <EmptyState symbol="≡" title="조회 가능한 본문이 없습니다" description="현재 버전의 인덱싱 상태를 확인해 주세요." />}</div>
    </> : null}
  </section>;
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

function formatBytes(value: number | null | undefined) {
  if (value === null || value === undefined) return "—";
  if (value < 1024) return `${value} B`;
  if (value < 1024 ** 2) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 ** 2).toFixed(1)} MB`;
}
