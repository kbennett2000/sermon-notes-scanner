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
 * Pure helpers for the songbird connection settings (slice F6). No Android dependencies.
 */
public final class SongbirdSettings {

  private SongbirdSettings() {}

  /**
   * Normalizes a base URL for storage: trims whitespace and strips trailing slashes. {@code http://} is
   * accepted as-is — songbird is a LAN/tailnet service, not forced to https. Returns {@code ""} for null.
   */
  public static String normalizeBaseUrl(String raw) {
    if (raw == null) return "";
    String s = raw.trim();
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  /** True when both the base URL and the token are present (the Send gate). */
  public static boolean canSend(String baseUrl, String token) {
    return baseUrl != null
        && !baseUrl.trim().isEmpty()
        && token != null
        && !token.trim().isEmpty();
  }
}
