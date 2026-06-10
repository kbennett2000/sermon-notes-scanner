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

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.anchor.BookNames;
import de.schliweb.makeacopy.anchor.SpanResolution;
import de.schliweb.makeacopy.anchor.VerseTable;
import de.schliweb.makeacopy.databinding.FragmentEditBinding;
import de.schliweb.makeacopy.ui.edit.EditViewModel.EditUiState;
import de.schliweb.makeacopy.ui.export.session.CombinedOcrTextProvider;
import de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The edit screen (slice F4): the operator fixes the combined OCR text, confirms a structured anchor,
 * and sets title/date/tags, then proceeds. Collects inputs into a {@code SermonDraft}; F5 renders the
 * note body. Fragment-scoped {@link EditViewModel} — a fresh visit re-derives, rotation preserves edits.
 */
@dagger.hilt.android.AndroidEntryPoint
public class EditFragment extends Fragment {

  private FragmentEditBinding binding;
  private EditViewModel viewModel;
  private SermonDraftViewModel draftHolder;
  private List<String> bookCodes;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentEditBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    viewModel = new ViewModelProvider(this).get(EditViewModel.class);
    draftHolder = new ViewModelProvider(requireActivity()).get(SermonDraftViewModel.class);
    ExportSessionViewModel session =
        new ViewModelProvider(requireActivity()).get(ExportSessionViewModel.class);

    // One-time prefill (guarded inside the ViewModel; safe across rotation).
    VerseTable table = VerseTableLoader.load(requireContext());
    if (table == null) {
      UIUtils.showToast(
          requireContext(),
          getString(R.string.error_verse_table_missing),
          android.widget.Toast.LENGTH_LONG);
    }
    String combined = CombinedOcrTextProvider.fromPages(session.getPages().getValue());
    viewModel.initialize(combined, table, LocalDate.now(ZoneId.systemDefault()).toString());

    setupBookPicker();
    bindInitialFields();
    wireWatchers();
    wireDatePicker();
    wireContinue();

    viewModel.getState().observe(getViewLifecycleOwner(), this::renderDerived);
  }

  private void setupBookPicker() {
    bookCodes = BookNames.orderedCodes();
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(
            requireContext(),
            android.R.layout.simple_list_item_1,
            BookNames.orderedDisplayNames());
    binding.bookSpinner.setAdapter(adapter);
    binding.bookSpinner.setOnItemClickListener(
        (parent, v, position, id) -> {
          if (position >= 0 && position < bookCodes.size()) {
            viewModel.setBookUsfm(bookCodes.get(position));
          }
        });
  }

  /** Sets the input fields from the current state ONCE (initial bind / rotation restore). */
  private void bindInitialFields() {
    EditUiState s = viewModel.getState().getValue();
    if (s == null) return;
    binding.editText.setText(s.editedText());
    binding.chapterField.setText(s.chapterText());
    binding.verseFromField.setText(s.verseFromText());
    binding.verseToField.setText(s.verseToText());
    binding.titleField.setText(s.title());
    binding.dateField.setText(s.dateIso());
    binding.tagsField.setText(s.tagsText());
    if (s.bookUsfm() != null && !s.bookUsfm().isEmpty()) {
      String name = BookNames.displayName(s.bookUsfm());
      if (name != null) binding.bookSpinner.setText(name, false);
    }
  }

  private void wireWatchers() {
    binding.editText.addTextChangedListener(simpleWatcher(viewModel::setText));
    binding.chapterField.addTextChangedListener(simpleWatcher(viewModel::setChapter));
    binding.verseFromField.addTextChangedListener(simpleWatcher(viewModel::setVerseFrom));
    binding.verseToField.addTextChangedListener(simpleWatcher(viewModel::setVerseTo));
    binding.titleField.addTextChangedListener(simpleWatcher(viewModel::setTitle));
    binding.tagsField.addTextChangedListener(simpleWatcher(viewModel::setTags));
  }

  private void wireDatePicker() {
    binding.dateField.setOnClickListener(v -> showDatePicker());
  }

  private void showDatePicker() {
    EditUiState s = viewModel.getState().getValue();
    LocalDate seed;
    try {
      seed = (s != null && s.dateIso() != null && !s.dateIso().isEmpty())
          ? LocalDate.parse(s.dateIso())
          : LocalDate.now(ZoneId.systemDefault());
    } catch (DateTimeParseException e) {
      seed = LocalDate.now(ZoneId.systemDefault());
    }
    DatePickerDialog dlg =
        new DatePickerDialog(
            requireContext(),
            (picker, year, month, dayOfMonth) -> {
              String iso = LocalDate.of(year, month + 1, dayOfMonth).toString();
              viewModel.setDate(iso);
              binding.dateField.setText(iso);
            },
            seed.getYear(),
            seed.getMonthValue() - 1,
            seed.getDayOfMonth());
    dlg.show();
  }

  private void wireContinue() {
    binding.buttonContinue.setOnClickListener(
        v -> {
          EditUiState s = viewModel.getState().getValue();
          if (s == null || !s.canProceed()) return;
          if (s.titleWarning()) {
            androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.title_no_title)
                    .setMessage(R.string.msg_no_title)
                    .setPositiveButton(R.string.btn_continue, (d, w) -> proceed())
                    .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                    .create();
            dialog.setOnShowListener(
                dlg ->
                    DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
            dialog.show();
          } else {
            proceed();
          }
        });
  }

  private void proceed() {
    if (!isAdded()) return;
    de.schliweb.makeacopy.draft.SermonDraft draft = viewModel.buildDraft();
    if (draft == null) return;
    draftHolder.setDraft(draft);
    try {
      Navigation.findNavController(requireView()).navigate(R.id.navigation_draft_preview);
    } catch (IllegalArgumentException | IllegalStateException ignored) {
      // destination unavailable — no-op
    }
  }

  /** Updates only derived/read-only UI from state; never rewrites the operator's input fields. */
  private void renderDerived(EditUiState s) {
    if (binding == null || s == null) return;
    binding.passageValue.setText(s.passageLabel());
    binding.verseWarning.setVisibility(s.verseOrderWarning() ? View.VISIBLE : View.GONE);
    binding.buttonContinue.setEnabled(s.canProceed());
    // Inline anchor error on the chapter field.
    if (s.anchorStatus() == SpanResolution.Status.CHAPTER_OUT_OF_RANGE) {
      binding.chapterInputLayout.setError(getString(R.string.error_chapter_out_of_range));
    } else if (s.anchorStatus() == SpanResolution.Status.UNKNOWN_BOOK) {
      binding.bookInputLayout.setError(getString(R.string.error_unknown_book));
    } else {
      binding.chapterInputLayout.setError(null);
      binding.bookInputLayout.setError(null);
    }
  }

  private static TextWatcher simpleWatcher(java.util.function.Consumer<String> sink) {
    return new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {}

      @Override
      public void afterTextChanged(Editable e) {
        sink.accept(e == null ? "" : e.toString());
      }
    };
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }
}
