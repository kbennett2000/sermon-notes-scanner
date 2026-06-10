/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.songbird;

/**
 * The network seam for sending an import document to songbird (slice F6b). songbird uses Argon2
 * cookie-session auth, so a send is login-per-send: POST login → cookied POST import (→ best-effort
 * logout). A thin interface so {@link de.schliweb.makeacopy.ui.finalize.FinalizeViewModel} can be
 * unit-tested with a fake — the real implementation is {@link HttpImportPoster}.
 */
public interface ImportPoster {

  /**
   * Logs in with {@code username}/{@code password}, then POSTs {@code json} to the import endpoint with
   * the captured session cookie. Implementations must never log or echo the credentials. Returns the raw
   * {@link SongbirdExchange}; connection failures map to {@link PostResult#unreachable()} rather than
   * throwing.
   *
   * @param baseUrl normalized base URL (no trailing slash)
   * @param username songbird username (login body only)
   * @param password songbird password (login body only)
   * @param json the emitted import document
   */
  SongbirdExchange send(String baseUrl, String username, String password, String json);
}
