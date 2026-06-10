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
 * Raw outcome of an HTTP POST across the {@link ImportPoster} seam (slice F6) — no exceptions cross the
 * seam, so it is trivially fakeable in tests. Carries no token (never echoed anywhere).
 *
 * @param networkError true when the connection failed/timed out (host unreachable)
 * @param status the HTTP status code (0 when {@code networkError})
 * @param body the response body (may be empty)
 */
public record PostResult(boolean networkError, int status, String body) {

  public static PostResult unreachable() {
    return new PostResult(true, 0, "");
  }

  public static PostResult http(int status, String body) {
    return new PostResult(false, status, body == null ? "" : body);
  }
}
