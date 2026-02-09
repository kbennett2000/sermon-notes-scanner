package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Schlanke Abstraktion für Corner-Detection.
 *
 * Implementierungen dürfen niemals Exceptions nach außen werfen,
 * sondern müssen Fehler als {@link DetectionResult#success} zurückgeben.
 */
public interface CornerDetector {
    DetectionResult detect(Bitmap src, Context ctx);
}
