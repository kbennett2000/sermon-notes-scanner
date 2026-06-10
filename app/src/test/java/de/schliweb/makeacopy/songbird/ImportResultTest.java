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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for {@link ImportResult#from} classification (slice F6). */
public class ImportResultTest {

  @Test
  public void success_withSummary() {
    ImportResult r =
        ImportResult.from(PostResult.http(200, "{\"annotations\": {\"created\": 1, \"skipped\": 2}}"));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertTrue(r.summaryPresent());
    assertEquals(1, r.created());
    assertEquals(2, r.skipped());
  }

  @Test
  public void success_idempotentReimport_skippedReported() {
    ImportResult r =
        ImportResult.from(PostResult.http(200, "{\"annotations\": {\"created\": 0, \"skipped\": 1}}"));
    assertTrue(r.isSuccess());
    assertEquals(0, r.created());
    assertEquals(1, r.skipped());
  }

  @Test
  public void success_201_withSummary() {
    ImportResult r =
        ImportResult.from(PostResult.http(201, "{\"annotations\": {\"created\": 1, \"skipped\": 0}}"));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertTrue(r.summaryPresent());
  }

  @Test
  public void success_missingSummary_stillSuccess() {
    ImportResult r = ImportResult.from(PostResult.http(200, "{\"ok\": true}"));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertFalse(r.summaryPresent());
  }

  @Test
  public void success_malformedBody_stillSuccess() {
    ImportResult r = ImportResult.from(PostResult.http(200, "not json at all"));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertFalse(r.summaryPresent());
  }

  @Test
  public void success_ignoresUnknownExtraFields() {
    ImportResult r =
        ImportResult.from(
            PostResult.http(
                200, "{\"annotations\": {\"created\": 3, \"skipped\": 0, \"extra\": 9}, \"x\": 1}"));
    assertTrue(r.summaryPresent());
    assertEquals(3, r.created());
  }

  @Test
  public void unauthorized_401and403() {
    assertEquals(ImportResult.Status.UNAUTHORIZED, ImportResult.from(PostResult.http(401, "nope")).status());
    assertEquals(ImportResult.Status.UNAUTHORIZED, ImportResult.from(PostResult.http(403, "nope")).status());
  }

  @Test
  public void httpError_carriesCodeAndSnippet() {
    ImportResult r = ImportResult.from(PostResult.http(422, "validation: book_usfm required"));
    assertEquals(ImportResult.Status.HTTP_ERROR, r.status());
    assertEquals(422, r.httpCode());
    assertEquals("validation: book_usfm required", r.detail());
  }

  @Test
  public void httpError_snippetTruncatedTo200() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 500; i++) big.append('x');
    ImportResult r = ImportResult.from(PostResult.http(500, big.toString()));
    assertEquals(200, r.detail().length());
  }

  @Test
  public void networkError_unreachable() {
    ImportResult r = ImportResult.from(PostResult.unreachable());
    assertEquals(ImportResult.Status.UNREACHABLE, r.status());
  }
}
