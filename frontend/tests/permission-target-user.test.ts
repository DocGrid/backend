import assert from "node:assert/strict";
import test from "node:test";

import type { PermissionTargetUser } from "../app/lib/api-types.ts";
import { permissionTargetUserLabel } from "../app/lib/permission-target-user.ts";

test("동명이인은 부서·이메일·사용자 ID로 구분한다", () => {
  const first: PermissionTargetUser = {
    userId: 20,
    name: "김기민",
    email: "gimin@example.com",
    departmentId: 3,
    departmentName: "개발팀",
  };
  const second: PermissionTargetUser = {
    ...first,
    userId: 21,
    email: "gimin2@example.com",
  };

  assert.equal(permissionTargetUserLabel(first), "개발팀 · gimin@example.com · #20");
  assert.equal(permissionTargetUserLabel(second), "개발팀 · gimin2@example.com · #21");
  assert.notEqual(permissionTargetUserLabel(first), permissionTargetUserLabel(second));
});

test("부서가 없는 사용자는 소속 없음으로 표시한다", () => {
  const user: PermissionTargetUser = {
    userId: 22,
    name: "강철웅",
    email: "cheolung@example.com",
    departmentId: null,
    departmentName: null,
  };

  assert.equal(permissionTargetUserLabel(user), "소속 없음 · cheolung@example.com · #22");
});
