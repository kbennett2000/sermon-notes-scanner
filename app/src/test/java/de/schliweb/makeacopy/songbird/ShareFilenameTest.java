/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.songbird;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.draft.SermonDraft;
import java.util.Collections;
import org.junit.Test;

/** Tests for {@link ShareFilename} (slice F6). */
public class ShareFilenameTest {

  private static SermonDraft draft(ResolvedSpan span, String date) {
    return new SermonDraft("body", span, "1 Samuel 25", "T", date, Collections.singletonList("sermon"));
  }

  @Test
  public void filename_fromSpanAndDate() {
    assertEquals(
        "sermon-import-1SA-25-2026-05-10.json",
        ShareFilename.forDraft(draft(new ResolvedSpan("1SA", 25, 1, 25, 44), "2026-05-10")));
  }

  @Test
  public void filename_sanitizesUnsafeChars() {
    // A pathological date with a slash must not produce a path separator.
    String name = ShareFilename.forDraft(draft(new ResolvedSpan("1SA", 25, 1, 25, 44), "2026/05/10"));
    assertEquals("sermon-import-1SA-25-2026_05_10.json", name);
  }
}
