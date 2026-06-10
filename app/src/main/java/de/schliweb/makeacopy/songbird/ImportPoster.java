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
 * The network seam for posting the import document to songbird (slice F6). A thin interface so {@link
 * de.schliweb.makeacopy.ui.finalize.FinalizeViewModel} can be unit-tested with a fake — the real
 * implementation is {@link HttpImportPoster}.
 */
public interface ImportPoster {

  /**
   * POSTs {@code json} to songbird. Implementations must never log or echo {@code token}. Returns a
   * {@link PostResult}; connection failures map to {@link PostResult#unreachable()} rather than throwing.
   *
   * @param baseUrl normalized base URL (no trailing slash)
   * @param token bearer token (header only)
   * @param json the emitted import document
   */
  PostResult post(String baseUrl, String token, String json);
}
