import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}-${path}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://localhost${path}`, {
      headers: { accept: "text/html", host: "localhost" },
    }),
    {
      ASSETS: {
        fetch: async () => new Response("Not found", { status: 404 }),
      },
    },
    {
      waitUntil() {},
      passThroughOnException() {},
    },
  );
}

test("server-renders every application route", async () => {
  const routes = [
    "/",
    "/login",
    "/signup",
    "/search",
    "/documents",
    "/documents/1024",
    "/collections",
    "/collections/12",
    "/permissions",
    "/mcp-tokens",
    "/account",
    "/admin/dashboard",
    "/admin/indexing-jobs",
    "/admin/indexing-jobs/4402",
    "/admin/workers",
    "/admin/users",
  ];

  for (const route of routes) {
    const response = await render(route);
    assert.equal(response.status, 200, `${route} should render`);
    assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);
  }
});

test("renders DocGrid auth and protected loading boundaries", async () => {
  const [home, login, signup] = await Promise.all([
    render("/"),
    render("/login"),
    render("/signup"),
  ]);

  const homeHtml = await home.text();
  const loginHtml = await login.text();
  const signupHtml = await signup.text();
  assert.match(homeHtml, /<html lang="ko">/i);
  assert.match(homeHtml, /<title>DocGrid — 팀의 지식에서 정확한 답을<\/title>/i);
  assert.match(homeHtml, /세션을 확인하는 중입니다/);
  assert.match(loginHtml, /DocGrid 이메일과 비밀번호/);
  assert.match(loginHtml, /<a href="\/signup" target="_top">회원가입<\/a>/);
  assert.match(signupHtml, /가입 가능한 부서 목록을 불러오는 중입니다/);
  assert.match(signupHtml, /<button class="primary-button auth-submit" disabled="">부서 목록 불러오는 중…<\/button>/);
  assert.doesNotMatch(`${homeHtml}${loginHtml}${signupHtml}`, /로그인 없이 둘러보기|signin-with-chatgpt|codex-preview|Your site is taking shape/i);
});

test("uses full-page navigation for vinext catch-all routes", async () => {
  const navigationFiles = [
    "../app/components/AppShell.tsx",
    "../app/components/AuthPage.tsx",
    "../app/features/CollectionsPage.tsx",
    "../app/features/DocumentsPage.tsx",
    "../app/features/SearchPage.tsx",
    "../app/features/AdminPages.tsx",
  ];
  const sources = await Promise.all(navigationFiles.map((file) => readFile(new URL(file, import.meta.url), "utf8")));
  const source = sources.join("\n");
  assert.doesNotMatch(source, /from ["']next\/link["']|<Link\b/);

  const internalAnchors = source.match(/<a\b[^>]*\bhref=(?:["']\/|\{(?:`\/|[^}]*["']\/))[^>]*>/g) ?? [];
  assert.ok(internalAnchors.length > 0, "internal links should be present");
  for (const anchor of internalAnchors) {
    assert.match(anchor, /\btarget=["']_top["']/, `${anchor} should bypass vinext client navigation`);
  }
  assert.match(source, /AbortSignal\.timeout\(SEARCH_TIMEOUT_MS\)/, "search should finish before the Sites request limit");
});

test("wires document metadata update and soft delete actions to their permissions", async () => {
  const source = await readFile(new URL("../app/features/DocumentsPage.tsx", import.meta.url), "utf8");

  assert.match(source, /permission\?\.canWrite[\s\S]*문서 정보 수정/);
  assert.match(source, /method: "PATCH"/);
  assert.match(source, /permission\?\.canAdmin[\s\S]*문서 삭제/);
  assert.match(source, /method: "DELETE"/);
  assert.match(source, /window\.location\.assign\("\/documents"\)/);
});
