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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.Test;

/**
 * Behavior tests for {@link AnchorFinder} against BUILD-BRIEF §6 and the Appendix C fixture (real
 * PaddleOCR output, committed verbatim as a test resource). All offline and deterministic.
 */
public class AnchorFinderTest {

  private static StructuralAnchor found(String text) {
    Optional<StructuralAnchor> a = AnchorFinder.find(text);
    return a.orElse(null);
  }

  // ---- Primary fixture assertion ----

  /**
   * The Appendix C sermon is on 1 Samuel 25, but the finder is BY DESIGN best-effort: the scrambled
   * title splits "I Samuel" from "25", so the first structurally-resolvable reference is the
   * cross-reference {@code (1Sam. 22:1)}. Right book, chapter 22 — the operator nudges 22→25 on the
   * F4 edit screen. Do NOT "fix" the finder to be cleverer than the spec: landing on 1SA 22:1 here is
   * the correct, intended outcome (and proves the Appendix B map is what keeps it off John 1:14-17).
   */
  @Test
  public void appendixCFixture_landsOn1SA22colon1_byDesign() throws IOException {
    String fixture = readFixture();
    assertEquals(StructuralAnchor.verse("1SA", 22, 1), found(fixture));
  }

  // ---- Targeted Appendix C cases ----

  @Test
  public void romanNumeral_withVerseRange() {
    assertEquals(StructuralAnchor.range("1SA", 25, 3, 6), found("I Samuel 25:3-6"));
  }

  @Test
  public void romanNumeral_secondVerseRange() {
    assertEquals(StructuralAnchor.range("1SA", 24, 1, 2), found("I Samuel 24:1-2"));
  }

  @Test
  public void abbreviationWithPeriod_insideParens() {
    assertEquals(StructuralAnchor.verse("1SA", 22, 1), found("(1Sam. 22:1)"));
  }

  @Test
  public void abbreviationWithPeriod_singleVerse() {
    assertEquals(StructuralAnchor.verse("MAT", 4, 11), found("Mt. 4:11"));
  }

  @Test
  public void chapterOnly_staysChapterOnly() {
    // D2 end-verse fill is F3b/F5 — F3 keeps a whole-chapter reference chapter-only.
    assertEquals(StructuralAnchor.chapterOnly("PSA", 23), found("Ps. 23"));
  }

  @Test
  public void bareNumberRange_withNoBook_isNotResolvable() {
    assertFalse(AnchorFinder.find("1. David's request (1-9)").isPresent());
    assertFalse(AnchorFinder.find("A. Abigail became the messenger... (21-31)").isPresent());
    assertFalse(AnchorFinder.find("(1-9)").isPresent());
  }

  @Test
  public void gospelJohn_resolvesWhenNothingEarlier() {
    assertEquals(StructuralAnchor.range("JHN", 1, 14, 17), found("John 1:14-17; Ephesians 2:22-29"));
  }

  // ---- Disambiguation through find() ----

  @Test
  public void disambiguation_firstJohnVsGospelJohn() {
    assertEquals(StructuralAnchor.verse("1JN", 2, 1), found("1 John 2:1"));
    assertEquals(StructuralAnchor.verse("JHN", 2, 1), found("John 2:1"));
  }

  @Test
  public void disambiguation_pipeJohnVariant() {
    assertEquals(StructuralAnchor.verse("1JN", 2, 1), found("|john 2:1"));
  }

  @Test
  public void disambiguation_judgesVsJude() {
    assertEquals(StructuralAnchor.range("JDG", 5, 1, 31), found("Judges 5:1-31"));
    assertEquals(StructuralAnchor.chapterOnly("JUD", 1), found("Jude 1"));
  }

  @Test
  public void disambiguation_philippiansVsPhilemon() {
    assertEquals(StructuralAnchor.verse("PHP", 4, 13), found("Phil 4:13"));
    assertEquals(StructuralAnchor.verse("PHM", 1, 6), found("Philem 1:6"));
  }

  // ---- Negative space ----

  @Test
  public void noReferences_returnsEmpty() {
    assertFalse(AnchorFinder.find("The key people in the story. Abigail and Nabal.").isPresent());
    assertFalse(AnchorFinder.find("").isPresent());
    assertFalse(AnchorFinder.find(null).isPresent());
  }

  @Test
  public void bookTokenWithNoNumber_isSkipped() {
    assertFalse(AnchorFinder.find("Read John today and Genesis tomorrow.").isPresent());
    assertFalse(AnchorFinder.find("Psalm of praise").isPresent());
  }

  @Test
  public void multiWordBook_songOfSolomon() {
    assertEquals(StructuralAnchor.verse("SNG", 2, 1), found("Song of Solomon 2:1"));
  }

  // ---- Ordering: earlier resolvable reference beats a later cleaner one ----

  @Test
  public void ordering_earlierResolvableWins_overLaterCleanerReference() {
    // (1Sam. 22:1) appears first; the cleaner "John 1:14-17" appears later and must LOSE.
    String text = "...the army around him. (1Sam. 22:1)\nlater line\nJohn 1:14-17";
    assertEquals(StructuralAnchor.verse("1SA", 22, 1), found(text));
  }

  @Test
  public void ordering_titleSplitBookFromChapter_doesNotMatchThere() {
    // Book token and chapter separated by other words → not captured at the title.
    String text = "A Story 'Grace - I Samuel & Truth'. 25\nlater: Mt. 4:11";
    assertEquals(StructuralAnchor.verse("MAT", 4, 11), found(text));
  }

  // ---- Fixture loading ----

  private static String readFixture() throws IOException {
    try (InputStream in =
        AnchorFinderTest.class.getResourceAsStream("/anchor/appendix_c_fixture.txt")) {
      assertNotNull("Appendix C fixture resource must be on the test classpath", in);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
      assertTrue("fixture should contain the cross-reference", s.contains("(1Sam. 22:1)"));
      return s;
    }
  }
}
