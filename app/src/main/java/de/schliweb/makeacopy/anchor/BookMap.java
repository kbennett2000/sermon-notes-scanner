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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Book name/abbreviation → USFM map, ported verbatim from BUILD-BRIEF Appendix B (slice F3).
 *
 * <p>This is the single source of book knowledge for the fork's anchor finder: {@link AnchorFinder}
 * holds no book names of its own and recognizes books purely by calling {@link #resolve(String)}.
 * Adding an accepted form is a one-line change in {@link #BOOKS}.
 *
 * <p>Lookup is keyed by the Appendix B <em>normalized</em> form. Normalization (applied to both the
 * table's accepted forms at build time and to a scanned phrase at lookup time):
 *
 * <ol>
 *   <li>Convert a leading standalone Roman numeral directly preceding a book name: {@code I→1},
 *       {@code II→2}, {@code III→3}. (Enforced naturally — the substituted form only resolves if it
 *       is a real key, so {@code I. The} → {@code 1the} → no match.)
 *   <li>Lowercase.
 *   <li>Remove all whitespace and all {@code .} characters.
 * </ol>
 *
 * So {@code I Samuel} → {@code 1 Samuel} → {@code 1samuel}; {@code Ps.} → {@code ps}; {@code 1Sam.} →
 * {@code 1sam}. The OCR variant {@code |john} → {@code 1JN} is kept as a literal key (Tesseract reads
 * the leading "1" of "1 John" as a pipe); {@code |} is deliberately not stripped.
 *
 * <p>Disambiguation per Appendix B: a leading digit/Roman routes to the epistle ({@code 1 John} →
 * {@code 1JN}) while bare {@code john/jhn/jn} is the Gospel {@code JHN}; {@code judges…} → {@code JDG}
 * and {@code jude…} → {@code JUD} (no bare {@code jud→JDG}); {@code phil/php} → {@code PHP} and
 * {@code philem/phlm/phm} → {@code PHM}.
 *
 * <p>Pure Java — no Android dependencies.
 */
public final class BookMap {

  /** Wrapping punctuation stripped from a token's ends before normalization (NOT {@code . | : -}). */
  private static final String WRAP_CHARS = "()[]{}\"',;";

  private BookMap() {}

  /**
   * The Appendix B table, listed exactly as the brief prints it: {@code {USFM, form, form, …}}. Each
   * form is normalized at build time to produce the lookup keys. Roman-numeral prefixes are NOT listed
   * — they are produced by the normalization rule applied in {@link #resolve(String)}.
   */
  private static final String[][] BOOKS = {
    // --- Old Testament ---
    {"GEN", "genesis", "gen", "ge", "gn"},
    {"EXO", "exodus", "exod", "exo", "ex"},
    {"LEV", "leviticus", "lev", "lv"},
    {"NUM", "numbers", "num", "nu", "nm"},
    {"DEU", "deuteronomy", "deut", "deu", "dt"},
    {"JOS", "joshua", "josh", "jos", "jsh"},
    {"JDG", "judges", "judg", "jdg", "jg"},
    {"RUT", "ruth", "rut", "ru"},
    {"1SA", "1 samuel", "1 sam", "1sa", "1 sm"},
    {"2SA", "2 samuel", "2 sam", "2sa", "2 sm"},
    {"1KI", "1 kings", "1 kgs", "1 ki", "1kg"},
    {"2KI", "2 kings", "2 kgs", "2 ki"},
    {"1CH", "1 chronicles", "1 chron", "1 chr", "1 ch"},
    {"2CH", "2 chronicles", "2 chron", "2 chr", "2 ch"},
    {"EZR", "ezra", "ezr"},
    {"NEH", "nehemiah", "neh", "ne"},
    {"EST", "esther", "esth", "est"},
    {"JOB", "job", "jb"},
    {"PSA", "psalm", "psalms", "ps", "psa", "pslm"},
    {"PRO", "proverbs", "prov", "pro", "prv", "pr"},
    {"ECC", "ecclesiastes", "eccl", "ecc", "qoh"},
    {"SNG", "song of solomon", "song of songs", "song", "sos", "canticles", "cant"},
    {"ISA", "isaiah", "isa", "is"},
    {"JER", "jeremiah", "jer", "je"},
    {"LAM", "lamentations", "lam", "la"},
    {"EZK", "ezekiel", "ezek", "eze", "ezk"},
    {"DAN", "daniel", "dan", "da", "dn"},
    {"HOS", "hosea", "hos", "ho"},
    {"JOL", "joel", "joe", "jl"},
    {"AMO", "amos", "amo", "am"},
    {"OBA", "obadiah", "obad", "oba", "ob"},
    {"JON", "jonah", "jon", "jnh"},
    {"MIC", "micah", "mic", "mc"},
    {"NAM", "nahum", "nah", "na"},
    {"HAB", "habakkuk", "hab", "hb"},
    {"ZEP", "zephaniah", "zeph", "zep", "zp"},
    {"HAG", "haggai", "hag", "hg"},
    {"ZEC", "zechariah", "zech", "zec", "zc"},
    {"MAL", "malachi", "mal", "ml"},
    // --- New Testament ---
    {"MAT", "matthew", "matt", "mat", "mt"},
    {"MRK", "mark", "mrk", "mar", "mk"},
    {"LUK", "luke", "luk", "lk"},
    {"JHN", "john", "jhn", "jn"},
    {"ACT", "acts", "act", "ac"},
    {"ROM", "romans", "rom", "ro", "rm"},
    {"1CO", "1 corinthians", "1 cor", "1co"},
    {"2CO", "2 corinthians", "2 cor", "2co"},
    {"GAL", "galatians", "gal", "ga"},
    {"EPH", "ephesians", "eph", "ephes"},
    {"PHP", "philippians", "phil", "php", "pp"},
    {"COL", "colossians", "col"},
    {"1TH", "1 thessalonians", "1 thess", "1 th", "1thes"},
    {"2TH", "2 thessalonians", "2 thess", "2 th", "2thes"},
    {"1TI", "1 timothy", "1 tim", "1 ti"},
    {"2TI", "2 timothy", "2 tim", "2 ti"},
    {"TIT", "titus", "tit"},
    {"PHM", "philemon", "philem", "phlm", "phm"},
    {"HEB", "hebrews", "heb"},
    {"JAS", "james", "jas", "jam", "jm"},
    {"1PE", "1 peter", "1 pet", "1pe"},
    {"2PE", "2 peter", "2 pet", "2pe"},
    {"1JN", "1 john", "1 jn", "1jo", "1jn", "|john"},
    {"2JN", "2 john", "2 jn", "2jo"},
    {"3JN", "3 john", "3 jn", "3jo"},
    {"JUD", "jude", "jud", "jd"},
    {"REV", "revelation", "rev", "re", "apoc"},
  };

  /** Normalized form → USFM. Insertion-ordered for stable iteration in tests. */
  private static final Map<String, String> MAP = buildMap();

  private static Map<String, String> buildMap() {
    Map<String, String> m = new LinkedHashMap<>();
    for (String[] row : BOOKS) {
      String usfm = row[0];
      for (int i = 1; i < row.length; i++) {
        String key = normalize(row[i]);
        String prev = m.put(key, usfm);
        if (prev != null && !prev.equals(usfm)) {
          // Two different books claim the same normalized form — a porting error.
          throw new IllegalStateException(
              "BookMap collision on key \"" + key + "\": " + prev + " vs " + usfm);
        }
      }
    }
    return Collections.unmodifiableMap(m);
  }

  /**
   * Normalizes a single token/form: lowercase, then remove all whitespace and {@code .} characters.
   * Does not strip wrapping punctuation or apply the Roman-numeral rule (see {@link #resolve}).
   */
  public static String normalize(String s) {
    if (s == null) return "";
    String lower = s.toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(lower.length());
    for (int i = 0; i < lower.length(); i++) {
      char c = lower.charAt(i);
      if (c == '.' || Character.isWhitespace(c)) continue;
      sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Resolves a candidate book phrase (one or more whitespace-separated words, e.g. {@code "I Samuel"},
   * {@code "(1Sam."}, {@code "Ps."}, {@code "|john"}) to a USFM code, applying the full Appendix B
   * normalization including the leading-Roman-numeral rule. Returns empty when the phrase is not a
   * known book.
   */
  public static Optional<String> resolve(String phrase) {
    if (phrase == null) return Optional.empty();
    String trimmed = phrase.trim();
    if (trimmed.isEmpty()) return Optional.empty();
    String[] words = trimmed.split("\\s+");
    StringBuilder key = new StringBuilder();
    for (int i = 0; i < words.length; i++) {
      String w = stripWrap(words[i]);
      if (i == 0) {
        Integer roman = leadingRoman(w);
        if (roman != null) {
          w = String.valueOf(roman);
        }
      }
      key.append(normalize(w));
    }
    return Optional.ofNullable(MAP.get(key.toString()));
  }

  /** Strips wrapping punctuation ({@link #WRAP_CHARS}) from both ends of a word. */
  private static String stripWrap(String w) {
    int start = 0;
    int end = w.length();
    while (start < end && WRAP_CHARS.indexOf(w.charAt(start)) >= 0) start++;
    while (end > start && WRAP_CHARS.indexOf(w.charAt(end - 1)) >= 0) end--;
    return w.substring(start, end);
  }

  /**
   * Returns 1/2/3 if the word is a standalone Roman numeral I/II/III (case-insensitive, ignoring a
   * trailing list-marker period), else null.
   */
  private static Integer leadingRoman(String w) {
    String t = w.toLowerCase(Locale.ROOT).replace(".", "");
    switch (t) {
      case "i":
        return 1;
      case "ii":
        return 2;
      case "iii":
        return 3;
      default:
        return null;
    }
  }

  /** Test/inspection accessor: the immutable normalized-form → USFM map. */
  public static Map<String, String> asMap() {
    return MAP;
  }
}
