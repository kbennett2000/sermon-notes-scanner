package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertArrayEquals;
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
 * M6d: Pfadwahl A/B (Corner vs Mask) muss deterministisch sein.
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadPostprocessTrainedChooseTest {

    private static final String TAG = "DocQuadChooseTest";

    @Test
    public void choose_isDeterministic_andConsistentWithFallbackFlag() throws Exception {
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

        // M6d: Standardpfad nutzt REFINE_3X3 für Corners.
        DocQuadPostprocessor.Result r1 = DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);
        DocQuadPostprocessor.Result r2 = DocQuadPostprocessor.postprocess(out, DocQuadPostprocessor.PeakMode.REFINE_3X3);

        assertNotNull(r1);
        assertNotNull(r2);

        assertNotNull(r1.chosenSource());
        assertEquals(r1.chosenSource(), r2.chosenSource());

        Log.i(TAG, "chosenSource=" + r1.chosenSource() + " quadFromMaskUsedFallback=" + r1.quadFromMaskUsedFallback());

        assertNotNull(r1.chosenQuad256());
        assertEquals(4, r1.chosenQuad256().length);

        // Determinismus: exakte Gleichheit auf denselben Outputs.
        assertQuadEquals(r1.chosenQuad256(), r2.chosenQuad256());

        // Sanity: finite + grober Range.
        for (int i = 0; i < 4; i++) {
            assertNotNull(r1.chosenQuad256()[i]);
            assertEquals(2, r1.chosenQuad256()[i].length);
            double x = r1.chosenQuad256()[i][0];
            double y = r1.chosenQuad256()[i][1];
            assertTrue(Double.isFinite(x));
            assertTrue(Double.isFinite(y));
            assertTrue(x >= 0.0 && x <= 256.0);
            assertTrue(y >= 0.0 && y <= 256.0);
        }

        if (r1.chosenSource() == DocQuadPostprocessor.ChosenSource.MASK) {
            assertTrue("MASK darf nicht gewählt werden, wenn Mask-Quad Fallback ist", !r1.quadFromMaskUsedFallback());
            assertQuadEquals(r1.quadFromMask256(), r1.chosenQuad256());
        } else {
            assertQuadEquals(r1.corners256(), r1.chosenQuad256());
        }
    }

    private static void assertQuadEquals(double[][] a, double[][] b) {
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(4, a.length);
        assertEquals(4, b.length);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(a[i], b[i], 0.0);
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
}
