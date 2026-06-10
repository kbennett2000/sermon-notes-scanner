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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Tests for {@link ImportResult#from} (slice F6b) against songbird's REAL {@code ImportSummary} shape and
 * the cookie-session login-per-send flow. Response fixtures match songbird source
 * (github.com/kbennett2000/songbird @ 89f894e — api/schemas.py ImportSummary,
 * tests/import_export_test.py).
 */
public class ImportResultTest {

  private static PostResult ok2xx(String body) {
    return PostResult.http(200, body);
  }

  // ---- success (login ok → import 2xx → ImportSummary) ----

  @Test
  public void firstImport_fromCommittedFixture() throws IOException {
    String body = readResource("/songbird/import_summary.json");
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), ok2xx(body)));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertTrue(r.summaryPresent());
    assertEquals(1, r.created());
    assertEquals(0, r.skipped());
    assertEquals(0, r.failed());
    assertFalse(r.hasFailures());
  }

  @Test
  public void idempotentReimport_reportsSkipped() {
    String body =
        "{\"annotations\":{\"created\":0,\"skipped\":1,\"failed\":0},"
            + "\"sermon_notes\":{\"created\":0,\"skipped\":0,\"failed\":0},\"errors\":[]}";
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), ok2xx(body)));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertEquals(0, r.created());
    assertEquals(1, r.skipped());
    assertEquals(0, r.failed());
  }

  @Test
  public void failuresReported_withFirstErrorAsDetail() {
    String body =
        "{\"annotations\":{\"created\":0,\"skipped\":0,\"failed\":1},"
            + "\"sermon_notes\":{\"created\":0,\"skipped\":0,\"failed\":0},"
            + "\"errors\":[\"annotation 1SA 25:1: unknown translation(s): XYZ\"]}";
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), ok2xx(body)));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertTrue(r.hasFailures());
    assertEquals(1, r.failed());
    assertEquals("annotation 1SA 25:1: unknown translation(s): XYZ", r.detail());
  }

  @Test
  public void failedCountsSermonNotesToo() {
    String body =
        "{\"annotations\":{\"created\":1,\"skipped\":0,\"failed\":0},"
            + "\"sermon_notes\":{\"created\":0,\"skipped\":0,\"failed\":2},\"errors\":[\"x\",\"y\"]}";
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), ok2xx(body)));
    assertEquals(2, r.failed());
    assertTrue(r.hasFailures());
  }

  @Test
  public void success2xx_missingSummary_stillSuccessNoCounts() {
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), ok2xx("{\"ok\":true}")));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertFalse(r.summaryPresent());
  }

  @Test
  public void success2xx_malformedBody_stillSuccess() {
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), ok2xx("not json")));
    assertEquals(ImportResult.Status.SUCCESS, r.status());
    assertFalse(r.summaryPresent());
  }

  // ---- login failures ----

  @Test
  public void loginRejected_401_isLoginRejected() {
    PostResult login = PostResult.http(401, "{\"detail\":{\"code\":\"INVALID_CREDENTIALS\"}}");
    ImportResult r = ImportResult.from(SongbirdExchange.loginOnly(login));
    assertEquals(ImportResult.Status.LOGIN_REJECTED, r.status());
  }

  @Test
  public void loginNetworkError_isUnreachable() {
    ImportResult r = ImportResult.from(SongbirdExchange.loginOnly(PostResult.unreachable()));
    assertEquals(ImportResult.Status.UNREACHABLE, r.status());
  }

  @Test
  public void loginOtherNon2xx_isHttpError() {
    ImportResult r = ImportResult.from(SongbirdExchange.loginOnly(PostResult.http(500, "boom")));
    assertEquals(ImportResult.Status.HTTP_ERROR, r.status());
    assertEquals(500, r.httpCode());
    assertEquals("boom", r.detail());
  }

  // ---- import failures AFTER a successful login ----

  @Test
  public void importUnauthorizedAfterLogin_isHttpError_notLoginRejected() {
    // A 401 at import (cookie didn't stick) is NOT a credentials problem — login already succeeded.
    PostResult imp = PostResult.http(401, "{\"detail\":{\"code\":\"NOT_AUTHENTICATED\"}}");
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), imp));
    assertEquals(ImportResult.Status.HTTP_ERROR, r.status());
    assertEquals(401, r.httpCode());
  }

  @Test
  public void importConcordDown_502_isHttpError() {
    PostResult imp = PostResult.http(502, "{\"detail\":{\"code\":\"CONCORD_UNREACHABLE\"}}");
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), imp));
    assertEquals(ImportResult.Status.HTTP_ERROR, r.status());
    assertEquals(502, r.httpCode());
  }

  @Test
  public void importNetworkErrorAfterLogin_isUnreachable() {
    ImportResult r = ImportResult.from(SongbirdExchange.of(ok2xx("{}"), PostResult.unreachable()));
    assertEquals(ImportResult.Status.UNREACHABLE, r.status());
  }

  @Test
  public void httpErrorSnippet_truncatedTo200() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 500; i++) big.append('x');
    ImportResult r = ImportResult.from(SongbirdExchange.loginOnly(PostResult.http(500, big.toString())));
    assertEquals(200, r.detail().length());
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = ImportResultTest.class.getResourceAsStream(path)) {
      if (in == null) throw new IOException("missing test resource: " + path);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
