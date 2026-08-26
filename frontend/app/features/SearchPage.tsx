"use client";

// vinext production navigation uses full requests because its client router does not complete catch-all route transitions.
/* eslint-disable @next/next/no-html-link-for-pages */

import { FormEvent, useCallback, useEffect, useRef, useState } from "react";
import { apiRequest, errorMessage } from "../lib/api";
import type { Collection, DocumentSummary, PageResponse, SearchConversation, SearchConversationSummary, SearchConversationTurn, SearchResponse } from "../lib/api-types";
import { conversationIdFromSearch, conversationPath, upsertConversationTurn } from "../lib/conversation-history";
import { groupSearchSources } from "../lib/search-sources";
import { useRagAnswerSocket } from "../lib/useRagAnswerSocket";
import { ErrorState, LoadingState, StatusPill, formatDate } from "../components/ui";

// 검색 범위 드롭다운에 읽기 가능한 컬렉션 전체를 보여주기 위해 100건씩 모든 페이지를 이어붙인다.
async function loadAllCollections(): Promise<Collection[]> {
  const all: Collection[] = [];
  let page = 0;
  for (;;) {
    const result = await apiRequest<PageResponse<Collection>>(`/collections?page=${page}&size=100`);
    all.push(...result.content);
    if (result.last) return all;
    page += 1;
  }
}

type SuggestedQuestion = { documentId: number; label: string };

// 실제 검색 가능한(INDEXED) 문서가 없는 하드코딩 질문은 관련 문서를 찾지 못하는 경험으로 이어지므로,
// 최근 인덱싱된 문서 제목에서 직접 질문을 만든다 — 문서가 바뀌어도 항상 결과가 나오는 질문만 보여준다.
async function loadSuggestedQuestions(): Promise<SuggestedQuestion[]> {
  const result = await apiRequest<PageResponse<DocumentSummary>>("/api/documents?status=INDEXED&page=0&size=3");
  return result.content.map((document) => ({ documentId: document.documentId, label: `${document.title}에 대해 알려줘` }));
}
const SEARCH_TIMEOUT_MS = 29_000;
// WebSocket push가 유실돼도(연결 끊김 등) 답변이 영원히 "생성 중"으로 멈춰 보이지 않도록 하는 안전망.
const ANSWER_POLL_INTERVAL_MS = 3_000;
// 이 시간 넘게 대기 중이면 로딩 문구를 바꿔 "멈춘 것처럼 보이는" 느낌을 줄인다(QA-P0-01).
const LONG_WAIT_NOTICE_MS = 30_000;

export function SearchPage() {
  const [query, setQuery] = useState("");
  const [topK, setTopK] = useState(5);
  const [collectionId, setCollectionId] = useState("");
  const [collections, setCollections] = useState<Collection[]>([]);
  const [suggestions, setSuggestions] = useState<SuggestedQuestion[]>([]);
  const [conversationId, setConversationId] = useState<number | null>(null);
  const [conversationTitle, setConversationTitle] = useState("");
  const [turns, setTurns] = useState<SearchConversationTurn[]>([]);
  const [conversationHistory, setConversationHistory] = useState<SearchConversationSummary[]>([]);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [conversationLoading, setConversationLoading] = useState(false);
  const [result, setResult] = useState<SearchResponse | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState("");
  const pollTimer = useRef<number | null>(null);
  // "지금 화면이 보여주고 있어야 할 queryId"를 별도로 들고 있는다 — refreshAnswer의 응답이
  // 돌아왔을 때 그 사이 사용자가 새 검색을 시작해 이미 낡은 queryId가 됐는지 판별하는 용도다.
  const activeQueryIdRef = useRef<number | null>(null);
  // 기록을 빠르게 연속 선택하거나 로딩 중 새 대화로 이동하면 가장 마지막 화면 요청만 반영한다.
  const conversationRequestIdRef = useRef(0);
  const searchRequestIdRef = useRef(0);

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
    try {
      const page = await apiRequest<PageResponse<SearchConversationSummary>>("/search/conversations?page=0&size=20");
      setConversationHistory(page.content);
      setHistoryTotal(page.totalElements);
    } catch {
      setConversationHistory([]);
      setHistoryTotal(0);
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  const loadConversation = useCallback(async (
    selectedConversationId: number,
    navigation: "push" | "replace" | "none" = "push",
  ) => {
    const requestId = ++conversationRequestIdRef.current;
    // 진행 중인 새 질문 응답이 불러온 과거 대화를 뒤늦게 덮지 못하게 무효화한다.
    searchRequestIdRef.current += 1;
    setSearching(false);
    setConversationLoading(true);
    setError("");
    try {
      const conversation = await apiRequest<SearchConversation>(`/search/conversations/${selectedConversationId}`);
      if (conversationRequestIdRef.current !== requestId) return;
      const latest = conversation.turns.at(-1)?.response ?? null;
      setConversationId(conversation.conversationId);
      setConversationTitle(conversation.title);
      setTurns(conversation.turns);
      setResult(latest);
      setQuery("");
      activeQueryIdRef.current = latest?.queryId ?? null;
      if (navigation === "push") {
        window.history.pushState({}, "", conversationPath(conversation.conversationId));
      } else if (navigation === "replace") {
        window.history.replaceState({}, "", conversationPath(conversation.conversationId));
      }
      setHistoryOpen(false);
    } catch (reason) {
      if (conversationRequestIdRef.current === requestId) setError(errorMessage(reason));
    } finally {
      if (conversationRequestIdRef.current === requestId) setConversationLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadAllCollections().then(setCollections).catch(() => setCollections([]));
    void loadSuggestedQuestions().then(setSuggestions).catch(() => setSuggestions([]));
    // 초기 렌더가 끝난 뒤 저장된 기록과 URL 대화를 복원해 effect 안의 동기 상태 변경을 피한다.
    const initialLoadTimer = window.setTimeout(() => {
      void loadHistory();
      const restoredConversationId = conversationIdFromSearch(window.location.search);
      if (restoredConversationId) void loadConversation(restoredConversationId, "none");
    }, 0);
    return () => window.clearTimeout(initialLoadTimer);
  }, [loadConversation, loadHistory]);

  useEffect(() => {
    function restoreFromBrowserHistory() {
      const restoredConversationId = conversationIdFromSearch(window.location.search);
      if (restoredConversationId) {
        void loadConversation(restoredConversationId, "none");
        return;
      }
      // 새 대화 URL로 돌아온 경우 서버 호출 없이 초기 검색 화면을 복원한다.
      conversationRequestIdRef.current += 1;
      searchRequestIdRef.current += 1;
      activeQueryIdRef.current = null;
      setConversationLoading(false);
      setSearching(false);
      setConversationId(null);
      setConversationTitle("");
      setTurns([]);
      setResult(null);
      setQuery("");
      setError("");
      setHistoryOpen(false);
    }
    window.addEventListener("popstate", restoreFromBrowserHistory);
    return () => window.removeEventListener("popstate", restoreFromBrowserHistory);
  }, [loadConversation]);

  // AI 답변이 아직 생성 중일 때만 true — WebSocket과 폴백 폴링을 이때만 연다.
  const awaitingAnswer = result?.ragStatus === "PROCESSING";

  const refreshAnswer = useCallback(() => {
    const queryId = activeQueryIdRef.current;
    if (queryId === null) return;
    apiRequest<SearchResponse>(`/search/${queryId}`)
      .then((response) => {
        // 응답이 돌아오는 사이 사용자가 다른 검색을 시작했다면(activeQueryIdRef가 바뀜), 이건
        // 이미 화면과 무관해진 낡은 응답이다 — 새 검색 결과를 덮어쓰지 않도록 버린다.
        if (activeQueryIdRef.current !== queryId) return;
        setResult(response);
        setTurns((current) => current.map((turn) => turn.queryId === queryId
          ? { ...turn, response }
          : turn));
      })
      .catch(() => {
        // 재조회 실패는 조용히 무시한다 — 다음 폴링/push 때 다시 시도된다. 검색 결과는 이미 화면에
        // 떠 있으니 사용자에게 굳이 에러를 보여줄 필요가 없다.
      });
  }, []);

  const socketStatus = useRagAnswerSocket(awaitingAnswer, refreshAnswer);

  useEffect(() => {
    if (!awaitingAnswer) {
      if (pollTimer.current) window.clearInterval(pollTimer.current);
      pollTimer.current = null;
      return;
    }
    pollTimer.current = window.setInterval(refreshAnswer, ANSWER_POLL_INTERVAL_MS);
    return () => {
      if (pollTimer.current) window.clearInterval(pollTimer.current);
      pollTimer.current = null;
    };
  }, [awaitingAnswer, refreshAnswer]);

  // 대기가 길어지고 있다는 걸 사용자에게 알려주는 용도일 뿐, 실제 재조회/타임아웃 로직과는
  // 무관하다 — 답변이 오면(awaitingAnswer가 false가 되면) 자동으로 꺼진다.
  // deps에 result?.queryId도 넣는다 — 이전 검색도 PROCESSING, 새 검색도 PROCESSING이면
  // awaitingAnswer 값 자체는 안 바뀌어서 queryId 없이는 이 effect가 재실행되지 않고, 이전
  // 검색의 타이머/longWait 상태가 새 검색에 그대로 이어져 버린다.
  const [longWait, setLongWait] = useState(false);
  useEffect(() => {
    if (!awaitingAnswer) return;
    const timer = window.setTimeout(() => setLongWait(true), LONG_WAIT_NOTICE_MS);
    return () => {
      window.clearTimeout(timer);
      setLongWait(false);
    };
  }, [awaitingAnswer, result?.queryId]);

  async function search(searchText = query) {
    const trimmed = searchText.trim();
    if (!trimmed) return;
    const requestId = ++searchRequestIdRef.current;
    // 과거 대화 로딩과 새 질문이 겹쳐도 사용자가 마지막으로 시작한 검색을 우선한다.
    conversationRequestIdRef.current += 1;
    setConversationLoading(false);
    setSearching(true);
    setError("");
    // 새 검색을 시작하는 순간, 이전 queryId를 향해 날아가고 있을지 모르는 refreshAnswer 응답을
    // 전부 무효화한다 — POST 응답이 오기 전까지는 "유효한 활성 queryId가 없는" 상태로 둔다.
    activeQueryIdRef.current = null;
    try {
      // 검색 결과는 여기서 바로 오지만, AI 답변(answer)은 비동기 생성이라 이 응답엔 아직 없을 수
      // 있다(ragStatus: PROCESSING) — 그 경우 아래 useRagAnswerSocket/폴링이 이어받는다.
      const response = await apiRequest<SearchResponse>("/search", {
        method: "POST",
        signal: AbortSignal.timeout(SEARCH_TIMEOUT_MS),
        body: {
          queryText: trimmed,
          topK,
          collectionId: collectionId ? Number(collectionId) : null,
          conversationId,
        },
      });
      if (searchRequestIdRef.current !== requestId) return;
      activeQueryIdRef.current = response.queryId;
      setConversationId(response.conversationId);
      if (!conversationId) setConversationTitle(trimmed);
      setTurns((current) => upsertConversationTurn(current, trimmed, response, new Date().toISOString()));
      setResult(response);
      setQuery("");
      if (conversationId === null) {
        window.history.pushState({}, "", conversationPath(response.conversationId));
      } else {
        window.history.replaceState({}, "", conversationPath(response.conversationId));
      }
      void loadHistory();
    } catch (reason) {
      if (searchRequestIdRef.current === requestId) {
        setError(reason instanceof DOMException && ["AbortError", "TimeoutError"].includes(reason.name)
          ? "검색 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."
          : errorMessage(reason));
      }
    } finally {
      if (searchRequestIdRef.current === requestId) setSearching(false);
    }
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    void search();
  }

  function startNewConversation() {
    conversationRequestIdRef.current += 1;
    searchRequestIdRef.current += 1;
    activeQueryIdRef.current = null;
    setConversationLoading(false);
    setSearching(false);
    setConversationId(null);
    setConversationTitle("");
    setTurns([]);
    setResult(null);
    setQuery("");
    setError("");
    setHistoryOpen(false);
    window.history.pushState({}, "", conversationPath(null));
  }

  return (
    <section className={`content search-view ${turns.length || searching || error ? "has-results" : ""}`}>
      <div className="conversation-controls">
        <button type="button" className={historyOpen ? "active" : ""} aria-expanded={historyOpen} aria-controls="conversation-history-panel" onClick={() => setHistoryOpen((open) => !open)}>
          <span>☰</span> 대화 기록 <small>{historyTotal}</small><b>{historyOpen ? "▴" : "▾"}</b>
        </button>
        {conversationId ? <button type="button" onClick={startNewConversation}><span>＋</span> 새 대화</button> : null}
      </div>
      {historyOpen ? <div className="conversation-history-panel" id="conversation-history-panel">
        <div className="conversation-history-head"><div><strong>최근 대화</strong><p>최근 20개까지 표시하며, 선택하면 질문·답변과 근거 문서를 복원합니다.</p></div><button type="button" aria-label="대화 기록 닫기" onClick={() => setHistoryOpen(false)}>×</button></div>
        {historyLoading ? <div className="conversation-history-loading">대화 기록을 불러오는 중입니다.</div> : conversationHistory.length ? <div className="conversation-history-list">{conversationHistory.map((conversation) => <button type="button" className={conversation.conversationId === conversationId ? "active" : ""} key={conversation.conversationId} onClick={() => void loadConversation(conversation.conversationId, conversation.conversationId === conversationId ? "replace" : "push")}><span>✦</span><div><strong>{conversation.title}</strong><small>{formatDate(conversation.lastMessageAt)}</small></div><b>→</b></button>)}</div> : <div className="conversation-history-empty"><span>○</span><strong>저장된 대화가 없습니다.</strong><p>첫 질문을 입력하면 이곳에 자동으로 저장됩니다.</p></div>}
      </div> : null}
      {conversationLoading ? <LoadingState label="대화 내용을 불러오는 중입니다." /> : turns.length === 0 && !searching && !error ? <div className="search-landing">
        <div className="eyebrow"><span>✦</span> AI KNOWLEDGE SEARCH</div>
        <h1>팀의 지식에서<br /><em>정확한 답</em>을 찾으세요.</h1>
        <p className="hero-copy">벡터 검색과 권한 검증을 거쳐, 출처가 명확한 답변을 제공합니다.</p>
        <form className="hero-search" onSubmit={submit}><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} aria-label="문서 검색" placeholder="질문을 입력하세요" /><button aria-label="검색">↑</button></form>
        <div className="search-options"><label>검색 범위<select value={collectionId} onChange={(event) => setCollectionId(event.target.value)}><option value="">전체 컬렉션</option>{collections.map((collection) => <option value={collection.collectionId} key={collection.collectionId}>{collection.name}</option>)}</select></label><label>결과 수<select value={topK} onChange={(event) => setTopK(Number(event.target.value))}><option value={5}>Top-K 5</option><option value={10}>Top-K 10</option><option value={20}>Top-K 20</option></select></label></div>
        {suggestions.length ? <div className="suggestions"><span>추천 질문</span>{suggestions.map((item) => <button key={item.documentId} onClick={() => void search(item.label)}>{item.label}<b>↗</b></button>)}</div> : null}
        <div className="feature-links"><a href="/documents" target="_top"><span>▤</span><div><strong>문서 탐색</strong><small>문서와 인덱싱 상태 확인</small></div><b>→</b></a><a href="/collections" target="_top"><span>▱</span><div><strong>컬렉션</strong><small>주제별 검색 범위 관리</small></div><b>→</b></a><a href="/mcp-tokens" target="_top"><span>⌁</span><div><strong>MCP 연동</strong><small>AI 클라이언트 연결</small></div><b>→</b></a></div>
      </div> : <div className="results-page">
        <div className="conversation-heading"><div><span>AI CONVERSATION</span><h1>{conversationTitle || "새 대화"}</h1><p>{turns.length}개의 질문과 답변이 저장되어 있습니다.</p></div><small>{conversationId ? "자동 저장됨" : "새 대화"}</small></div>
        <form className="results-search" onSubmit={submit}><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} aria-label="문서 검색" placeholder={turns.length ? "이 대화에 이어서 질문하세요" : "질문을 입력하세요"} /><select aria-label="검색 범위" value={collectionId} onChange={(event) => setCollectionId(event.target.value)}><option value="">전체 컬렉션</option>{collections.map((collection) => <option value={collection.collectionId} key={collection.collectionId}>{collection.name}</option>)}</select><select aria-label="결과 수" value={topK} onChange={(event) => setTopK(Number(event.target.value))}><option value={5}>Top-K 5</option><option value={10}>Top-K 10</option><option value={20}>Top-K 20</option></select><button disabled={searching || !query.trim()}>{searching ? "검색 중" : "전송"}</button></form>
        {error ? <ErrorState message={error} onRetry={() => void search()} /> : null}
        {searching ? <div className="thinking-card"><div className="thinking-orb"><span /><span /><span /></div><div><strong>권한이 있는 문서에서 답을 찾고 있어요</strong><p>벡터 유사도 검색을 진행 중입니다.</p></div></div> : null}
        <div className="conversation-turns">{turns.map((turn, index) => <ConversationTurnView key={turn.queryId} turn={turn} latest={index === turns.length - 1} longWait={longWait && turn.queryId === result?.queryId} socketStatus={turn.queryId === result?.queryId ? socketStatus : null} />)}</div>
      </div>}
    </section>
  );
}

function ConversationTurnView({ turn, latest, longWait, socketStatus }: { turn: SearchConversationTurn; latest: boolean; longWait: boolean; socketStatus: string | null }) {
  const groupedSources = groupSearchSources(turn.response);
  // 확정된 SUCCESS 답변에 Citation이 없으면 "관련 문서 없음"이라는 서버 판단을 그대로 따른다.
  const showRawResults = turn.response.results.length > 0
    && (turn.response.ragStatus === "PROCESSING" || turn.response.ragStatus === "FAILED");
  const hasSources = showRawResults || groupedSources.length > 0;
  const answerStatus = turn.response.ragStatus === "PROCESSING"
    ? (socketStatus === "LIVE" ? "답변 생성 중 · 실시간 연결됨" : "답변 생성 중 · 자동 갱신 대기")
    : turn.response.ragStatus === "FAILED"
      ? "AI 생성 실패 · 검색 결과 저장됨"
      : "답변 저장됨";

  return <section className="conversation-turn">
    <div className="conversation-user-message"><span>나</span><div><p>{turn.queryText}</p><small>{formatDate(turn.createdAt)}</small></div></div>
    <div className="results-meta"><span>AI 답변</span><small>{answerStatus}</small></div>
    <article className="answer-card"><div className="answer-icon">✦</div><div className="answer-content">
      {turn.response.ragStatus === "PROCESSING"
        ? <p className="answer-loading"><span className="thinking-orb"><span /><span /><span /></span>{longWait ? "생각보다 오래 걸리고 있어요. 조금만 더 기다려 주세요…" : "AI가 답변을 정리하고 있어요…"}</p>
        : <p>{turn.response.answer || "접근 가능한 문서에서 답을 생성하지 못했습니다."}</p>}
    </div></article>
    {hasSources ? <details className="conversation-sources" open={latest}><summary><span>검색 결과와 근거 문서</span><small>{showRawResults ? turn.response.results.length : groupedSources.length}개 보기</small></summary><div className="source-list">
      {showRawResults
        ? turn.response.results.map((item) => <a className="source-card" key={`${item.documentId}-${item.chunkId}`} href={`/documents/${item.documentId}`} target="_top"><span className="source-labels"><span className="source-number">{`[${item.rank}]`}</span></span><div><div className="source-title"><h3>{item.documentTitle}</h3><StatusPill value={`${(item.similarityScore * 100).toFixed(1)}%`} /></div><p>{`"${item.chunkText}"`}</p><span>{item.pageNo ? `${item.pageNo}페이지 · ` : ""}문서 상세 보기 →</span></div></a>)
        : groupedSources.map((source) => <a className="source-card" key={source.documentId} href={`/documents/${source.documentId}`} target="_top"><span className="source-labels">{source.labels.map((label) => <span className="source-number" key={label}>{label}</span>)}</span><div><div className="source-title"><h3>{source.documentTitle}</h3>{source.similarityScore !== null ? <StatusPill value={`${(source.similarityScore * 100).toFixed(1)}%`} /> : null}</div><p>{source.excerpts.map((excerpt) => `“${excerpt}”`).join(" · ")}</p><span>{source.chunkCount > 1 ? `${source.chunkCount}개 근거 · ` : ""}{source.pages.length > 0 ? `${source.pages.map((page) => `${page}페이지`).join(", ")} · ` : ""}문서 상세 보기 →</span></div></a>)}
    </div></details> : null}
  </section>;
}
