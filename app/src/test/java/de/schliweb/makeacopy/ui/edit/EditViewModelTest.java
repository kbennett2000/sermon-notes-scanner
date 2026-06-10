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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.anchor.SpanResolution;
import de.schliweb.makeacopy.anchor.VerseTable;
import de.schliweb.makeacopy.draft.SermonDraft;
import de.schliweb.makeacopy.ui.edit.EditViewModel.EditUiState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link EditViewModel} (slice F4): prefill, live re-resolve, blocking vs warning, the
 * "text edit doesn't move the anchor" rule, and draft assembly. The sample {@link VerseTable} (3 books)
 * is injected — no Android. LiveData runs synchronously via {@link ArchTaskExecutor}.
 */
public class EditViewModelTest {

  private VerseTable table;
  private EditViewModel vm;

  @Before
  public void setUp() throws IOException {
    ArchTaskExecutor.getInstance()
        .setDelegate(
            new TaskExecutor() {
              @Override
              public void executeOnDiskIO(Runnable runnable) {
                runnable.run();
              }

              @Override
              public void postToMainThread(Runnable runnable) {
                runnable.run();
              }

              @Override
              public boolean isMainThread() {
                return true;
              }
            });
    table = VerseTable.fromJson(readResource("/anchor/verse_counts_sample.json"));
    vm = new EditViewModel();
  }

  @After
  public void tearDown() {
    ArchTaskExecutor.getInstance().setDelegate(null);
  }

  private EditUiState s() {
    EditUiState st = vm.getState().getValue();
    assertNotNull(st);
    return st;
  }

  @Test
  public void initialize_prefillsTextAndAnchorFromFinder() {
    vm.initialize("The army gathered. (1Sam. 22:1)", table, "2026-06-10", "sermon");
    EditUiState st = s();
    assertEquals("The army gathered. (1Sam. 22:1)", st.editedText());
    assertEquals("1SA", st.bookUsfm());
    assertEquals("22", st.chapterText());
    assertEquals("1", st.verseFromText());
    assertEquals("", st.verseToText());
    assertEquals("1 Samuel 22:1", st.passageLabel());
    assertEquals("2026-06-10", st.dateIso());
    assertEquals(Arrays.asList("sermon"), st.tags());
    assertTrue(st.canProceed());
    assertEquals(SpanResolution.Status.RESOLVED, st.anchorStatus());
  }

  @Test
  public void initialize_prefillsConfiguredDefaultTags() {
    vm.initialize("", table, "2026-06-10", "sermon, majestic view");
    EditUiState st = s();
    assertEquals("sermon, majestic view", st.tagsText());
    assertEquals(Arrays.asList("sermon", "majestic view"), st.tags());
  }

  @Test
  public void initialize_blankDefaultTags_yieldsNoTags() {
    vm.initialize("", table, "2026-06-10", "");
    EditUiState st = s();
    assertEquals("", st.tagsText());
    assertTrue(st.tags().isEmpty());
  }

  @Test
  public void initialize_noAnchor_leavesFieldsEmptyAndBlocks() {
    vm.initialize("No scripture references here at all.", table, "2026-06-10", "sermon");
    EditUiState st = s();
    assertEquals("", st.bookUsfm());
    assertEquals("", st.chapterText());
    assertNull(st.anchorStatus());
    assertFalse(st.canProceed());
  }

  @Test
  public void initialize_isGuarded_secondCallDoesNotWipeEdits() {
    vm.initialize("(1Sam. 22:1)", table, "2026-06-10", "sermon");
    vm.setChapter("5");
    vm.initialize("Totally different text", table, "2026-06-11", "sermon");
    EditUiState st = s();
    assertEquals("5", st.chapterText());
    assertEquals("1SA", st.bookUsfm());
  }

  @Test
  public void chapterOnly_resolvesWholeChapter() {
    vm.initialize("", table, "2026-06-10", "sermon");
    vm.setBookUsfm("PSA");
    vm.setChapter("23");
    EditUiState st = s();
    assertTrue(st.canProceed());
    assertEquals("Psalms 23", st.passageLabel());
    SermonDraft d = vm.buildDraft();
    assertEquals(new ResolvedSpan("PSA", 23, 1, 23, 6), d.span());
  }

  @Test
  public void chapterOutOfRange_blocks() {
    vm.initialize("", table, "2026-06-10", "sermon");
    vm.setBookUsfm("MAT"); // sample MAT has 5 chapters
    vm.setChapter("9");
    EditUiState st = s();
    assertFalse(st.canProceed());
    assertEquals(SpanResolution.Status.CHAPTER_OUT_OF_RANGE, st.anchorStatus());
  }

  @Test
  public void incompleteAnchor_blocks() {
    vm.initialize("", table, "2026-06-10", "sermon");
    vm.setBookUsfm("PSA");
    vm.setChapter(""); // no chapter yet
    EditUiState st = s();
    assertFalse(st.canProceed());
    assertNull(st.anchorStatus());
  }

  @Test
  public void reversedVerseRange_warnsButDoesNotBlock() {
    vm.initialize("", table, "2026-06-10", "sermon");
    vm.setBookUsfm("1SA");
    vm.setChapter("25");
    vm.setVerseFrom("6");
    vm.setVerseTo("3");
    EditUiState st = s();
    assertTrue(st.canProceed());
    assertTrue(st.verseOrderWarning());
    // Range passes through unchanged (F3b best-effort).
    assertEquals(new ResolvedSpan("1SA", 25, 6, 25, 3), vm.buildDraft().span());
  }

  @Test
  public void setText_doesNotMoveTheAnchor() {
    vm.initialize("(1Sam. 22:1)", table, "2026-06-10", "sermon");
    vm.setText("Now it says John 1:1 instead");
    EditUiState st = s();
    assertEquals("1SA", st.bookUsfm());
    assertEquals("22", st.chapterText());
    assertEquals("Now it says John 1:1 instead", st.editedText());
  }

  @Test
  public void titleWarning_trueWhenBlank_falseWhenSet() {
    vm.initialize("", table, "2026-06-10", "sermon");
    assertTrue(s().titleWarning());
    vm.setTitle("A Story about David & Abigail");
    assertFalse(s().titleWarning());
  }

  @Test
  public void buildDraft_assemblesAllInputs() {
    vm.initialize("edited body text", table, "2026-06-10", "sermon");
    vm.setBookUsfm("1SA");
    vm.setChapter("25");
    vm.setVerseFrom("3");
    vm.setVerseTo("6");
    vm.setTitle("  David & Abigail  ");
    vm.setDate("2026-05-10");
    vm.setTags("sermon, grace");
    SermonDraft d = vm.buildDraft();
    assertNotNull(d);
    assertEquals("edited body text", d.editedText());
    assertEquals(new ResolvedSpan("1SA", 25, 3, 25, 6), d.span());
    assertEquals("1 Samuel 25:3-6", d.passageLabel());
    assertEquals("David & Abigail", d.title());
    assertEquals("2026-05-10", d.dateIso());
    assertEquals(Arrays.asList("sermon", "grace"), d.tags());
  }

  @Test
  public void buildDraft_nullWhenCannotProceed() {
    vm.initialize("", table, "2026-06-10", "sermon");
    assertNull(vm.buildDraft());
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = EditViewModelTest.class.getResourceAsStream(path)) {
      if (in == null) throw new IOException("missing test resource: " + path);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
