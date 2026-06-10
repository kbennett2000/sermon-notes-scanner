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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentFinalizeBinding;
import de.schliweb.makeacopy.draft.SermonDraft;
import de.schliweb.makeacopy.emit.ImportJsonEmitter;
import de.schliweb.makeacopy.songbird.ImportResult;
import de.schliweb.makeacopy.songbird.ShareFilename;
import de.schliweb.makeacopy.songbird.SongbirdPrefsHelper;
import de.schliweb.makeacopy.ui.camera.CameraViewModel;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.edit.SermonDraftViewModel;
import de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel;
import de.schliweb.makeacopy.ui.finalize.FinalizeViewModel.Phase;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Finalize screen (slice F6): preview the emitted songbird import JSON, POST it to songbird, or share it
 * as a file. The permanent terminus of the workflow — it replaced the F4/F5 TEMP draft-preview stub.
 * Reads the activity-scoped {@link SermonDraftViewModel}; the POST runs via {@link FinalizeViewModel}.
 */
@dagger.hilt.android.AndroidEntryPoint
public class FinalizeFragment extends Fragment {

  /** Brief pause so the operator sees the created/skipped result before the screen returns to start. */
  private static final long AUTO_RETURN_DELAY_MS = 1500L;

  private FragmentFinalizeBinding binding;
  private FinalizeViewModel viewModel;
  private SermonDraft draft;
  private String json;
  private boolean returning; // one-shot guard: a successful send auto-returns exactly once

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentFinalizeBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    viewModel = new ViewModelProvider(this).get(FinalizeViewModel.class);
    SermonDraftViewModel holder =
        new ViewModelProvider(requireActivity()).get(SermonDraftViewModel.class);
    draft = holder.getDraft().getValue();
    json = draft != null ? ImportJsonEmitter.emit(draft) : null;
    binding.jsonPreview.setText(json != null ? json : "(no draft)");

    binding.buttonSend.setOnClickListener(v -> onSend());
    binding.buttonShare.setOnClickListener(v -> onShare());
    binding.buttonSettings.setOnClickListener(v -> goToSettings());
    binding.settingsHint.setOnClickListener(v -> goToSettings());

    viewModel.getState().observe(getViewLifecycleOwner(), this::renderState);
  }

  @Override
  public void onResume() {
    super.onResume();
    // Re-gate after the operator may have just configured settings.
    updateSendGate();
  }

  private void updateSendGate() {
    if (binding == null) return;
    boolean configured = SongbirdPrefsHelper.isConfigured(requireContext());
    boolean haveDraft = json != null;
    boolean sending =
        viewModel.getState().getValue() != null
            && viewModel.getState().getValue().phase() == Phase.SENDING;
    binding.buttonSend.setEnabled(configured && haveDraft && !sending);
    binding.buttonShare.setEnabled(haveDraft);
    binding.settingsHint.setVisibility(configured ? View.GONE : View.VISIBLE);
  }

  private void onSend() {
    if (json == null) return;
    String baseUrl = SongbirdPrefsHelper.getBaseUrl(requireContext());
    String username = SongbirdPrefsHelper.getUsername(requireContext());
    String password = SongbirdPrefsHelper.getPassword(requireContext());
    viewModel.send(baseUrl, username, password, json);
  }

  private void onShare() {
    if (json == null || draft == null) return;
    try {
      File f = new File(requireContext().getCacheDir(), ShareFilename.forDraft(draft));
      try (FileOutputStream fos = new FileOutputStream(f)) {
        fos.write(json.getBytes(StandardCharsets.UTF_8));
      }
      Uri uri =
          FileProvider.getUriForFile(
              requireContext(), BuildConfig.APPLICATION_ID + ".fileprovider", f);
      Intent share = new Intent(Intent.ACTION_SEND);
      share.setType("application/json");
      share.putExtra(Intent.EXTRA_STREAM, uri);
      share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(share, getString(R.string.btn_share_json)));
    } catch (Exception e) {
      UIUtils.showToast(
          requireContext(), getString(R.string.finalize_share_failed), Toast.LENGTH_LONG);
    }
  }

  private void goToSettings() {
    try {
      Navigation.findNavController(requireView()).navigate(R.id.navigation_settings);
    } catch (IllegalArgumentException | IllegalStateException ignored) {
      // destination unavailable — no-op
    }
  }

  private void renderState(FinalizeViewModel.SendUiState state) {
    if (binding == null || state == null) return;
    if (state.phase() == Phase.SENDING) {
      binding.resultText.setText(R.string.finalize_sending);
    } else if (state.phase() == Phase.DONE && state.result() != null) {
      binding.resultText.setText(describe(state.result()));
      maybeAutoReturn(state.result());
    }
    updateSendGate();
  }

  /**
   * A clean import (SUCCESS, no rejected entries) ends the workflow: briefly show the result, then
   * clear the session and return to the camera start screen so the next handout starts fresh. This
   * also prevents re-tapping Send on a note that already imported. Failures stay put so the operator
   * can read the error and retry.
   */
  private void maybeAutoReturn(ImportResult r) {
    if (returning || r == null || !r.isSuccess() || r.failed() > 0) return;
    returning = true;
    binding.buttonSend.setEnabled(false);
    binding.getRoot().postDelayed(this::returnToStart, AUTO_RETURN_DELAY_MS);
  }

  /** Mirrors the page hub's "start over" reset (ExportFragment): wipe session + capture state. */
  private void returnToStart() {
    if (binding == null || !isAdded()) return;
    ViewModelProvider activity = new ViewModelProvider(requireActivity());
    activity.get(ExportSessionViewModel.class).setInitial(null);
    activity.get(SermonDraftViewModel.class).clear();
    CameraViewModel cameraViewModel = activity.get(CameraViewModel.class);
    CropViewModel cropViewModel = activity.get(CropViewModel.class);
    cameraViewModel.setImageUri(null);
    cropViewModel.setImageCropped(false);
    cropViewModel.setImageBitmap(null);
    cropViewModel.setOriginalImageBitmap(null);
    cropViewModel.setImageLoaded(false);
    NavOptions navOptions =
        new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
    try {
      Navigation.findNavController(requireView())
          .navigate(R.id.navigation_camera, null, navOptions);
    } catch (IllegalArgumentException | IllegalStateException ignored) {
      // destination unavailable — no-op
    }
  }

  private String describe(ImportResult r) {
    switch (r.status()) {
      case SUCCESS:
        if (!r.summaryPresent()) {
          return getString(R.string.finalize_imported_no_summary);
        }
        if (r.failed() > 0) {
          // songbird rejected entries — surface it prominently with the first reason.
          return getString(
              R.string.finalize_imported_with_failures, r.created(), r.skipped(), r.failed(), r.detail());
        }
        return getString(R.string.finalize_imported, r.created(), r.skipped());
      case UNREACHABLE:
        return getString(R.string.finalize_unreachable);
      case LOGIN_REJECTED:
        return getString(R.string.finalize_login_rejected);
      case HTTP_ERROR:
      default:
        return getString(R.string.finalize_http_error, r.httpCode(), r.detail());
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }
}
