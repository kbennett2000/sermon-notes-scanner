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

/**
 * Resolves a {@link StructuralAnchor} (what was printed on the page) into the five-field {@link
 * ResolvedSpan} that BUILD-BRIEF Appendix A requires, using the bundled verse-count table (decision
 * D2). Pure — the table is injected, never loaded here.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>chapter-only (e.g. {@code Ps. 23}) → {@code startVerse = 1}, {@code endVerse =
 *       table[book][chapter]}.
 *   <li>single verse (e.g. {@code Mt. 4:11}) → {@code start = end = 11}.
 *   <li>verse range as printed → passed through unchanged.
 *   <li>book absent / chapter out of range → a typed {@link SpanResolution} failure (F4 surfaces it).
 * </ul>
 *
 * <p>Verse numbers are never validated (§6: no verse-existence checking) — {@code Ps 23:99} resolves
 * fine. The table is consulted only to fill chapter-only end-verses and to gate chapter validity.
 */
public final class SpanResolver {

  private SpanResolver() {}

  public static SpanResolution resolve(StructuralAnchor anchor, VerseTable table) {
    String usfm = anchor.bookUsfm();
    int chapter = anchor.chapter();

    Integer chapterVerses = table.verseCount(usfm, chapter);
    if (chapterVerses == null) {
      // Distinguish "book not in table" from "chapter out of range" for a clearer F4 message.
      if (table.chapterCount(usfm) == 0) {
        return SpanResolution.unknownBook(usfm);
      }
      return SpanResolution.chapterOutOfRange(usfm, chapter);
    }

    int startVerse;
    int endVerse;
    if (anchor.startVerse() == null) {
      // chapter-only → whole chapter
      startVerse = 1;
      endVerse = chapterVerses;
    } else if (anchor.endVerse() == null) {
      // single verse
      startVerse = anchor.startVerse();
      endVerse = anchor.startVerse();
    } else {
      // verse range as printed — passed through unchanged
      startVerse = anchor.startVerse();
      endVerse = anchor.endVerse();
    }

    return SpanResolution.resolved(
        new ResolvedSpan(usfm, chapter, startVerse, chapter, endVerse));
  }
}
