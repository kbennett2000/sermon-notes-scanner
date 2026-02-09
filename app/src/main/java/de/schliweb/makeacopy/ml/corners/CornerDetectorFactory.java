package de.schliweb.makeacopy.ml.corners;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Zentrale Policy-Factory, damit Crop und Live nicht auseinanderlaufen.
 *
 * <p>DocQuad ist der Standard-Detector mit OpenCV als Fallback.</p>
 */
public final class CornerDetectorFactory {

    private CornerDetectorFactory() {
    }

    /**
     * Crop-Policy: DocQuad → OpenCV-only → Fallback.
     *
     * <p>Für einmalige Erkennung im Crop-Screen (ohne Throttling).</p>
     */
    @NonNull
    public static CornerDetector forCrop(@NonNull Context ctx) {
        return new CompositeCornerDetector(new DocQuadDetector(), new OpenCvCornerDetector());
    }

    /**
     * Live-Policy: DocQuad (cached + throttled) → OpenCV-only.
     *
     * <p>Für kontinuierliche Live-Kamera-Analyse mit Throttling (~4 Hz).</p>
     */
    @NonNull
    public static CornerDetector forLive(@NonNull Context ctx) {
        return new CompositeCornerDetector(
                new ThrottledDocQuadLiveDetector(ctx.getApplicationContext()),
                new OpenCvCornerDetector()
        );
    }
}
