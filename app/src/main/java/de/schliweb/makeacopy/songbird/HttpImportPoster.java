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

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Real {@link ImportPoster}: songbird's Argon2 cookie-session flow (slice F6b), login-per-send with
 * explicit cookie handling (no global {@code CookieManager}):
 *
 * <ol>
 *   <li>{@code POST {base}/api/v1/auth/login} {username,password} → capture the {@code songbird_session}
 *       cookie from {@code Set-Cookie}.
 *   <li>{@code POST {base}/api/v1/import} with a {@code Cookie} header + the JSON body.
 *   <li>best-effort {@code POST {base}/api/v1/auth/logout} (ignored outcome) — deletes the session row so
 *       login-per-send doesn't accumulate 30-day rows.
 * </ol>
 *
 * Context-free. Timeouts connect 5s / read 15s, no retries (idempotent — operator re-taps). Credentials
 * live only in the login body + the cookie header — never logged. Not unit-tested (no MockWebServer);
 * validated on-device via the {@link ImportPoster} seam, which a fake covers in {@code
 * FinalizeViewModelTest}.
 */
public final class HttpImportPoster implements ImportPoster {

  private static final int CONNECT_TIMEOUT_MS = 5000;
  private static final int READ_TIMEOUT_MS = 15000;
  private static final String COOKIE_NAME = "songbird_session";

  @Override
  public SongbirdExchange send(String baseUrl, String username, String password, String json) {
    String[] cookieOut = new String[1];
    PostResult login =
        post(baseUrl + "/api/v1/auth/login", loginJson(username, password), null, cookieOut);
    if (login.networkError() || login.status() < 200 || login.status() >= 300) {
      return SongbirdExchange.loginOnly(login);
    }
    String cookie = cookieOut[0]; // session token value, or null if no Set-Cookie
    PostResult imported = post(baseUrl + "/api/v1/import", json, cookie, null);
    if (cookie != null) {
      // Best-effort cleanup so the per-send session row doesn't linger 30 days; outcome ignored.
      post(baseUrl + "/api/v1/auth/logout", "", cookie, null);
    }
    return SongbirdExchange.of(login, imported);
  }

  /** One POST. {@code cookie} (if non-null) is sent as the session header; {@code cookieOut} (if
   * non-null) receives the captured {@code songbird_session} value from the response. */
  private static PostResult post(String urlStr, String body, String cookie, String[] cookieOut) {
    HttpURLConnection conn = null;
    try {
      URL url = new URL(urlStr);
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
      conn.setReadTimeout(READ_TIMEOUT_MS);
      conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      conn.setRequestProperty("Accept", "application/json");
      if (cookie != null) {
        conn.setRequestProperty("Cookie", COOKIE_NAME + "=" + cookie);
      }
      conn.setDoOutput(true);
      byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
      try (OutputStream os = conn.getOutputStream()) {
        os.write(b);
      }
      int status = conn.getResponseCode();
      if (cookieOut != null) {
        cookieOut[0] = extractSessionCookie(conn);
      }
      InputStream stream =
          (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
      return PostResult.http(status, readAll(stream));
    } catch (IOException e) {
      return PostResult.unreachable();
    } finally {
      if (conn != null) conn.disconnect();
    }
  }

  /** The {@code songbird_session} value from any {@code Set-Cookie} response header, or null. */
  private static String extractSessionCookie(HttpURLConnection conn) {
    for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
      if (e.getKey() == null || !"Set-Cookie".equalsIgnoreCase(e.getKey())) continue;
      for (String value : e.getValue()) {
        if (value != null && value.startsWith(COOKIE_NAME + "=")) {
          String rest = value.substring((COOKIE_NAME + "=").length());
          int semi = rest.indexOf(';');
          return semi >= 0 ? rest.substring(0, semi) : rest;
        }
      }
    }
    return null;
  }

  private static String loginJson(String username, String password) {
    JsonObject o = new JsonObject();
    o.addProperty("username", username == null ? "" : username);
    o.addProperty("password", password == null ? "" : password);
    return o.toString();
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
