package de.schliweb.makeacopy.ml.corners;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import de.schliweb.makeacopy.ml.docquad.DocQuadLetterbox;
import de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner;
import de.schliweb.makeacopy.ml.docquad.DocQuadPostprocessor;

/**
 * Produktiver Adapter für DocQuadNet-256.
 */
public final class DocQuadDetector implements CornerDetector {

    // Release-Asset (F-Droid kompatibel, kein Download)
    public static final String DEFAULT_MODEL_ASSET_PATH = "docquad/docquadnet256_trained_opset17.onnx";

    private final String modelAssetPath;

    // Optional injected runner (for live caching). Not owned/closed by this detector.
    private final DocQuadOrtRunner injectedRunner;

    public DocQuadDetector() {
        this(DEFAULT_MODEL_ASSET_PATH, null);
    }

    public DocQuadDetector(String modelAssetPath) {
        this(modelAssetPath, null);
    }

    /**
     * Live/Provider ctor: uses a cached runner.
     */
    public DocQuadDetector(DocQuadOrtRunner injectedRunner) {
        this(DEFAULT_MODEL_ASSET_PATH, injectedRunner);
    }

    private DocQuadDetector(String modelAssetPath, DocQuadOrtRunner injectedRunner) {
        this.modelAssetPath = modelAssetPath;
        this.injectedRunner = injectedRunner;
    }

    @Override
    public DetectionResult detect(Bitmap src, Context ctx) {
        if (src == null || ctx == null) return DetectionResult.fail(Source.DOCQUAD);

        Bitmap in256 = null;
        try {
            int srcW = src.getWidth();
            int srcH = src.getHeight();
            if (srcW <= 0 || srcH <= 0) return DetectionResult.fail(Source.DOCQUAD);

            DocQuadLetterbox lb = DocQuadLetterbox.create(srcW, srcH, DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H);
            in256 = renderLetterbox256(src, lb);
            float[] input = bitmapToNchwFloat01(in256);

            DocQuadOrtRunner.Outputs outputs;
            if (injectedRunner != null) {
                outputs = injectedRunner.run(input);
            } else {
                try (DocQuadOrtRunner runner = new DocQuadOrtRunner(ctx, modelAssetPath)) {
                    outputs = runner.run(input);
                }
            }

            DocQuadPostprocessor.Result r = DocQuadPostprocessor.postprocess(outputs, lb, DocQuadPostprocessor.PeakMode.REFINE_3X3);
            if (r == null || r.chosenQuadOriginal() == null || r.chosenQuadOriginal().length != 4)
                return DetectionResult.fail(Source.DOCQUAD);

            if (!isValidQuad(r.chosenQuadOriginal(), srcW, srcH)) return DetectionResult.fail(Source.DOCQUAD);

            return DetectionResult.successDebug(
                    Source.DOCQUAD,
                    r.chosenQuadOriginal(),
                    String.valueOf(r.chosenSource()),
                    r.penaltyMask(),
                    r.penaltyCorners()
            );
        } catch (Throwable t) {
            return DetectionResult.fail(Source.DOCQUAD);
        } finally {
            // Live analysis can call this repeatedly; avoid accumulating Bitmap native memory.
            try {
                if (in256 != null && !in256.isRecycled()) in256.recycle();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Preprocess exakt wie Training: RGB, 0..1, NCHW float32.
     */
    private static float[] bitmapToNchwFloat01(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w != DocQuadOrtRunner.IN_W || h != DocQuadOrtRunner.IN_H) {
            throw new IllegalArgumentException("bitmap must be 256x256");
        }
        int hw = h * w;
        float[] out = new float[3 * hw];
        int[] px = new int[hw];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                float r = ((c >> 16) & 0xFF) / 255.0f;
                float g = ((c >> 8) & 0xFF) / 255.0f;
                float b = (c & 0xFF) / 255.0f;
                int idx = y * w + x;
                out[0 + idx] = r;
                out[hw + idx] = g;
                out[2 * hw + idx] = b;
            }
        }
        return out;
    }

    private static Bitmap renderLetterbox256(Bitmap src, DocQuadLetterbox lb) {
        Bitmap out = Bitmap.createBitmap(DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(android.graphics.Color.BLACK);

        float left = (float) lb.offsetX;
        float top = (float) lb.offsetY;
        float right = (float) (lb.offsetX + (double) lb.srcW * lb.scale);
        float bottom = (float) (lb.offsetY + (double) lb.srcH * lb.scale);
        RectF dst = new RectF(left, top, right, bottom);
        canvas.drawBitmap(src, null, dst, null);
        return out;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static boolean isValidQuad(double[][] c, int w, int h) {
        if (c == null || c.length != 4) return false;
        for (int i = 0; i < 4; i++) {
            if (c[i] == null || c[i].length != 2) return false;
            double x = c[i][0];
            double y = c[i][1];
            if (!isFinite(x) || !isFinite(y)) return false;
            // Plausibility: leicht außerhalb tolerieren, aber nicht komplett wild
            if (x < -w * 0.25 || x > w * 1.25) return false;
            if (y < -h * 0.25 || y > h * 1.25) return false;
        }
        return true;
    }
}
