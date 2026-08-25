import assert from "node:assert/strict";
import test from "node:test";

import { confirmSessionExpired } from "../app/lib/file-auth.ts";

test("파일 요청만 실패하고 내 정보 조회가 성공하면 세션을 유지한다", async () => {
  let expired = false;

  await confirmSessionExpired(async () => 200, () => {
    expired = true;
  });

  assert.equal(expired, false);
});

test("내 정보 조회도 401이면 세션을 만료시킨다", async () => {
  let expired = false;

  await confirmSessionExpired(async () => 401, () => {
    expired = true;
  });

  assert.equal(expired, true);
});

test("내 정보 확인 중 네트워크 오류가 나면 세션을 유지한다", async () => {
  let expired = false;

  await confirmSessionExpired(async () => {
    throw new Error("network unavailable");
  }, () => {
    expired = true;
  });

  assert.equal(expired, false);
});
