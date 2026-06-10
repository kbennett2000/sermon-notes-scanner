/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.finalize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import de.schliweb.makeacopy.songbird.ImportPoster;
import de.schliweb.makeacopy.songbird.ImportResult;
import de.schliweb.makeacopy.songbird.PostResult;
import de.schliweb.makeacopy.songbird.SongbirdExchange;
import de.schliweb.makeacopy.ui.finalize.FinalizeViewModel.Phase;
import de.schliweb.makeacopy.ui.finalize.FinalizeViewModel.SendUiState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link FinalizeViewModel} via a fake {@link ImportPoster} + inline executor (slice F6b):
 * the login-per-send two-step flow — login-fail, login-ok/import-fail, login-ok/import-ok.
 */
public class FinalizeViewModelTest {

  /** Records the args it was called with and returns a canned exchange. */
  private static final class FakePoster implements ImportPoster {
    String baseUrl;
    String username;
    String password;
    String json;
    SongbirdExchange result;

    @Override
    public SongbirdExchange send(String baseUrl, String username, String password, String json) {
      this.baseUrl = baseUrl;
      this.username = username;
      this.password = password;
      this.json = json;
      return result;
    }
  }

  @Before
  public void setUp() {
    ArchTaskExecutor.getInstance()
        .setDelegate(
            new TaskExecutor() {
              @Override
              public void executeOnDiskIO(Runnable r) {
                r.run();
              }

              @Override
              public void postToMainThread(Runnable r) {
                r.run();
              }

              @Override
              public boolean isMainThread() {
                return true;
              }
            });
  }

  @After
  public void tearDown() {
    ArchTaskExecutor.getInstance().setDelegate(null);
  }

  private static FinalizeViewModel vm(FakePoster fake) {
    return new FinalizeViewModel(fake, Runnable::run); // inline executor
  }

  @Test
  public void send_loginOkImportOk_passesCredsAndPublishesSuccess() {
    FakePoster fake = new FakePoster();
    fake.result =
        SongbirdExchange.of(
            PostResult.http(200, "{}"),
            PostResult.http(200, "{\"annotations\":{\"created\":1,\"skipped\":0,\"failed\":0}}"));
    FinalizeViewModel vm = vm(fake);

    vm.send("http://host:8077", "kris", "s3cret", "{json}");

    assertEquals("http://host:8077", fake.baseUrl);
    assertEquals("kris", fake.username);
    assertEquals("s3cret", fake.password);
    assertEquals("{json}", fake.json);

    SendUiState s = vm.getState().getValue();
    assertNotNull(s);
    assertEquals(Phase.DONE, s.phase());
    assertEquals(ImportResult.Status.SUCCESS, s.result().status());
    assertEquals(1, s.result().created());
  }

  @Test
  public void send_loginFail_publishesLoginRejected() {
    FakePoster fake = new FakePoster();
    fake.result = SongbirdExchange.loginOnly(PostResult.http(401, "{\"detail\":{}}"));
    FinalizeViewModel vm = vm(fake);
    vm.send("http://host:8077", "kris", "wrong", "{}");
    assertEquals(ImportResult.Status.LOGIN_REJECTED, vm.getState().getValue().result().status());
  }

  @Test
  public void send_loginOkImportFail_publishesHttpError() {
    FakePoster fake = new FakePoster();
    fake.result = SongbirdExchange.of(PostResult.http(200, "{}"), PostResult.http(502, "concord down"));
    FinalizeViewModel vm = vm(fake);
    vm.send("http://host:8077", "kris", "s3cret", "{}");
    ImportResult r = vm.getState().getValue().result();
    assertEquals(ImportResult.Status.HTTP_ERROR, r.status());
    assertEquals(502, r.httpCode());
  }

  @Test
  public void send_loginUnreachable_publishesUnreachable() {
    FakePoster fake = new FakePoster();
    fake.result = SongbirdExchange.loginOnly(PostResult.unreachable());
    FinalizeViewModel vm = vm(fake);
    vm.send("http://host:8077", "kris", "s3cret", "{}");
    assertEquals(ImportResult.Status.UNREACHABLE, vm.getState().getValue().result().status());
  }
}
