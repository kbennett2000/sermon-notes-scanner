/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.edit;

import android.content.Context;
import android.util.Log;
import de.schliweb.makeacopy.anchor.VerseTable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thin, cached loader for the bundled verse-count asset (the loader F3b deferred). Reads
 * {@code assets/anchor/verse_counts.json} once and caches the parsed {@link VerseTable}; parsing itself
 * lives in the unit-tested {@link VerseTable#fromJson}. This is the only Android-coupled piece of the
 * anchor span path — validated on-device.
 */
public final class VerseTableLoader {

  private static final String TAG = "VerseTableLoader";
  private static final String ASSET_PATH = "anchor/verse_counts.json";

  private static volatile VerseTable cached;

  private VerseTableLoader() {}

  /**
   * Loads (and caches) the verse-count table from assets. Returns {@code null} if the asset is missing
   * or malformed — the caller surfaces that to the operator rather than crashing.
   */
  public static VerseTable load(Context context) {
    VerseTable local = cached;
    if (local != null) return local;
    synchronized (VerseTableLoader.class) {
      if (cached != null) return cached;
      try (InputStream is = context.getApplicationContext().getAssets().open(ASSET_PATH)) {
        cached = VerseTable.fromJson(readUtf8(is));
        return cached;
      } catch (IOException | RuntimeException e) {
        Log.e(TAG, "Failed to load " + ASSET_PATH, e);
        return null;
      }
    }
  }

  private static String readUtf8(InputStream is) throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
    return new String(bos.toByteArray(), StandardCharsets.UTF_8);
  }
}
