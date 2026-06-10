/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.draft;

import de.schliweb.makeacopy.anchor.ResolvedSpan;
import java.util.List;

/**
 * The operator-confirmed inputs the edit screen (F4) hands onward. F4 <em>collects</em> these; F5 is the
 * single deterministic birthplace of {@code note_markdown} — this object never holds an assembled body.
 *
 * <p>Carries the resolved five-field {@link ResolvedSpan} (the edit screen blocks proceeding unless the
 * anchor resolves, so F5 gets Appendix A's {@code book_usfm}/{@code start_chapter}/{@code start_verse}/
 * {@code end_chapter}/{@code end_verse} directly) plus the human {@code passageLabel} (for F5's
 * "{passage} — {date}" body line, which preserves the chapter-only form).
 *
 * <p>Pure value type — no Android dependencies.
 *
 * @param editedText the operator-edited combined OCR text
 * @param span the resolved five-field Scripture span
 * @param passageLabel the human passage label (e.g. "1 Samuel 25" or "1 Samuel 25:3-6")
 * @param title the confirmed title (may be empty — F4 warns but does not block)
 * @param dateIso the sermon date as {@code yyyy-MM-dd}
 * @param tags the tags (defaults to {@code ["sermon"]})
 */
public record SermonDraft(
    String editedText,
    ResolvedSpan span,
    String passageLabel,
    String title,
    String dateIso,
    List<String> tags) {}
