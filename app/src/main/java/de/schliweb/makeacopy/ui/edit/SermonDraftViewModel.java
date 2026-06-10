/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.edit;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import de.schliweb.makeacopy.draft.SermonDraft;

/**
 * Activity-scoped hand-off for the produced {@link SermonDraft} (slice F4). The edit screen sets it on
 * proceed and the finalize screen reads it — avoiding Parcelable nav-arg boilerplate. This is the seam
 * the finalize screen (F6) consumes to emit, POST, or share the import document.
 */
public class SermonDraftViewModel extends ViewModel {

  private final MutableLiveData<SermonDraft> draft = new MutableLiveData<>();

  public LiveData<SermonDraft> getDraft() {
    return draft;
  }

  public void setDraft(SermonDraft d) {
    draft.setValue(d);
  }

  /** Clears the handed-off draft (e.g. after a successful send, before returning to start). */
  public void clear() {
    draft.setValue(null);
  }
}
