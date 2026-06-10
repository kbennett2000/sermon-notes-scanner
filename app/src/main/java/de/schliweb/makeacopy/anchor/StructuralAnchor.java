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
 * A Scripture reference as it was <em>structurally</em> resolved from OCR text (slice F3).
 *
 * <p>This represents WHAT WAS ON THE PAGE — the book, chapter, and the verse range exactly as printed.
 * A chapter-only reference stays chapter-only here (both verses null). Filling an end-verse for a
 * whole-chapter passage and emitting the five flat anchor fields (start/end chapter+verse) is span
 * resolution per decision D2 — that is F3b/F5, deliberately not done here.
 *
 * <p>Pure value type; record {@code equals}/{@code hashCode} drive the finder's unit tests.
 *
 * @param bookUsfm the USFM code from {@link BookMap} (e.g. {@code "1SA"})
 * @param chapter the printed chapter number
 * @param startVerse the printed start verse, or {@code null} for a chapter-only reference
 * @param endVerse the printed end verse of a range, or {@code null} when there is no range
 */
public record StructuralAnchor(
    String bookUsfm, int chapter, Integer startVerse, Integer endVerse) {

  /** A chapter-only reference, e.g. {@code Ps. 23} → {@code PSA 23}. */
  public static StructuralAnchor chapterOnly(String bookUsfm, int chapter) {
    return new StructuralAnchor(bookUsfm, chapter, null, null);
  }

  /** A single-verse reference, e.g. {@code 1Sam. 22:1} → {@code 1SA 22:1}. */
  public static StructuralAnchor verse(String bookUsfm, int chapter, int verse) {
    return new StructuralAnchor(bookUsfm, chapter, verse, null);
  }

  /** A verse-range reference (as printed), e.g. {@code 1 Samuel 25:3-6} → {@code 1SA 25:3-6}. */
  public static StructuralAnchor range(String bookUsfm, int chapter, int startVerse, int endVerse) {
    return new StructuralAnchor(bookUsfm, chapter, startVerse, endVerse);
  }
}
