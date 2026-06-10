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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Completeness + fidelity tests for {@link BookNames}: every USFM code known to {@link BookMap} has a
 * display name and vice versa, the picker list is the 66 in canonical order, and a few names are pinned.
 */
public class BookNamesTest {

  @Test
  public void everyBookMapUsfmHasADisplayName_andViceVersa() {
    Set<String> bookMapCodes = new HashSet<>(BookMap.asMap().values());
    Set<String> nameCodes = new HashSet<>(BookNames.orderedCodes());
    assertEquals("BookNames codes must equal BookMap's 66 USFM codes", bookMapCodes, nameCodes);
    for (String usfm : bookMapCodes) {
      String name = BookNames.displayName(usfm);
      assertNotNull("missing display name for " + usfm, name);
      assertTrue("blank display name for " + usfm, !name.trim().isEmpty());
    }
  }

  @Test
  public void orderedCodes_has66Distinct() {
    List<String> codes = BookNames.orderedCodes();
    assertEquals(66, codes.size());
    assertEquals(66, new HashSet<>(codes).size());
  }

  @Test
  public void orderedLists_areParallelAndCanonical() {
    List<String> codes = BookNames.orderedCodes();
    List<String> names = BookNames.orderedDisplayNames();
    assertEquals(codes.size(), names.size());
    assertEquals("GEN", codes.get(0));
    assertEquals("Genesis", names.get(0));
    assertEquals("REV", codes.get(65));
    assertEquals("Revelation", names.get(65));
    for (int i = 0; i < codes.size(); i++) {
      assertEquals(BookNames.displayName(codes.get(i)), names.get(i));
    }
  }

  @Test
  public void spotCheckNames() {
    assertEquals("1 Samuel", BookNames.displayName("1SA"));
    assertEquals("Psalms", BookNames.displayName("PSA"));
    assertEquals("Song of Solomon", BookNames.displayName("SNG"));
    assertEquals("Philippians", BookNames.displayName("PHP"));
    assertEquals("3 John", BookNames.displayName("3JN"));
    assertEquals("Revelation", BookNames.displayName("REV"));
  }

  @Test
  public void unknownCode_returnsNull() {
    assertNull(BookNames.displayName("XXX"));
    assertNull(BookNames.displayName(null));
  }
}
