"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AccountPage } from "../features/AccountPage";
import { AdminUsersPage, DashboardPage, IndexingJobDetailPage, IndexingJobsPage, WorkersPage } from "../features/AdminPages";
import { CollectionDetailPage, CollectionsPage } from "../features/CollectionsPage";
import { DocumentDetailPage, DocumentsPage } from "../features/DocumentsPage";
import { McpTokensPage } from "../features/McpTokensPage";
import { PermissionsPage } from "../features/PermissionsPage";
import { SearchPage } from "../features/SearchPage";
import { AppShell } from "./AppShell";
import { AuthPage } from "./AuthPage";
import { AuthProvider, useAuth } from "./AuthProvider";
import { BrandMark, EmptyState, Toast } from "./ui";
import { UploadModal } from "./UploadModal";

type UploadState = { open: boolean; documentId?: number };

export default function DocGridApp({ initialRoute }: { initialRoute: string }) {
  return <AuthProvider><DocGridRouter initialRoute={initialRoute === "/" ? "/search" : initialRoute} /></AuthProvider>;
}

function DocGridRouter({ initialRoute }: { initialRoute: string }) {
  const route = initialRoute.split("?")[0];
  const { user, loading } = useAuth();
  const [upload, setUpload] = useState<UploadState>({ open: false });
  const [toast, setToast] = useState("");
  const publicRoute = route === "/login" || route === "/signup";

  useEffect(() => {
    if (!loading && !user && !publicRoute) {
      window.location.replace(`/login?returnTo=${encodeURIComponent(route)}`);
    }
  }, [loading, publicRoute, route, user]);

  const notify = useCallback((message: string) => setToast(message), []);
  const page = useMemo(() => {
    if (!user) return null;
    if (route === "/search") return <SearchPage />;
    if (route === "/documents") return <DocumentsPage onUpload={() => setUpload({ open: true })} />;
    if (route.startsWith("/documents/")) {
      const id = numericTail(route);
      return id ? <DocumentDetailPage documentId={id} onVersionUpload={() => setUpload({ open: true, documentId: id })} notify={notify} /> : <NotFound />;
    }
    if (route === "/collections") return <CollectionsPage notify={notify} />;
    if (route.startsWith("/collections/")) {
      const id = numericTail(route);
      return id ? <CollectionDetailPage collectionId={id} notify={notify} /> : <NotFound />;
    }
    if (route === "/permissions") return <PermissionsPage notify={notify} />;
    if (route === "/mcp-tokens") return <McpTokensPage notify={notify} />;
    if (route === "/account") return <AccountPage />;
    if (route.startsWith("/admin/") && !user.roles.includes("ADMIN")) return <Forbidden />;
    if (route === "/admin/dashboard") return <DashboardPage notify={notify} />;
    if (route === "/admin/indexing-jobs") return <IndexingJobsPage notify={notify} />;
    if (route.startsWith("/admin/indexing-jobs/")) {
      const id = numericTail(route);
      return id ? <IndexingJobDetailPage jobId={id} notify={notify} /> : <NotFound />;
    }
    if (route === "/admin/workers") return <WorkersPage />;
    if (route === "/admin/users") return <AdminUsersPage notify={notify} />;
    return <NotFound />;
  }, [notify, route, user]);

  if (publicRoute) return <AuthPage mode={route === "/signup" ? "signup" : "login"} />;
  if (loading || !user) return <AppLoading />;

  return <>
    <AppShell route={route} onUpload={() => setUpload({ open: true })}>{page}</AppShell>
    {upload.open ? <UploadModal documentId={upload.documentId} onClose={() => setUpload({ open: false })} onSuccess={notify} /> : null}
    {toast ? <Toast message={toast} onDone={() => setToast("")} /> : null}
  </>;
}

function numericTail(route: string) {
  const value = Number(route.split("/").at(-1));
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

function AppLoading() {
  return <main className="app-loading"><BrandMark /><strong>DocGrid</strong><span className="loading-spinner" /><p>세션을 확인하는 중입니다.</p></main>;
}

function Forbidden() {
  return <section className="content page-view"><EmptyState symbol="⌘" title="관리자 권한이 필요합니다" description="현재 계정에는 이 화면을 볼 권한이 없습니다." /></section>;
}

function NotFound() {
  return <section className="content page-view"><EmptyState symbol="⌕" title="화면을 찾을 수 없습니다" description="사이드바에서 다른 기능을 선택해 주세요." /></section>;
}
