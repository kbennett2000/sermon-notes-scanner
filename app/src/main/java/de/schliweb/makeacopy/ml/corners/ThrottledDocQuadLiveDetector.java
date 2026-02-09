package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;

/**
 * Live-DocQuad Detector mit:
 * - ORT/Model init genau einmal
 * - deterministischem Time-Throttle
 *
 * <p>Wird ausschließlich bei `docquad_prod_enabled=true` instanziiert (Factory garantiert das).</p>
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
    private volatile long lastRunMs = 0L;
    private volatile DetectionResult lastResult = null;

    ThrottledDocQuadLiveDetector(@NonNull Context appCtx) {
        this(appCtx, SystemClock::uptimeMillis, null);
    }

    // Package-private ctor for tests.
    ThrottledDocQuadLiveDetector(@NonNull Context appCtx,
                                @NonNull TimeSource timeSource,
                                @NonNull Inference inference) {
        this.appCtx = appCtx;
        this.timeSource = timeSource;
        if (inference != null) {
            this.inference = inference;
        } else {
            // Production default: lazy-load a cached ORT runner and use DocQuadDetector with injected runner.
            this.inference = new Inference() {
                @Override
                public DetectionResult run(Bitmap src, Context ctx) throws Exception {
                    DocQuadOrtRunner r = ensureRunner();
                    return new DocQuadDetector(r).detect(src, ctx);
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
            byte[] modelBytes = readAssetFully(appCtx, DocQuadDetector.DEFAULT_MODEL_ASSET_PATH);
            runner = new DocQuadOrtRunner(modelBytes);
            return runner;
        }
    }

    private static byte[] readAssetFully(Context context, String assetPath) throws Exception {
        try (InputStream is = context.getAssets().open(assetPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) >= 0) {
                if (n > 0) baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }
}
