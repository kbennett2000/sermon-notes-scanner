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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import de.schliweb.makeacopy.databinding.FragmentDraftPreviewBinding;
import de.schliweb.makeacopy.draft.SermonDraft;
import de.schliweb.makeacopy.emit.ImportJsonEmitter;

/**
 * F4-TEMP terminus: read-only dump of the {@link SermonDraft} the edit screen produced, with a clearly
 * marked "F5/F6 pending" note. Replaced when F5 (emitter) and F6 (finalize) land. Reads the
 * activity-scoped {@link SermonDraftViewModel}.
 */
@dagger.hilt.android.AndroidEntryPoint
public class DraftPreviewFragment extends Fragment {

  private FragmentDraftPreviewBinding binding;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentDraftPreviewBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    SermonDraftViewModel holder =
        new ViewModelProvider(requireActivity()).get(SermonDraftViewModel.class);
    SermonDraft d = holder.getDraft().getValue();
    binding.draftDump.setText(render(d));
  }

  private static String render(SermonDraft d) {
    if (d == null) return "(no draft)";
    // F5: show the real emitted songbird import JSON (the payload F6 will POST/share).
    return ImportJsonEmitter.emit(d);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }
}
