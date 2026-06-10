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

import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.draft.SermonDraft;

/**
 * Deterministic cache filename for the shared import JSON (slice F6):
 * {@code sermon-import-{bookUsfm}-{startChapter}-{dateIso}.json}, with each component sanitized to a
 * filesystem-safe set. Pure.
 */
public final class ShareFilename {

  private ShareFilename() {}

  public static String forDraft(SermonDraft draft) {
    ResolvedSpan span = draft.span();
    String book = span != null ? span.bookUsfm() : "UNK";
    int chapter = span != null ? span.startChapter() : 0;
    String date = draft.dateIso() == null ? "" : draft.dateIso();
    return "sermon-import-" + sanitize(book) + "-" + chapter + "-" + sanitize(date) + ".json";
  }

  /** Keeps {@code [A-Za-z0-9._-]}; everything else becomes {@code _}. */
  private static String sanitize(String s) {
    if (s == null || s.isEmpty()) return "x";
    return s.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
