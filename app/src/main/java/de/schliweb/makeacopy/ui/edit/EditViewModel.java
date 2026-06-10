/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.edit;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.makeacopy.anchor.AnchorFinder;
import de.schliweb.makeacopy.anchor.PassageLabel;
import de.schliweb.makeacopy.anchor.SpanResolution;
import de.schliweb.makeacopy.anchor.SpanResolver;
import de.schliweb.makeacopy.anchor.StructuralAnchor;
import de.schliweb.makeacopy.anchor.VerseTable;
import de.schliweb.makeacopy.draft.SermonDraft;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Edit-screen state (slice F4). Holds the operator's edits and live anchor resolution, and assembles the
 * {@link SermonDraft} on proceed. Fragment-scoped: a fresh visit re-derives everything; the guarded
 * {@link #initialize} survives rotation without wiping edits.
 *
 * <p>Context-free (the {@link VerseTable} is injected), so it is plain-JVM unit-testable. {@code
 * AnchorFinder}/{@code SpanResolver} are pure static calls.
 */
public class EditViewModel extends ViewModel {

  /** Immutable snapshot of the edit screen. {@code anchorStatus} is null while book/chapter incomplete. */
  public record EditUiState(
      String editedText,
      String bookUsfm,
      String chapterText,
      String verseFromText,
      String verseToText,
      String title,
      String dateIso,
      String tagsText,
      List<String> tags,
      String passageLabel,
      SpanResolution.Status anchorStatus,
      boolean canProceed,
      boolean verseOrderWarning,
      boolean titleWarning) {}

  private final MutableLiveData<EditUiState> state = new MutableLiveData<>();

  // Mutable working fields; an immutable snapshot is published after every change.
  private boolean initialized;
  private VerseTable table;
  private String editedText = "";
  private String bookUsfm = null;
  private String chapterText = "";
  private String verseFromText = "";
  private String verseToText = "";
  private String title = "";
  private String dateIso = "";
  private String tagsText = "sermon";
  private de.schliweb.makeacopy.anchor.ResolvedSpan lastSpan;
  private String lastPassageLabel = "";

  public LiveData<EditUiState> getState() {
    return state;
  }

  /**
   * One-time prefill. No-op if already initialized (so a configuration change does not wipe edits).
   * Runs {@link AnchorFinder#find} ONCE on entry; the operator owns the anchor after that.
   *
   * @param combinedText the F2 combined OCR string
   * @param verseTable the bundled verse-count table (injected; loaded by the fragment)
   * @param todayIso today's date as {@code yyyy-MM-dd}
   * @param defaultTags the operator's configured default tags (comma-separated) to prefill the tag
   *     box; passed verbatim (the {@code "sermon"} fallback lives in the prefs helper)
   */
  public void initialize(
      String combinedText, VerseTable verseTable, String todayIso, String defaultTags) {
    if (initialized) return;
    initialized = true;
    this.table = verseTable;
    this.editedText = combinedText == null ? "" : combinedText;
    this.title = "";
    this.dateIso = todayIso == null ? "" : todayIso;
    this.tagsText = defaultTags == null ? "" : defaultTags;

    Optional<StructuralAnchor> found = AnchorFinder.find(this.editedText);
    if (found.isPresent()) {
      StructuralAnchor a = found.get();
      bookUsfm = a.bookUsfm();
      chapterText = String.valueOf(a.chapter());
      verseFromText = a.startVerse() != null ? String.valueOf(a.startVerse()) : "";
      verseToText = a.endVerse() != null ? String.valueOf(a.endVerse()) : "";
    } else {
      bookUsfm = null;
      chapterText = "";
      verseFromText = "";
      verseToText = "";
    }
    publish();
  }

  // --- field setters (anchor fields trigger re-resolve via publish) ---

  /** Updates the edited OCR text. Deliberately does NOT re-run AnchorFinder — the operator owns the anchor. */
  public void setText(String t) {
    editedText = t == null ? "" : t;
    publish();
  }

  public void setBookUsfm(String usfm) {
    bookUsfm = usfm;
    publish();
  }

  public void setChapter(String c) {
    chapterText = c == null ? "" : c;
    publish();
  }

  public void setVerseFrom(String v) {
    verseFromText = v == null ? "" : v;
    publish();
  }

  public void setVerseTo(String v) {
    verseToText = v == null ? "" : v;
    publish();
  }

  public void setTitle(String t) {
    title = t == null ? "" : t;
    publish();
  }

  public void setDate(String iso) {
    dateIso = iso == null ? "" : iso;
    publish();
  }

  public void setTags(String csv) {
    tagsText = csv == null ? "" : csv;
    publish();
  }

  /**
   * Assembles the draft. Returns null unless the anchor currently resolves (callers gate on {@code
   * canProceed}). The body is NOT assembled here — that is F5.
   */
  public SermonDraft buildDraft() {
    EditUiState s = state.getValue();
    if (s == null || !s.canProceed() || lastSpan == null) return null;
    return new SermonDraft(
        editedText, lastSpan, lastPassageLabel, title.trim(), dateIso, parseTags(tagsText));
  }

  // --- derivation ---

  private void publish() {
    Integer chapter = parseIntOrNull(chapterText);
    Integer vFrom = parseIntOrNull(verseFromText);
    Integer vTo = parseIntOrNull(verseToText);
    boolean hasBook = bookUsfm != null && !bookUsfm.isEmpty();

    StructuralAnchor anchor = null;
    if (hasBook && chapter != null) {
      if (vFrom == null) {
        anchor = StructuralAnchor.chapterOnly(bookUsfm, chapter);
      } else if (vTo == null) {
        anchor = StructuralAnchor.verse(bookUsfm, chapter, vFrom);
      } else {
        anchor = StructuralAnchor.range(bookUsfm, chapter, vFrom, vTo);
      }
    }

    String passageLabel = anchor != null ? PassageLabel.format(anchor) : "";
    SpanResolution.Status status = null;
    boolean canProceed = false;
    lastSpan = null;
    if (anchor != null && table != null) {
      SpanResolution res = SpanResolver.resolve(anchor, table);
      status = res.status();
      if (res.isResolved()) {
        canProceed = true;
        lastSpan = res.span();
      }
    }
    lastPassageLabel = passageLabel;

    boolean verseOrderWarning = vFrom != null && vTo != null && vTo < vFrom;
    boolean titleWarning = title == null || title.trim().isEmpty();

    state.setValue(
        new EditUiState(
            editedText,
            bookUsfm == null ? "" : bookUsfm,
            chapterText,
            verseFromText,
            verseToText,
            title,
            dateIso,
            tagsText,
            parseTags(tagsText),
            passageLabel,
            status,
            canProceed,
            verseOrderWarning,
            titleWarning));
  }

  private static Integer parseIntOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    if (t.isEmpty()) return null;
    try {
      return Integer.valueOf(t);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static List<String> parseTags(String csv) {
    if (csv == null) return Collections.emptyList();
    List<String> out = new ArrayList<>();
    for (String part : csv.split(",")) {
      String t = part.trim();
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }
}
