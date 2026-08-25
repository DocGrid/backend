import type { PermissionTargetUser } from "./api-types";

export function permissionTargetUserLabel(user: PermissionTargetUser): string {
  return `${user.departmentName ?? "소속 없음"} · ${user.email} · #${user.userId}`;
}
