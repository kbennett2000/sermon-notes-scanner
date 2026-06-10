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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.draft.SermonDraft;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/** Tests for {@link ImportJsonEmitter} (slice F5) — golden byte-equality, validity, span, escaping. */
public class ImportJsonEmitterTest {

  /** The reference draft that produces the committed golden file. */
  private static SermonDraft referenceDraft() {
    return new SermonDraft(
        "I. The key people in the story.\nA. Abigail\n\n1. Her name means joy.",
        new ResolvedSpan("1SA", 25, 1, 25, 44),
        "1 Samuel 25",
        "A Story about David & Abigail — 'Grace & Truth'",
        "2026-05-10",
        Arrays.asList("sermon", "grace"));
  }

  @Test
  public void referenceDraft_matchesGoldenFileByteForByte() throws IOException {
    String golden = readResource("/emit/golden_import.json");
    assertEquals(golden, ImportJsonEmitter.emit(referenceDraft()));
  }

  @Test
  public void deterministic_equalDraftsProduceIdenticalStrings() {
    assertEquals(ImportJsonEmitter.emit(referenceDraft()), ImportJsonEmitter.emit(referenceDraft()));
  }

  @Test
  public void invariants_andNullVsAbsent() {
    JsonObject root = JsonParser.parseString(ImportJsonEmitter.emit(referenceDraft())).getAsJsonObject();
    assertEquals(1, root.get("version").getAsInt());
    // exported_at must be PRESENT and null, not absent.
    assertTrue(root.has("exported_at"));
    assertTrue(root.get("exported_at").isJsonNull());
    assertTrue(root.has("sermon_notes"));
    assertEquals(0, root.getAsJsonArray("sermon_notes").size());

    JsonArray anns = root.getAsJsonArray("annotations");
    assertEquals(1, anns.size());
    JsonObject a = anns.get(0).getAsJsonObject();
    assertEquals("1SA", a.get("book_usfm").getAsString());
    assertEquals(25, a.get("start_chapter").getAsInt());
    assertEquals(1, a.get("start_verse").getAsInt());
    assertEquals(25, a.get("end_chapter").getAsInt());
    assertEquals(44, a.get("end_verse").getAsInt());
    assertTrue(a.has("color"));
    assertTrue(a.get("color").isJsonNull());
    assertEquals("all", a.get("scope_type").getAsString());
    assertEquals(0, a.getAsJsonArray("scope_translations").size());
    JsonArray tags = a.getAsJsonArray("tags");
    assertEquals(2, tags.size());
    assertEquals("sermon", tags.get(0).getAsString());
    assertEquals("grace", tags.get(1).getAsString());
  }

  @Test
  public void chapterOnlyResolvedSpan_passesThrough() {
    SermonDraft d =
        new SermonDraft(
            "x", new ResolvedSpan("PSA", 23, 1, 23, 6), "Psalms 23", "T", "2026-05-10",
            Collections.singletonList("sermon"));
    JsonObject a = annotation(d);
    assertEquals(1, a.get("start_verse").getAsInt());
    assertEquals(6, a.get("end_verse").getAsInt());
  }

  @Test
  public void reversedVerseRange_normalizedAtTheWire() {
    SermonDraft d =
        new SermonDraft(
            "x", new ResolvedSpan("1SA", 25, 6, 25, 3), "1 Samuel 25:6-3", "T", "2026-05-10",
            Collections.singletonList("sermon"));
    JsonObject a = annotation(d);
    assertEquals(3, a.get("start_verse").getAsInt());
    assertEquals(6, a.get("end_verse").getAsInt());
    assertEquals(25, a.get("start_chapter").getAsInt());
    assertEquals(25, a.get("end_chapter").getAsInt());
  }

  @Test
  public void singleVerse_startEqualsEnd() {
    SermonDraft d =
        new SermonDraft(
            "x", new ResolvedSpan("MAT", 4, 11, 4, 11), "Matthew 4:11", "T", "2026-05-10",
            Collections.singletonList("sermon"));
    JsonObject a = annotation(d);
    assertEquals(11, a.get("start_verse").getAsInt());
    assertEquals(11, a.get("end_verse").getAsInt());
  }

  @Test
  public void escaping_quotesAndBackslashesRoundTrip() {
    SermonDraft d =
        new SermonDraft(
            "path C:\\temp and \"quote\"",
            new ResolvedSpan("1SA", 25, 1, 25, 44),
            "1 Samuel 25",
            "He said \"hi\" \\ end",
            "2026-05-10",
            Collections.singletonList("sermon"));
    String json = ImportJsonEmitter.emit(d);
    // Parses cleanly and the unescaped value contains the literal characters.
    JsonObject a = JsonParser.parseString(json).getAsJsonObject()
        .getAsJsonArray("annotations").get(0).getAsJsonObject();
    String note = a.get("note_markdown").getAsString();
    assertTrue(note.contains("He said \"hi\" \\ end"));
    assertTrue(note.contains("path C:\\temp and \"quote\""));
  }

  @Test
  public void tags_trimmedDedupedEmptiesDropped_inDraftOrder() {
    SermonDraft d =
        new SermonDraft(
            "x", new ResolvedSpan("1SA", 25, 1, 25, 44), "1 Samuel 25", "T", "2026-05-10",
            Arrays.asList(" sermon ", "grace", "sermon", "  ", "Grace"));
    JsonArray tags = annotation(d).getAsJsonArray("tags");
    // "sermon" (trimmed), "grace", then "Grace" (case-sensitive distinct); duplicate "sermon" + blank dropped.
    assertEquals(3, tags.size());
    assertEquals("sermon", tags.get(0).getAsString());
    assertEquals("grace", tags.get(1).getAsString());
    assertEquals("Grace", tags.get(2).getAsString());
  }

  @Test
  public void emptyTags_emitEmptyArray() {
    SermonDraft d =
        new SermonDraft(
            "x", new ResolvedSpan("1SA", 25, 1, 25, 44), "1 Samuel 25", "T", "2026-05-10",
            Collections.emptyList());
    assertFalse(ImportJsonEmitter.emit(d).contains("\"tags\": [\""));
    assertTrue(ImportJsonEmitter.emit(d).contains("\"tags\": []"));
  }

  private static JsonObject annotation(SermonDraft d) {
    return JsonParser.parseString(ImportJsonEmitter.emit(d)).getAsJsonObject()
        .getAsJsonArray("annotations").get(0).getAsJsonObject();
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = ImportJsonEmitterTest.class.getResourceAsStream(path)) {
      if (in == null) throw new IOException("missing test resource: " + path);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
