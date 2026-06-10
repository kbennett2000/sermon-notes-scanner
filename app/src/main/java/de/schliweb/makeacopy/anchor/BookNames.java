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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * USFM code → canonical English display name for all 66 books (slice F4), the human-facing counterpart
 * to {@link BookMap}'s reverse lookup. Used by the edit screen's book picker and by {@link PassageLabel}.
 *
 * <p>Insertion order is canonical (Genesis … Revelation), so {@link #orderedCodes()} drives the picker
 * directly. Display names are app data (not localized UI strings), kept in code so the set stays in
 * lock-step with {@code BookMap} — enforced by {@code BookNamesTest}.
 *
 * <p>Pure Java — no Android dependencies.
 */
public final class BookNames {

  private static final Map<String, String> NAMES = build();

  private BookNames() {}

  private static Map<String, String> build() {
    Map<String, String> m = new LinkedHashMap<>();
    // Old Testament (39)
    m.put("GEN", "Genesis");
    m.put("EXO", "Exodus");
    m.put("LEV", "Leviticus");
    m.put("NUM", "Numbers");
    m.put("DEU", "Deuteronomy");
    m.put("JOS", "Joshua");
    m.put("JDG", "Judges");
    m.put("RUT", "Ruth");
    m.put("1SA", "1 Samuel");
    m.put("2SA", "2 Samuel");
    m.put("1KI", "1 Kings");
    m.put("2KI", "2 Kings");
    m.put("1CH", "1 Chronicles");
    m.put("2CH", "2 Chronicles");
    m.put("EZR", "Ezra");
    m.put("NEH", "Nehemiah");
    m.put("EST", "Esther");
    m.put("JOB", "Job");
    m.put("PSA", "Psalms");
    m.put("PRO", "Proverbs");
    m.put("ECC", "Ecclesiastes");
    m.put("SNG", "Song of Solomon");
    m.put("ISA", "Isaiah");
    m.put("JER", "Jeremiah");
    m.put("LAM", "Lamentations");
    m.put("EZK", "Ezekiel");
    m.put("DAN", "Daniel");
    m.put("HOS", "Hosea");
    m.put("JOL", "Joel");
    m.put("AMO", "Amos");
    m.put("OBA", "Obadiah");
    m.put("JON", "Jonah");
    m.put("MIC", "Micah");
    m.put("NAM", "Nahum");
    m.put("HAB", "Habakkuk");
    m.put("ZEP", "Zephaniah");
    m.put("HAG", "Haggai");
    m.put("ZEC", "Zechariah");
    m.put("MAL", "Malachi");
    // New Testament (27)
    m.put("MAT", "Matthew");
    m.put("MRK", "Mark");
    m.put("LUK", "Luke");
    m.put("JHN", "John");
    m.put("ACT", "Acts");
    m.put("ROM", "Romans");
    m.put("1CO", "1 Corinthians");
    m.put("2CO", "2 Corinthians");
    m.put("GAL", "Galatians");
    m.put("EPH", "Ephesians");
    m.put("PHP", "Philippians");
    m.put("COL", "Colossians");
    m.put("1TH", "1 Thessalonians");
    m.put("2TH", "2 Thessalonians");
    m.put("1TI", "1 Timothy");
    m.put("2TI", "2 Timothy");
    m.put("TIT", "Titus");
    m.put("PHM", "Philemon");
    m.put("HEB", "Hebrews");
    m.put("JAS", "James");
    m.put("1PE", "1 Peter");
    m.put("2PE", "2 Peter");
    m.put("1JN", "1 John");
    m.put("2JN", "2 John");
    m.put("3JN", "3 John");
    m.put("JUD", "Jude");
    m.put("REV", "Revelation");
    return Collections.unmodifiableMap(m);
  }

  /** The display name for a USFM code, or {@code null} if unknown. */
  public static String displayName(String usfm) {
    return NAMES.get(usfm);
  }

  /** The 66 USFM codes in canonical order (Genesis … Revelation) — drives the book picker. */
  public static List<String> orderedCodes() {
    return Collections.unmodifiableList(new java.util.ArrayList<>(NAMES.keySet()));
  }

  /** The display names in canonical order, parallel to {@link #orderedCodes()}. */
  public static List<String> orderedDisplayNames() {
    return Collections.unmodifiableList(new java.util.ArrayList<>(NAMES.values()));
  }
}
