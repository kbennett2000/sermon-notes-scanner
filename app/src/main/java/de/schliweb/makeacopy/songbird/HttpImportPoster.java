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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Real {@link ImportPoster}: a single {@code HttpURLConnection} POST to {@code {base}/api/v1/import}
 * (slice F6). Context-free. No retries — the songbird import is idempotent, so the operator simply taps
 * Send again to retry. The token is set as the {@code Authorization} header only — never logged.
 *
 * <p>Not unit-tested (no MockWebServer in this project, by decision) — validated on-device via the
 * {@link ImportPoster} seam, which a fake covers in {@code FinalizeViewModelTest}.
 */
public final class HttpImportPoster implements ImportPoster {

  private static final int CONNECT_TIMEOUT_MS = 5000;
  private static final int READ_TIMEOUT_MS = 15000;

  @Override
  public PostResult post(String baseUrl, String token, String json) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(baseUrl + "/api/v1/import");
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Authorization", "Bearer " + token);
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      conn.setDoOutput(true);

      byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(body);
      }

      int status = conn.getResponseCode();
      InputStream stream =
          (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
      return PostResult.http(status, readAll(stream));
    } catch (IOException e) {
      // Host unreachable / timeout / DNS — surfaced as UNREACHABLE (no token in any message).
      return PostResult.unreachable();
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  private static String readAll(InputStream in) throws IOException {
    if (in == null) return "";
    try (InputStream s = in) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = s.read(buf)) != -1) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
