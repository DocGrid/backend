"use client";

// vinext production navigation uses full requests because its client router does not complete catch-all route transitions.
/* eslint-disable @next/next/no-html-link-for-pages */

import { useMemo, useState } from "react";
import { useAuth } from "./AuthProvider";
import { BrandMark, initials } from "./ui";

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
  { label: "INTEGRATION", items: [["/mcp-tokens", "⌁", "MCP 토큰"]] },
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

const titles: Record<string, string> = {
  "/search": "AI 검색",
  "/documents": "문서",
  "/collections": "컬렉션",
  "/permissions": "권한 관리",
  "/mcp-tokens": "MCP 토큰",
  "/account": "내 계정",
  "/admin/dashboard": "RAGOps",
  "/admin/indexing-jobs": "인덱싱 Job",
  "/admin/workers": "Worker",
  "/admin/users": "사용자·역할",
};

export function AppShell({ route, onUpload, children }: {
  route: string;
  onUpload: () => void;
  children: React.ReactNode;
}) {
  const { user } = useAuth();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const admin = user?.roles.includes("ADMIN") ?? false;
  const routeTitle = useMemo(() => {
    if (route.startsWith("/documents/")) return "문서 상세";
    if (route.startsWith("/collections/")) return "컬렉션 상세";
    if (route.startsWith("/admin/indexing-jobs/")) return "Job 상세";
    return titles[route] ?? "DocGrid";
  }, [route]);

  function isActive(path: string) {
    if (path === "/documents") return route.startsWith("/documents");
    if (path === "/collections") return route.startsWith("/collections");
    if (path === "/admin/indexing-jobs") return route.startsWith("/admin/indexing-jobs");
    return route === path;
  }

  return (
    <div className="app-shell">
      <aside className={`sidebar ${mobileNavOpen ? "open" : ""}`}>
        <a className="brand" href="/search" target="_top"><BrandMark /><strong>DocGrid</strong></a>
        <button className="primary-button sidebar-upload" onClick={onUpload}>＋ 문서 업로드</button>
        <nav className="side-nav" aria-label="주요 메뉴">
          {navSections.map((section) => {
            if (section.label === "ADMIN" && !admin) return null;
            return <div className="nav-section" key={section.label}><p>{section.label}</p>{section.items.map(([path, symbol, label]) => <a className={isActive(path) ? "active" : ""} href={path} target="_top" key={path}><span>{symbol}</span>{label}</a>)}</div>;
          })}
        </nav>
        <a className="workspace-card" href="/account" target="_top"><div className="workspace-icon">{initials(user?.name)}</div><div><strong>{user?.name}</strong><span>{user?.departmentName ?? "소속 없음"} · {admin ? "ADMIN" : "USER"}</span></div><b>→</b></a>
      </aside>
      {mobileNavOpen ? <button className="nav-backdrop" onClick={() => setMobileNavOpen(false)} aria-label="메뉴 닫기" /> : null}
      <main className="main-area">
        <header className="topbar">
          <div className="topbar-left"><button className="mobile-menu" onClick={() => setMobileNavOpen(true)} aria-label="메뉴 열기">☰</button><a href="/search" target="_top">DocGrid</a><span>/</span><strong>{routeTitle}</strong></div>
          <div className="topbar-actions"><span className="connection-badge"><i /> DOCGRID API</span><a className="profile" href="/account" target="_top"><div className="avatar">{initials(user?.name)}</div><div><strong>{user?.name}</strong><span>{user?.departmentName ?? "소속 없음"} · {admin ? "ADMIN" : "USER"}</span></div><b>⌄</b></a></div>
        </header>
        {children}
      </main>
    </div>
  );
}
