/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import lombok.experimental.UtilityClass;

/**
 * Helper class that centralizes reading the SharedPreferences still used by the kept flow: the
 * "Skip OCR" capture toggle, the multi-page hub's pending-add-page flag, and the last PDF-import
 * directory hint. The export-OUTPUT preferences (PDF/JPEG/inbox/last-export-uri) were removed in
 * F1c-2 together with the export feature.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
public final class ExportPrefsHelper {

  private static final String PREFS_NAME = "export_options";

  public static SharedPreferences getPrefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public static boolean isSkipOcr(Context context) {
    return getPrefs(context).getBoolean("skip_ocr", false);
  }

  public static boolean isPendingAddPage(Context context) {
    return getPrefs(context).getBoolean("pending_add_page", false);
  }

  public static void clearPendingAddPage(Context context) {
    getPrefs(context).edit().putBoolean("pending_add_page", false).apply();
  }

  public static void setPendingAddPage(Context context) {
    getPrefs(context).edit().putBoolean("pending_add_page", true).apply();
  }

  public static String getLastImportUri(Context context) {
    String value = getPrefs(context).getString("last_import_uri", null);
    Log.d("ExportPrefsHelper", "getLastImportUri: " + value);
    return value;
  }

  public static void setLastImportUri(Context context, String uri) {
    Log.d("ExportPrefsHelper", "setLastImportUri: " + uri);
    getPrefs(context).edit().putString("last_import_uri", uri).apply();
  }
}
