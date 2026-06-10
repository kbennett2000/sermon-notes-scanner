/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentSettingsBinding;
import de.schliweb.makeacopy.songbird.SongbirdPrefsHelper;
import de.schliweb.makeacopy.utils.ui.UIUtils;

/**
 * Minimal settings screen (slice F6b): the songbird base URL + username + password. There was no
 * pre-existing settings UI in the stripped fork, so this is the surviving settings surface — reached from
 * the finalize screen. The password field is masked (password toggle); values persist via the encrypted
 * {@link SongbirdPrefsHelper}. The password is never logged or echoed.
 */
@dagger.hilt.android.AndroidEntryPoint
public class SettingsFragment extends Fragment {

  private FragmentSettingsBinding binding;

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentSettingsBinding.inflate(inflater, container, false);
    return binding.getRoot();
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    binding.baseUrlField.setText(SongbirdPrefsHelper.getBaseUrl(requireContext()));
    binding.usernameField.setText(SongbirdPrefsHelper.getUsername(requireContext()));
    binding.passwordField.setText(SongbirdPrefsHelper.getPassword(requireContext()));
    binding.buttonSaveSettings.setOnClickListener(v -> save());
  }

  private void save() {
    String baseUrl =
        binding.baseUrlField.getText() == null ? "" : binding.baseUrlField.getText().toString();
    String username =
        binding.usernameField.getText() == null ? "" : binding.usernameField.getText().toString();
    String password =
        binding.passwordField.getText() == null ? "" : binding.passwordField.getText().toString();
    SongbirdPrefsHelper.setBaseUrl(requireContext(), baseUrl); // normalized inside
    SongbirdPrefsHelper.setUsername(requireContext(), username);
    SongbirdPrefsHelper.setPassword(requireContext(), password);
    UIUtils.showToast(requireContext(), getString(R.string.settings_saved), Toast.LENGTH_SHORT);
    try {
      Navigation.findNavController(requireView()).popBackStack();
    } catch (IllegalStateException ignored) {
      // not on a back stack — no-op
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    binding = null;
  }
}
