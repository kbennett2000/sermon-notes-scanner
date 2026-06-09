/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link OcrTextJoiner}, the pure F2 combined-OCR join logic. Pins the
 * combined-string contract: hub order preserved, per-page trim, empty/null pages skipped, exactly
 * "\n\n" between survivors, deterministic, zero pages → "".
 */
public class OcrTextJoinerTest {

  @Test
  public void join_twoPages_normalCase_joinsWithBlankLine() {
    assertEquals("A\n\nB", OcrTextJoiner.join(Arrays.asList("A", "B")));
  }

  @Test
  public void join_orderPreserved_reorderRespected() {
    // The joiner preserves input order; hub reorder is reflected by the caller's list order.
    assertEquals("B\n\nA", OcrTextJoiner.join(Arrays.asList("B", "A")));
  }

  @Test
  public void join_emptyPage_skippedNoDoubleSeparator() {
    assertEquals("A\n\nB", OcrTextJoiner.join(Arrays.asList("A", "", "B")));
  }

  @Test
  public void join_whitespaceOnlyPage_skipped() {
    assertEquals("A\n\nB", OcrTextJoiner.join(Arrays.asList("A", "   ", "B")));
    assertEquals("A\n\nB", OcrTextJoiner.join(Arrays.asList("A", "\n\t ", "B")));
  }

  @Test
  public void join_trimsEachPage_internalWhitespaceUntouched() {
    assertEquals("A\n\nB", OcrTextJoiner.join(Arrays.asList("  A  ", "B\n")));
    // Internal whitespace within a page is preserved.
    assertEquals("a b\n\nc", OcrTextJoiner.join(Arrays.asList("  a b  ", "c")));
  }

  @Test
  public void join_deterministic_sameInputSameOutput() {
    List<String> pages = Arrays.asList("  one ", "", "two", "   ", "three\n");
    String first = OcrTextJoiner.join(pages);
    String second = OcrTextJoiner.join(pages);
    assertEquals(first, second);
    assertEquals("one\n\ntwo\n\nthree", first);
  }

  @Test
  public void join_singlePage_returnsTrimmedPage() {
    assertEquals("A", OcrTextJoiner.join(Collections.singletonList("A")));
    assertEquals("A", OcrTextJoiner.join(Collections.singletonList("  A  ")));
  }

  @Test
  public void join_zeroPages_returnsEmptyString() {
    assertEquals("", OcrTextJoiner.join(new ArrayList<>()));
    assertEquals("", OcrTextJoiner.join(null));
  }

  @Test
  public void join_nullElement_skipped() {
    assertEquals("A\n\nB", OcrTextJoiner.join(Arrays.asList("A", null, "B")));
  }

  @Test
  public void join_allEmptyOrNull_returnsEmptyString() {
    assertEquals("", OcrTextJoiner.join(Arrays.asList("", "   ", null, "\n")));
  }
}
