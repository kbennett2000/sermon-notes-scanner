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

import java.util.ArrayList;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Pure join logic for the fork's combined OCR artifact (slice F2).
 *
 * <p>Given the per-page OCR texts in hub order, produces the single combined string that the
 * downstream slices consume (F3 anchor scan, F4 edit screen, F5 emitter). The combined string is
 * THE artifact: page provenance is irrelevant after the join.
 *
 * <p>Contract (locked — see CLAUDE.md "Combined OCR text contract"):
 *
 * <ul>
 *   <li>Input order is preserved (callers pass pages in hub order).
 *   <li>Each page's text is trimmed of leading/trailing whitespace; internal whitespace is left
 *       untouched.
 *   <li>Pages that are {@code null} or empty/whitespace-only after trimming contribute nothing — no
 *       stray separators.
 *   <li>Surviving pages are joined with exactly {@code "\n\n"} (one blank line). The separator must
 *       read as plain whitespace to F3's scanner — no marker tokens, no headers.
 *   <li>Deterministic: identical input lists produce byte-identical output.
 *   <li>Zero (or all-empty) pages → {@code ""}.
 * </ul>
 *
 * <p>This class has no Android dependencies and is unit-tested in isolation.
 */
@UtilityClass
public class OcrTextJoiner {

  /** Separator placed between surviving pages: exactly one blank line. */
  private static final String SEPARATOR = "\n\n";

  /**
   * Joins per-page OCR texts into the combined artifact.
   *
   * @param pageTexts per-page OCR texts in hub order; may be null, and individual elements may be
   *     null or blank
   * @return the combined string per the contract above; never null
   */
  public static String join(List<String> pageTexts) {
    if (pageTexts == null || pageTexts.isEmpty()) {
      return "";
    }
    List<String> kept = new ArrayList<>(pageTexts.size());
    for (String pageText : pageTexts) {
      if (pageText == null) {
        continue;
      }
      String trimmed = pageText.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      kept.add(trimmed);
    }
    return String.join(SEPARATOR, kept);
  }
}
