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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Tests for {@link SpanResolver} (slice F3b, decision D2) against a small HAND-MADE sample table (3
 * books) — NOT the real Concord-generated asset. Offline and deterministic.
 */
public class SpanResolverTest {

  private static VerseTable table;

  @BeforeClass
  public static void loadSampleTable() throws IOException {
    table = VerseTable.fromJson(readResource("/anchor/verse_counts_sample.json"));
  }

  @Test
  public void chapterOnly_fillsWholeChapterFromTable() {
    // Ps. 23 has 6 verses in the sample → PSA 23:1 → 23:6.
    SpanResolution r = SpanResolver.resolve(StructuralAnchor.chapterOnly("PSA", 23), table);
    assertTrue(r.isResolved());
    assertEquals(new ResolvedSpan("PSA", 23, 1, 23, 6), r.span());
  }

  @Test
  public void verseRange_passesThroughUnchanged() {
    // 1 Samuel 25:3-6 → 1SA 25:3 → 25:6 (the table is not consulted for the verses).
    SpanResolution r = SpanResolver.resolve(StructuralAnchor.range("1SA", 25, 3, 6), table);
    assertTrue(r.isResolved());
    assertEquals(new ResolvedSpan("1SA", 25, 3, 25, 6), r.span());
  }

  @Test
  public void singleVerse_startEqualsEnd() {
    // Mt. 4:11 → MAT 4:11 → 4:11.
    SpanResolution r = SpanResolver.resolve(StructuralAnchor.verse("MAT", 4, 11), table);
    assertTrue(r.isResolved());
    assertEquals(new ResolvedSpan("MAT", 4, 11, 4, 11), r.span());
  }

  @Test
  public void unknownBook_typedFailure() {
    // REV is not in the 3-book sample.
    SpanResolution r = SpanResolver.resolve(StructuralAnchor.chapterOnly("REV", 1), table);
    assertFalse(r.isResolved());
    assertEquals(SpanResolution.Status.UNKNOWN_BOOK, r.status());
    assertNull(r.span());
  }

  @Test
  public void chapterOutOfRange_typedFailure() {
    // MAT in the sample has 5 chapters; chapter 9 is out of range.
    SpanResolution r = SpanResolver.resolve(StructuralAnchor.chapterOnly("MAT", 9), table);
    assertFalse(r.isResolved());
    assertEquals(SpanResolution.Status.CHAPTER_OUT_OF_RANGE, r.status());
    assertNull(r.span());
  }

  @Test
  public void chapterZeroOrNegative_typedFailure() {
    assertEquals(
        SpanResolution.Status.CHAPTER_OUT_OF_RANGE,
        SpanResolver.resolve(StructuralAnchor.chapterOnly("PSA", 0), table).status());
  }

  @Test
  public void outOfRangeVerse_isNotValidated() {
    // §6: no verse-existence checking. Ps 23 has 6 verses but 23:99 still resolves as printed.
    SpanResolution r = SpanResolver.resolve(StructuralAnchor.verse("PSA", 23, 99), table);
    assertTrue(r.isResolved());
    assertEquals(new ResolvedSpan("PSA", 23, 99, 23, 99), r.span());
  }

  @Test
  public void reversedPrintedRange_passedThroughUnchanged() {
    // Best-effort: an odd printed range is not "fixed" here — F4 is the safety net.
    SpanResolution r = SpanResolver.resolve(StructuralAnchor.range("1SA", 25, 6, 3), table);
    assertTrue(r.isResolved());
    assertEquals(new ResolvedSpan("1SA", 25, 6, 25, 3), r.span());
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = SpanResolverTest.class.getResourceAsStream(path)) {
      if (in == null) throw new IOException("missing test resource: " + path);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
