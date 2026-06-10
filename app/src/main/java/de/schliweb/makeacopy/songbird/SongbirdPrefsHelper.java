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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Stores the songbird connection settings (slice F6, decision D1): base URL + bearer token, both at rest
 * in {@link EncryptedSharedPreferences}. The token is NEVER written to plaintext prefs, NEVER logged, and
 * NEVER echoed — error handling logs only generic messages.
 */
public final class SongbirdPrefsHelper {

  private static final String TAG = "SongbirdPrefs";
  private static final String FILE = "songbird_secure_prefs";
  private static final String KEY_BASE_URL = "songbird_base_url";
  private static final String KEY_TOKEN = "songbird_bearer_token";

  private SongbirdPrefsHelper() {}

  public static String getBaseUrl(Context ctx) {
    SharedPreferences p = open(ctx);
    return p == null ? "" : p.getString(KEY_BASE_URL, "");
  }

  public static String getToken(Context ctx) {
    SharedPreferences p = open(ctx);
    return p == null ? "" : p.getString(KEY_TOKEN, "");
  }

  /** Persists the base URL, normalized (trim + strip trailing slash). */
  public static void setBaseUrl(Context ctx, String value) {
    SharedPreferences p = open(ctx);
    if (p != null) p.edit().putString(KEY_BASE_URL, SongbirdSettings.normalizeBaseUrl(value)).apply();
  }

  /** Persists the bearer token (trimmed). Never logged. */
  public static void setToken(Context ctx, String value) {
    SharedPreferences p = open(ctx);
    if (p != null) p.edit().putString(KEY_TOKEN, value == null ? "" : value.trim()).apply();
  }

  public static boolean isConfigured(Context ctx) {
    return SongbirdSettings.canSend(getBaseUrl(ctx), getToken(ctx));
  }

  private static SharedPreferences open(Context ctx) {
    try {
      Context app = ctx.getApplicationContext();
      MasterKey key =
          new MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
      return EncryptedSharedPreferences.create(
          app,
          FILE,
          key,
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    } catch (Throwable t) {
      // Generic message only — never include pref values.
      Log.e(TAG, "Failed to open encrypted settings", t);
      return null;
    }
  }
}
