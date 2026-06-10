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

/**
 * The raw two-step outcome of a login-per-send exchange with songbird (slice F6b): the login request's
 * {@link PostResult} and, when login reached 2xx, the import request's {@link PostResult}. No exceptions
 * cross the {@link ImportPoster} seam, so this is trivially fakeable in tests. Carries no credentials.
 *
 * @param login the login POST outcome (never null)
 * @param imported the import POST outcome, or {@code null} when login did not reach 2xx (so import was
 *     never attempted)
 */
public record SongbirdExchange(PostResult login, PostResult imported) {

  /** Login failed (network error or non-2xx); import not attempted. */
  public static SongbirdExchange loginOnly(PostResult login) {
    return new SongbirdExchange(login, null);
  }

  /** Login succeeded and import was attempted. */
  public static SongbirdExchange of(PostResult login, PostResult imported) {
    return new SongbirdExchange(login, imported);
  }
}
