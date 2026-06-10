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
import de.schliweb.makeacopy.anchor.ResolvedSpan;
import de.schliweb.makeacopy.databinding.FragmentDraftPreviewBinding;
import de.schliweb.makeacopy.draft.SermonDraft;

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
    ResolvedSpan s = d.span();
    StringBuilder sb = new StringBuilder();
    sb.append("title: ").append(d.title()).append('\n');
    sb.append("passage: ").append(d.passageLabel()).append('\n');
    sb.append("date: ").append(d.dateIso()).append('\n');
    sb.append("tags: ").append(d.tags()).append('\n');
    if (s != null) {
      sb.append("span: ")
          .append(s.bookUsfm())
          .append(' ')
          .append(s.startChapter())
          .append(':')
          .append(s.startVerse())
          .append(" -> ")
          .append(s.endChapter())
          .append(':')
          .append(s.endVerse())
          .append('\n');
    }
    sb.append("\n--- note text ---\n").append(d.editedText());
    return sb.toString();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }
}
