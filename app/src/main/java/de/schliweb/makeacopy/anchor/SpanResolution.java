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
 * Typed result of {@link SpanResolver#resolve} (slice F3b): either a resolved {@link ResolvedSpan} or a
 * named failure. Never a crash, never a silent default — F4 surfaces a failure to the operator.
 *
 * @param status the outcome
 * @param span the resolved span when {@code status == RESOLVED}, otherwise {@code null}
 * @param detail a short human-readable explanation (for failures; empty on success)
 */
public record SpanResolution(Status status, ResolvedSpan span, String detail) {

  /** Resolution outcomes. */
  public enum Status {
    /** The span was resolved (chapter-only filled, single verse, or range passed through). */
    RESOLVED,
    /** The anchor's book is not present in the verse-count table. */
    UNKNOWN_BOOK,
    /** The anchor's chapter is outside the book's chapter range in the table. */
    CHAPTER_OUT_OF_RANGE
  }

  /** True iff this is a successful resolution. */
  public boolean isResolved() {
    return status == Status.RESOLVED;
  }

  static SpanResolution resolved(ResolvedSpan span) {
    return new SpanResolution(Status.RESOLVED, span, "");
  }

  static SpanResolution unknownBook(String usfm) {
    return new SpanResolution(Status.UNKNOWN_BOOK, null, "Unknown book in verse-count table: " + usfm);
  }

  static SpanResolution chapterOutOfRange(String usfm, int chapter) {
    return new SpanResolution(
        Status.CHAPTER_OUT_OF_RANGE, null, "Chapter out of range: " + usfm + " " + chapter);
  }
}
