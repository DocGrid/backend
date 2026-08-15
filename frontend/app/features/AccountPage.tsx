"use client";

import { useAuth } from "../components/AuthProvider";
import { PageHeading, StatusPill, formatDate, initials } from "../components/ui";

export function AccountPage() {
  const { user, signOut } = useAuth();
  if (!user) return null;

  async function handleSignOut() {
    await signOut();
    window.location.replace("/login");
  }

  return <section className="content page-view"><PageHeading kicker="MY ACCOUNT" title="내 계정" description="현재 로그인한 DocGrid 계정과 역할을 확인하세요." actions={<button className="secondary-button" onClick={handleSignOut}>로그아웃</button>} /><div className="account-layout"><div className="profile-card"><div className="profile-avatar">{initials(user.name)}</div><h2>{user.name}</h2><span>{user.email}</span><div className="role-chips">{user.roles.map((role) => <StatusPill value={role} key={role} />)}</div><dl><div><dt>상태</dt><dd>{user.status}</dd></div><div><dt>사용자 ID</dt><dd>#{user.userId}</dd></div></dl></div><div className="account-main"><div className="panel-card"><div className="panel-heading"><div><h2>계정 정보</h2><p>/auth/me 응답</p></div></div><dl className="account-info"><div><dt>이름</dt><dd>{user.name}</dd></div><div><dt>닉네임</dt><dd>{user.nickname ?? "—"}</dd></div><div><dt>부서</dt><dd>{user.departmentName ?? "—"}</dd></div><div><dt>부서 ID</dt><dd>{user.departmentId ?? "—"}</dd></div><div><dt>가입 시각</dt><dd>{formatDate(user.createdAt)}</dd></div><div><dt>마지막 로그인</dt><dd>{formatDate(user.lastLoginAt)}</dd></div></dl></div><div className="panel-card"><div className="panel-heading"><div><h2>역할</h2><p>백엔드 인가 규칙에 사용되는 역할입니다.</p></div></div><div className="role-map">{user.roles.map((role) => <div key={role}><StatusPill value={role} /><span>{role === "ADMIN" ? "운영 대시보드와 관리자 API에 접근할 수 있습니다." : role === "DOCUMENT_MANAGER" ? "문서 운영 권한을 가집니다." : "일반 문서 검색과 컬렉션 기능을 사용할 수 있습니다."}</span></div>)}</div></div></div></div></section>;
}
