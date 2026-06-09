/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export.session;

import android.util.Log;
import de.schliweb.makeacopy.utils.ocr.OcrTextJoiner;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives the fork's combined OCR string (slice F2) from the page hub's session pages.
 *
 * <p>This is the thin impure glue between the durable on-disk per-page OCR text and the pure {@link
 * OcrTextJoiner}. It iterates the pages in their current list order — i.e. hub/filmstrip order,
 * including any operator reorder — reads each page's plain OCR text from disk, and hands the ordered
 * list to the joiner.
 *
 * <p>Per-page text comes from the {@code text.txt} that both write sites ({@code ScanPersister} and
 * the background OCR reprocess job) co-write next to {@code words.json} in {@code scans/{id}/}. That
 * file holds the validated plain text ({@code OCRPostProcessor.wordsToText(...)} output), so this
 * reader never parses {@code words.json} and never touches frozen-core OCR code. Resolution is
 * best-effort: a page with no readable text contributes nothing.
 */
public final class CombinedOcrTextProvider {

  private static final String TAG = "CombinedOcrTextProvider";

  private CombinedOcrTextProvider() {}

  /**
   * Builds the combined OCR string from the given pages, in list order.
   *
   * @param pages the hub's pages in display order (e.g. {@code ExportSessionViewModel.getPages()
   *     .getValue()}); may be null or empty
   * @return the combined string per the {@link OcrTextJoiner} contract; never null
   */
  public static String fromPages(List<CompletedScan> pages) {
    if (pages == null || pages.isEmpty()) {
      return "";
    }
    List<String> pageTexts = new ArrayList<>(pages.size());
    for (CompletedScan page : pages) {
      pageTexts.add(page == null ? "" : readPageText(page));
    }
    return OcrTextJoiner.join(pageTexts);
  }

  /**
   * Reads a single page's plain OCR text, best-effort. Prefers the canonical {@code text.txt} in the
   * page's scan directory; falls back to the {@code ocrTextPath} file itself when it is a plain
   * (non-{@code words_json}) payload. Returns {@code ""} when nothing is readable.
   */
  private static String readPageText(CompletedScan page) {
    String ocrPath = page.ocrTextPath();
    if (ocrPath == null) {
      return "";
    }
    File ocrFile = new File(ocrPath);
    File dir = ocrFile.getParentFile();
    if (dir != null) {
      File txt = new File(dir, "text.txt");
      if (txt.isFile()) {
        return readUtf8(txt);
      }
    }
    // Fallback: the path itself is a readable plain payload (e.g. ocrFormat "plain"/null).
    if (ocrFile.isFile() && !"words_json".equals(page.ocrFormat())) {
      return readUtf8(ocrFile);
    }
    return "";
  }

  /** Reads a file as UTF-8, returning {@code ""} on any error. */
  private static String readUtf8(File file) {
    try (FileInputStream fis = new FileInputStream(file)) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = fis.read(buf)) != -1) {
        bos.write(buf, 0, n);
      }
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    } catch (Throwable t) {
      Log.w(TAG, "Failed to read OCR text: " + file.getAbsolutePath(), t);
      return "";
    }
  }
}
