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

/** Tests for {@link SongbirdSettings} URL normalization + send gating (slice F6). */
public class SongbirdSettingsTest {

  @Test
  public void normalize_trimsAndStripsTrailingSlashes() {
    assertEquals("http://host:8000", SongbirdSettings.normalizeBaseUrl("  http://host:8000/  "));
    assertEquals("http://host:8000", SongbirdSettings.normalizeBaseUrl("http://host:8000///"));
    assertEquals("https://h", SongbirdSettings.normalizeBaseUrl("https://h"));
  }

  @Test
  public void normalize_acceptsHttp_andIsIdempotent() {
    String once = SongbirdSettings.normalizeBaseUrl("http://192.168.1.62:8000/");
    assertEquals("http://192.168.1.62:8000", once);
    assertEquals(once, SongbirdSettings.normalizeBaseUrl(once));
  }

  @Test
  public void normalize_nullToEmpty() {
    assertEquals("", SongbirdSettings.normalizeBaseUrl(null));
  }

  @Test
  public void canSend_requiresAllThree() {
    assertTrue(SongbirdSettings.canSend("http://h:8000", "kris", "pw"));
    assertFalse(SongbirdSettings.canSend("", "kris", "pw"));
    assertFalse(SongbirdSettings.canSend("http://h:8000", "", "pw"));
    assertFalse(SongbirdSettings.canSend("http://h:8000", "kris", ""));
    assertFalse(SongbirdSettings.canSend("  ", "kris", "pw"));
    assertFalse(SongbirdSettings.canSend("http://h:8000", "  ", "pw"));
    assertFalse(SongbirdSettings.canSend("http://h:8000", "kris", "   "));
    assertFalse(SongbirdSettings.canSend(null, null, null));
  }
}
