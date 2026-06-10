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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Classified outcome of a songbird login-per-send exchange (slice F6b), derived purely from a {@link
 * SongbirdExchange}. Each status maps to a distinguishable, actionable message on the finalize screen.
 *
 * <p>Success parses songbird's {@code ImportSummary}:
 * {@code {"annotations":{"created","skipped","failed"},"sermon_notes":{…},"errors":[…]}}. Tolerant:
 * unknown fields ignored; a 2xx import without a usable summary is still success (no counts).
 *
 * @param status the outcome category
 * @param created annotations created (SUCCESS only)
 * @param skipped annotations skipped — the idempotency proof (SUCCESS only)
 * @param failed total entries songbird rejected (annotations + sermon_notes; SUCCESS only)
 * @param summaryPresent whether the 2xx import response carried a parseable annotations summary
 * @param httpCode the HTTP status (HTTP_ERROR only; 0 otherwise)
 * @param detail HTTP_ERROR body snippet, or the first {@code errors[]} reason when {@code failed>0}
 *     (≤200 chars; never contains credentials)
 */
public record ImportResult(
    Status status,
    int created,
    int skipped,
    int failed,
    boolean summaryPresent,
    int httpCode,
    String detail) {

  public enum Status {
    SUCCESS,
    UNREACHABLE,
    LOGIN_REJECTED,
    HTTP_ERROR
  }

  private static final int SNIPPET_MAX = 200;

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  /** True when the import succeeded but songbird rejected one or more entries. */
  public boolean hasFailures() {
    return status == Status.SUCCESS && failed > 0;
  }

  /** Classifies a raw {@link SongbirdExchange}. Pure. */
  public static ImportResult from(SongbirdExchange ex) {
    if (ex == null || ex.login() == null || ex.login().networkError()) {
      return unreachable();
    }
    PostResult login = ex.login();
    if (!is2xx(login.status())) {
      if (login.status() == 401 || login.status() == 403) {
        return new ImportResult(Status.LOGIN_REJECTED, 0, 0, 0, false, login.status(), "");
      }
      return httpError(login.status(), login.body());
    }
    // Login succeeded — classify the import.
    PostResult imp = ex.imported();
    if (imp == null || imp.networkError()) {
      return unreachable();
    }
    if (is2xx(imp.status())) {
      return parseSummary(imp.body());
    }
    return httpError(imp.status(), imp.body());
  }

  private static ImportResult parseSummary(String body) {
    try {
      JsonObject root = JsonParser.parseString(body).getAsJsonObject();
      JsonObject ann = root.getAsJsonObject("annotations");
      if (ann != null && ann.has("created") && ann.has("skipped")) {
        int created = ann.get("created").getAsInt();
        int skipped = ann.get("skipped").getAsInt();
        int failed = optInt(ann, "failed") + sermonFailed(root);
        return new ImportResult(
            Status.SUCCESS, created, skipped, failed, true, 0, failed > 0 ? firstError(root) : "");
      }
    } catch (RuntimeException ignore) {
      // 2xx but unparseable/missing summary — still a success, just no counts.
    }
    return new ImportResult(Status.SUCCESS, 0, 0, 0, false, 0, "");
  }

  private static int sermonFailed(JsonObject root) {
    JsonObject sn = root.getAsJsonObject("sermon_notes");
    return sn == null ? 0 : optInt(sn, "failed");
  }

  private static int optInt(JsonObject o, String key) {
    return (o != null && o.has(key) && o.get(key).isJsonPrimitive()) ? o.get(key).getAsInt() : 0;
  }

  private static String firstError(JsonObject root) {
    JsonArray errors = root.getAsJsonArray("errors");
    if (errors != null && errors.size() > 0) {
      return snippet(errors.get(0).getAsString());
    }
    return "";
  }

  private static ImportResult unreachable() {
    return new ImportResult(Status.UNREACHABLE, 0, 0, 0, false, 0, "");
  }

  private static ImportResult httpError(int code, String body) {
    return new ImportResult(Status.HTTP_ERROR, 0, 0, 0, false, code, snippet(body));
  }

  private static boolean is2xx(int s) {
    return s >= 200 && s < 300;
  }

  private static String snippet(String body) {
    if (body == null) return "";
    String t = body.trim();
    return t.length() <= SNIPPET_MAX ? t : t.substring(0, SNIPPET_MAX);
  }
}
