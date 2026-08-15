"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { ACCESS_TOKEN_KEY, AUTH_EXPIRED_EVENT, apiRequest } from "../lib/api";
import type { LoginResponse, MeResponse } from "../lib/api-types";

type AuthContextValue = {
  user: MeResponse | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<MeResponse>;
  logout: () => void;
  signOut: () => Promise<void>;
  refresh: () => Promise<MeResponse | null>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<MeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  const logout = useCallback(() => {
    window.sessionStorage.removeItem(ACCESS_TOKEN_KEY);
    setUser(null);
  }, []);

  const signOut = useCallback(async () => {
    try {
      await apiRequest("/auth/logout", { method: "POST" });
    } catch {
      // 백엔드 로그아웃이 실패해도 클라이언트 세션은 항상 정리한다.
    } finally {
      logout();
    }
  }, [logout]);

  const refresh = useCallback(async () => {
    const token = window.sessionStorage.getItem(ACCESS_TOKEN_KEY);
    if (!token) {
      setUser(null);
      setLoading(false);
      return null;
    }

    try {
      const me = await apiRequest<MeResponse>("/auth/me");
      setUser(me);
      return me;
    } catch {
      logout();
      return null;
    } finally {
      setLoading(false);
    }
  }, [logout]);

  useEffect(() => {
    // 1. Rehydrate only the short-lived in-tab token when the application mounts.
    const timer = window.setTimeout(() => void refresh(), 0);
    // 2. Centralize expiry handling for any API call that returns 401.
    window.addEventListener(AUTH_EXPIRED_EVENT, logout);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener(AUTH_EXPIRED_EVENT, logout);
    };
  }, [logout, refresh]);

  const login = useCallback(async (email: string, password: string) => {
    const response = await apiRequest<LoginResponse>("/auth/login", {
      method: "POST",
      auth: false,
      body: { email, password },
    });
    window.sessionStorage.setItem(ACCESS_TOKEN_KEY, response.accessToken);
    const me = await apiRequest<MeResponse>("/auth/me");
    setUser(me);
    return me;
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, logout, signOut, refresh }),
    [user, loading, login, logout, signOut, refresh],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("AuthProvider 안에서 useAuth를 사용해야 합니다.");
  return context;
}
