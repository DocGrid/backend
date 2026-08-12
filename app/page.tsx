"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";

type View = "search" | "documents" | "collections" | "operations";

const navItems: { id: View; label: string; symbol: string }[] = [
  { id: "search", label: "AI 검색", symbol: "⌕" },
  { id: "documents", label: "문서", symbol: "▤" },
  { id: "collections", label: "컬렉션", symbol: "▱" },
  { id: "operations", label: "운영 현황", symbol: "⌁" },
];

const suggestions = [
  "신규 입사자 온보딩 절차는?",
  "법인카드 사용 기준 알려줘",
  "프로덕션 장애 대응 순서",
];

const demoDocuments = [
  {
    title: "2026 제품 로드맵",
    type: "PDF",
    owner: "제품전략팀",
    updated: "12분 전",
    status: "검색 가능",
    tone: "violet",
  },
  {
    title: "신규 입사자 온보딩 가이드",
    type: "DOCX",
    owner: "People 팀",
    updated: "어제",
    status: "검색 가능",
    tone: "green",
  },
  {
    title: "보안 사고 대응 매뉴얼 v3",
    type: "PDF",
    owner: "보안팀",
    updated: "8월 8일",
    status: "검색 가능",
    tone: "orange",
  },
  {
    title: "고객 인터뷰 아카이브",
    type: "MD",
    owner: "UX 리서치",
    updated: "8월 5일",
    status: "인덱싱 중",
    tone: "blue",
  },
];

const sources = [
  {
    label: "[1]",
    title: "신규 입사자 온보딩 가이드",
    page: "4페이지",
    score: "94%",
    quote:
      "입사 첫날에는 오전 10시까지 People 팀 오리엔테이션에 참석하고, 계정 및 장비 수령 여부를 확인합니다.",
  },
  {
    label: "[2]",
    title: "IT 계정 및 장비 지급 정책",
    page: "2페이지",
    score: "89%",
    quote:
      "업무 시스템 계정은 입사일 기준으로 자동 발급되며, 추가 권한은 팀 리더 승인 후 부여됩니다.",
  },
  {
    label: "[3]",
    title: "첫 주 체크리스트",
    page: "1페이지",
    score: "86%",
    quote:
      "첫 주 안에 버디 미팅, 팀별 업무 소개, 보안 교육을 완료하고 체크리스트를 제출합니다.",
  },
];

const initialJobs = [
  {
    id: "#1842",
    document: "2026 영업 플레이북.pdf",
    status: "실패",
    error: "EMBEDDING_TIMEOUT",
    time: "3분 전",
  },
  {
    id: "#1839",
    document: "정보보호 교육자료.docx",
    status: "실패",
    error: "PARSER_ERROR",
    time: "18분 전",
  },
  {
    id: "#1834",
    document: "시장 분석 보고서.pdf",
    status: "처리 중",
    error: "—",
    time: "26분 전",
  },
];

function BrandMark() {
  return (
    <div className="brand-mark" aria-hidden="true">
      <span />
      <span />
      <span />
    </div>
  );
}

function StatusPill({ children, kind = "neutral" }: { children: React.ReactNode; kind?: string }) {
  return <span className={`status-pill ${kind}`}>{children}</span>;
}

export default function Home() {
  const [view, setView] = useState<View>("search");
  const [query, setQuery] = useState("");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [collectionOpen, setCollectionOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [selectedFile, setSelectedFile] = useState("");
  const [uploading, setUploading] = useState(false);
  const [toast, setToast] = useState("");
  const [selectedSource, setSelectedSource] = useState<(typeof sources)[number] | null>(null);
  const [jobs, setJobs] = useState(initialJobs);

  const pageTitle = useMemo(
    () => navItems.find((item) => item.id === view)?.label ?? "AI 검색",
    [view],
  );

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  function navigate(nextView: View) {
    setView(nextView);
    setMobileNavOpen(false);
  }

  function submitSearch(event?: FormEvent) {
    event?.preventDefault();
    if (!query.trim()) return;

    // The short delay keeps the prototype interaction close to an AI answer flow.
    setSearching(true);
    setSubmittedQuery("");
    window.setTimeout(() => {
      setSubmittedQuery(query.trim());
      setSearching(false);
    }, 720);
  }

  function runSuggestion(suggestion: string) {
    setQuery(suggestion);
    setSearching(true);
    window.setTimeout(() => {
      setSubmittedQuery(suggestion);
      setSearching(false);
    }, 620);
  }

  function finishUpload() {
    if (!selectedFile) return;
    setUploading(true);
    window.setTimeout(() => {
      setUploading(false);
      setUploadOpen(false);
      setSelectedFile("");
      setToast("문서가 등록되었습니다. 인덱싱을 시작합니다.");
    }, 1000);
  }

  function retryJob(id: string) {
    setJobs((current) =>
      current.map((job) =>
        job.id === id ? { ...job, status: "대기 중", error: "—", time: "방금" } : job,
      ),
    );
    setToast(`${id} 작업을 다시 대기열에 추가했습니다.`);
  }

  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileNavOpen ? "open" : ""}`}>
        <div className="brand">
          <BrandMark />
          <strong>DocGrid</strong>
        </div>

        <button className="primary-button sidebar-upload" onClick={() => setUploadOpen(true)}>
          <span>＋</span> 문서 업로드
        </button>

        <nav className="side-nav" aria-label="주요 메뉴">
          <p className="nav-label">WORKSPACE</p>
          {navItems.slice(0, 3).map((item) => (
            <button
              className={view === item.id ? "active" : ""}
              key={item.id}
              onClick={() => navigate(item.id)}
            >
              <span className="nav-symbol">{item.symbol}</span>
              {item.label}
              {item.id === "documents" && <small>24</small>}
            </button>
          ))}

          <p className="nav-label nav-label-admin">ADMIN</p>
          {navItems.slice(3).map((item) => (
            <button
              className={view === item.id ? "active" : ""}
              key={item.id}
              onClick={() => navigate(item.id)}
            >
              <span className="nav-symbol">{item.symbol}</span>
              {item.label}
              <span className="alert-dot" aria-label="주의 항목 있음" />
            </button>
          ))}
        </nav>

        <div className="workspace-card">
          <div className="workspace-icon">B</div>
          <div>
            <strong>브릭스 주식회사</strong>
            <span>Enterprise workspace</span>
          </div>
          <button aria-label="워크스페이스 설정">•••</button>
        </div>
      </aside>

      {mobileNavOpen && <button className="nav-backdrop" aria-label="메뉴 닫기" onClick={() => setMobileNavOpen(false)} />}

      <main className="main-area">
        <header className="topbar">
          <div className="topbar-left">
            <button className="mobile-menu" aria-label="메뉴 열기" onClick={() => setMobileNavOpen(true)}>
              ☰
            </button>
            <span className="breadcrumb">DocGrid</span>
            <span className="breadcrumb-divider">/</span>
            <strong>{pageTitle}</strong>
          </div>
          <div className="topbar-actions">
            <button className="icon-button" aria-label="알림">
              ♢<span className="notification-dot" />
            </button>
            <div className="profile">
              <div className="avatar">김</div>
              <div>
                <strong>김민지</strong>
                <span>Product Designer</span>
              </div>
              <span className="chevron">⌄</span>
            </div>
          </div>
        </header>

        {view === "search" && (
          <section className={`content search-view ${submittedQuery ? "has-results" : ""}`}>
            {!submittedQuery && !searching ? (
              <div className="search-landing">
                <div className="eyebrow"><span>✦</span> AI KNOWLEDGE SEARCH</div>
                <h1>팀의 지식에서<br /><em>정확한 답</em>을 찾으세요.</h1>
                <p className="hero-copy">흩어진 사내 문서를 한곳에서 검색하고, 근거가 포함된 답변을 바로 확인하세요.</p>

                <form className="hero-search" onSubmit={submitSearch}>
                  <span className="search-icon">⌕</span>
                  <input
                    aria-label="문서 검색"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    placeholder="무엇이든 물어보세요"
                  />
                  <span className="shortcut">⌘ K</span>
                  <button aria-label="검색 실행">↑</button>
                </form>

                <div className="suggestions">
                  <span>추천 질문</span>
                  {suggestions.map((suggestion) => (
                    <button key={suggestion} onClick={() => runSuggestion(suggestion)}>
                      {suggestion}<b>↗</b>
                    </button>
                  ))}
                </div>

                <div className="recent-section">
                  <div className="section-heading">
                    <div>
                      <h2>최근 업데이트</h2>
                      <p>새롭게 추가되거나 변경된 문서예요.</p>
                    </div>
                    <button onClick={() => navigate("documents")}>전체 보기 <span>→</span></button>
                  </div>
                  <div className="document-grid">
                    {demoDocuments.slice(0, 3).map((document) => (
                      <button className="document-card" key={document.title} onClick={() => setToast(`${document.title} 문서를 열었습니다.`)}>
                        <div className={`file-badge ${document.tone}`}><span>▤</span>{document.type}</div>
                        <h3>{document.title}</h3>
                        <p>{document.owner}</p>
                        <div className="card-footer">
                          <span>{document.updated}</span>
                          <span className="card-arrow">↗</span>
                        </div>
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            ) : (
              <div className="results-page">
                <form className="results-search" onSubmit={submitSearch}>
                  <span>⌕</span>
                  <input value={query} onChange={(event) => setQuery(event.target.value)} aria-label="검색어" />
                  <button>검색</button>
                </form>

                {searching ? (
                  <div className="thinking-card">
                    <div className="thinking-orb"><span /><span /><span /></div>
                    <div><strong>문서에서 답을 찾고 있어요</strong><p>접근 가능한 문서의 의미를 분석하고 있습니다.</p></div>
                  </div>
                ) : (
                  <>
                    <div className="results-meta"><span>AI 답변</span><small>3개의 문서를 참고했어요</small></div>
                    <article className="answer-card">
                      <div className="answer-icon">✦</div>
                      <div className="answer-content">
                        <h2>{submittedQuery}</h2>
                        <p>신규 입사자는 첫날 오전 10시까지 <strong>People 팀 오리엔테이션</strong>에 참석한 뒤 업무 계정과 장비를 수령합니다. <button onClick={() => setSelectedSource(sources[0])}>[1]</button></p>
                        <p>첫 주 안에는 버디 미팅, 팀별 업무 소개, 필수 보안 교육을 완료해야 하며, 추가 시스템 권한은 팀 리더 승인 후 부여됩니다. <button onClick={() => setSelectedSource(sources[1])}>[2]</button> <button onClick={() => setSelectedSource(sources[2])}>[3]</button></p>
                        <div className="answer-actions">
                          <span>답변이 도움이 되었나요?</span>
                          <button aria-label="도움됨">♡</button>
                          <button aria-label="도움되지 않음">♢</button>
                          <button onClick={() => { navigator.clipboard?.writeText("신규 입사자 온보딩 안내"); setToast("답변을 복사했습니다."); }}>▣ 복사</button>
                        </div>
                      </div>
                    </article>

                    <div className="source-heading"><h2>근거 문서</h2><span>유사도 높은 순</span></div>
                    <div className="source-list">
                      {sources.map((source) => (
                        <button className="source-card" key={source.label} onClick={() => setSelectedSource(source)}>
                          <span className="source-number">{source.label}</span>
                          <div>
                            <div className="source-title"><h3>{source.title}</h3><StatusPill kind="match">{source.score} 일치</StatusPill></div>
                            <p>“{source.quote}”</p>
                            <span>{source.page} · 문서 열기 ↗</span>
                          </div>
                        </button>
                      ))}
                    </div>
                  </>
                )}
              </div>
            )}
          </section>
        )}

        {view === "documents" && (
          <section className="content page-view">
            <div className="page-header">
              <div><span className="page-kicker">KNOWLEDGE BASE</span><h1>문서</h1><p>검색에 활용되는 팀 문서를 관리하세요.</p></div>
              <button className="primary-button" onClick={() => setUploadOpen(true)}>＋ 문서 업로드</button>
            </div>
            <div className="toolbar">
              <div className="toolbar-search"><span>⌕</span><input placeholder="문서 이름 검색" /></div>
              <button>모든 형식 <span>⌄</span></button>
              <button>모든 상태 <span>⌄</span></button>
              <span className="toolbar-count">총 24개 문서</span>
            </div>
            <div className="table-card document-table">
              <div className="table-row table-head"><span>문서</span><span>소유 팀</span><span>상태</span><span>최근 수정</span><span /></div>
              {demoDocuments.map((document) => (
                <div className="table-row" key={document.title}>
                  <div className="doc-name"><div className={`file-square ${document.tone}`}>▤</div><div><strong>{document.title}</strong><small>{document.type} · 2.4 MB</small></div></div>
                  <span>{document.owner}</span>
                  <span><StatusPill kind={document.status === "검색 가능" ? "success" : "progress"}>{document.status}</StatusPill></span>
                  <span>{document.updated}</span>
                  <button className="more-button">•••</button>
                </div>
              ))}
            </div>
          </section>
        )}

        {view === "collections" && (
          <section className="content page-view">
            <div className="page-header">
              <div><span className="page-kicker">CURATED KNOWLEDGE</span><h1>컬렉션</h1><p>주제와 팀별로 문서를 묶어 검색 범위를 관리하세요.</p></div>
              <button className="primary-button" onClick={() => setCollectionOpen(true)}>＋ 새 컬렉션</button>
            </div>
            <div className="collection-grid">
              {[
                { name: "신규 입사자 허브", description: "온보딩, 복지, 보안 교육에 필요한 문서", docs: 8, color: "#6d5dfc", members: ["김", "이", "박"] },
                { name: "제품 전략", description: "로드맵과 고객 리서치, 제품 의사결정 기록", docs: 12, color: "#1ca887", members: ["최", "정"] },
                { name: "운영 매뉴얼", description: "서비스 운영과 장애 대응을 위한 가이드", docs: 6, color: "#e88a35", members: ["오", "한", "유"] },
                { name: "보안·컴플라이언스", description: "보안 정책과 필수 규정, 감사 자료", docs: 9, color: "#3977d6", members: ["문", "장"] },
              ].map((collection) => (
                <button className="collection-card" key={collection.name} onClick={() => setToast(`${collection.name} 컬렉션을 열었습니다.`)}>
                  <div className="collection-top"><span className="folder-shape" style={{ "--folder-color": collection.color } as React.CSSProperties}>▱</span><span>•••</span></div>
                  <h2>{collection.name}</h2><p>{collection.description}</p>
                  <div className="collection-footer"><div className="member-stack">{collection.members.map((member, index) => <span key={`${member}-${index}`}>{member}</span>)}</div><strong>{collection.docs}개 문서</strong></div>
                </button>
              ))}
            </div>
          </section>
        )}

        {view === "operations" && (
          <section className="content page-view operations-view">
            <div className="page-header operations-header">
              <div><span className="page-kicker">RAGOPS</span><h1>운영 현황</h1><p>문서 인덱싱과 검색 시스템 상태를 실시간으로 확인하세요.</p></div>
              <div className="live-indicator"><span /> 모든 시스템 정상 <small>방금 업데이트</small></div>
            </div>
            <div className="metric-grid">
              <div className="metric-card"><div><span>전체 문서</span><b>24,892</b></div><span className="metric-icon purple">▤</span><small><b>↑ 8.4%</b> 지난달 대비</small></div>
              <div className="metric-card"><div><span>검색 가능</span><b>23,741</b></div><span className="metric-icon green">✓</span><small><b>95.4%</b> 인덱싱 완료</small></div>
              <div className="metric-card"><div><span>처리 대기</span><b>132</b></div><span className="metric-icon orange">◷</span><small><b>8건</b> 현재 처리 중</small></div>
              <div className="metric-card"><div><span>24시간 검색</span><b>1,284</b></div><span className="metric-icon blue">⌕</span><small><b>↑ 12.7%</b> 어제 대비</small></div>
            </div>
            <div className="ops-grid">
              <div className="system-card">
                <div className="panel-heading"><div><h2>시스템 상태</h2><p>Worker 및 외부 서비스 연결</p></div><button>상세 보기 →</button></div>
                {[
                  ["Indexing Worker", "5 / 6 활성", "정상"],
                  ["Embedding Server", "평균 228ms", "정상"],
                  ["Ollama RAG", "평균 1.8s", "정상"],
                ].map(([name, detail, status]) => <div className="system-row" key={name}><span className="system-dot" /><strong>{name}</strong><span>{detail}</span><StatusPill kind="success">{status}</StatusPill></div>)}
              </div>
              <div className="performance-card">
                <div className="panel-heading"><div><h2>평균 처리 시간</h2><p>최근 7일 인덱싱 성능</p></div><StatusPill kind="match">3.2초</StatusPill></div>
                <div className="bar-chart" aria-label="최근 7일 평균 처리 시간 차트">
                  {[54, 68, 49, 78, 62, 88, 64].map((height, index) => <span key={index} style={{ height: `${height}%` }} />)}
                </div>
                <div className="chart-days"><span>월</span><span>화</span><span>수</span><span>목</span><span>금</span><span>토</span><span>일</span></div>
              </div>
            </div>
            <div className="table-card jobs-card">
              <div className="panel-heading jobs-heading"><div><h2>최근 인덱싱 작업</h2><p>실패한 작업은 원인을 확인하고 다시 처리할 수 있어요.</p></div><button className="secondary-button" onClick={() => setToast("실패한 작업 2건을 대기열에 추가했습니다.")}>모두 재시도</button></div>
              <div className="job-row job-head"><span>작업 ID</span><span>문서</span><span>상태</span><span>오류 코드</span><span>업데이트</span><span /></div>
              {jobs.map((job) => <div className="job-row" key={job.id}><strong>{job.id}</strong><span>{job.document}</span><span><StatusPill kind={job.status === "실패" ? "danger" : job.status === "처리 중" ? "progress" : "neutral"}>{job.status}</StatusPill></span><code>{job.error}</code><span>{job.time}</span>{job.status === "실패" ? <button className="retry-button" onClick={() => retryJob(job.id)}>↻ 재시도</button> : <button className="more-button">•••</button>}</div>)}
            </div>
          </section>
        )}
      </main>

      {uploadOpen && (
        <div className="modal-layer" role="presentation" onMouseDown={() => setUploadOpen(false)}>
          <div className="modal" role="dialog" aria-modal="true" aria-labelledby="upload-title" onMouseDown={(event) => event.stopPropagation()}>
            <div className="modal-header"><div><span className="modal-symbol">⇧</span><div><h2 id="upload-title">문서 업로드</h2><p>검색할 수 있는 새 문서를 추가합니다.</p></div></div><button onClick={() => setUploadOpen(false)}>×</button></div>
            <label className={`dropzone ${selectedFile ? "selected" : ""}`}>
              <input type="file" accept=".pdf,.docx,.txt,.md" onChange={(event) => setSelectedFile(event.target.files?.[0]?.name ?? "")} />
              <span className="upload-illustration">▤</span>
              {selectedFile ? <><strong>{selectedFile}</strong><small>업로드할 준비가 되었습니다.</small></> : <><strong>파일을 끌어놓거나 클릭해 선택하세요</strong><small>PDF, DOCX, TXT, MD · 최대 50MB</small></>}
            </label>
            <div className="form-field"><label htmlFor="doc-title">문서 제목</label><input id="doc-title" defaultValue={selectedFile.replace(/\.[^.]+$/, "")} placeholder="문서 제목을 입력하세요" /></div>
            <div className="form-field"><label htmlFor="visibility">공개 범위</label><select id="visibility"><option>나만 보기</option><option>워크스페이스 전체</option><option>특정 컬렉션</option></select></div>
            <div className="modal-footer"><button className="secondary-button" onClick={() => setUploadOpen(false)}>취소</button><button className="primary-button" disabled={!selectedFile || uploading} onClick={finishUpload}>{uploading ? "업로드 중…" : "업로드 시작"}</button></div>
          </div>
        </div>
      )}

      {collectionOpen && (
        <div className="modal-layer" role="presentation" onMouseDown={() => setCollectionOpen(false)}>
          <div className="modal compact-modal" role="dialog" aria-modal="true" aria-labelledby="collection-title" onMouseDown={(event) => event.stopPropagation()}>
            <div className="modal-header"><div><span className="modal-symbol mint">▱</span><div><h2 id="collection-title">새 컬렉션</h2><p>관련 문서를 하나의 검색 범위로 묶습니다.</p></div></div><button onClick={() => setCollectionOpen(false)}>×</button></div>
            <div className="form-field"><label htmlFor="collection-name">컬렉션 이름</label><input id="collection-name" placeholder="예: 디자인 시스템" /></div>
            <div className="form-field"><label htmlFor="collection-description">설명</label><textarea id="collection-description" placeholder="이 컬렉션을 설명해 주세요." rows={3} /></div>
            <div className="form-field"><label htmlFor="collection-visibility">공개 범위</label><select id="collection-visibility"><option>비공개</option><option>워크스페이스 전체</option></select></div>
            <div className="modal-footer"><button className="secondary-button" onClick={() => setCollectionOpen(false)}>취소</button><button className="primary-button" onClick={() => { setCollectionOpen(false); setToast("새 컬렉션을 만들었습니다."); }}>컬렉션 만들기</button></div>
          </div>
        </div>
      )}

      {selectedSource && (
        <div className="source-drawer" role="dialog" aria-label="출처 미리보기">
          <div className="drawer-header"><span>{selectedSource.label}</span><button onClick={() => setSelectedSource(null)}>×</button></div>
          <div className="drawer-body"><StatusPill kind="match">{selectedSource.score} 일치</StatusPill><h2>{selectedSource.title}</h2><p className="drawer-page">{selectedSource.page}</p><div className="quote-block">“{selectedSource.quote}”</div><button className="primary-button" onClick={() => setToast("문서 미리보기를 열었습니다.")}>원문 열기 ↗</button></div>
        </div>
      )}

      {toast && <div className="toast"><span>✓</span>{toast}</div>}
    </div>
  );
}
