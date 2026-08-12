"use client";

import { FormEvent, useEffect, useMemo, useState } from "react";

type ModalName = "upload" | "version" | "collection" | "add-document" | "permission" | "token" | "role" | null;

const navSections = [
  {
    label: "WORKSPACE",
    items: [
      ["/search", "⌕", "AI 검색"],
      ["/documents", "▤", "문서"],
      ["/collections", "▱", "컬렉션"],
      ["/permissions", "⌘", "권한 관리"],
    ],
  },
  {
    label: "INTEGRATION",
    items: [["/mcp-tokens", "⌁", "MCP 토큰"]],
  },
  {
    label: "ADMIN",
    items: [
      ["/admin/dashboard", "◫", "RAGOps"],
      ["/admin/indexing-jobs", "⚙", "인덱싱 Job"],
      ["/admin/workers", "▰", "Worker"],
      ["/admin/users", "♙", "사용자·역할"],
    ],
  },
] as const;

const documents = [
  { id: 1024, title: "배포 운영 가이드", type: "PDF", owner: "플랫폼개발팀", version: "v3", status: "EMBEDDING", visibility: "DEPARTMENT", updated: "12분 전", tone: "violet" },
  { id: 1018, title: "신규 입사자 온보딩 가이드", type: "DOCX", owner: "People 팀", version: "v2", status: "INDEXED", visibility: "PUBLIC", updated: "어제", tone: "green" },
  { id: 996, title: "보안 사고 대응 매뉴얼", type: "PDF", owner: "보안팀", version: "v3", status: "INDEXED", visibility: "DEPARTMENT", updated: "8월 8일", tone: "orange" },
  { id: 971, title: "고객 인터뷰 아카이브", type: "MD", owner: "UX 리서치", version: "v1", status: "PARSING", visibility: "COLLECTION", updated: "8월 5일", tone: "blue" },
  { id: 954, title: "법인카드 사용 정책", type: "PDF", owner: "재무팀", version: "v4", status: "FAILED", visibility: "PUBLIC", updated: "8월 1일", tone: "red" },
];

const collections = [
  { id: 12, name: "운영 문서", description: "배포·장애 대응에 필요한 팀 운영 가이드", docs: 8, visibility: "DEPARTMENT", status: "ACTIVE", color: "#6558e8" },
  { id: 18, name: "신규 입사자 허브", description: "온보딩, 복지, 보안 교육에 필요한 문서", docs: 12, visibility: "PUBLIC", status: "ACTIVE", color: "#1b9d82" },
  { id: 21, name: "제품 전략", description: "로드맵과 고객 리서치, 제품 의사결정 기록", docs: 6, visibility: "PRIVATE", status: "ACTIVE", color: "#e88a35" },
  { id: 7, name: "2025 아카이브", description: "지난 연도의 정책과 종료된 프로젝트 자료", docs: 24, visibility: "PRIVATE", status: "ARCHIVED", color: "#667085" },
];

const sources = [
  { label: "[1]", title: "배포 운영 가이드", page: "12페이지", score: "91.3%", quote: "헬스체크가 2회 연속 실패하면 배포 파이프라인은 직전 안정 릴리스 태그로 자동 롤백을 트리거합니다." },
  { label: "[2]", title: "DB 마이그레이션 정책", page: "4페이지", score: "87.4%", quote: "Flyway 마이그레이션은 되돌릴 수 없으므로 롤백 시 역방향 스크립트를 수동으로 준비합니다." },
  { label: "[3]", title: "인시던트 대응 SOP", page: "2페이지", score: "80.2%", quote: "장애 등급 산정 후 24시간 이내 보고서를 공유하고 후속 액션을 담당자에게 배정합니다." },
];

const initialJobs = [
  { id: 4417, status: "PROCESSING", document: "배포 운영 가이드", version: "v3 · EMBEDDING", model: "BAAI/bge-m3", worker: "indexing-worker-01", retry: "0 / 3", error: "—", created: "08-12 09:41" },
  { id: 4402, status: "FAILED", document: "DB 마이그레이션 정책", version: "v1 · FAILED", model: "BAAI/bge-m3", worker: "indexing-worker-03", retry: "3 / 3", error: "EMBEDDING_PROVIDER_UNAVAILABLE", created: "08-12 08:12" },
  { id: 4396, status: "PENDING", document: "보안 점검 체크리스트", version: "v1 · UPLOADED", model: "BAAI/bge-m3", worker: "—", retry: "1 / 3", error: "—", created: "08-12 07:58" },
  { id: 4381, status: "INDEXED", document: "인시던트 대응 SOP", version: "v2 · INDEXED", model: "BAAI/bge-m3", worker: "indexing-worker-02", retry: "0 / 3", error: "—", created: "08-11 22:04" },
  { id: 4377, status: "FAILED", document: "사내 규정 2026", version: "v1 · FAILED", model: "BAAI/bge-m3", worker: "indexing-worker-03", retry: "3 / 3", error: "DOCUMENT_CONTENT_INVALID", created: "08-11 19:30" },
  { id: 4351, status: "FAILED", document: "보안 점검 체크리스트", version: "v1 · FAILED", model: "BAAI/bge-m3", worker: "indexing-worker-01", retry: "3 / 3", error: "STORAGE_UNAVAILABLE", created: "08-11 13:07" },
];

const initialTokens = [
  { id: 41, created: "2026-08-12 10:02", lastUsed: "—", status: "사용 가능" },
  { id: 33, created: "2026-07-02 09:15", lastUsed: "2026-08-11 18:44", status: "사용 가능" },
  { id: 28, created: "2026-05-20 13:31", lastUsed: "2026-06-30 11:07", status: "폐기됨" },
];

const workers = [
  { name: "indexing-worker-01", id: "#1", status: "ACTIVE", host: "docgrid-api-01", ip: "10.0.0.12", instance: "2f3a2d8c...9abc", heartbeat: "3초 전", started: "08-12 07:30" },
  { name: "indexing-worker-02", id: "#2", status: "ACTIVE", host: "docgrid-api-02", ip: "10.0.0.13", instance: "7b11c4de...10f2", heartbeat: "2초 전", started: "08-12 07:30" },
  { name: "indexing-worker-03", id: "#3", status: "DEAD", host: "docgrid-api-03", ip: "10.0.0.14", instance: "c93aa07f...4d81", heartbeat: "6분 전", started: "08-12 07:30" },
  { name: "indexing-worker-04", id: "#4", status: "ACTIVE", host: "docgrid-api-04", ip: "10.0.0.15", instance: "18ee9b23...77a0", heartbeat: "1초 전", started: "08-12 07:31" },
  { name: "indexing-worker-05", id: "#5", status: "IDLE", host: "docgrid-api-05", ip: "10.0.0.16", instance: "a02f7cc1...be44", heartbeat: "4초 전", started: "08-12 07:31" },
  { name: "indexing-worker-06", id: "#6", status: "IDLE", host: "docgrid-api-06", ip: "10.0.0.17", instance: "d5c8100b...31ac", heartbeat: "5초 전", started: "08-12 07:31" },
];

const initialUsers = [
  { id: 21, name: "이수진", email: "sujin@docgrid.io", department: "플랫폼개발팀", roles: ["USER"], status: "ACTIVE" },
  { id: 18, name: "박준호", email: "junho@docgrid.io", department: "인프라팀", roles: ["USER", "DOCUMENT_MANAGER"], status: "ACTIVE" },
  { id: 7, name: "김기민", email: "gimin@docgrid.io", department: "플랫폼개발팀", roles: ["USER", "ADMIN"], status: "ACTIVE" },
  { id: 29, name: "최다은", email: "daeun@docgrid.io", department: "경영지원팀", roles: ["USER"], status: "LOCKED" },
];

function BrandMark() {
  return <div className="brand-mark" aria-hidden="true"><span /><span /><span /></div>;
}

function StatusPill({ value }: { value: string }) {
  const normalized = value.toLowerCase().replaceAll(" ", "-");
  return <span className={`status-pill status-${normalized}`}>{value}</span>;
}

function ApiFlag({ children }: { children: React.ReactNode }) {
  return <span className="api-flag">{children}</span>;
}

function PageHeading({
  kicker,
  title,
  description,
  actions,
}: {
  kicker: string;
  title: string;
  description: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="page-heading">
      <div><span className="page-kicker">{kicker}</span><h1>{title}</h1><p>{description}</p></div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

function EmptyState({ symbol, title, description }: { symbol: string; title: string; description: string }) {
  return <div className="empty-state"><span>{symbol}</span><strong>{title}</strong><p>{description}</p></div>;
}

function AuthPage({ mode }: { mode: "login" | "signup" }) {
  const signup = mode === "signup";

  function submit(event: FormEvent) {
    event.preventDefault();
    window.location.href = "/search";
  }

  return (
    <main className="auth-shell">
      <a className="auth-skip" href="/search">로그인 없이 둘러보기 →</a>
      <section className="auth-brand-panel">
        <div className="auth-brand"><BrandMark /><strong>DocGrid</strong></div>
        <div>
          <span className="auth-eyebrow">KNOWLEDGE, CONNECTED</span>
          <h1>{signup ? <>팀의 지식을<br />한곳에서 시작하세요.</> : <>흩어진 문서에서<br /><em>정확한 답</em>을 찾으세요.</>}</h1>
          <p>{signup ? "가입과 동시에 USER 권한이 부여되며 소속 부서의 문서를 탐색할 수 있습니다." : "권한이 허용된 사내 문서를 검색하고 근거가 포함된 AI 답변을 확인하세요."}</p>
        </div>
        <ul><li>문서 업로드부터 임베딩·색인까지 자동화</li><li>권한 사전 필터와 실시간 접근 검증</li><li>MCP 토큰으로 AI 클라이언트 연동</li></ul>
      </section>
      <section className="auth-form-panel">
        <form className="auth-form" onSubmit={submit}>
          <div className="auth-mobile-brand"><BrandMark /><strong>DocGrid</strong></div>
          <span className="page-kicker">{signup ? "CREATE ACCOUNT" : "WELCOME BACK"}</span>
          <h2>{signup ? "회원가입" : "로그인"}</h2>
          <p>{signup ? "모든 항목은 필수입니다." : "이메일과 비밀번호를 입력하세요."}</p>
          <label>이메일 <input type="email" defaultValue={signup ? "" : "gimin@docgrid.io"} placeholder="name@company.com" required /></label>
          <label>비밀번호 <input type="password" defaultValue={signup ? "" : "docgrid123"} placeholder="8자 이상" required /></label>
          {signup && <><label>이름 <input placeholder="홍길동" required /></label><label>부서 <select defaultValue="3"><option value="3">플랫폼개발팀 · PLT</option><option value="5">People 팀 · PPL</option><option value="7">보안팀 · SEC</option></select></label></>}
          <button className="primary-button auth-submit">{signup ? "가입하고 시작하기" : "로그인"}</button>
          <span className="auth-switch">{signup ? "이미 계정이 있나요?" : "계정이 없으신가요?"} <a href={signup ? "/login" : "/signup"}>{signup ? "로그인" : "회원가입"}</a></span>
        </form>
      </section>
    </main>
  );
}

export default function PrototypeApp({ initialRoute }: { initialRoute: string }) {
  const route = initialRoute === "/" ? "/search" : initialRoute;
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [modal, setModal] = useState<ModalName>(null);
  const [toast, setToast] = useState("");
  const [query, setQuery] = useState("배포 실패 시 롤백 절차가 어떻게 되나요?");
  const [submittedQuery, setSubmittedQuery] = useState("");
  const [searching, setSearching] = useState(false);
  const [selectedFile, setSelectedFile] = useState("");
  const [uploading, setUploading] = useState(false);
  const [jobs, setJobs] = useState(initialJobs);
  const [tokens, setTokens] = useState(initialTokens);
  const [issuedToken, setIssuedToken] = useState("");
  const [permissions, setPermissions] = useState([
    { id: 81, target: "이수진", type: "USER", permission: "WRITE", expires: "2026-12-31" },
    { id: 67, target: "플랫폼개발팀", type: "DEPARTMENT", permission: "READ", expires: "없음" },
    { id: 49, target: "DOCUMENT_MANAGER", type: "ROLE", permission: "ADMIN", expires: "없음" },
  ]);
  const [users, setUsers] = useState(initialUsers);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(""), 2500);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const routeTitle = useMemo(() => {
    if (route.startsWith("/documents/")) return "문서 상세";
    if (route.startsWith("/collections/")) return "컬렉션 상세";
    if (route.startsWith("/admin/indexing-jobs/")) return "Job 상세";
    const titles: Record<string, string> = {
      "/search": "AI 검색", "/documents": "문서", "/collections": "컬렉션", "/permissions": "권한 관리",
      "/mcp-tokens": "MCP 토큰", "/account": "내 계정", "/admin/dashboard": "RAGOps",
      "/admin/indexing-jobs": "인덱싱 Job", "/admin/workers": "Worker", "/admin/users": "사용자·역할",
    };
    return titles[route] ?? "AI 검색";
  }, [route]);

  if (route === "/login" || route === "/signup") return <AuthPage mode={route === "/login" ? "login" : "signup"} />;

  function isActive(path: string) {
    if (path === "/documents") return route.startsWith("/documents");
    if (path === "/collections") return route.startsWith("/collections");
    if (path === "/admin/indexing-jobs") return route.startsWith("/admin/indexing-jobs");
    return route === path;
  }

  function submitSearch(event?: FormEvent) {
    event?.preventDefault();
    if (!query.trim()) return;
    // 1. Show the retrieval state before revealing the answer.
    setSearching(true);
    setSubmittedQuery("");
    // 2. Resolve to a deterministic demo response for prototype review.
    window.setTimeout(() => { setSubmittedQuery(query.trim()); setSearching(false); }, 700);
  }

  function finishUpload() {
    if (!selectedFile) return;
    // 1. Simulate upload and asynchronous indexing job creation.
    setUploading(true);
    // 2. Close with a status-oriented confirmation rather than claiming indexing completion.
    window.setTimeout(() => { setUploading(false); setModal(null); setSelectedFile(""); setToast("업로드가 접수되어 인덱싱을 시작했습니다."); }, 900);
  }

  function retryJob(id: number) {
    setJobs((current) => current.map((job) => job.id === id ? { ...job, status: "PENDING", error: "—", retry: "0 / 3" } : job));
    setToast(`#${id} 작업을 대기열에 다시 추가했습니다.`);
  }

  function issueToken() {
    const rawToken = "dgk_9f2c1a7e4b8d3056a1c9e7f2b4d80a63";
    setIssuedToken(rawToken);
    setTokens((current) => [{ id: 52, created: "방금", lastUsed: "—", status: "사용 가능" }, ...current]);
    setModal(null);
    setToast("새 MCP 토큰을 발급했습니다. 원문은 한 번만 표시됩니다.");
  }

  function renderPage() {
    if (route === "/search") return (
      <section className={`content search-view ${submittedQuery || searching ? "has-results" : ""}`}>
        {!submittedQuery && !searching ? (
          <div className="search-landing">
            <div className="eyebrow"><span>✦</span> AI KNOWLEDGE SEARCH</div>
            <h1>팀의 지식에서<br /><em>정확한 답</em>을 찾으세요.</h1>
            <p className="hero-copy">벡터 검색과 권한 검증을 거쳐, 출처가 명확한 답변을 제공합니다.</p>
            <form className="hero-search" onSubmit={submitSearch}><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} aria-label="문서 검색" /><button>↑</button></form>
            <div className="search-options"><label>검색 범위<select><option>전체 컬렉션</option><option>운영 문서</option><option>신규 입사자 허브</option></select></label><label>결과 수<select><option>Top-K 5</option><option>Top-K 10</option><option>Top-K 20</option></select></label></div>
            <div className="suggestions"><span>추천 질문</span>{["배포 실패 시 롤백 절차", "법인카드 사용 기준", "보안 사고 보고 순서"].map((item) => <button key={item} onClick={() => { setQuery(item); window.setTimeout(() => submitSearch(), 0); }}>{item}<b>↗</b></button>)}</div>
            <div className="feature-links"><a href="/documents"><span>▤</span><div><strong>문서 탐색</strong><small>24개 문서와 인덱싱 상태 보기</small></div><b>→</b></a><a href="/collections"><span>▱</span><div><strong>컬렉션</strong><small>주제별 검색 범위 관리하기</small></div><b>→</b></a><a href="/mcp-tokens"><span>⌁</span><div><strong>MCP 연동</strong><small>AI 클라이언트 연결하기</small></div><b>→</b></a></div>
          </div>
        ) : (
          <div className="results-page">
            <form className="results-search" onSubmit={submitSearch}><span>⌕</span><input value={query} onChange={(event) => setQuery(event.target.value)} /><select><option>전체 컬렉션</option><option>운영 문서</option></select><select><option>Top-K 5</option><option>Top-K 10</option></select><button>검색</button></form>
            {searching ? <div className="thinking-card"><div className="thinking-orb"><span /><span /><span /></div><div><strong>권한이 있는 문서에서 답을 찾고 있어요</strong><p>벡터 유사도와 live check를 적용 중입니다.</p></div></div> : <>
              <div className="results-meta"><span>AI 답변</span><small>3개의 문서를 참고했어요 · queryId 4821</small></div>
              <article className="answer-card"><div className="answer-icon">✦</div><div className="answer-content"><h2>{submittedQuery}</h2><p>배포 실패가 감지되면 먼저 <strong>헬스체크 실패 원인</strong>과 직전 안정 릴리스 태그를 확인합니다. <button>[1]</button> 롤백 후에는 DB 마이그레이션의 적용 여부를 점검해야 하며, 장애 보고서는 24시간 이내 공유합니다. <button>[2]</button> <button>[3]</button></p><div className="answer-actions"><span>답변이 도움이 되었나요?</span><button>♡</button><button>♢</button><button onClick={() => setToast("답변을 복사했습니다.")}>▣ 복사</button></div></div></article>
              <div className="source-heading"><h2>검색 결과와 근거 문서</h2><span>유사도 높은 순</span></div><div className="source-list">{sources.map((source) => <a className="source-card" key={source.label} href="/documents/1024"><span className="source-number">{source.label}</span><div><div className="source-title"><h3>{source.title}</h3><StatusPill value={source.score} /></div><p>“{source.quote}”</p><span>{source.page} · 문서 상세 보기 →</span></div></a>)}</div>
            </>}
          </div>
        )}
      </section>
    );

    if (route === "/documents") return (
      <section className="content page-view">
        <PageHeading kicker="KNOWLEDGE BASE" title="문서" description="검색에 활용되는 문서와 인덱싱 상태를 관리하세요." actions={<><ApiFlag>데모 데이터 · 목록 API 준비 중</ApiFlag><button className="primary-button" onClick={() => setModal("upload")}>＋ 문서 업로드</button></>} />
        <div className="toolbar"><div className="toolbar-search"><span>⌕</span><input placeholder="문서 이름 검색" /></div><button>모든 형식 ⌄</button><button>모든 상태 ⌄</button><button>모든 공개 범위 ⌄</button><span className="toolbar-count">총 24개 문서</span></div>
        <div className="data-table documents-table"><div className="data-row data-head"><span>문서</span><span>소유 팀</span><span>버전</span><span>상태</span><span>공개 범위</span><span>최근 수정</span><span /></div>{documents.map((document) => <div className="data-row" key={document.id}><div className="doc-name"><span className={`file-square ${document.tone}`}>▤</span><div><a href={`/documents/${document.id}`}>{document.title}</a><small>{document.type} · documentId {document.id}</small></div></div><span>{document.owner}</span><span>{document.version}</span><span><StatusPill value={document.status} /></span><span><StatusPill value={document.visibility} /></span><span>{document.updated}</span><a className="row-link" href={`/documents/${document.id}`}>→</a></div>)}</div>
      </section>
    );

    if (route.startsWith("/documents/")) return (
      <section className="content page-view">
        <div className="detail-back"><a href="/documents">← 문서 목록</a><span>documentId 1024</span></div>
        <PageHeading kicker="DOCUMENT DETAIL" title="배포 운영 가이드" description="현재 검색 가능한 버전과 처리 중인 새 버전을 분리해서 확인하세요." actions={<button className="primary-button" onClick={() => setModal("version")}>＋ 새 버전 올리기</button>} />
        <div className="progress-card"><div className="panel-heading"><div><h2>인덱싱 진행 상태</h2><p>3초 간격 폴링 · 현재 EMBEDDING 단계</p></div><StatusPill value="INDEXING" /></div><div className="stepper">{[["✓","UPLOADED","done"],["✓","PARSING","done"],["✓","CHUNKED","done"],["3","EMBEDDING","current"],["4","INDEXED",""]].map(([number,label,state]) => <div className={`step ${state}`} key={label}><span>{number}</span><strong>{label}</strong></div>)}</div></div>
        <div className="detail-grid three"><div className="panel-card"><div className="panel-heading"><h2>현재 검색 가능한 버전</h2><code>currentVersion</code></div><dl><div><dt>versionNo</dt><dd>v2</dd></div><div><dt>status</dt><dd><StatusPill value="INDEXED" /></dd></div><div><dt>공개 범위</dt><dd>DEPARTMENT</dd></div></dl><p className="panel-note">검색 결과에는 이 버전의 청크만 노출됩니다.</p></div><div className="panel-card"><div className="panel-heading"><h2>처리 중인 버전</h2><code>processingVersion</code></div><dl><div><dt>versionNo</dt><dd>v3</dd></div><div><dt>status</dt><dd><StatusPill value="EMBEDDING" /></dd></div><div><dt>jobStatus</dt><dd><StatusPill value="PROCESSING" /></dd></div></dl><p className="panel-note">완료되면 currentVersion이 v3으로 교체됩니다.</p></div><div className="panel-card"><div className="panel-heading"><h2>버전 히스토리</h2><span>3개</span></div><div className="mini-table"><div><b>v3</b><StatusPill value="EMBEDDING" /><span>오늘 09:41</span></div><div><b>v2</b><StatusPill value="INDEXED" /><span>07-30 14:02</span></div><div><b>v1</b><StatusPill value="FAILED" /><span>07-28 11:20</span></div></div></div></div>
        <div className="detail-actions-bar"><a href="/permissions">⌘ 이 문서 권한 관리</a><a href="/collections/12">▱ 포함된 컬렉션 보기</a><a href="/admin/indexing-jobs/4402">⚙ 인덱싱 Job 상세</a></div>
      </section>
    );

    if (route === "/collections") return (
      <section className="content page-view"><PageHeading kicker="CURATED KNOWLEDGE" title="컬렉션" description="문서를 주제와 팀별로 묶어 검색 범위를 관리하세요." actions={<button className="primary-button" onClick={() => setModal("collection")}>＋ 새 컬렉션</button>} /><div className="collection-grid">{collections.map((collection) => <a className="collection-card" href={`/collections/${collection.id}`} key={collection.id}><div className="collection-top"><span className="folder-shape" style={{ "--folder-color": collection.color } as React.CSSProperties}>▱</span><span>#{collection.id}</span></div><div className="collection-badges"><StatusPill value={collection.visibility} /><StatusPill value={collection.status} /></div><h2>{collection.name}</h2><p>{collection.description}</p><div className="collection-footer"><div className="member-stack"><span>김</span><span>이</span><span>박</span></div><strong>{collection.docs}개 문서 →</strong></div></a>)}</div></section>
    );

    if (route.startsWith("/collections/")) return (
      <section className="content page-view"><div className="detail-back"><a href="/collections">← 컬렉션 목록</a><span>collectionId 12</span></div><PageHeading kicker="COLLECTION DETAIL" title="운영 문서" description="배포·장애 대응에 필요한 팀 운영 가이드 모음입니다." actions={<><button className="secondary-button" onClick={() => setModal("permission")}>권한 관리</button><button className="primary-button" onClick={() => setModal("add-document")}>＋ 문서 추가</button></>} /><div className="collection-summary"><div><span className="folder-shape" style={{ "--folder-color": "#6558e8" } as React.CSSProperties}>▱</span><div><strong>운영 문서</strong><span>ownerUserId 7 · 2026-05-02 생성</span></div></div><div><StatusPill value="DEPARTMENT" /><StatusPill value="ACTIVE" /><strong>8개 문서</strong></div></div><div className="section-title"><div><h2>포함 문서</h2><p>이 컬렉션으로 검색할 수 있는 문서입니다.</p></div><ApiFlag>목록 API 준비 중 · 데모 데이터</ApiFlag></div><div className="data-table"><div className="data-row collection-doc-head"><span>문서</span><span>상태</span><span>공개 범위</span><span>추가 시각</span><span /></div>{documents.slice(0,4).map((document) => <div className="data-row collection-doc-row" key={document.id}><div className="doc-name"><span className={`file-square ${document.tone}`}>▤</span><div><a href={`/documents/${document.id}`}>{document.title}</a><small>documentId {document.id}</small></div></div><span><StatusPill value={document.status} /></span><span>{document.visibility}</span><span>2026-08-12 09:41</span><button className="danger-text" onClick={() => setToast("컬렉션에서 문서를 제거했습니다.")}>제거</button></div>)}</div></section>
    );

    if (route === "/permissions") return (
      <section className="content page-view"><PageHeading kicker="ACCESS CONTROL" title="권한 관리" description="문서와 컬렉션에 사용자·역할·부서 단위 접근 권한을 부여하세요." actions={<button className="primary-button" onClick={() => setModal("permission")}>＋ 권한 부여</button>} /><div className="resource-selector"><div><span className="file-square violet">▤</span><div><strong>배포 운영 가이드</strong><span>documentId 1024</span></div></div><button>대상 변경 ⌄</button></div><div className="permission-layout"><div className="panel-card permission-summary"><div className="panel-heading"><h2>내 권한 요약</h2><code>/permissions/documents/1024/me</code></div><div className="permission-checks"><StatusPill value="canRead ✓" /><StatusPill value="canWrite ✓" /><StatusPill value="canAdmin ✕" /></div><span className="field-label">권한이 부여된 경로</span><div className="source-chips"><b>DEPARTMENT</b><b>ROLE</b><b>OWNER</b><b>PUBLIC</b></div><p className="panel-note">하나라도 허용이면 접근 가능하며 회수는 이 사용자에게 직접 부여된 권한만 적용됩니다.</p></div><div className="panel-card permission-list"><div className="panel-heading"><h2>부여된 권한</h2><ApiFlag>목록 API 준비 중</ApiFlag></div><div className="mini-table permission-table">{permissions.map((permission) => <div key={permission.id}><span><strong>{permission.target}</strong><small>{permission.type}</small></span><StatusPill value={permission.permission} /><span>{permission.expires}</span><button className="danger-text" onClick={() => { setPermissions((current) => current.filter((item) => item.id !== permission.id)); setToast("권한을 회수했습니다."); }}>회수</button></div>)}</div></div></div>
      </section>
    );

    if (route === "/mcp-tokens") return (
      <section className="content page-view"><PageHeading kicker="AI INTEGRATION" title="MCP 토큰" description="Claude Desktop 등 AI 클라이언트가 DocGrid 도구를 호출할 때 사용하는 키입니다." actions={<button className="primary-button" onClick={() => setModal("token")}>＋ 토큰 발급</button>} />{issuedToken && <div className="token-reveal"><div><strong>🔐 토큰 원문은 지금 한 번만 표시됩니다.</strong><span>{issuedToken}</span></div><button onClick={() => { navigator.clipboard?.writeText(issuedToken); setToast("토큰을 복사했습니다."); }}>복사</button></div>}<div className="detail-grid token-grid"><div className="panel-card"><div className="panel-heading"><div><h2>발급된 토큰</h2><p>revokedAt이 없으면 사용할 수 있습니다.</p></div></div><div className="mini-table token-table">{tokens.map((token) => <div key={token.id}><strong>#{token.id}</strong><span>{token.created}</span><span>{token.lastUsed}</span><StatusPill value={token.status} />{token.status === "사용 가능" ? <button className="danger-text" onClick={() => setTokens((current) => current.map((item) => item.id === token.id ? { ...item, status: "폐기됨" } : item))}>폐기</button> : <span />}</div>)}</div></div><div className="panel-card"><div className="panel-heading"><div><h2>사용할 수 있는 도구</h2><p>접근 가능한 문서만 반환합니다.</p></div></div><div className="tool-list"><div><code>search_documents</code><span>질문과 관련된 문서 청크 벡터 검색</span></div><div><code>get_document_detail</code><span>문서 메타데이터와 현재 버전 조회</span></div><div><code>get_indexing_status</code><span>문서 인덱싱 진행 상태 조회</span></div></div></div></div></section>
    );

    if (route === "/account") return (
      <section className="content page-view"><PageHeading kicker="PROFILE" title="내 계정" description="내 정보와 역할에 따라 사용할 수 있는 기능을 확인하세요." actions={<a className="secondary-button" href="/login">로그아웃</a>} /><div className="account-layout"><div className="profile-card"><div className="profile-avatar">기민</div><h2>김기민</h2><span>gimin@docgrid.io</span><div className="role-chips"><StatusPill value="USER" /><StatusPill value="ADMIN" /></div><dl><div><dt>상태</dt><dd><StatusPill value="ACTIVE" /></dd></div><div><dt>부서</dt><dd>플랫폼개발팀</dd></div></dl></div><div className="account-main"><div className="panel-card"><div className="panel-heading"><h2>계정 정보</h2><code>GET /auth/me</code></div><dl className="account-info"><div><dt>userId</dt><dd>7</dd></div><div><dt>nickname</dt><dd>기민</dd></div><div><dt>departmentId</dt><dd>3</dd></div><div><dt>createdAt</dt><dd>2026-03-04 09:12</dd></div><div><dt>lastLoginAt</dt><dd>2026-08-12 08:55</dd></div><div><dt>status</dt><dd>ACTIVE</dd></div></dl></div><div className="panel-card"><div className="panel-heading"><h2>역할에 따라 보이는 메뉴</h2></div><div className="role-map"><div><StatusPill value="USER" /><span>검색 · 문서 · 컬렉션 · 권한 · MCP 토큰</span></div><div><StatusPill value="DOCUMENT_MANAGER" /><span>문서 운영 담당 · 관리자 부여</span></div><div><StatusPill value="ADMIN" /><span>RAGOps · 인덱싱 Job · Worker · 사용자·역할</span></div></div></div></div></div></section>
    );

    if (route === "/admin/dashboard") return (
      <section className="content page-view"><PageHeading kicker="RAGOPS" title="운영 현황" description="문서 인덱싱과 검색 시스템 상태를 실시간으로 확인하세요." actions={<div className="live-indicator"><span /> 실시간 연결 · /topic/dashboard</div>} /><div className="metric-grid"><div className="metric-card"><span>전체 문서</span><b>25,368</b><small>soft-delete 제외</small></div><div className="metric-card success-metric"><span>검색 가능</span><b>21,742</b><small>INDEXED</small></div><div className="metric-card"><span>인덱싱 대기</span><b>132</b><small>UPLOADED · INDEXING</small></div><div className="metric-card"><span>최근 24시간 검색</span><b>342</b><small>search.recent24hCount</small></div></div><div className="metric-grid job-metrics"><div className="metric-card"><span>대기</span><b>132</b><small>PENDING</small></div><div className="metric-card"><span>처리 중</span><b>8</b><small>PROCESSING</small></div><div className="metric-card danger-metric"><span>실패</span><b>27</b><button onClick={() => setToast("실패한 작업 27건을 대기열에 추가했습니다.")}>실패 전체 재처리</button></div><div className="metric-card"><span>평균 처리 시간</span><b>3,200<em>ms</em></b><small>Queue 대기 제외</small></div><div className="metric-card"><span>정상 Worker</span><b>5<em>/ 6</em></b><small>ACTIVE · IDLE</small></div></div><div className="alert-row"><div className="danger-alert">▣ <strong>Worker 1대가 DEAD 상태입니다.</strong> docgrid-api-03 · 마지막 heartbeat 6분 전 <a href="/admin/workers">확인 →</a></div><div className="warning-alert">⚠ <strong>실패 27건이 재시도 한도에 도달했습니다.</strong></div></div><div className="dashboard-grid"><div className="panel-card"><div className="panel-heading"><div><h2>최근 실패 Job</h2><p>클릭하면 Attempt와 Event를 추적할 수 있어요.</p></div><a href="/admin/indexing-jobs">전체 보기 →</a></div><div className="mini-table failed-jobs">{jobs.filter((job) => job.status === "FAILED").map((job) => <div key={job.id}><a href={`/admin/indexing-jobs/${job.id}`}>#{job.id}</a><span>{job.document}</span><code>{job.error}</code><span>{job.retry}</span><button onClick={() => retryJob(job.id)}>재처리</button></div>)}</div></div><div className="panel-card"><div className="panel-heading"><div><h2>실패 유형 분포</h2><p>FAILED 27건</p></div></div><div className="failure-bars">{[["EMBEDDING_PROVIDER_UNAVAILABLE",14],["DOCUMENT_CONTENT_INVALID",7],["STORAGE_UNAVAILABLE",4],["WORKER_INTERNAL_ERROR",2]].map(([name,count]) => <div key={String(name)}><span><b>{name}</b><em>{count}</em></span><i><b style={{ width: `${Number(count) * 6}%` }} /></i></div>)}</div></div></div></section>
    );

    if (route === "/admin/indexing-jobs") return (
      <section className="content page-view wide-page"><PageHeading kicker="INDEXING OPERATIONS" title="인덱싱 Job" description="상태·문서·Worker 기준으로 필터링하고 실패 작업을 재처리하세요." actions={<button className="primary-button" onClick={() => setToast("실패한 작업을 모두 재처리 요청했습니다.")}>실패 전체 재처리</button>} /><div className="toolbar job-filters"><span className="filter-label">필터</span><button>status: 전체 ⌄</button><input placeholder="documentId" /><input placeholder="workerId" /><button>size 20 ⌄</button><span /><button>초기화</button><button className="filter-submit">조회</button></div><div className="data-table jobs-table"><div className="data-row data-head"><span>JOB</span><span>상태</span><span>문서</span><span>버전</span><span>모델</span><span>WORKER</span><span>재시도</span><span>오류 코드</span><span>생성</span><span /></div>{jobs.map((job) => <div className={`data-row ${job.id === 4402 ? "selected-row" : ""}`} key={job.id}><a href={`/admin/indexing-jobs/${job.id}`}>#{job.id}</a><span><StatusPill value={job.status} /></span><span>{job.document}</span><span>{job.version}</span><span>{job.model}</span><span>{job.worker}</span><span>{job.retry}</span><code>{job.error}</code><span>{job.created}</span>{job.status === "FAILED" ? <button className="retry-button" onClick={() => retryJob(job.id)}>재처리</button> : <a className="row-link" href={`/admin/indexing-jobs/${job.id}`}>→</a>}</div>)}<div className="pagination"><span>총 214건 · 20건씩</span><div><button>이전</button><button className="active">1</button><button>2</button><button>3</button><button>다음</button></div></div></div></section>
    );

    if (route.startsWith("/admin/indexing-jobs/")) return (
      <section className="content page-view wide-page"><div className="detail-back"><a href="/admin/indexing-jobs">← 인덱싱 Job 목록</a><span>jobId 4402</span></div><PageHeading kicker="JOB TRACE" title="Job #4402" description="왜 실패했고 어느 단계에서 멈췄는지 Attempt와 Event로 추적하세요." actions={<><StatusPill value="FAILED" /><button className="primary-button" onClick={() => retryJob(4402)}>이 Job 재처리</button></>} /><div className="job-detail-grid"><div className="panel-card job-info"><div className="panel-heading"><h2>Job 정보</h2><a href="/documents/1024">문서 보기 →</a></div><dl><div><dt>문서</dt><dd>DB 마이그레이션 정책 #9</dd></div><div><dt>버전</dt><dd>v1 · <StatusPill value="FAILED" /></dd></div><div><dt>모델</dt><dd>BAAI/bge-m3 · v1</dd></div><div><dt>priority</dt><dd>0</dd></div><div><dt>재시도</dt><dd>3 / 3 · 한도 도달</dd></div><div><dt>소유 Worker</dt><dd>indexing-worker-03</dd></div><div><dt>lockExpiresAt</dt><dd>2026-08-12 08:17</dd></div><div><dt>errorCode</dt><dd><code>EMBEDDING_PROVIDER_UNAVAILABLE</code></dd></div></dl><div className="warning-alert">⚠ 수동 재처리 시 기존 retryCount는 보존됩니다.</div></div><div className="panel-card attempts"><div className="panel-heading"><div><h2>실행 시도</h2><p>/attempts</p></div></div><div className="mini-table attempt-table"><div className="table-labels"><span>#</span><span>상태</span><span>WORKER</span><span>소요</span><span>오류 코드</span></div><div><b>3</b><StatusPill value="FAILED" /><span>worker-03</span><span>42,031ms</span><code>EMBEDDING_PROVIDER_UNAVAILABLE</code></div><div><b>2</b><StatusPill value="FAILED" /><span>worker-01</span><span>38,110ms</span><code>EMBEDDING_PROVIDER_UNAVAILABLE</code></div><div><b>1</b><StatusPill value="FAILED" /><span>worker-02</span><span>1,250ms</span><code>STORAGE_UNAVAILABLE</code></div></div><p className="panel-note">실패 유형: STORAGE_UNAVAILABLE · DOCUMENT_CONTENT_INVALID · EMBEDDING_PROVIDER_UNAVAILABLE · WORKER_INTERNAL_ERROR</p></div><div className="panel-card timeline-card"><div className="panel-heading"><h2>이벤트 타임라인</h2><code>/events</code></div><div className="event-timeline">{[["FAILED","08:12","PROCESSING → FAILED · 재시도 한도 도달","danger"],["EMBEDDING_FAILED","08:11","임베딩 서버 응답 없음","purple"],["RETRY","08:05","FAILED → PENDING · 3번째 자동 재시도","purple"],["EMBEDDING_STARTED","08:06","CHUNKED → EMBEDDING","purple"],["CHUNKED","07:59","청크 24개 생성","success"],["LOCKED","07:58","worker-03이 Job claim","purple"],["JOB_CREATED","07:55","업로드 Job 생성","purple"]].map(([name,time,detail,tone]) => <div className={tone} key={name}><i /><span><strong>{name} <small>{time}</small></strong><p>{detail}</p></span></div>)}</div></div></div></section>
    );

    if (route === "/admin/workers") return (
      <section className="content page-view wide-page"><PageHeading kicker="INFRASTRUCTURE" title="Worker 모니터" description="Heartbeat 기준 Worker 상태와 실행 인스턴스를 확인하세요." actions={<div className="refresh-note">30초마다 자동 새로고침 · 방금</div>} /><div className="worker-metrics"><div><span>ACTIVE</span><b>3</b><small>Job 처리 중</small></div><div><span>IDLE</span><b>2</b><small>대기 중</small></div><div className="danger"><span>DEAD</span><b>1</b><small>heartbeat 끊김</small></div><div><span>STOPPED</span><b>0</b><small>정상 종료</small></div></div><div className="data-table worker-table"><div className="data-row data-head"><span>WORKER</span><span>상태</span><span>호스트</span><span>IP</span><span>INSTANCE ID</span><span>마지막 HEARTBEAT</span><span>시작</span></div>{workers.map((worker) => <div className={`data-row ${worker.status === "DEAD" ? "danger-row" : ""}`} key={worker.id}><span><strong>{worker.name}</strong> {worker.id}</span><span><StatusPill value={worker.status} /></span><span>{worker.host}</span><code>{worker.ip}</code><code>{worker.instance}</code><span>{worker.heartbeat}</span><span>{worker.started}</span></div>)}</div><div className="danger-alert worker-alert">▣ <strong>indexing-worker-03이 DEAD로 판정되었습니다.</strong> 이 Worker가 잡고 있던 Job은 lease 만료 후 다른 Worker가 다시 가져갑니다.</div></section>
    );

    if (route === "/admin/users") return (
      <section className="content page-view"><PageHeading kicker="ADMINISTRATION" title="사용자·역할" description="사용자별 역할을 확인하고 문서 운영 또는 관리자 권한을 부여하세요." actions={<ApiFlag>사용자 목록 API 준비 중 · 데모 데이터</ApiFlag>} /><div className="users-layout"><div className="panel-card user-list"><div className="toolbar"><div className="toolbar-search"><span>⌕</span><input placeholder="이름 또는 이메일 검색" /></div><button>부서 전체 ⌄</button></div><div className="mini-table users-table">{users.map((user) => <div key={user.id}><span><strong>{user.name}</strong><small>{user.email}</small></span><span>{user.department}</span><span className="role-chips">{user.roles.map((role) => <StatusPill value={role} key={role} />)}</span><StatusPill value={user.status} /><button onClick={() => setModal("role")}>역할 부여</button></div>)}</div></div><div className="user-side"><div className="panel-card"><div className="panel-heading"><div><h2>역할 부여</h2><p>선택 사용자 · 이수진</p></div><code>userId 21</code></div><div className="role-selector"><button>USER</button><button className="active">DOCUMENT_MANAGER</button><button>ADMIN</button></div><button className="primary-button full-button" onClick={() => { setUsers((current) => current.map((user) => user.id === 21 ? { ...user, roles: ["USER", "DOCUMENT_MANAGER"] } : user)); setToast("DOCUMENT_MANAGER 역할을 부여했습니다."); }}>부여하기</button></div><div className="warning-alert">⚠ 이미 부여된 역할을 다시 부여하면 409를 반환합니다.</div><div className="panel-card result-card"><h2>부여 결과 응답</h2><dl><div><dt>userId</dt><dd>21</dd></div><div><dt>email</dt><dd>sujin@docgrid.io</dd></div><div><dt>roles</dt><dd>USER · DOCUMENT_MANAGER</dd></div></dl></div></div></div></section>
    );

    return <section className="content page-view"><EmptyState symbol="⌕" title="화면을 찾을 수 없습니다" description="사이드바에서 다른 기능을 선택해 주세요." /></section>;
  }

  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileNavOpen ? "open" : ""}`}>
        <a className="brand" href="/search"><BrandMark /><strong>DocGrid</strong></a>
        <button className="primary-button sidebar-upload" onClick={() => setModal("upload")}>＋ 문서 업로드</button>
        <nav className="side-nav" aria-label="주요 메뉴">{navSections.map((section) => <div className="nav-section" key={section.label}><p>{section.label}</p>{section.items.map(([path,symbol,label]) => <a className={isActive(path) ? "active" : ""} href={path} key={path}><span>{symbol}</span>{label}{path === "/documents" && <small>24</small>}{path === "/admin/indexing-jobs" && <i>3</i>}</a>)}</div>)}</nav>
        <a className="workspace-card" href="/account"><div className="workspace-icon">김</div><div><strong>김기민</strong><span>브릭스 · ADMIN</span></div><b>→</b></a>
      </aside>
      {mobileNavOpen && <button className="nav-backdrop" onClick={() => setMobileNavOpen(false)} aria-label="메뉴 닫기" />}
      <main className="main-area"><header className="topbar"><div className="topbar-left"><button className="mobile-menu" onClick={() => setMobileNavOpen(true)}>☰</button><a href="/search">DocGrid</a><span>/</span><strong>{routeTitle}</strong></div><div className="topbar-actions"><span className="prototype-badge">INTERACTIVE PROTOTYPE</span><button className="notification-button">♢<i /></button><a className="profile" href="/account"><div className="avatar">김</div><div><strong>김기민</strong><span>Platform · ADMIN</span></div><b>⌄</b></a></div></header>{renderPage()}</main>

      {(modal === "upload" || modal === "version") && <div className="modal-layer" onMouseDown={() => setModal(null)}><div className="modal" onMouseDown={(event) => event.stopPropagation()}><div className="modal-header"><div><span className="modal-symbol">⇧</span><div><h2>{modal === "version" ? "새 버전 업로드" : "문서 업로드"}</h2><p>{modal === "version" ? "현재 문서에 새 버전을 추가합니다." : "검색할 수 있는 새 문서를 추가합니다."}</p></div></div><button onClick={() => setModal(null)}>×</button></div><label className={`dropzone ${selectedFile ? "selected" : ""}`}><input type="file" accept=".pdf,.docx,.txt,.md" onChange={(event) => setSelectedFile(event.target.files?.[0]?.name ?? "")} /><span>▤</span>{selectedFile ? <><strong>{selectedFile}</strong><small>업로드할 준비가 되었습니다.</small></> : <><strong>파일을 끌어놓거나 클릭해 선택하세요</strong><small>PDF, DOCX, TXT, MD · 최대 50MB</small></>}</label>{modal === "upload" && <><label className="form-field">문서 제목<input defaultValue={selectedFile.replace(/\.[^.]+$/, "")} placeholder="문서 제목" /></label><label className="form-field">설명<input placeholder="선택 입력" /></label><label className="form-field">공개 범위<select><option>DEPARTMENT · 같은 부서만</option><option>PRIVATE · 나만 보기</option><option>COLLECTION · 컬렉션 권한</option><option>PUBLIC · 전체 공개</option></select></label></>}<div className="modal-footer"><button className="secondary-button" onClick={() => setModal(null)}>취소</button><button className="primary-button" disabled={!selectedFile || uploading} onClick={finishUpload}>{uploading ? "업로드 중…" : "업로드 시작"}</button></div></div></div>}
      {modal === "collection" && <div className="modal-layer" onMouseDown={() => setModal(null)}><div className="modal compact-modal" onMouseDown={(event) => event.stopPropagation()}><div className="modal-header"><div><span className="modal-symbol mint">▱</span><div><h2>새 컬렉션</h2><p>관련 문서를 하나의 검색 범위로 묶습니다.</p></div></div><button onClick={() => setModal(null)}>×</button></div><label className="form-field">컬렉션 이름<input placeholder="예: 디자인 시스템" /></label><label className="form-field">설명<textarea rows={3} placeholder="컬렉션을 설명해 주세요." /></label><label className="form-field">공개 범위<select><option>PRIVATE</option><option>DEPARTMENT</option><option>PUBLIC</option></select></label><div className="modal-footer"><button className="secondary-button" onClick={() => setModal(null)}>취소</button><button className="primary-button" onClick={() => { setModal(null); setToast("새 컬렉션을 만들었습니다."); }}>컬렉션 만들기</button></div></div></div>}
      {modal === "add-document" && <div className="modal-layer" onMouseDown={() => setModal(null)}><div className="modal compact-modal" onMouseDown={(event) => event.stopPropagation()}><div className="modal-header"><div><span className="modal-symbol mint">＋</span><div><h2>컬렉션에 문서 추가</h2><p>운영 문서 컬렉션에 포함할 문서를 선택하세요.</p></div></div><button onClick={() => setModal(null)}>×</button></div><div className="document-picker">{documents.map((document) => <label key={document.id}><input type="checkbox" /><span className={`file-square ${document.tone}`}>▤</span><span><strong>{document.title}</strong><small>{document.status}</small></span></label>)}</div><div className="modal-footer"><button className="secondary-button" onClick={() => setModal(null)}>취소</button><button className="primary-button" onClick={() => { setModal(null); setToast("선택한 문서를 컬렉션에 추가했습니다."); }}>문서 추가</button></div></div></div>}
      {modal === "permission" && <div className="modal-layer" onMouseDown={() => setModal(null)}><div className="modal" onMouseDown={(event) => event.stopPropagation()}><div className="modal-header"><div><span className="modal-symbol">⌘</span><div><h2>권한 부여</h2><p>배포 운영 가이드 · documentId 1024</p></div></div><button onClick={() => setModal(null)}>×</button></div><label className="form-field">대상 타입<div className="segmented"><button className="active">USER</button><button>ROLE</button><button>DEPARTMENT</button></div></label><label className="form-field">대상 사용자<select><option>이수진 · sujin@docgrid.io</option><option>박준호 · junho@docgrid.io</option></select></label><label className="form-field">권한 종류<div className="segmented"><button>READ</button><button className="active">WRITE</button><button>ADMIN</button></div></label><label className="form-field">만료 시각<input type="datetime-local" defaultValue="2026-12-31T23:59" /></label><div className="modal-footer"><button className="secondary-button" onClick={() => setModal(null)}>취소</button><button className="primary-button" onClick={() => { setPermissions((current) => [{ id: Date.now(), target: "이수진", type: "USER", permission: "WRITE", expires: "2026-12-31" }, ...current]); setModal(null); setToast("WRITE 권한을 부여했습니다."); }}>권한 부여</button></div></div></div>}
      {modal === "token" && <div className="modal-layer" onMouseDown={() => setModal(null)}><div className="modal compact-modal" onMouseDown={(event) => event.stopPropagation()}><div className="modal-header"><div><span className="modal-symbol mint">⌁</span><div><h2>MCP 토큰 발급</h2><p>토큰은 내 계정 권한과 동일하게 동작합니다.</p></div></div><button onClick={() => setModal(null)}>×</button></div><div className="warning-alert">⚠ 원문은 발급 직후 한 번만 표시됩니다. 안전한 곳에 복사해 두세요.</div><div className="modal-footer"><button className="secondary-button" onClick={() => setModal(null)}>취소</button><button className="primary-button" onClick={issueToken}>토큰 발급</button></div></div></div>}
      {modal === "role" && <div className="modal-layer" onMouseDown={() => setModal(null)}><div className="modal compact-modal" onMouseDown={(event) => event.stopPropagation()}><div className="modal-header"><div><span className="modal-symbol">♙</span><div><h2>사용자 역할 부여</h2><p>이수진 · sujin@docgrid.io</p></div></div><button onClick={() => setModal(null)}>×</button></div><label className="form-field">역할 코드<select><option>DOCUMENT_MANAGER</option><option>ADMIN</option><option>USER</option></select></label><div className="warning-alert">이미 부여된 역할이면 409 응답이 발생합니다.</div><div className="modal-footer"><button className="secondary-button" onClick={() => setModal(null)}>취소</button><button className="primary-button" onClick={() => { setModal(null); setToast("DOCUMENT_MANAGER 역할을 부여했습니다."); }}>부여하기</button></div></div></div>}
      {toast && <div className="toast"><span>✓</span>{toast}</div>}
    </div>
  );
}
