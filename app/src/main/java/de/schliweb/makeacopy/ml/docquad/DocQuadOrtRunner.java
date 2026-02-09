package de.schliweb.makeacopy.ml.docquad;

import ai.onnxruntime.*;
import android.content.Context;
import android.util.Log;
import de.schliweb.makeacopy.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The {@code DocQuadOrtRunner} class provides functionality for running inference
 * on an ONNX model that is expected to process document quad detection tasks.
 * It uses ONNX Runtime for performing the inference and handles input validation,
 * output extraction, and shapes validation for specific outputs.
 * <p>
 * This class is designed to work with ONNX models that require input tensors
 * with dimensions [1, 3, 256, 256] and produce specific outputs with known
 * shapes. It is optimized for use with Android applications by supporting
 * model loading from assets or byte arrays.
 * <p>
 * For performance optimization, this class provides:
 * <ul>
 *   <li>Singleton access via {@link #getInstance(Context, String)} to avoid repeated model loading</li>
 *   <li>Asynchronous loading via {@link #getInstanceAsync(Context, String, Executor)} for non-blocking initialization</li>
 * </ul>
 */
public final class DocQuadOrtRunner implements AutoCloseable {

    private static final String TAG = "DocQuadOrtRunner";

    public static final int IN_H = 256;
    public static final int IN_W = 256;
    public static final int OUT_H = 64;
    public static final int OUT_W = 64;

    private static volatile DocQuadOrtRunner instance;
    private static final Object LOCK = new Object();
    private static final Executor DEFAULT_EXECUTOR = Executors.newSingleThreadExecutor();

    private final OrtEnvironment env;
    private final OrtSession session;

    public DocQuadOrtRunner(Context context, String modelAssetPath) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        byte[] modelBytes = readAssetFully(context, modelAssetPath);

        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            // Try NNAPI, then XNNPACK (both optional, fall back to CPU)
            try {
                opts.addNnapi();
                Log.i(TAG, "NNAPI EP enabled");
            } catch (Throwable t) {
                Log.i(TAG, "NNAPI not available: " + t.getMessage());
            }
            try {
                opts.addXnnpack(java.util.Collections.emptyMap());
                Log.i(TAG, "XNNPACK EP enabled");
            } catch (Throwable t) {
                Log.i(TAG, "XNNPACK not available: " + t.getMessage());
            }
            this.session = env.createSession(modelBytes, opts);
        }
    }

    /**
     * Alternative construction from an already loaded ONNX byte array.
     * <p>
     * Used for debug/telemetry when the model is not available as an APK asset.
     */
    public DocQuadOrtRunner(byte[] modelBytes) throws Exception {
        if (modelBytes == null || modelBytes.length == 0) {
            throw new IllegalArgumentException("modelBytes must not be null/empty");
        }
        this.env = OrtEnvironment.getEnvironment();

        try (OrtSession.SessionOptions opts = new OrtSession.SessionOptions()) {
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            // Try NNAPI, then XNNPACK (both optional, fall back to CPU)
            try {
                opts.addNnapi();
                Log.i(TAG, "NNAPI EP enabled");
            } catch (Throwable t) {
                Log.i(TAG, "NNAPI not available: " + t.getMessage());
            }
            try {
                opts.addXnnpack(java.util.Collections.emptyMap());
                Log.i(TAG, "XNNPACK EP enabled");
            } catch (Throwable t) {
                Log.i(TAG, "XNNPACK not available: " + t.getMessage());
            }
            this.session = env.createSession(modelBytes, opts);
        }
    }

    /**
     * Returns a singleton instance of {@code DocQuadOrtRunner}, creating it if necessary.
     * This method is thread-safe and ensures the model is loaded only once.
     * <p>
     * Use this method when you need to reuse the same model instance across multiple
     * inference calls to avoid the overhead of repeated model loading.
     *
     * @param context        the Android context for accessing assets
     * @param modelAssetPath the path to the ONNX model file in assets
     * @return the singleton instance
     * @throws Exception if model loading fails
     */
    public static DocQuadOrtRunner getInstance(Context context, String modelAssetPath) throws Exception {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new DocQuadOrtRunner(context, modelAssetPath);
                }
            }
        }
        return instance;
    }

    /**
     * Asynchronously returns a singleton instance of {@code DocQuadOrtRunner}.
     * Uses the default single-thread executor for background loading.
     * <p>
     * This method is useful for non-blocking initialization, e.g., during app startup
     * or when entering a camera fragment.
     *
     * @param context        the Android context for accessing assets
     * @param modelAssetPath the path to the ONNX model file in assets
     * @return a CompletableFuture that completes with the singleton instance
     */
    public static CompletableFuture<DocQuadOrtRunner> getInstanceAsync(Context context, String modelAssetPath) {
        return getInstanceAsync(context, modelAssetPath, DEFAULT_EXECUTOR);
    }

    /**
     * Asynchronously returns a singleton instance of {@code DocQuadOrtRunner}
     * using the provided executor.
     *
     * @param context        the Android context for accessing assets
     * @param modelAssetPath the path to the ONNX model file in assets
     * @param executor       the executor to use for background loading
     * @return a CompletableFuture that completes with the singleton instance
     */
    public static CompletableFuture<DocQuadOrtRunner> getInstanceAsync(Context context, String modelAssetPath, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return getInstance(context, modelAssetPath);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    /**
     * Checks if the singleton instance is already loaded.
     *
     * @return true if the instance is loaded and ready for inference
     */
    public static boolean isInstanceLoaded() {
        return instance != null;
    }

    /**
     * Releases the singleton instance and closes the underlying ONNX session.
     * After calling this method, the next call to {@link #getInstance(Context, String)}
     * will create a new instance.
     * <p>
     * This method is useful for releasing resources when the model is no longer needed,
     * e.g., when the app goes to background or memory is low.
     */
    public static void releaseInstance() {
        synchronized (LOCK) {
            if (instance != null) {
                try {
                    instance.close();
                } catch (Exception e) {
                    Log.w(TAG, "Error closing instance: " + e.getMessage());
                }
                instance = null;
            }
        }
    }

    public Outputs run(float[] inputNchw) throws Exception {
        if (inputNchw == null || inputNchw.length != 3 * IN_H * IN_W) {
            throw new IllegalArgumentException("inputNchw must have length " + (3 * IN_H * IN_W));
        }

        long[] inputShape = new long[]{1, 3, IN_H, IN_W};
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputNchw), inputShape);
             OrtSession.Result results = session.run(Collections.singletonMap("input", input))) {

            // Always read outputs by name (robust against output order).
            float[][][][] maskLogits = getRequiredFloat4d(results, "mask_logits");
            float[][][][] cornerHeatmaps = getRequiredFloat4d(results, "corner_heatmaps");

            // Optional: fast shape assertion in debug/test builds.
            if (BuildConfig.DEBUG) {
                assertShapeMask(maskLogits);
                assertShapeCorners(cornerHeatmaps);
            }
            return new Outputs(maskLogits, cornerHeatmaps);
        }
    }

    @Override
    public void close() throws Exception {
        session.close();
        // OrtEnvironment is global/shared; not closed.
    }

    private static byte[] readAssetFully(Context context, String assetPath) throws IOException {
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

    private static float[][][][] getRequiredFloat4d(OrtSession.Result results, String outputName) throws OrtException {
        Optional<OnnxValue> ov = results.get(outputName);
        if (ov.isEmpty()) {
            throw new IllegalStateException(buildMissingOutputMessage(outputName, results));
        }

        Object v = ov.get().getValue();
        if (!(v instanceof float[][][][])) {
            throw new IllegalStateException(
                    "Output '" + outputName + "' has unexpected Java type: " +
                            (v == null ? "null" : v.getClass().getName()));
        }
        return (float[][][][]) v;
    }

    private static String buildMissingOutputMessage(String missingName, OrtSession.Result results) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, OnnxValue> e : results) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey());
        }
        return "ONNX output missing: '" + missingName + "'. Available outputs: [" + sb + "]";
    }

    private static void assertShapeMask(float[][][][] maskLogits) {
        if (maskLogits == null
                || maskLogits.length != 1
                || maskLogits[0] == null
                || maskLogits[0].length != 1
                || maskLogits[0][0] == null
                || maskLogits[0][0].length != OUT_H
                || maskLogits[0][0][0] == null
                || maskLogits[0][0][0].length != OUT_W) {
            throw new IllegalStateException("mask_logits has unexpected shape (expected [1][1][64][64])");
        }
        for (int y = 0; y < OUT_H; y++) {
            if (maskLogits[0][0][y] == null || maskLogits[0][0][y].length != OUT_W) {
                throw new IllegalStateException("mask_logits has unexpected shape (expected [1][1][64][64])");
            }
        }
    }

    private static void assertShapeCorners(float[][][][] cornerHeatmaps) {
        if (cornerHeatmaps == null
                || cornerHeatmaps.length != 1
                || cornerHeatmaps[0] == null
                || cornerHeatmaps[0].length != 4) {
            throw new IllegalStateException("corner_heatmaps has unexpected shape (expected [1][4][64][64])");
        }
        for (int c = 0; c < 4; c++) {
            if (cornerHeatmaps[0][c] == null
                    || cornerHeatmaps[0][c].length != OUT_H
                    || cornerHeatmaps[0][c][0] == null
                    || cornerHeatmaps[0][c][0].length != OUT_W) {
                throw new IllegalStateException("corner_heatmaps has unexpected shape (expected [1][4][64][64])");
            }
            for (int y = 0; y < OUT_H; y++) {
                if (cornerHeatmaps[0][c][y] == null || cornerHeatmaps[0][c][y].length != OUT_W) {
                    throw new IllegalStateException("corner_heatmaps has unexpected shape (expected [1][4][64][64])");
                }
            }
        }
    }

    /**
     * @param maskLogits     [1][1][64][64]
     * @param cornerHeatmaps [1][4][64][64]
     */
    public record Outputs(float[][][][] maskLogits, float[][][][] cornerHeatmaps) {
    }
}
