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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the sermon's anchor in the combined OCR text (slice F3), per BUILD-BRIEF §6.
 *
 * <p>The anchor is the <strong>first structurally-resolvable</strong> Scripture reference scanning the
 * text top-to-bottom: a recognized book token (via {@link BookMap}, after normalization) immediately
 * followed by a chapter, with an optional {@code :verse} or {@code :verse-verse}. No Concord call, no
 * verse/chapter-existence checking — best-effort by design; the F4 edit screen is the safety net.
 *
 * <p>First match wins. There is no multi-reference extraction and no confidence scoring. The scanner
 * holds no book names — all book knowledge is in {@link BookMap}, so adding an accepted form there is
 * picked up here automatically.
 *
 * <p>Pure Java — no Android dependencies.
 */
public final class AnchorFinder {

  /** Longest book phrase in the map is three words ("song of solomon"). */
  private static final int MAX_BOOK_WORDS = 3;

  /** Chapter[:verse[-verse]] after wrapping punctuation is stripped. Range dash: ASCII or en/em. */
  private static final Pattern CHAPTER =
      Pattern.compile("^(\\d+)(?::(\\d+)(?:[-\\u2013\\u2014](\\d+))?)?$");

  /** Wrapping punctuation stripped from a chapter token's ends (NOT {@code : - .}). */
  private static final String WRAP_CHARS = "()[]{}\"',;";

  private AnchorFinder() {}

  /**
   * Scans the text and returns the first resolvable anchor, or empty if none.
   *
   * @param text the combined OCR text (typically from {@code CombinedOcrTextProvider}); may be null
   * @return the first {@link StructuralAnchor}, or {@link Optional#empty()}
   */
  public static Optional<StructuralAnchor> find(String text) {
    if (text == null || text.isEmpty()) return Optional.empty();
    String[] tokens = text.trim().split("\\s+");
    if (tokens.length == 0) return Optional.empty();

    for (int i = 0; i < tokens.length; i++) {
      // Longest book phrase first (3→1 words), so multi-word and prefixed names win over short forms.
      int maxLen = Math.min(MAX_BOOK_WORDS, tokens.length - i);
      for (int len = maxLen; len >= 1; len--) {
        String phrase = join(tokens, i, len);
        Optional<String> usfm = BookMap.resolve(phrase);
        if (usfm.isEmpty()) continue;
        // The chapter must be the immediately following token.
        int chapterIdx = i + len;
        if (chapterIdx >= tokens.length) continue;
        StructuralAnchor anchor = parseChapter(usfm.get(), tokens[chapterIdx]);
        if (anchor != null) return Optional.of(anchor);
        // Book matched but no chapter follows — try a shorter window at this position.
      }
    }
    return Optional.empty();
  }

  private static String join(String[] tokens, int start, int len) {
    StringBuilder sb = new StringBuilder();
    for (int k = 0; k < len; k++) {
      if (k > 0) sb.append(' ');
      sb.append(tokens[start + k]);
    }
    return sb.toString();
  }

  /** Parses a chapter token into an anchor for {@code usfm}, or null if it is not a chapter. */
  private static StructuralAnchor parseChapter(String usfm, String token) {
    Matcher m = CHAPTER.matcher(stripWrap(token));
    if (!m.matches()) return null;
    int chapter = Integer.parseInt(m.group(1));
    if (m.group(2) == null) {
      return StructuralAnchor.chapterOnly(usfm, chapter);
    }
    int startVerse = Integer.parseInt(m.group(2));
    if (m.group(3) == null) {
      return StructuralAnchor.verse(usfm, chapter, startVerse);
    }
    return StructuralAnchor.range(usfm, chapter, startVerse, Integer.parseInt(m.group(3)));
  }

  private static String stripWrap(String w) {
    int start = 0;
    int end = w.length();
    while (start < end && WRAP_CHARS.indexOf(w.charAt(start)) >= 0) start++;
    while (end > start && WRAP_CHARS.indexOf(w.charAt(end - 1)) >= 0) end--;
    return w.substring(start, end);
  }
}
