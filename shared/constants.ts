// Shared Security Constants - Single Source of Truth
// Used by: Android (Kotlin), Server (Node.js), Windows Agent (C#)

export const SecurityConstants = {
  // Device Authentication
  MAX_DEVICE_ATTEMPTS: 3,
  DEVICE_BLOCK_KEY: 'device_blocked',
  DEVICE_FAIL_COUNT_KEY: 'device_fail_count',
  DEVICE_ID_KEY: 'device_id',

  // Admin Authentication
  MAX_ADMIN_LOGIN_ATTEMPTS: 3,
  ADMIN_SESSION_COOKIE: 'admin_session',
  ADMIN_SESSION_TTL_MS: 7 * 24 * 60 * 60 * 1000, // 7 days

  // WebSocket Keepalive
  WS_KEEPALIVE_MS: 15000,      // Protocol-level ping (WS ping/pong)
  APP_KEEPALIVE_MS: 20000,     // Application-level keepalive frames (defeats NAT/proxy)

  // Reconnection
  RECONNECT_BASE_DELAY_MS: 1000,
  RECONNECT_MAX_DELAY_MS: 30000,
  RECONNECT_MAX_ATTEMPTS: 10,

  // Network
  TLS_PORT: 443,
  DEFAULT_WS_PORT: 3001,
  DEFAULT_WSS_PORT: 443,

  // Update
  GITHUB_API_BASE: 'https://api.github.com',
  UPDATE_CHECK_TIMEOUT_MS: 10000,

  // Headers
  DEVICE_ID_HEADER: 'X-Device-ID',
  ADMIN_PASSWORD_HEADER: 'X-Admin-Password',
  AUTHORIZATION_HEADER: 'Authorization',

  // Error Codes
  ERROR_DEVICE_BLOCKED: 'DEVICE_BLOCKED',
  ERROR_ADMIN_LOGIN_LOCKED: 'ADMIN_LOGIN_LOCKED',
  ERROR_INVALID_CREDENTIALS: 'INVALID_CREDENTIALS',
  ERROR_UPDATE_REQUIRED: 'UPDATE_REQUIRED',
};

export type SecurityConstants = typeof SecurityConstants;