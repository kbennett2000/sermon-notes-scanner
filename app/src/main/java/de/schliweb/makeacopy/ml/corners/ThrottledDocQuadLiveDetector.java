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
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;

/**
 * Live-DocQuad Detector mit: - ORT/Model init genau einmal - deterministischem Time-Throttle
 *
 * <p>Wird ausschließlich bei `docquad_prod_enabled=true` instanziiert (Factory garantiert das).
 */
final class ThrottledDocQuadLiveDetector implements CornerDetector {

  private static final long MIN_INTERVAL_MS = 250L; // ~4 Hz

  interface TimeSource {
    long nowMs();
  }

  interface Inference {
    DetectionResult run(Bitmap src, Context ctx) throws Exception;
  }

  private final Context appCtx;
  private final TimeSource timeSource;
  private final Inference inference;

  private volatile DocQuadDetector cachedDetector; // reuse across frames
  private volatile long lastRunMs = 0L;
  private volatile DetectionResult lastResult = null;

  /** Production ctor with a pre-loaded ORT runner (injected via DI). */
  ThrottledDocQuadLiveDetector(@NonNull Context appCtx, @NonNull DocQuadOrtRunner injectedRunner) {
    this.appCtx = appCtx;
    this.timeSource = SystemClock::uptimeMillis;
    this.inference =
        new Inference() {
          @Override
          public DetectionResult run(Bitmap src, Context ctx) throws Exception {
            DocQuadDetector det = cachedDetector;
            if (det == null) {
              det = new DocQuadDetector(injectedRunner);
              cachedDetector = det;
            }
            return det.detect(src, ctx);
          }
        };
  }

  // Package-private ctor for tests.
  ThrottledDocQuadLiveDetector(
      @NonNull Context appCtx, @NonNull TimeSource timeSource, @NonNull Inference inference) {
    this.appCtx = appCtx;
    this.timeSource = timeSource;
    this.inference = inference;
  }

  @Override
  public DetectionResult detect(Bitmap src, Context ctx) {
    if (src == null) return DetectionResult.fail(Source.DOCQUAD);

    long now = timeSource.nowMs();
    DetectionResult cached = lastResult;
    if (cached != null && (now - lastRunMs) < MIN_INTERVAL_MS) {
      return cached;
    }

    try {
      DetectionResult out = inference.run(src, appCtx);
      lastRunMs = now;
      lastResult = out;
      return out;
    } catch (Throwable t) {
      lastRunMs = now;
      DetectionResult out = DetectionResult.fail(Source.DOCQUAD);
      lastResult = out;
      return out;
    }
  }
}
