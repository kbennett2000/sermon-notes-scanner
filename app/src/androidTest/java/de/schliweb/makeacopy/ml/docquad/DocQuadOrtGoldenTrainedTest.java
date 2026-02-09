package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Assume;

/**
 * M4 Golden-Test (trained snapshot): Android ONNX Runtime liefert für das trainierte Modell
 * die erwarteten Snapshot-Stats.
 *
 * - Input ist synthetisch und entspricht exakt `training/docquad_m3/golden_samples.py` (v1).
 * - `mask_area` ist v1-definiert als `sigmoid(mask_logits) > 0.5` (strict).
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadOrtGoldenTrainedTest {

    private static final String MASK_AREA_DEFINITION_V1 = "mask_prob_gt_0.5";

    // Toleranz analog zur Python-Prüfung (Float-Stats, ddof=0).
    private static final double EPS = 1e-4;

    @Test
    public void golden_stats_match_trained_snapshot() throws Exception {
        // instrumentation context -> Assets der Test-APK (expected stats JSON)
        Context instrCtx = InstrumentationRegistry.getInstrumentation().getContext();
        // target context -> Assets der App-APK (ONNX model)
        Context targetCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Assume.assumeTrue(
                "trained tests disabled (set RUN_TRAINED_TESTS=1 to enable)",
                TrainedTestConfig.trainedTestsEnabled()
        );

        String modelAsset = TrainedTestConfig.resolveTrainedModelAsset(targetCtx);
        String expectedJsonAsset = TrainedTestConfig.resolveTrainedExpectedStatsAsset(instrCtx);

        // 1) Input exakt wie in Python Golden Sample v1
        float[] input = makeGoldenInputV1Nchw();

        // 2) ORT laufen lassen
        DocQuadOrtRunner.Outputs out;
        try (DocQuadOrtRunner runner = new DocQuadOrtRunner(targetCtx, modelAsset)) {
            out = runner.run(input);
        }

        assertNotNull(out);
        assertEquals(1, out.maskLogits().length);
        assertEquals(1, out.maskLogits()[0].length);
        assertEquals(DocQuadOrtRunner.OUT_H, out.maskLogits()[0][0].length);
        assertEquals(DocQuadOrtRunner.OUT_W, out.maskLogits()[0][0][0].length);

        assertEquals(1, out.cornerHeatmaps().length);
        assertEquals(4, out.cornerHeatmaps()[0].length);
        assertEquals(DocQuadOrtRunner.OUT_H, out.cornerHeatmaps()[0][0].length);
        assertEquals(DocQuadOrtRunner.OUT_W, out.cornerHeatmaps()[0][0][0].length);

        int maskArea = computeMaskAreaV1(out.maskLogits());
        Stats cornerStats = computeMeanStd(out.cornerHeatmaps());
        Stats maskStats = computeMeanStd(out.maskLogits());

        // 3) Erwartete Stats laden
        JsonObject expected = readJsonAsset(instrCtx, expectedJsonAsset);
        assertEquals(MASK_AREA_DEFINITION_V1, expected.get("mask_area_definition").getAsString());
        // Snapshot-Datei ist nicht als Schema-Version gedacht (Schema bleibt v1).
        assertTrue(expected.has("version"));

        JsonObject sample0 = expected.getAsJsonObject("samples").getAsJsonObject("sample0");

        assertEquals(sample0.get("mask_area").getAsInt(), maskArea);
        assertClose(sample0.get("corner_heatmaps_mean").getAsDouble(), cornerStats.mean, EPS);
        assertClose(sample0.get("corner_heatmaps_std").getAsDouble(), cornerStats.std, EPS);
        assertClose(sample0.get("mask_logits_mean").getAsDouble(), maskStats.mean, EPS);
        assertClose(sample0.get("mask_logits_std").getAsDouble(), maskStats.std, EPS);
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

    /**
     * `mask_area` Definition v1 (FIX):
     * - `mask_prob = sigmoid(mask_logits)`
     * - `mask_bin = mask_prob > 0.5` (strict)
     * - `mask_area = sum(mask_bin)`
     */
    private static int computeMaskAreaV1(float[][][][] maskLogits) {
        float[][] m = maskLogits[0][0];
        int area = 0;
        for (int y = 0; y < DocQuadOrtRunner.OUT_H; y++) {
            for (int x = 0; x < DocQuadOrtRunner.OUT_W; x++) {
                double logit = (double) m[y][x];
                double prob = 1.0 / (1.0 + Math.exp(-logit));
                if (prob > 0.5) {
                    area++;
                }
            }
        }
        return area;
    }

    private static final class Stats {
        final double mean;
        final double std;

        Stats(double mean, double std) {
            this.mean = mean;
            this.std = std;
        }
    }

    private static Stats computeMeanStd(float[][][][] x) {
        double sum = 0.0;
        double sumSq = 0.0;
        long n = 0;
        for (int b = 0; b < x.length; b++) {
            float[][][] xb = x[b];
            for (int c = 0; c < xb.length; c++) {
                float[][] xc = xb[c];
                for (int y = 0; y < xc.length; y++) {
                    float[] row = xc[y];
                    for (int i = 0; i < row.length; i++) {
                        double v = (double) row[i];
                        sum += v;
                        sumSq += v * v;
                        n++;
                    }
                }
            }
        }
        assertTrue(n > 0);
        double mean = sum / (double) n;
        double var = sumSq / (double) n - mean * mean;
        if (var < 0.0) var = 0.0; // numerische Rundungsfehler
        return new Stats(mean, Math.sqrt(var));
    }

    private static void assertClose(double expected, double actual, double eps) {
        assertTrue("expected=" + expected + " actual=" + actual + " eps=" + eps,
                Math.abs(expected - actual) <= eps);
    }

    private static JsonObject readJsonAsset(Context ctx, String assetPath) throws Exception {
        try (InputStream is = ctx.getAssets().open(assetPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = is.read(buf)) >= 0) {
                if (n > 0) baos.write(buf, 0, n);
            }
            String s = baos.toString(StandardCharsets.UTF_8);
            return JsonParser.parseString(s).getAsJsonObject();
        }
    }
}
