/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.emit;

import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.draft.SermonDraft;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Emits the songbird ImportDocument JSON for a {@link SermonDraft} (slice F5), per BUILD-BRIEF
 * Appendix A. Output is <strong>byte-stable</strong>: a fixed-order {@link StringBuilder} walk owns the
 * exact key sequence, 2-space indentation, present-and-null fields, and string escaping — rather than a
 * reflection/Gson serialization where key order and null/indent handling are incidental. A committed
 * golden file pins the wire format.
 *
 * <p>Invariants are hard-coded: {@code version} 1, {@code exported_at} null, {@code color} null,
 * {@code scope_type} "all", {@code scope_translations} [], {@code sermon_notes} [], exactly one
 * annotation. The reversed-range divergence (Appendix A requires end ≥ start) is normalized here.
 *
 * <p>Pure Java — no Android dependencies, no network (POST is F6).
 */
public final class ImportJsonEmitter {

  private static final String I2 = "  ";
  private static final String I4 = "    ";
  private static final String I6 = "      ";

  private ImportJsonEmitter() {}

  public static String emit(SermonDraft draft) {
    ResolvedSpan span = draft.span();
    if (span == null) {
      throw new IllegalArgumentException("SermonDraft.span must be non-null (resolved before emit)");
    }
    // Normalize so (end_chapter, end_verse) >= (start_chapter, start_verse).
    int sc = span.startChapter();
    int sv = span.startVerse();
    int ec = span.endChapter();
    int ev = span.endVerse();
    if (lessThan(ec, ev, sc, sv)) {
      int tc = sc, tv = sv;
      sc = ec;
      sv = ev;
      ec = tc;
      ev = tv;
    }

    String note = NoteMarkdown.build(draft);

    StringBuilder b = new StringBuilder(512);
    b.append("{\n");
    b.append(I2).append("\"version\": 1,\n");
    b.append(I2).append("\"exported_at\": null,\n");
    b.append(I2).append("\"annotations\": [\n");
    b.append(I4).append("{\n");
    b.append(I6).append("\"book_usfm\": \"").append(escape(span.bookUsfm())).append("\",\n");
    b.append(I6).append("\"start_chapter\": ").append(sc).append(",\n");
    b.append(I6).append("\"start_verse\": ").append(sv).append(",\n");
    b.append(I6).append("\"end_chapter\": ").append(ec).append(",\n");
    b.append(I6).append("\"end_verse\": ").append(ev).append(",\n");
    b.append(I6).append("\"note_markdown\": \"").append(escape(note)).append("\",\n");
    b.append(I6).append("\"color\": null,\n");
    b.append(I6).append("\"scope_type\": \"all\",\n");
    b.append(I6).append("\"scope_translations\": [],\n");
    b.append(I6).append("\"tags\": ").append(tagsArray(draft.tags())).append("\n");
    b.append(I4).append("}\n");
    b.append(I2).append("],\n");
    b.append(I2).append("\"sermon_notes\": []\n");
    b.append("}");
    return b.toString();
  }

  private static boolean lessThan(int c1, int v1, int c2, int v2) {
    return c1 < c2 || (c1 == c2 && v1 < v2);
  }

  /** Renders tags inline: trimmed, empties dropped, case-sensitively deduped (first wins), draft order. */
  private static String tagsArray(List<String> tags) {
    Set<String> kept = new LinkedHashSet<>();
    if (tags != null) {
      for (String t : tags) {
        if (t == null) continue;
        String trimmed = t.trim();
        if (!trimmed.isEmpty()) kept.add(trimmed);
      }
    }
    if (kept.isEmpty()) return "[]";
    List<String> parts = new ArrayList<>(kept.size());
    for (String t : kept) parts.add("\"" + escape(t) + "\"");
    return "[" + String.join(", ", parts) + "]";
  }

  /** Escapes a string for a JSON string literal (no gratuitous escaping; non-ASCII passes through). */
  private static String escape(String s) {
    if (s == null) return "";
    StringBuilder out = new StringBuilder(s.length() + 16);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          out.append("\\\"");
          break;
        case '\\':
          out.append("\\\\");
          break;
        case '\n':
          out.append("\\n");
          break;
        case '\r':
          out.append("\\r");
          break;
        case '\t':
          out.append("\\t");
          break;
        case '\b':
          out.append("\\b");
          break;
        case '\f':
          out.append("\\f");
          break;
        default:
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
      }
    }
    return out.toString();
  }
}
