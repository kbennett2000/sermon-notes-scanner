package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Deterministischer Orchestrator.
 *
 * Policy:
 * - Erst DocQuad
 * - Dann Legacy (OpenCV-only)
 */
public final class CompositeCornerDetector implements CornerDetector {

    private final CornerDetector docQuad;
    private final CornerDetector legacy;

    public CompositeCornerDetector(CornerDetector docQuad, CornerDetector legacy) {
        this.docQuad = docQuad;
        this.legacy = legacy;
    }

    @Override
    public DetectionResult detect(Bitmap src, Context ctx) {
        DetectionResult r1 = null;
        try {
            r1 = docQuad.detect(src, ctx);
        } catch (Throwable ignore) {
        }
        if (r1 != null && r1.success) return r1;

        DetectionResult r2 = null;
        try {
            r2 = legacy.detect(src, ctx);
        } catch (Throwable ignore) {
        }
        if (r2 != null && r2.success) return r2;
        return DetectionResult.fail(Source.FALLBACK);
    }
}
