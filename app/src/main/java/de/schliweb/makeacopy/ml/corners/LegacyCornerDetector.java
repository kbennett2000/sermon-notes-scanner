package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Legacy-Adapter: OpenCV-only Corner-Detection (Phase C: DocAligner entfernt).
 */
public final class LegacyCornerDetector implements CornerDetector {
    @Override
    public DetectionResult detect(Bitmap src, Context ctx) {
        return new OpenCvCornerDetector().detect(src, ctx);
    }
}
