"use client";

import { useEffect } from "react";

export function BrandMark() {
  return <div className="brand-mark" aria-hidden="true"><span /><span /><span /></div>;
}

export function StatusPill({ value }: { value: string }) {
  const normalized = value.toLowerCase().replaceAll(" ", "-").replaceAll("_", "-");
  return <span className={`status-pill status-${normalized}`}>{value}</span>;
}

export function PageHeading({ kicker, title, description, actions }: {
  kicker: string;
  title: string;
  description: string;
  actions?: React.ReactNode;
}) {
  return (
    <div className="page-heading">
      <div><span className="page-kicker">{kicker}</span><h1>{title}</h1><p>{description}</p></div>
      {actions ? <div className="page-actions">{actions}</div> : null}
    </div>
  );
}

export function LoadingState({ label = "데이터를 불러오는 중입니다." }: { label?: string }) {
  return <div className="loading-state" role="status"><span className="loading-spinner" /><strong>{label}</strong></div>;
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return <div className="error-state" role="alert"><span>!</span><div><strong>요청을 완료하지 못했습니다.</strong><p>{message}</p></div>{onRetry ? <button className="secondary-button" onClick={onRetry}>다시 시도</button> : null}</div>;
}

export function EmptyState({ symbol, title, description }: { symbol: string; title: string; description: string }) {
  return <div className="empty-state"><span>{symbol}</span><strong>{title}</strong><p>{description}</p></div>;
}

export function Notice({ children }: { children: React.ReactNode }) {
  return <div className="api-notice">{children}</div>;
}

export function Toast({ message, onDone }: { message: string; onDone: () => void }) {
  useEffect(() => {
    const timer = window.setTimeout(onDone, 2800);
    return () => window.clearTimeout(timer);
  }, [message, onDone]);

  return <div className="toast"><span>✓</span>{message}</div>;
}

export function formatDate(value: string | null | undefined, withTime = true) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("ko-KR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    ...(withTime ? { hour: "2-digit", minute: "2-digit" } : {}),
  }).format(date);
}

export function initials(name?: string | null) {
  return name?.trim().slice(0, 1) || "D";
}
