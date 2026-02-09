package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * M6c: Mask→Quad Sanity-Test (trained ONNX, deterministisch, ohne Magic-Values).
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadPostprocessTrainedMaskQuadTest {

    private static final String TAG = "DocQuadMaskQuad";

    @Test
    public void maskQuad_isDeterministic_andInReasonableBounds() throws Exception {
        // target context -> App-APK assets (trained ONNX)
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Assume.assumeTrue(
                "trained tests disabled (set RUN_TRAINED_TESTS=1 to enable)",
                TrainedTestConfig.trainedTestsEnabled()
        );

        String modelAsset = TrainedTestConfig.resolveTrainedModelAsset(ctx);

        float[] input = makeGoldenInputV1Nchw();

        DocQuadOrtRunner.Outputs out;
        try (DocQuadOrtRunner runner = new DocQuadOrtRunner(ctx, modelAsset)) {
            out = runner.run(input);
        }
        assertNotNull(out);

        DocQuadPostprocessor.Result r1 = DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);
        DocQuadPostprocessor.Result r2 = DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);

        assertNotNull(r1);
        assertNotNull(r2);
        assertNotNull(r1.quadFromMask256());
        assertEquals(4, r1.quadFromMask256().length);

        // Logcat wird von Gradle/UTP eingesammelt; System.out ist nicht zuverlässig sichtbar.
        Log.i(TAG, "quadFromMaskUsedFallback=" + r1.quadFromMaskUsedFallback());

        assertEquals(r1.quadFromMaskUsedFallback(), r2.quadFromMaskUsedFallback());

        for (int i = 0; i < 4; i++) {
            assertNotNull(r1.quadFromMask256()[i]);
            assertEquals(2, r1.quadFromMask256()[i].length);

            double x = r1.quadFromMask256()[i][0];
            double y = r1.quadFromMask256()[i][1];
            assertTrue(Double.isFinite(x));
            assertTrue(Double.isFinite(y));

            // PCA-Quad kann minimal über 254 hinausgehen, sollte aber nicht wild sein.
            assertTrue(x >= 0.0 && x <= 256.0);
            assertTrue(y >= 0.0 && y <= 256.0);

            // Determinismus: exakte Gleichheit.
            assertEquals(x, r2.quadFromMask256()[i][0], 0.0);
            assertEquals(y, r2.quadFromMask256()[i][1], 0.0);
        }

        double areaAbs = Math.abs(shoelaceArea(r1.quadFromMask256()));
        assertTrue(areaAbs > 0.0);

        if (r1.quadFromMaskUsedFallback()) {
            // Minimaler Fallback: quad == corners256 (exact)
            assertNotNull(r1.corners256());
            for (int i = 0; i < 4; i++) {
                assertEquals(r1.corners256()[i][0], r1.quadFromMask256()[i][0], 0.0);
                assertEquals(r1.corners256()[i][1], r1.quadFromMask256()[i][1], 0.0);
            }
        }
    }

    /**
     * Entspricht exakt `training/docquad_m3/golden_samples.py` → `_make_input_sample_v1()`.
     *
     * Shape: [1,3,256,256] NCHW, float32, Wertebereich 0..1.
     */
    private static float[] makeGoldenInputV1Nchw() {
        int h = DocQuadOrtRunner.IN_H;
        int w = DocQuadOrtRunner.IN_W;
        float[] out = new float[1 * 3 * h * w];

        // Channel 0 (R): horizontal gradient x/255
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[idx(0, y, x)] = (float) x / 255.0f;
            }
        }
        // Channel 1 (G): vertical gradient y/255
        for (int y = 0; y < h; y++) {
            float v = (float) y / 255.0f;
            for (int x = 0; x < w; x++) {
                out[idx(1, y, x)] = v;
            }
        }
        // Channel 2 (B): constant 0.25
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[idx(2, y, x)] = 0.25f;
            }
        }

        // R-channel block: [64:192, 64:192] = 1.0
        for (int y = 64; y < 192; y++) {
            for (int x = 64; x < 192; x++) {
                out[idx(0, y, x)] = 1.0f;
            }
        }
        return out;
    }

    private static int idx(int c, int y, int x) {
        // NCHW: ((c * H) + y) * W + x
        return ((c * DocQuadOrtRunner.IN_H) + y) * DocQuadOrtRunner.IN_W + x;
    }

    private static double shoelaceArea(double[][] pts) {
        double s = 0.0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            s += pts[i][0] * pts[j][1] - pts[j][0] * pts[i][1];
        }
        return 0.5 * s;
    }
}
