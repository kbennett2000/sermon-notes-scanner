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

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.makeacopy.songbird.HttpImportPoster;
import de.schliweb.makeacopy.songbird.ImportPoster;
import de.schliweb.makeacopy.songbird.ImportResult;
import de.schliweb.makeacopy.songbird.SongbirdExchange;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives the single songbird POST (slice F6). Runs {@link ImportPoster} off the main thread and publishes
 * {@code IDLE → SENDING → DONE(result)}. The poster and executor are injected, so the ViewModel is
 * unit-testable with a fake poster + inline executor (no network, no Looper).
 */
public class FinalizeViewModel extends ViewModel {

  public enum Phase {
    IDLE,
    SENDING,
    DONE
  }

  /** UI state; {@code result} is non-null only when {@code phase == DONE}. */
  public record SendUiState(Phase phase, ImportResult result) {}

  private final MutableLiveData<SendUiState> state =
      new MutableLiveData<>(new SendUiState(Phase.IDLE, null));
  private final ImportPoster poster;
  private final Executor executor;

  /** Production constructor (used by ViewModelProvider). */
  public FinalizeViewModel() {
    this(new HttpImportPoster(), Executors.newSingleThreadExecutor());
  }

  /** Test constructor — inject a fake poster and an inline executor. */
  public FinalizeViewModel(ImportPoster poster, Executor executor) {
    this.poster = poster;
    this.executor = executor;
  }

  public LiveData<SendUiState> getState() {
    return state;
  }

  /** Logs in and sends the JSON; safe to call again to retry (idempotent). */
  public void send(String baseUrl, String username, String password, String json) {
    state.setValue(new SendUiState(Phase.SENDING, null));
    executor.execute(
        () -> {
          SongbirdExchange ex = poster.send(baseUrl, username, password, json);
          state.postValue(new SendUiState(Phase.DONE, ImportResult.from(ex)));
        });
  }

  @Override
  protected void onCleared() {
    if (executor instanceof ExecutorService es) {
      es.shutdown();
    }
  }
}
