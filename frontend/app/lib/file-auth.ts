/**
 * Expires the client session only when the canonical identity endpoint confirms an unauthorized token.
 * Endpoint-specific and transient verification failures deliberately leave the existing session untouched.
 */
export async function confirmSessionExpired(
  requestIdentityStatus: () => Promise<number>,
  expireSession: () => void,
): Promise<void> {
  try {
    if (await requestIdentityStatus() === 401) expireSession();
  } catch {
    // A network failure cannot prove token expiry, so the original file error remains the only visible failure.
  }
}
