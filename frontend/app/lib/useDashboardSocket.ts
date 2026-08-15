"use client";

import { useEffect, useState } from "react";
import { ACCESS_TOKEN_KEY } from "./api";

const WS_BASE_URL = (process.env.NEXT_PUBLIC_BACKEND_WS_URL ?? "http://localhost:8080").replace(/\/$/, "");

export type DashboardSocketStatus = "CONNECTING" | "LIVE" | "POLLING";

/** RAGOps Dashboard 상태 전이 push(/topic/dashboard)를 구독해 onMessage를 트리거한다. 연결에 실패하면 POLLING으로 폴백한다. */
export function useDashboardSocket(onMessage: () => void): DashboardSocketStatus {
  const [status, setStatus] = useState<DashboardSocketStatus>(() => {
    const token = typeof window === "undefined" ? null : window.sessionStorage.getItem(ACCESS_TOKEN_KEY);
    return token ? "CONNECTING" : "POLLING";
  });

  useEffect(() => {
    const token = typeof window === "undefined" ? null : window.sessionStorage.getItem(ACCESS_TOKEN_KEY);
    if (!token) return;

    // 1. 관리자 Dashboard STOMP Topic에 직접 연결해 상태 전이 push를 수신한다.
    const socketUrl = `${WS_BASE_URL.replace(/^http/, "ws")}/ws/websocket`;
    const socket = new WebSocket(socketUrl);
    socket.onopen = () => socket.send(
      `CONNECT\naccept-version:1.2\nAuthorization:Bearer ${token}\nheart-beat:10000,10000\n\n\0`,
    );
    socket.onmessage = (event) => {
      const frame = String(event.data);
      if (frame.startsWith("CONNECTED")) {
        socket.send("SUBSCRIBE\nid:ragops-dashboard\ndestination:/topic/dashboard\nack:auto\n\n\0");
        setStatus("LIVE");
        return;
      }
      // 2. push를 신호로만 사용하고 Frame 본문 대신 최신 관리자 API를 다시 읽는다.
      if (frame.startsWith("MESSAGE")) onMessage();
    };
    socket.onerror = () => setStatus("POLLING");
    socket.onclose = () => setStatus("POLLING");
    return () => socket.close();
  }, [onMessage]);

  return status;
}
