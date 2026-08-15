"use client";

// vinext production navigation uses full requests because its client router does not complete catch-all route transitions.
/* eslint-disable @next/next/no-html-link-for-pages */

import { FormEvent, useEffect, useState } from "react";
import { apiRequest, errorMessage } from "../lib/api";
import type { Collection, SearchResponse } from "../lib/api-types";
import { groupSearchSources } from "../lib/search-sources";
import { ErrorState, StatusPill } from "../components/ui";

const suggestions = ["배포 실패 시 롤백 절차", "법인카드 사용 기준", "보안 사고 보고 순서"];
const SEARCH_TIMEOUT_MS = 29_000;

export function SearchPage() {
  const [query, setQuery] = useState("");
  const [topK, setTopK] = useState(5);
  const [collectionId, setCollectionId] = useState("");
  const [collections, setCollections] = useState<Collection[]>([]);
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    apiRequest<Collection[]>("/collections").then(setCollections).catch(() => setCollections([]));
  }, []);

  async function search(searchText = query) {
    const trimmed = searchText.trim();
    if (!trimmed) return;
    setQuery(trimmed);
    setSearching(true);
    setError("");
    try {
      const response = await apiRequest<SearchResponse>("/search", {
        method: "POST",
        signal: AbortSignal.timeout(SEARCH_TIMEOUT_MS),
        body: {
          queryText: trimmed,
          topK,
          collectionId: collectionId ? Number(collectionId) : null,
        },
      });
      setResult(response);
    } catch (reason) {
      setResult(null);
      setError(reason instanceof DOMException && ["AbortError", "TimeoutError"].includes(reason.name)
        ? "검색 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."
        : errorMessage(reason));
    } finally {
      setSearching(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void search();
  }

  const groupedSources = result ? groupSearchSources(result) : [];

  return (
    <section className={`content search-view ${result || searching || error ? "has-results" : ""}`}>
      {!result && !searching && !error ? <div className="search-landing">
        <div className="eyebrow"><span>✦</span> AI KNOWLEDGE SEARCH</div>
        <h1>팀의 지식에서<br /><em>정확한 답</em>을 찾으세요.</h1>
        <p className="hero-copy">벡터 검색과 권한 검증을 거쳐, 출처가 명확한 답변을 제공합니다.</p>
        <form className="hero-search" onSubmit={submit}><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} aria-label="문서 검색" placeholder="질문을 입력하세요" /><button aria-label="검색">↑</button></form>
        <div className="search-options"><label>검색 범위<select value={collectionId} onChange={(event) => setCollectionId(event.target.value)}><option value="">전체 컬렉션</option>{collections.map((collection) => <option value={collection.collectionId} key={collection.collectionId}>{collection.name}</option>)}</select></label><label>결과 수<select value={topK} onChange={(event) => setTopK(Number(event.target.value))}><option value={5}>Top-K 5</option><option value={10}>Top-K 10</option><option value={20}>Top-K 20</option></select></label></div>
        <div className="suggestions"><span>추천 질문</span>{suggestions.map((item) => <button key={item} onClick={() => void search(item)}>{item}<b>↗</b></button>)}</div>
        <div className="feature-links"><a href="/documents" target="_top"><span>▤</span><div><strong>문서 탐색</strong><small>문서와 인덱싱 상태 확인</small></div><b>→</b></a><a href="/collections" target="_top"><span>▱</span><div><strong>컬렉션</strong><small>주제별 검색 범위 관리</small></div><b>→</b></a><a href="/mcp-tokens" target="_top"><span>⌁</span><div><strong>MCP 연동</strong><small>AI 클라이언트 연결</small></div><b>→</b></a></div>
      </div> : <div className="results-page">
        <form className="results-search" onSubmit={submit}><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} aria-label="문서 검색" /><select value={collectionId} onChange={(event) => setCollectionId(event.target.value)}><option value="">전체 컬렉션</option>{collections.map((collection) => <option value={collection.collectionId} key={collection.collectionId}>{collection.name}</option>)}</select><select value={topK} onChange={(event) => setTopK(Number(event.target.value))}><option value={5}>Top-K 5</option><option value={10}>Top-K 10</option><option value={20}>Top-K 20</option></select><button disabled={searching}>{searching ? "검색 중" : "검색"}</button></form>
        {error ? <ErrorState message={error} onRetry={() => void search()} /> : null}
        {searching ? <div className="thinking-card"><div className="thinking-orb"><span /><span /><span /></div><div><strong>권한이 있는 문서에서 답을 찾고 있어요</strong><p>벡터 유사도 검색과 RAG 답변 생성을 진행 중입니다.</p></div></div> : null}
        {result ? <>
          <div className="results-meta"><span>AI 답변</span><small>{result.results.length}개의 검색 결과 · queryId {result.queryId}</small></div>
          <article className="answer-card"><div className="answer-icon">✦</div><div className="answer-content"><h2>{query}</h2><p>{result.answer || "접근 가능한 문서에서 답을 생성하지 못했습니다."}</p></div></article>
          <div className="source-heading"><h2>검색 결과와 근거 문서</h2><span>유사도 높은 순</span></div>
          <div className="source-list">{groupedSources.map((source) => <a className="source-card" key={source.documentId} href={`/documents/${source.documentId}`} target="_top"><span className="source-labels">{source.labels.map((label) => <span className="source-number" key={label}>{label}</span>)}</span><div><div className="source-title"><h3>{source.documentTitle}</h3>{source.similarityScore !== null ? <StatusPill value={`${(source.similarityScore * 100).toFixed(1)}%`} /> : null}</div><p>{source.excerpts.map((excerpt) => `“${excerpt}”`).join(" · ")}</p><span>{source.chunkCount > 1 ? `${source.chunkCount}개 근거 · ` : ""}{source.pages.length > 0 ? `${source.pages.map((page) => `${page}페이지`).join(", ")} · ` : ""}문서 상세 보기 →</span></div></a>)}</div>
        </> : null}
      </div>}
    </section>
  );
}
