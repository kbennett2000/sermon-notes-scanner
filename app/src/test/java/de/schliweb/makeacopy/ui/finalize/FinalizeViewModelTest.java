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
import de.schliweb.makeacopy.ui.finalize.FinalizeViewModel.Phase;
import de.schliweb.makeacopy.ui.finalize.FinalizeViewModel.SendUiState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link FinalizeViewModel} via a fake {@link ImportPoster} + inline executor (slice F6). */
public class FinalizeViewModelTest {

  /** Records the args it was called with and returns a canned PostResult. */
  private static final class FakePoster implements ImportPoster {
    String baseUrl;
    String token;
    String json;
    PostResult result;

    @Override
    public PostResult post(String baseUrl, String token, String json) {
      this.baseUrl = baseUrl;
      this.token = token;
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
  public void send_success_passesArgsAndPublishesDone() {
    FakePoster fake = new FakePoster();
    fake.result = PostResult.http(200, "{\"annotations\": {\"created\": 1, \"skipped\": 0}}");
    FinalizeViewModel vm = vm(fake);

    vm.send("http://host:8000", "secret-token", "{json}");

    // The poster received exactly what we passed.
    assertEquals("http://host:8000", fake.baseUrl);
    assertEquals("secret-token", fake.token);
    assertEquals("{json}", fake.json);

    SendUiState s = vm.getState().getValue();
    assertNotNull(s);
    assertEquals(Phase.DONE, s.phase());
    assertEquals(ImportResult.Status.SUCCESS, s.result().status());
    assertEquals(1, s.result().created());
    assertEquals(0, s.result().skipped());
  }

  @Test
  public void send_unreachable_publishesUnreachable() {
    FakePoster fake = new FakePoster();
    fake.result = PostResult.unreachable();
    FinalizeViewModel vm = vm(fake);
    vm.send("http://host:8000", "t", "{}");
    assertEquals(ImportResult.Status.UNREACHABLE, vm.getState().getValue().result().status());
  }

  @Test
  public void send_unauthorized_publishesUnauthorized() {
    FakePoster fake = new FakePoster();
    fake.result = PostResult.http(401, "denied");
    FinalizeViewModel vm = vm(fake);
    vm.send("http://host:8000", "bad", "{}");
    assertEquals(ImportResult.Status.UNAUTHORIZED, vm.getState().getValue().result().status());
  }
}
