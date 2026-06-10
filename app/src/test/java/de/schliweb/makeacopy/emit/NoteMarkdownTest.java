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

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.draft.SermonDraft;
import java.util.Arrays;
import org.junit.Test;

/** Tests for {@link NoteMarkdown} body assembly (slice F5, D1 minimal body). */
public class NoteMarkdownTest {

  private static SermonDraft draft(String title, String passage, String date, String text) {
    return new SermonDraft(
        text, new ResolvedSpan("1SA", 25, 1, 25, 44), passage, title, date, Arrays.asList("sermon"));
  }

  @Test
  public void referenceBody_matchesAppendixAShape() {
    SermonDraft d =
        draft(
            "A Story about David & Abigail — 'Grace & Truth'",
            "1 Samuel 25",
            "2026-05-10",
            "I. The key people in the story.\nA. Abigail");
    assertEquals(
        "# A Story about David & Abigail — 'Grace & Truth'\n\n"
            + "1 Samuel 25 — 2026-05-10\n\n"
            + "- I. The key people in the story.\n"
            + "- A. Abigail\n",
        NoteMarkdown.build(d));
  }

  @Test
  public void emptyTitle_omitsHeadingEntirely() {
    SermonDraft d = draft("", "Psalms 23", "2026-05-10", "line one");
    assertEquals("Psalms 23 — 2026-05-10\n\n- line one\n", NoteMarkdown.build(d));
  }

  @Test
  public void whitespaceOnlyTitle_omitsHeading() {
    SermonDraft d = draft("   ", "Psalms 23", "2026-05-10", "line one");
    assertEquals("Psalms 23 — 2026-05-10\n\n- line one\n", NoteMarkdown.build(d));
  }

  @Test
  public void whitespaceOnlyLines_dropped_andSeamsCollapse() {
    SermonDraft d = draft("T", "Psalms 23", "2026-05-10", "a\n\n   \nb\n\t\nc");
    assertEquals(
        "# T\n\nPsalms 23 — 2026-05-10\n\n- a\n- b\n- c\n", NoteMarkdown.build(d));
  }

  @Test
  public void interiorSpacing_preserved_edgesTrimmed() {
    SermonDraft d = draft("T", "Psalms 23", "2026-05-10", "  a    b  ");
    assertEquals("# T\n\nPsalms 23 — 2026-05-10\n\n- a    b\n", NoteMarkdown.build(d));
  }

  @Test
  public void emphasisCharacters_passThroughUnmodified() {
    SermonDraft d = draft("T", "Psalms 23", "2026-05-10", "*foo*\n_bar_\n**baz**");
    assertEquals(
        "# T\n\nPsalms 23 — 2026-05-10\n\n- *foo*\n- _bar_\n- **baz**\n", NoteMarkdown.build(d));
  }

  @Test
  public void bulletPrefix_appliedUniformly() {
    SermonDraft d = draft("T", "Psalms 23", "2026-05-10", "- x\n-");
    assertEquals("# T\n\nPsalms 23 — 2026-05-10\n\n- - x\n- -\n", NoteMarkdown.build(d));
  }

  @Test
  public void noBodyLines_endsAfterPassageLine() {
    SermonDraft d = draft("T", "Psalms 23", "2026-05-10", "   \n\n");
    assertEquals("# T\n\nPsalms 23 — 2026-05-10\n", NoteMarkdown.build(d));
  }

  @Test
  public void noTitleNoBody_justPassageLine() {
    SermonDraft d = draft("", "Psalms 23", "2026-05-10", "");
    assertEquals("Psalms 23 — 2026-05-10\n", NoteMarkdown.build(d));
  }

  @Test
  public void carriageReturnsTrimmed() {
    SermonDraft d = draft("T", "Psalms 23", "2026-05-10", "a\r\nb\r");
    assertEquals("# T\n\nPsalms 23 — 2026-05-10\n\n- a\n- b\n", NoteMarkdown.build(d));
  }
}
