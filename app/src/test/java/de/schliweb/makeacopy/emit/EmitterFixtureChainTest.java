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
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.schliweb.makeacopy.anchor.AnchorFinder;
import de.schliweb.makeacopy.anchor.PassageLabel;
import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.anchor.SpanResolution;
import de.schliweb.makeacopy.anchor.SpanResolver;
import de.schliweb.makeacopy.anchor.StructuralAnchor;
import de.schliweb.makeacopy.anchor.VerseTable;
import de.schliweb.makeacopy.draft.SermonDraft;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;
import org.junit.Test;

/**
 * Integration proof (slice F5): the whole pure pipeline — Appendix C fixture → AnchorFinder →
 * operator nudge → SpanResolver (real bundled table) → SermonDraft → ImportJsonEmitter → parse.
 */
public class EmitterFixtureChainTest {

  @Test
  public void appendixCFixture_throughThePipeline_emitsCorrectSpanAndPassageLine() throws IOException {
    String fixture = readResource("/anchor/appendix_c_fixture.txt");

    // F3: auto-detect lands on 1SA 22:1 (right book, by design).
    Optional<StructuralAnchor> found = AnchorFinder.find(fixture);
    assertTrue("finder should resolve the fixture", found.isPresent());
    assertEquals("1SA", found.get().bookUsfm());

    // F4 operator nudge: the sermon is 1 Samuel 25 (chapter-only).
    StructuralAnchor nudged = StructuralAnchor.chapterOnly("1SA", 25);
    String passageLabel = PassageLabel.format(nudged);
    assertEquals("1 Samuel 25", passageLabel);

    // F3b: resolve against the REAL bundled table.
    VerseTable table = VerseTable.fromJson(readRealTable());
    SpanResolution res = SpanResolver.resolve(nudged, table);
    assertTrue(res.isResolved());
    ResolvedSpan span = res.span();

    // F4 produces the draft; F5 emits.
    SermonDraft draft =
        new SermonDraft(
            "I. The key people in the story.\nA. Abigail",
            span,
            passageLabel,
            "A Story about David & Abigail — 'Grace & Truth'",
            "2026-05-10",
            Arrays.asList("sermon"));
    String json = ImportJsonEmitter.emit(draft);

    JsonObject a = JsonParser.parseString(json).getAsJsonObject()
        .getAsJsonArray("annotations").get(0).getAsJsonObject();
    assertEquals("1SA", a.get("book_usfm").getAsString());
    assertEquals(25, a.get("start_chapter").getAsInt());
    assertEquals(1, a.get("start_verse").getAsInt());
    assertEquals(25, a.get("end_chapter").getAsInt());
    assertEquals(44, a.get("end_verse").getAsInt()); // 1 Samuel 25 has 44 verses
    String note = a.get("note_markdown").getAsString();
    assertTrue(
        "body must carry the passage line",
        note.contains("\n1 Samuel 25 — 2026-05-10\n"));
  }

  private static String readRealTable() throws IOException {
    for (String p :
        new String[] {
          "src/main/assets/anchor/verse_counts.json",
          "app/src/main/assets/anchor/verse_counts.json"
        }) {
      File f = new File(p);
      if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
    throw new IOException("real verse_counts.json not found from module dir");
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = EmitterFixtureChainTest.class.getResourceAsStream(path)) {
      if (in == null) throw new IOException("missing test resource: " + path);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
