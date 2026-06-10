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
import com.google.gson.JsonParser;

/**
 * Classified outcome of a songbird import (slice F6), derived purely from a {@link PostResult}. The four
 * statuses each map to a distinguishable, actionable message on the finalize screen. Tolerant parsing:
 * unknown fields ignored; a 2xx without a usable summary is still success.
 *
 * @param status the outcome category
 * @param created annotations created (SUCCESS only; 0 otherwise)
 * @param skipped annotations skipped — the idempotency proof (SUCCESS only)
 * @param summaryPresent whether the 2xx response carried a parseable created/skipped summary
 * @param httpCode the HTTP status (HTTP_ERROR only; 0 otherwise)
 * @param detail a short body snippet for HTTP_ERROR (≤200 chars; never contains the token)
 */
public record ImportResult(
    Status status, int created, int skipped, boolean summaryPresent, int httpCode, String detail) {

  public enum Status {
    SUCCESS,
    UNREACHABLE,
    UNAUTHORIZED,
    HTTP_ERROR
  }

  private static final int SNIPPET_MAX = 200;

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }

  /** Classifies a raw {@link PostResult}. Pure. */
  public static ImportResult from(PostResult r) {
    if (r == null || r.networkError()) {
      return new ImportResult(Status.UNREACHABLE, 0, 0, false, 0, "");
    }
    int s = r.status();
    if (s >= 200 && s < 300) {
      return parseSuccess(r.body());
    }
    if (s == 401 || s == 403) {
      return new ImportResult(Status.UNAUTHORIZED, 0, 0, false, s, "");
    }
    return new ImportResult(Status.HTTP_ERROR, 0, 0, false, s, snippet(r.body()));
  }

  private static ImportResult parseSuccess(String body) {
    try {
      JsonObject root = JsonParser.parseString(body).getAsJsonObject();
      JsonObject ann = root.getAsJsonObject("annotations");
      if (ann != null && ann.has("created") && ann.has("skipped")) {
        return new ImportResult(
            Status.SUCCESS, ann.get("created").getAsInt(), ann.get("skipped").getAsInt(), true, 0, "");
      }
    } catch (RuntimeException ignore) {
      // 2xx but unparseable/missing summary — still a success.
    }
    return new ImportResult(Status.SUCCESS, 0, 0, false, 0, "");
  }

  private static String snippet(String body) {
    if (body == null) return "";
    String t = body.trim();
    return t.length() <= SNIPPET_MAX ? t : t.substring(0, SNIPPET_MAX);
  }
}
