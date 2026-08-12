import assert from "node:assert/strict";
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

test("server-renders every prototype route", async () => {
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

test("renders navigation and detailed feature content", async () => {
  const [home, login, document, job] = await Promise.all([
    render("/"),
    render("/login"),
    render("/documents/1024"),
    render("/admin/indexing-jobs/4402"),
  ]);

  const homeHtml = await home.text();
  assert.match(homeHtml, /<html lang="ko">/i);
  assert.match(homeHtml, /<title>DocGrid — 팀의 지식에서 정확한 답을<\/title>/i);
  assert.match(homeHtml, /AI KNOWLEDGE SEARCH/);
  assert.match(homeHtml, /MCP 토큰/);
  assert.match(homeHtml, /사용자·역할/);

  assert.match(await login.text(), /로그인 없이 둘러보기/);
  assert.match(await document.text(), /인덱싱 진행 상태/);
  assert.match(await job.text(), /이벤트 타임라인/);
  assert.doesNotMatch(homeHtml, /codex-preview|Your site is taking shape|react-loading-skeleton/i);
});
