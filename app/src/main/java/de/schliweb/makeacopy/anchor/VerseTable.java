/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.anchor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The bundled canon-structural verse-count table (slice F3b, decision D2): USFM book code → per-chapter
 * verse counts (array index = chapter − 1). Generated once from Concord by
 * {@code tools/generate_verse_counts} and shipped as the app asset
 * {@code assets/anchor/verse_counts.json}.
 *
 * <p>The counts are treated as canonical structure. {@code meta.sourceTranslation} and
 * {@code meta.concordVersion} are provenance only; the resolver ignores them.
 *
 * <p>Pure: this class only parses a JSON string and answers lookups. Reading the asset bytes through
 * {@code Context.getAssets()} is a thin Android concern handled by the F4 wiring, which then calls
 * {@link #fromJson(String)}.
 */
public final class VerseTable {

  /** Provenance + shape metadata from the table's {@code meta} block. */
  public record Meta(
      String sourceTranslation, String concordVersion, String generatedAt, int bookCount) {}

  private final Meta meta;
  private final Map<String, int[]> books;

  private VerseTable(Meta meta, Map<String, int[]> books) {
    this.meta = meta;
    this.books = books;
  }

  /** Parses the {@code verse_counts.json} payload. Throws on malformed JSON / missing structure. */
  public static VerseTable fromJson(String json) {
    JsonObject root = JsonParser.parseString(json).getAsJsonObject();

    JsonObject m = root.getAsJsonObject("meta");
    if (m == null) {
      throw new IllegalArgumentException("verse_counts: missing \"meta\"");
    }
    Meta meta =
        new Meta(
            optString(m, "source_translation"),
            optString(m, "concord_version"),
            optString(m, "generated_at"),
            m.has("book_count") ? m.get("book_count").getAsInt() : 0);

    JsonObject b = root.getAsJsonObject("books");
    if (b == null) {
      throw new IllegalArgumentException("verse_counts: missing \"books\"");
    }
    Map<String, int[]> books = new LinkedHashMap<>();
    for (Map.Entry<String, JsonElement> e : b.entrySet()) {
      JsonArray arr = e.getValue().getAsJsonArray();
      int[] counts = new int[arr.size()];
      for (int i = 0; i < arr.size(); i++) {
        counts[i] = arr.get(i).getAsInt();
      }
      books.put(e.getKey(), counts);
    }
    return new VerseTable(meta, Collections.unmodifiableMap(books));
  }

  /**
   * The verse count for a book/chapter, or {@code null} when the book is absent or the chapter is
   * outside {@code 1..chapterCount}. Verse numbers are never validated here (BUILD-BRIEF §6).
   */
  public Integer verseCount(String bookUsfm, int chapter) {
    int[] counts = books.get(bookUsfm);
    if (counts == null) return null;
    if (chapter < 1 || chapter > counts.length) return null;
    return counts[chapter - 1];
  }

  /** Number of chapters recorded for a book, or 0 if the book is absent. */
  public int chapterCount(String bookUsfm) {
    int[] counts = books.get(bookUsfm);
    return counts == null ? 0 : counts.length;
  }

  /** The set of USFM book codes present in the table. */
  public Set<String> bookCodes() {
    return books.keySet();
  }

  /** The table's provenance/shape metadata. */
  public Meta meta() {
    return meta;
  }

  private static String optString(JsonObject o, String key) {
    return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
  }
}
