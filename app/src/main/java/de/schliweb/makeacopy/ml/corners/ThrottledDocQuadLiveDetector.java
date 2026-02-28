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
  private final Object lock = new Object();

  private volatile DocQuadOrtRunner runner; // created lazily
  private volatile DocQuadDetector cachedDetector; // reuse across frames
  private volatile long lastRunMs = 0L;
  private volatile DetectionResult lastResult = null;

  ThrottledDocQuadLiveDetector(@NonNull Context appCtx) {
    this(appCtx, SystemClock::uptimeMillis, null);
  }

  /** Production ctor with a pre-loaded ORT runner (injected via DI). */
  ThrottledDocQuadLiveDetector(@NonNull Context appCtx, @NonNull DocQuadOrtRunner injectedRunner) {
    this.appCtx = appCtx;
    this.timeSource = SystemClock::uptimeMillis;
    this.runner = injectedRunner;
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
    if (inference != null) {
      this.inference = inference;
    } else {
      // Production default: lazy-load a cached ORT runner and use DocQuadDetector with injected
      // runner.
      this.inference =
          new Inference() {
            @Override
            public DetectionResult run(Bitmap src, Context ctx) throws Exception {
              DocQuadOrtRunner r = ensureRunner();
              DocQuadDetector det = cachedDetector;
              if (det == null) {
                det = new DocQuadDetector(r);
                cachedDetector = det;
              }
              return det.detect(src, ctx);
            }
          };
    }
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

  private DocQuadOrtRunner ensureRunner() throws Exception {
    DocQuadOrtRunner existing = runner;
    if (existing != null) return existing;
    synchronized (lock) {
      if (runner != null) return runner;
      // Production default: use the centralized singleton which supports cache-loading and mmap.
      runner = DocQuadOrtRunner.getInstance(appCtx, DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
      return runner;
    }
  }
}
