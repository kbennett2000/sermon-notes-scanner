/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.emit;

import de.schliweb.makeacopy.draft.SermonDraft;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code note_markdown} body for the songbird import document (slice F5, decision D1 = minimal
 * body), per BUILD-BRIEF Appendix A. This is the single birthplace of the body string.
 *
 * <p>Structure (the Appendix A example is literally {@code "# T\n\nP — D\n\n- a\n- b\n"}):
 *
 * <ol>
 *   <li>{@code # {title}} — omitted entirely when the title is blank (never {@code "# "}).
 *   <li>blank line.
 *   <li>{@code {passageLabel} — {dateIso}} (em-dash with surrounding spaces).
 *   <li>blank line, then each non-empty edited-text line as a {@code - } list item.
 * </ol>
 *
 * <p>Plain Markdown only. The emitter never generates emphasis ({@code *}/{@code _}/{@code **}); operator
 * text that already contains those characters is passed through untouched (it is operator content, not
 * the emitter's). Lines are trimmed at the edges (dropping blanks and stray {@code \r}); interior spacing
 * is preserved. The bullet prefix is applied uniformly, so an operator line already beginning {@code "- "}
 * becomes {@code "- - …"} — deterministic by design.
 *
 * <p>Pure Java — no Android dependencies.
 */
public final class NoteMarkdown {

  /** Passage/date separator: space, EM DASH (U+2014), space — exactly as the Appendix A example. */
  private static final String DASH = " — ";

  private NoteMarkdown() {}

  public static String build(SermonDraft draft) {
    List<String> lines = new ArrayList<>();

    String title = draft.title();
    if (title != null && !title.trim().isEmpty()) {
      lines.add("# " + title.trim());
      lines.add("");
    }

    String passage = draft.passageLabel() == null ? "" : draft.passageLabel();
    String date = draft.dateIso() == null ? "" : draft.dateIso();
    lines.add(passage + DASH + date);

    List<String> body = bodyLines(draft.editedText());
    if (!body.isEmpty()) {
      lines.add("");
      for (String line : body) {
        lines.add("- " + line);
      }
    }

    return String.join("\n", lines) + "\n";
  }

  /** Splits on {@code \n}, trims each edge, drops blank lines; interior spacing preserved. */
  private static List<String> bodyLines(String editedText) {
    List<String> out = new ArrayList<>();
    if (editedText == null) return out;
    for (String raw : editedText.split("\n", -1)) {
      String t = raw.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }
}
