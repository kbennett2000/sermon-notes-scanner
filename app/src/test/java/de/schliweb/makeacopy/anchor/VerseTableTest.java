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
import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.BeforeClass;
import org.junit.Test;

/** Tests for {@link VerseTable} parsing + lookup against the hand-made sample table. */
public class VerseTableTest {

  private static VerseTable table;

  @BeforeClass
  public static void load() throws IOException {
    table = VerseTable.fromJson(readResource("/anchor/verse_counts_sample.json"));
  }

  @Test
  public void meta_isParsed() {
    VerseTable.Meta m = table.meta();
    assertEquals("SAMPLE", m.sourceTranslation());
    assertEquals("sample", m.concordVersion());
    assertEquals("2026-01-01T00:00:00Z", m.generatedAt());
    assertEquals(3, m.bookCount());
  }

  @Test
  public void verseCount_returnsCountForValidChapter() {
    assertEquals(Integer.valueOf(6), table.verseCount("PSA", 23)); // last chapter in sample
    assertEquals(Integer.valueOf(28), table.verseCount("1SA", 1)); // first chapter
    assertEquals(Integer.valueOf(44), table.verseCount("1SA", 25));
  }

  @Test
  public void verseCount_nullForOutOfRangeChapter() {
    assertNull(table.verseCount("MAT", 0));
    assertNull(table.verseCount("MAT", 6)); // sample MAT has 5 chapters
    assertNull(table.verseCount("MAT", -1));
  }

  @Test
  public void verseCount_nullForUnknownBook() {
    assertNull(table.verseCount("REV", 1));
  }

  @Test
  public void chapterCount_reflectsArrayLength() {
    assertEquals(23, table.chapterCount("PSA"));
    assertEquals(5, table.chapterCount("MAT"));
    assertEquals(31, table.chapterCount("1SA"));
    assertEquals(0, table.chapterCount("REV"));
  }

  @Test
  public void bookCodes_areThePresentBooks() {
    assertEquals(3, table.bookCodes().size());
    assert table.bookCodes().contains("PSA");
    assert table.bookCodes().contains("MAT");
    assert table.bookCodes().contains("1SA");
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = VerseTableTest.class.getResourceAsStream(path)) {
      if (in == null) throw new IOException("missing test resource: " + path);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
