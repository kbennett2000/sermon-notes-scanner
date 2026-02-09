package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * M4 Golden-Test: Android ONNX Runtime liefert für das Zero-Init-Modell exakt die erwarteten Stats.
 *
 * - Input ist synthetisch und entspricht exakt `training/docquad_m3/golden_samples.py` (v1).
 * - `mask_area` ist v1-definiert als `sigmoid(mask_logits) > 0.5` (strict).
 */
@RunWith(AndroidJUnit4.class)
public class DocQuadOrtGoldenTest {

    private static final String MODEL_ASSET = "docquad_m4/docquadnet256_zero_opset17.onnx";
    private static final String EXPECTED_JSON_ASSET = "docquad_m4/expected_stats_v1.json";

    private static final String MASK_AREA_DEFINITION_V1 = "mask_prob_gt_0.5";

    @Test
    public void golden_stats_match_v1() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getContext();

        // 1) Input exakt wie in Python Golden Sample v1
        float[] input = makeGoldenInputV1Nchw();

        // 2) ORT laufen lassen
        DocQuadOrtRunner.Outputs out;
        try (DocQuadOrtRunner runner = new DocQuadOrtRunner(ctx, MODEL_ASSET)) {
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

        int[] cornerArgmaxIdx = computeCornerArgmaxIdx(out.cornerHeatmaps());
        int maskArea = computeMaskAreaV1(out.maskLogits());

        // 3) Erwartete Stats laden
        JsonObject expected = readJsonAsset(ctx, EXPECTED_JSON_ASSET);
        assertEquals(MASK_AREA_DEFINITION_V1, expected.get("mask_area_definition").getAsString());

        JsonObject sample0 = expected.getAsJsonObject("samples").getAsJsonObject("sample0");
        JsonArray expCorners = sample0.getAsJsonArray("corner_argmax_idx");
        assertEquals(4, expCorners.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(expCorners.get(i).getAsInt(), cornerArgmaxIdx[i]);
        }
        assertEquals(sample0.get("mask_area").getAsInt(), maskArea);
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

    private static int[] computeCornerArgmaxIdx(float[][][][] cornerHeatmaps) {
        int[] out = new int[4];
        for (int c = 0; c < 4; c++) {
            float best = -Float.MAX_VALUE;
            int bestIdx = 0;
            float[][] hm = cornerHeatmaps[0][c];
            for (int y = 0; y < DocQuadOrtRunner.OUT_H; y++) {
                for (int x = 0; x < DocQuadOrtRunner.OUT_W; x++) {
                    float v = hm[y][x];
                    if (v > best) {
                        best = v;
                        bestIdx = y * DocQuadOrtRunner.OUT_W + x;
                    }
                }
            }
            out[c] = bestIdx;
        }
        return out;
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
