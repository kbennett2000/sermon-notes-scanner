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
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Map-fidelity tests for {@link BookMap} — the F3 porting proof. Forms are transcribed independently
 * from BUILD-BRIEF Appendix B (not copied from the production table) so a transposition typo on either
 * side is caught. Also covers the normalization rules (Roman numerals, abbreviation periods, wrapping
 * parens, the |john OCR variant) and the disambiguation rules.
 */
public class BookMapTest {

  /** Asserts every given accepted form resolves to {@code usfm}. */
  private static void assertBook(String usfm, String... forms) {
    for (String form : forms) {
      assertEquals(
          "form \"" + form + "\" should resolve to " + usfm,
          usfm,
          BookMap.resolve(form).orElse(null));
    }
  }

  // ---- Old Testament: every accepted form from Appendix B ----

  @Test
  public void oldTestament_allListedForms() {
    assertBook("GEN", "genesis", "gen", "ge", "gn");
    assertBook("EXO", "exodus", "exod", "exo", "ex");
    assertBook("LEV", "leviticus", "lev", "lv");
    assertBook("NUM", "numbers", "num", "nu", "nm");
    assertBook("DEU", "deuteronomy", "deut", "deu", "dt");
    assertBook("JOS", "joshua", "josh", "jos", "jsh");
    assertBook("JDG", "judges", "judg", "jdg", "jg");
    assertBook("RUT", "ruth", "rut", "ru");
    assertBook("1SA", "1 samuel", "1 sam", "1sa", "1 sm");
    assertBook("2SA", "2 samuel", "2 sam", "2sa", "2 sm");
    assertBook("1KI", "1 kings", "1 kgs", "1 ki", "1kg");
    assertBook("2KI", "2 kings", "2 kgs", "2 ki");
    assertBook("1CH", "1 chronicles", "1 chron", "1 chr", "1 ch");
    assertBook("2CH", "2 chronicles", "2 chron", "2 chr", "2 ch");
    assertBook("EZR", "ezra", "ezr");
    assertBook("NEH", "nehemiah", "neh", "ne");
    assertBook("EST", "esther", "esth", "est");
    assertBook("JOB", "job", "jb");
    assertBook("PSA", "psalm", "psalms", "ps", "psa", "pslm");
    assertBook("PRO", "proverbs", "prov", "pro", "prv", "pr");
    assertBook("ECC", "ecclesiastes", "eccl", "ecc", "qoh");
    assertBook("SNG", "song of solomon", "song of songs", "song", "sos", "canticles", "cant");
    assertBook("ISA", "isaiah", "isa", "is");
    assertBook("JER", "jeremiah", "jer", "je");
    assertBook("LAM", "lamentations", "lam", "la");
    assertBook("EZK", "ezekiel", "ezek", "eze", "ezk");
    assertBook("DAN", "daniel", "dan", "da", "dn");
    assertBook("HOS", "hosea", "hos", "ho");
    assertBook("JOL", "joel", "joe", "jl");
    assertBook("AMO", "amos", "amo", "am");
    assertBook("OBA", "obadiah", "obad", "oba", "ob");
    assertBook("JON", "jonah", "jon", "jnh");
    assertBook("MIC", "micah", "mic", "mc");
    assertBook("NAM", "nahum", "nah", "na");
    assertBook("HAB", "habakkuk", "hab", "hb");
    assertBook("ZEP", "zephaniah", "zeph", "zep", "zp");
    assertBook("HAG", "haggai", "hag", "hg");
    assertBook("ZEC", "zechariah", "zech", "zec", "zc");
    assertBook("MAL", "malachi", "mal", "ml");
  }

  // ---- New Testament: every accepted form from Appendix B ----

  @Test
  public void newTestament_allListedForms() {
    assertBook("MAT", "matthew", "matt", "mat", "mt");
    assertBook("MRK", "mark", "mrk", "mar", "mk");
    assertBook("LUK", "luke", "luk", "lk");
    assertBook("JHN", "john", "jhn", "jn");
    assertBook("ACT", "acts", "act", "ac");
    assertBook("ROM", "romans", "rom", "ro", "rm");
    assertBook("1CO", "1 corinthians", "1 cor", "1co");
    assertBook("2CO", "2 corinthians", "2 cor", "2co");
    assertBook("GAL", "galatians", "gal", "ga");
    assertBook("EPH", "ephesians", "eph", "ephes");
    assertBook("PHP", "philippians", "phil", "php", "pp");
    assertBook("COL", "colossians", "col");
    assertBook("1TH", "1 thessalonians", "1 thess", "1 th", "1thes");
    assertBook("2TH", "2 thessalonians", "2 thess", "2 th", "2thes");
    assertBook("1TI", "1 timothy", "1 tim", "1 ti");
    assertBook("2TI", "2 timothy", "2 tim", "2 ti");
    assertBook("TIT", "titus", "tit");
    assertBook("PHM", "philemon", "philem", "phlm", "phm");
    assertBook("HEB", "hebrews", "heb");
    assertBook("JAS", "james", "jas", "jam", "jm");
    assertBook("1PE", "1 peter", "1 pet", "1pe");
    assertBook("2PE", "2 peter", "2 pet", "2pe");
    assertBook("1JN", "1 john", "1 jn", "1jo", "1jn");
    assertBook("2JN", "2 john", "2 jn", "2jo");
    assertBook("3JN", "3 john", "3 jn", "3jo");
    assertBook("JUD", "jude", "jud", "jd");
    assertBook("REV", "revelation", "rev", "re", "apoc");
  }

  // ---- Normalization rules ----

  @Test
  public void romanNumeralPrefix_convertsToArabic_onlyBeforeBookName() {
    assertBook("1SA", "I Samuel", "i samuel");
    assertBook("2SA", "II Samuel");
    assertBook("1KI", "I Kings");
    assertBook("2KI", "II Kings");
    assertBook("1JN", "I John");
    assertBook("2JN", "II John");
    assertBook("3JN", "III John");
    // Roman numeral NOT before a book name does not resolve (list markers, stray numerals).
    assertFalse(BookMap.resolve("I").isPresent());
    assertFalse(BookMap.resolve("II").isPresent());
    assertFalse(BookMap.resolve("I. The").isPresent());
    assertFalse(BookMap.resolve("III").isPresent());
  }

  @Test
  public void abbreviationPeriods_andWrappingParens_areTolerated() {
    assertBook("1SA", "1Sam.", "(1Sam.", "1Sam.)", "(1Sam.)");
    assertBook("MAT", "Mt.", "(Mt.)");
    assertBook("PSA", "Ps.", "ps.");
    assertBook("PRO", "Prov.");
  }

  @Test
  public void pipeJohn_ocrVariant_resolvesTo1JN() {
    // Tesseract reads the leading "1" of "1 John" as a pipe; kept as a literal key.
    assertBook("1JN", "|john", "|John");
    // The pipe is deliberately not stripped, so it stays distinct from the bare Gospel.
    assertBook("JHN", "john");
  }

  // ---- Disambiguation rules (short abbreviations collide) ----

  @Test
  public void disambiguation_johnGospelVsEpistles() {
    assertBook("JHN", "John", "jhn", "jn");
    assertBook("1JN", "1 John", "1jn", "1jo");
    assertBook("2JN", "2 John");
    assertBook("3JN", "3 John");
  }

  @Test
  public void disambiguation_judgesVsJude() {
    assertBook("JDG", "judges", "judg", "jdg", "jg");
    assertBook("JUD", "jude", "jud", "jd");
  }

  @Test
  public void disambiguation_philippiansVsPhilemon() {
    assertBook("PHP", "phil", "php", "philippians");
    assertBook("PHM", "philem", "phlm", "phm", "philemon");
  }

  // ---- Structural guarantees ----

  @Test
  public void allSixtySixBooksReachable_viaFullNameAndPrimaryAbbrev() {
    // {USFM, full name, primary abbreviation}
    String[][] books = {
      {"GEN", "genesis", "gen"}, {"EXO", "exodus", "exod"}, {"LEV", "leviticus", "lev"},
      {"NUM", "numbers", "num"}, {"DEU", "deuteronomy", "deut"}, {"JOS", "joshua", "josh"},
      {"JDG", "judges", "judg"}, {"RUT", "ruth", "rut"}, {"1SA", "1 samuel", "1 sam"},
      {"2SA", "2 samuel", "2 sam"}, {"1KI", "1 kings", "1 kgs"}, {"2KI", "2 kings", "2 kgs"},
      {"1CH", "1 chronicles", "1 chron"}, {"2CH", "2 chronicles", "2 chron"}, {"EZR", "ezra", "ezr"},
      {"NEH", "nehemiah", "neh"}, {"EST", "esther", "esth"}, {"JOB", "job", "jb"},
      {"PSA", "psalms", "ps"}, {"PRO", "proverbs", "prov"}, {"ECC", "ecclesiastes", "eccl"},
      {"SNG", "song of solomon", "sos"}, {"ISA", "isaiah", "isa"}, {"JER", "jeremiah", "jer"},
      {"LAM", "lamentations", "lam"}, {"EZK", "ezekiel", "ezek"}, {"DAN", "daniel", "dan"},
      {"HOS", "hosea", "hos"}, {"JOL", "joel", "joe"}, {"AMO", "amos", "amo"},
      {"OBA", "obadiah", "obad"}, {"JON", "jonah", "jon"}, {"MIC", "micah", "mic"},
      {"NAM", "nahum", "nah"}, {"HAB", "habakkuk", "hab"}, {"ZEP", "zephaniah", "zeph"},
      {"HAG", "haggai", "hag"}, {"ZEC", "zechariah", "zech"}, {"MAL", "malachi", "mal"},
      {"MAT", "matthew", "matt"}, {"MRK", "mark", "mrk"}, {"LUK", "luke", "luk"},
      {"JHN", "john", "jhn"}, {"ACT", "acts", "act"}, {"ROM", "romans", "rom"},
      {"1CO", "1 corinthians", "1 cor"}, {"2CO", "2 corinthians", "2 cor"}, {"GAL", "galatians", "gal"},
      {"EPH", "ephesians", "eph"}, {"PHP", "philippians", "phil"}, {"COL", "colossians", "col"},
      {"1TH", "1 thessalonians", "1 thess"}, {"2TH", "2 thessalonians", "2 thess"},
      {"1TI", "1 timothy", "1 tim"}, {"2TI", "2 timothy", "2 tim"}, {"TIT", "titus", "tit"},
      {"PHM", "philemon", "philem"}, {"HEB", "hebrews", "heb"}, {"JAS", "james", "jas"},
      {"1PE", "1 peter", "1 pet"}, {"2PE", "2 peter", "2 pet"}, {"1JN", "1 john", "1 jn"},
      {"2JN", "2 john", "2 jn"}, {"3JN", "3 john", "3 jn"}, {"JUD", "jude", "jud"},
      {"REV", "revelation", "rev"},
    };
    assertEquals("expected all 66 books", 66, books.length);
    Set<String> usfms = new HashSet<>();
    for (String[] b : books) {
      assertBook(b[0], b[1], b[2]);
      usfms.add(b[0]);
    }
    assertEquals("66 distinct USFM codes", 66, usfms.size());
    // Every USFM in the production map is one of the 66.
    assertTrue(usfms.containsAll(new HashSet<>(BookMap.asMap().values())));
    assertTrue(new HashSet<>(BookMap.asMap().values()).containsAll(usfms));
  }

  @Test
  public void unknownTokens_doNotResolve() {
    assertFalse(BookMap.resolve("samuel").isPresent()); // bare "samuel" is not a listed form
    assertFalse(BookMap.resolve("saul").isPresent());
    assertFalse(BookMap.resolve("grace").isPresent());
    assertFalse(BookMap.resolve("").isPresent());
    assertFalse(BookMap.resolve(null).isPresent());
    assertFalse(BookMap.resolve("25").isPresent());
  }
}
