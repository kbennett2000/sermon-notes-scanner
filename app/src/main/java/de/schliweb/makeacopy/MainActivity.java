/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy;

import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.databinding.ActivityMainBinding;
import de.schliweb.makeacopy.services.CacheCleanupService;

/**
 * MainActivity represents the entry point of the application. This activity initializes the main
 * view and setups up the UI components.
 *
 * <p>It enables edge-to-edge display mode for a more immersive user interface experience and
 * inflates the main layout using view binding.
 *
 * <p>This class extends AppCompatActivity and overrides the onCreate method to set up the
 * activity's user interface.
 */
@AndroidEntryPoint
public class MainActivity extends AppCompatActivity {

  private ActivityMainBinding binding;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // Enable true edge-to-edge: app draws behind system bars
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

    binding = ActivityMainBinding.inflate(getLayoutInflater());
    setContentView(binding.getRoot());

    // Apply insets to top app bar and bottom bar container if present
    final View root = findViewById(android.R.id.content);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          Insets sb = insets.getInsets(WindowInsetsCompat.Type.systemBars());

          // Toolbar oben auffüttern
          MaterialToolbar topBar = findViewById(R.id.top_app_bar);
          if (topBar != null) {
            topBar.setPadding(
                topBar.getPaddingLeft(),
                sb.top,
                topBar.getPaddingRight(),
                topBar.getPaddingBottom());
          }

          // BottomAppBar/FAB-Container unten auffüttern
          /*
          View bottom = findViewById(R.id.bottom_bar_container);
          if (bottom != null) {
              bottom.setPadding(
                      bottom.getPaddingLeft(),
                      bottom.getPaddingTop(),
                      bottom.getPaddingRight(),
                      sb.bottom
              );
          }*/

          // Optional: der Mittelbereich soll NICHT extra Padding bekommen.
          return insets;
        });
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();

    // Trigger immediate cache cleanup when activity detects low memory
    CacheCleanupService.forceCleanup(this);
  }

  /**
   * Called when the operating system has determined that it is a good time for a process to trim
   * unneeded memory from its process.
   */
  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);

    // Trigger cache cleanup when app is in the background and memory is low (non-deprecated level)
    if (level >= TRIM_MEMORY_BACKGROUND) {
      CacheCleanupService.forceCleanup(this);
    }
  }
}
