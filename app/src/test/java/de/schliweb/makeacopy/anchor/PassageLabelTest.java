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

import org.junit.Test;

/** Tests for {@link PassageLabel} rendering across chapter-only / single-verse / range forms. */
public class PassageLabelTest {

  @Test
  public void chapterOnly() {
    assertEquals("1 Samuel 25", PassageLabel.format(StructuralAnchor.chapterOnly("1SA", 25)));
    assertEquals("Psalms 23", PassageLabel.format(StructuralAnchor.chapterOnly("PSA", 23)));
  }

  @Test
  public void singleVerse() {
    assertEquals("Matthew 4:11", PassageLabel.format(StructuralAnchor.verse("MAT", 4, 11)));
  }

  @Test
  public void range() {
    assertEquals("1 Samuel 25:3-6", PassageLabel.format(StructuralAnchor.range("1SA", 25, 3, 6)));
  }

  @Test
  public void unknownUsfm_fallsBackToCode() {
    assertEquals("XXX 1", PassageLabel.format(new StructuralAnchor("XXX", 1, null, null)));
  }

  @Test
  public void nullAnchor_returnsEmpty() {
    assertEquals("", PassageLabel.format(null));
  }
}
