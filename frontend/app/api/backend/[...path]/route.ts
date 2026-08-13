import type { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const DEFAULT_BACKEND_URL = "http://localhost:8080";

async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  const { path } = await context.params;
  if (!path.length || path.some((segment) => segment === "." || segment === "..")) {
    return Response.json({ success: false, message: "잘못된 API 경로입니다." }, { status: 400 });
  }

  const baseUrl = process.env.BACKEND_API_URL ?? DEFAULT_BACKEND_URL;
  const target = new URL(path.map(encodeURIComponent).join("/"), `${baseUrl.replace(/\/$/, "")}/`);
  target.search = new URL(request.url).search;

  const headers = new Headers(request.headers);
  headers.delete("host");
  headers.delete("origin");
  headers.delete("referer");
  headers.delete("content-length");

  // 1. Buffer the request once so JSON and multipart bodies are forwarded unchanged.
  const body = request.method === "GET" || request.method === "HEAD"
    ? undefined
    : await request.arrayBuffer();

  // 2. Keep browser traffic same-origin while the Worker performs the backend HTTP request.
  let upstream: Response;
  try {
    upstream = await fetch(target, {
      method: request.method,
      headers,
      body,
      redirect: "manual",
    });
  } catch {
    return Response.json(
      { success: false, status: 502, code: "BACKEND_UNAVAILABLE", message: "DocGrid 백엔드에 연결할 수 없습니다." },
      { status: 502 },
    );
  }

  // 3. Disable caching because every protected response is user-specific.
  const responseHeaders = new Headers(upstream.headers);
  responseHeaders.set("Cache-Control", "no-store");
  responseHeaders.delete("content-length");
  responseHeaders.delete("content-encoding");
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: responseHeaders,
  });
}

type ProxyContext = { params: Promise<{ path: string[] }> };

export const GET = (request: NextRequest, context: ProxyContext) => proxy(request, context);
export const POST = (request: NextRequest, context: ProxyContext) => proxy(request, context);
export const PUT = (request: NextRequest, context: ProxyContext) => proxy(request, context);
export const PATCH = (request: NextRequest, context: ProxyContext) => proxy(request, context);
export const DELETE = (request: NextRequest, context: ProxyContext) => proxy(request, context);
export const OPTIONS = (request: NextRequest, context: ProxyContext) => proxy(request, context);
