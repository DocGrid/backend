"use client";

import { useCallback, useEffect, useState } from "react";
import { apiRequest, errorMessage } from "../lib/api";
import type { McpToken, McpTokenIssue } from "../lib/api-types";
import { EmptyState, ErrorState, LoadingState, PageHeading, StatusPill, formatDate } from "../components/ui";

export function McpTokensPage({ notify }: { notify: (message: string) => void }) {
  const [tokens, setTokens] = useState<McpToken[]>([]);
  const [issued, setIssued] = useState<McpTokenIssue | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try { setTokens((await apiRequest<{ tokens: McpToken[] }>("/mcp/tokens")).tokens); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  async function issue() {
    setBusy(true);
    setError("");
    try {
      const response = await apiRequest<McpTokenIssue>("/mcp/tokens", { method: "POST" });
      setIssued(response);
      notify("MCP 토큰을 발급했습니다. 원문은 지금 한 번만 확인할 수 있습니다.");
      await load();
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function revoke(tokenId: number) {
    setBusy(true);
    try { await apiRequest(`/mcp/tokens/${tokenId}`, { method: "DELETE" }); notify(`토큰 #${tokenId}을 폐기했습니다.`); await load(); }
    catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  }

  async function copy() {
    if (!issued) return;
    await navigator.clipboard.writeText(issued.token);
    notify("토큰을 클립보드에 복사했습니다.");
  }

  return <section className="content page-view">
    <PageHeading kicker="MCP INTEGRATION" title="MCP 토큰" description="Claude Desktop 등 MCP 클라이언트에서 사용할 장기 API 키를 관리하세요." actions={<button className="primary-button" disabled={busy} onClick={() => void issue()}>＋ 새 토큰 발급</button>} />
    {error ? <ErrorState message={error} onRetry={() => void load()} /> : null}
    {issued ? <div className="token-reveal"><div><strong>한 번만 표시되는 토큰</strong><span>{issued.token}</span></div><button onClick={() => void copy()}>복사</button></div> : null}
    {loading ? <LoadingState label="MCP 토큰을 불러오는 중입니다." /> : null}
    {!loading && !tokens.length ? <EmptyState symbol="⌁" title="발급된 토큰이 없습니다" description="필요할 때 새 토큰을 발급하세요." /> : null}
    {!loading && tokens.length ? <div className="detail-grid token-grid"><div className="panel-card"><div className="panel-heading"><div><h2>내 토큰</h2><p>원문은 보안상 다시 표시되지 않습니다.</p></div></div><div className="mini-table token-table"><div className="table-labels"><span>ID</span><span>발급</span><span>마지막 사용</span><span>상태</span><span /></div>{tokens.map((token) => <div key={token.tokenId}><b>#{token.tokenId}</b><span>{formatDate(token.createdAt)}</span><span>{formatDate(token.lastUsedAt)}</span><StatusPill value={token.revokedAt ? "폐기됨" : "사용 가능"} />{token.revokedAt ? <span /> : <button className="danger-text" disabled={busy} onClick={() => void revoke(token.tokenId)}>폐기</button>}</div>)}</div></div><div className="panel-card"><div className="panel-heading"><div><h2>연동 안내</h2><p>토큰은 내 DocGrid 권한으로 동작합니다.</p></div></div><div className="tool-list"><div><code>Authorization</code><span><code>Authorization: Bearer docgrid_mcp_xxxxx</code> 형식으로, <code>Bearer </code> 뒤에 발급된 MCP 토큰을 그대로 넣습니다.</span></div><div><code>보안</code><span>토큰을 소스 코드나 공개 설정 파일에 저장하지 마세요.</span></div><div><code>폐기</code><span>노출이 의심되면 즉시 폐기하고 새 토큰을 발급하세요.</span></div></div></div></div> : null}
  </section>;
}
