/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import androidx.annotation.NonNull;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;

/**
 * Zentrale Policy-Factory, damit Crop und Live nicht auseinanderlaufen.
 *
 * <p>DocQuad ist der Standard-Detector mit OpenCV als Fallback.
 */
public final class CornerDetectorFactory {

  private CornerDetectorFactory() {}

  /**
   * Crop-Policy: DocQuad → OpenCV-only → Fallback.
   *
   * <p>Für einmalige Erkennung im Crop-Screen (ohne Throttling).
   *
   * @param runner pre-loaded DocQuadOrtRunner
   */
  @NonNull
  public static CornerDetector forCrop(@NonNull Context ctx, @NonNull DocQuadOrtRunner runner) {
    return new CompositeCornerDetector(new DocQuadDetector(runner), new OpenCvCornerDetector());
  }

  /**
   * Live-Policy: DocQuad (cached + throttled) → OpenCV-only.
   *
   * <p>Für kontinuierliche Live-Kamera-Analyse mit Throttling (~4 Hz).
   *
   * @param runner pre-loaded DocQuadOrtRunner
   */
  @NonNull
  public static CornerDetector forLive(@NonNull Context ctx, @NonNull DocQuadOrtRunner runner) {
    return new CompositeCornerDetector(
        new ThrottledDocQuadLiveDetector(ctx.getApplicationContext(), runner),
        new OpenCvCornerDetector());
  }
}
