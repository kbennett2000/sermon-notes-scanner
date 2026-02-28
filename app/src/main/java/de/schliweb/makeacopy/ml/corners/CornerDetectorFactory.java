package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
   */
  @NonNull
  public static CornerDetector forCrop(@NonNull Context ctx) {
    return new CompositeCornerDetector(new DocQuadDetector(), new OpenCvCornerDetector());
  }

  /**
   * Crop-Policy with an injected ORT runner (avoids re-loading the model).
   *
   * @param runner pre-loaded DocQuadOrtRunner (may be {@code null} for fallback to lazy loading)
   */
  @NonNull
  public static CornerDetector forCrop(@NonNull Context ctx, @Nullable DocQuadOrtRunner runner) {
    DocQuadDetector det = (runner != null) ? new DocQuadDetector(runner) : new DocQuadDetector();
    return new CompositeCornerDetector(det, new OpenCvCornerDetector());
  }

  /**
   * Live-Policy: DocQuad (cached + throttled) → OpenCV-only.
   *
   * <p>Für kontinuierliche Live-Kamera-Analyse mit Throttling (~4 Hz).
   */
  @NonNull
  public static CornerDetector forLive(@NonNull Context ctx) {
    return new CompositeCornerDetector(
        new ThrottledDocQuadLiveDetector(ctx.getApplicationContext()), new OpenCvCornerDetector());
  }

  /**
   * Live-Policy with an injected ORT runner (avoids re-loading the model).
   *
   * @param runner pre-loaded DocQuadOrtRunner (may be {@code null} for fallback to lazy loading)
   */
  @NonNull
  public static CornerDetector forLive(@NonNull Context ctx, @Nullable DocQuadOrtRunner runner) {
    if (runner != null) {
      return new CompositeCornerDetector(
          new ThrottledDocQuadLiveDetector(ctx.getApplicationContext(), runner),
          new OpenCvCornerDetector());
    }
    return forLive(ctx);
  }
}
