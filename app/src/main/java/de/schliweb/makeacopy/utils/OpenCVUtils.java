package de.schliweb.makeacopy.utils;

import ai.onnxruntime.*;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.util.Log;
import lombok.Getter;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.*;

/**
 * Utility class for performing various operations with OpenCV and ONNX runtime.
 * This class provides methods for initializing OpenCV, configuring safe mode,
 * manipulating images, and interacting with ONNX runtime models.
 * <p>
 * This class cannot be instantiated.
 */
public class OpenCVUtils {
    private static final String TAG = "OpenCVUtils";

    @Getter
    private static boolean isInitialized = false;

    private static boolean USE_SAFE_MODE = true;
    private static boolean USE_ADAPTIVE_THRESHOLD = false;
    private static final boolean USE_DEBUG_IMAGES = false;

    // ONNX model settings
    private static final String MODEL_ASSET_PATH = "docaligner/fastvit_t8_h_e_bifpn_256_fp32.onnx";
    private static volatile OrtEnvironment ortEnv;
    private static volatile OrtSession ortSession;

    // ---- thresholds (tuned) ----
    private static final double AREA_FRAC_MIN_ONNX = 0.008; // 0.8% der Bildfläche statt 5%
    private static final double SIDE_FRAC_MIN = 0.010; // 1% der kurzen Bildkante statt 2%
    private static final double CONF_MIN_AREA_FRAC = 0.008; // gleiche Untergrenze für die Confidence


    private OpenCVUtils() {
        // Utility class, no instances allowed
    }

    private static boolean isSafeMode() {
        return USE_SAFE_MODE;
    }

    /**
     * Initializes OpenCV by loading the native library.
     * This method should be called before using any OpenCV functionality.
     *
     * @param context The application context.
     * @return true if OpenCV was initialized successfully, false otherwise.
     */
    public static boolean init(Context context) {
        if (isInitialized) return true;

        try {
            System.loadLibrary("opencv_java4");
            Log.i(TAG, "OpenCV loaded manually via System.loadLibrary");
            configureSafeMode();
            initOnnxRuntime(context);
            isInitialized = true;
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV init error", t);
        }

        return isInitialized;
    }

    /**
     * Initializes the ONNX runtime for inference.
     * This method loads the ONNX model from the assets directory and creates an inference session.
     *
     * @param context The application context.
     */
    private static volatile String onnxInputName;

    /**
     * Initializes the ONNX runtime environment and loads the ONNX model for inference.
     * This method ensures that the ONNX runtime is properly set up, enabling optional
     * accelerations such as NNAPI and XNNPACK when available. The model is copied from
     * the application assets to a cache location and loaded with optimization options.
     *
     * @param context the application context used for accessing resources and cache directories
     */
    private static void initOnnxRuntime(Context context) {
        if (ortSession != null) return;
        Log.i(TAG, "Initializing ONNX runtime");

        try {
            File modelFile = copyAssetToCache(context, MODEL_ASSET_PATH);
            if (ortEnv == null) {
                synchronized (OpenCVUtils.class) {
                    if (ortEnv == null) {
                        ortEnv = OrtEnvironment.getEnvironment();
                    }
                }
            }
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

                ortSession = ortEnv.createSession(modelFile.getAbsolutePath(), opts);
                onnxInputName = ortSession.getInputNames().iterator().next();
            }
            Log.i(TAG, "ONNX model loaded from " + modelFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ONNX model", e);
        }
    }


    /**
     * Copies an asset from the app's assets folder to the application's cache directory.
     * If the asset already exists in the cache, it will not be copied again.
     *
     * @param context   the application context used to access the assets and cache directory
     * @param assetPath the path of the asset file to be copied, relative to the assets directory
     * @return a File object pointing to the copied asset in the cache directory
     * @throws IOException if an I/O error occurs during file copy
     */
    private static File copyAssetToCache(Context context, String assetPath) throws IOException {
        Log.i(TAG, "Copying asset " + assetPath + " to cache");
        AssetManager am = context.getAssets();
        File outFile = new File(context.getCacheDir(), new File(assetPath).getName());
        if (!outFile.exists()) {
            Log.i(TAG, "Asset " + assetPath + " not found in cache, copying...");
            try (InputStream is = am.open(assetPath);
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                }
            }
        } else {
            Log.i(TAG, "Asset " + assetPath + " already exists in cache");
        }
        return outFile;
    }

    /**
     * Configures the safe mode and adaptive threshold settings based on the device's specifications and characteristics.
     * <p>
     * This method evaluates the device manufacturer, model, device name, and Android SDK version
     * to determine whether the device is classified as high-end or an emulator. Using this evaluation,
     * it configures the `USE_SAFE_MODE` and `USE_ADAPTIVE_THRESHOLD` flags accordingly.
     * <p>
     * Conditions for classifying a device as high-end include:
     * - SDK version 29 or higher.
     * - The manufacturer does not contain "mediatek" or "spreadtrum".
     * - The device name does not contain "generic".
     * - The model does not contain "emulator" or "x86"/"x86_64".
     * - The manufacturer is associated with reputable brands like Google, Samsung, or Xiaomi.
     * <p>
     * Conditions for identifying a device as an emulator include:
     * - The device name contains "emu", "x86", or "x86_64".
     * - The model contains "sdk", "emulator", or "virtual".
     * - The manufacturer contains "genymotion".
     * <p>
     * Based on the classification:
     * - `USE_SAFE_MODE` is enabled if the device is not high-end or is identified as an emulator.
     * - `USE_ADAPTIVE_THRESHOLD` is enabled only if the device is high-end.
     * <p>
     * The method logs the safe mode and adaptive threshold configurations for debugging purposes.
     */
    private static void configureSafeMode() {
        String manufacturer = Build.MANUFACTURER.toLowerCase();
        String model = Build.MODEL.toLowerCase();
        String device = Build.DEVICE.toLowerCase();
        int sdk = Build.VERSION.SDK_INT;

        boolean isHighEnd = sdk >= 29 && !manufacturer.contains("mediatek") && !manufacturer.contains("spreadtrum") && !device.contains("generic") && !model.contains("emulator") && !device.contains("x86") && !device.contains("x86_64") && (manufacturer.contains("google") || manufacturer.contains("samsung") || manufacturer.contains("xiaomi"));
        boolean isEmulator = device.contains("emu") || model.contains("sdk") || model.contains("emulator") || model.contains("virtual") || manufacturer.contains("genymotion") || model.contains("generator");

        USE_SAFE_MODE = !isHighEnd || isEmulator;
        USE_ADAPTIVE_THRESHOLD = isHighEnd;

        Log.i(TAG, "Safe mode = " + USE_SAFE_MODE + ", AdaptiveThreshold = " + USE_ADAPTIVE_THRESHOLD);
        try {
            if (USE_SAFE_MODE) {
                // Disable aggressive SIMD/parallel optimizations that may use unsupported instructions on some CPUs
                org.opencv.core.Core.setUseOptimized(false);
                org.opencv.core.Core.setNumThreads(1);
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * Applies a perspective transformation to the given input matrix (image) using the specified source points
     * and maps it to a target size, ensuring the resulting perspective transformation fits within the target dimensions.
     * The function ensures safe handling of invalid inputs and cleans up intermediate resources.
     *
     * @param input      The input image represented as a {@code Mat} object. Must not be null or empty.
     * @param srcPoints  An array of four {@code Point} objects specifying the source quadrilateral
     *                   to be transformed. Must not be null and must contain exactly four points.
     * @param targetSize The target size for the output image, represented as a {@code Size} object.
     *                   Specifies the dimensions (width and height) of the transformed image.
     * @return A new {@code Mat} object containing the transformed (warped) image. If an error occurs
     * or invalid input is provided, the original input image is returned.
     */
    private static Mat warpPerspectiveSafe(Mat input, Point[] srcPoints, Size targetSize) {
        if (input == null || input.empty() || srcPoints == null || srcPoints.length != 4) {
            Log.e(TAG, "Invalid input or source points");
            return input;
        }

        Mat srcMat = new Mat(4, 1, CvType.CV_32FC2);
        Mat dstMat = new Mat(4, 1, CvType.CV_32FC2);
        Mat transform = new Mat();
        Mat output = new Mat();
        try {
            Point[] dstPoints = new Point[]{
                    new Point(0, 0),
                    new Point(targetSize.width - 1, 0),
                    new Point(targetSize.width - 1, targetSize.height - 1),
                    new Point(0, targetSize.height - 1)
            };

            for (int i = 0; i < 4; i++) {
                srcMat.put(i, 0, srcPoints[i].x, srcPoints[i].y);
                dstMat.put(i, 0, dstPoints[i].x, dstPoints[i].y);
            }

            transform = Imgproc.getPerspectiveTransform(srcMat, dstMat);
            Imgproc.warpPerspective(input, output, transform, targetSize);
            return output;
        } catch (Throwable t) {
            Log.e(TAG, "warpPerspective failed", t);
            release(output);
            return input;
        } finally {
            release(srcMat, dstMat, transform);
        }
    }

    /**
     * Applies a perspective correction to the given bitmap based on the specified corner points.
     * This method attempts to correct the image's perspective distortion by warping it to a target
     * size while maintaining the aspect ratio of the selected area defined by the corners.
     * The implementation uses OpenCV's warpPerspective if available and falls back
     * to Android's Matrix-based transformation if in safe mode.
     *
     * @param originalBitmap The input bitmap to which the perspective correction will be applied.
     *                       This cannot be null.
     * @param corners        An array of four points that represent the corners of the area to be corrected.
     *                       These points must be in the order: top-left, top-right, bottom-right, bottom-left.
     *                       The array must have exactly four points; otherwise, the original bitmap will be returned.
     * @return A new bitmap with the perspective correction applied. If an error occurs or the parameters
     * are invalid, the original bitmap is returned unmodified.
     */
    public static Bitmap applyPerspectiveCorrection(Bitmap originalBitmap, Point[] corners) {
        if (corners == null || corners.length != 4) return originalBitmap;
        Mat mat = new Mat();
        try {
            Utils.bitmapToMat(originalBitmap, mat);
            // Compute a tight target size based on the selection to preserve aspect ratio of the cropped area
            Size targetSize = computeWarpTargetSize(corners);
            if (!isSafeMode()) {
                Log.d(TAG, "Using OpenCV warpPerspective");
                Mat warped = warpPerspectiveSafe(mat, corners, targetSize);
                try {
                    Bitmap output = Bitmap.createBitmap((int) targetSize.width, (int) targetSize.height, Bitmap.Config.ARGB_8888);
                    Utils.matToBitmap(warped, output);
                    return output;
                } finally {
                    release(warped);
                }
            } else {
                Log.d(TAG, "Using Android Matrix warp fallback");
                return warpPerspectiveWithMatrix(originalBitmap, corners, targetSize);
            }
        } finally {
            release(mat);
        }
    }

    /**
     * Applies a perspective warp transformation to a given bitmap using specified corner points
     * and produces a new bitmap with the target dimensions.
     *
     * @param srcBitmap  the source bitmap to be transformed.
     * @param corners    an array of four {@link Point} objects representing the corner points of the region
     *                   in the source bitmap to be warped. The points should be in the order:
     *                   top-left, top-right, bottom-right, bottom-left.
     * @param targetSize the dimensions of the output bitmap, specified as a {@link Size} object.
     * @return a new {@link Bitmap} object containing the perspective-warped image with the specified dimensions.
     * If the corners array is null or does not contain exactly four points, the source bitmap is returned as-is.
     */
    private static Bitmap warpPerspectiveWithMatrix(Bitmap srcBitmap, Point[] corners, Size targetSize) {
        if (corners == null || corners.length != 4) return srcBitmap;

        int width = Math.max(1, (int) Math.round(targetSize.width));
        int height = Math.max(1, (int) Math.round(targetSize.height));

        float[] src = new float[]{(float) corners[0].x, (float) corners[0].y, (float) corners[1].x, (float) corners[1].y, (float) corners[2].x, (float) corners[2].y, (float) corners[3].x, (float) corners[3].y};

        float[] dst = new float[]{0, 0, width, 0, width, height, 0, height};

        Matrix matrix = new Matrix();
        matrix.setPolyToPoly(src, 0, dst, 0, 4);

        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        canvas.drawBitmap(srcBitmap, matrix, paint);
        return output;
    }


    // ---------- ONNX utilities ----------

    public static float[] fromBitmapBGR(Bitmap bitmap) {
        if (bitmap == null) throw new IllegalArgumentException("bitmap is null");
        Mat rgba = new Mat();
        Mat bgr = new Mat();
        try {
            Utils.bitmapToMat(bitmap, rgba);

            // Low-light probe
            Mat grayProbe = new Mat();
            Imgproc.cvtColor(rgba, grayProbe, Imgproc.COLOR_RGBA2GRAY);
            boolean low = isLowLight(grayProbe);
            grayProbe.release();
            if (low) preprocessLowLight(rgba);

            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);

            // Letterbox to 256x256 (BGR)
            Mat boxed = letterboxBGR(bgr, 256);

            Mat floatImage = new Mat();
            boxed.convertTo(floatImage, CvType.CV_32FC3, 1.0 / 255.0);

            List<Mat> ch = new ArrayList<>(3);
            Core.split(floatImage, ch); // B,G,R
            int H = 256, W = 256, C = 3, HW = H * W;
            float[] nchw = new float[C * HW];
            for (int c = 0; c < C; c++) {
                float[] buf = new float[HW];
                ch.get(c).get(0, 0, buf);
                System.arraycopy(buf, 0, nchw, c * HW, HW);
            }
            for (Mat cMat : ch) {
                if (cMat != null) cMat.release();
            }
            floatImage.release();
            boxed.release();
            return nchw;
        } finally {
            bgr.release();
            rgba.release();
        }
    }

    /**
     * Resizes the given source BGR image while preserving its aspect ratio
     * and places it onto a square canvas of the specified target size,
     * padding the remaining area with zeros (black).
     *
     * @param srcBGR The source image in BGR format to be resized and padded.
     * @param target The size of the target square canvas (in pixels).
     * @return A new Mat object representing the resized source image centered
     * on a square canvas of the specified target size with padding.
     */
    private static Mat letterboxBGR(Mat srcBGR, int target) {
        int w = srcBGR.width(), h = srcBGR.height();
        double s = Math.min(target / (double) w, target / (double) h);
        int nw = (int) Math.round(w * s), nh = (int) Math.round(h * s);

        Mat resized = new Mat();
        Imgproc.resize(srcBGR, resized, new Size(nw, nh), 0, 0, Imgproc.INTER_AREA);

        Mat canvas = Mat.zeros(target, target, srcBGR.type());
        int x = (target - nw) / 2, y = (target - nh) / 2;
        resized.copyTo(canvas.submat(new Rect(x, y, nw, nh)));
        resized.release();
        return canvas; // BGR 256x256, mit Padding
    }

    /**
     * Converts a given color {@link Bitmap} image to a grayscale {@link Bitmap}.
     *
     * @param src the source {@link Bitmap} to be converted; must be non-null and not recycled
     * @return a new {@link Bitmap} object in grayscale, or null if the conversion fails
     */
    public static Bitmap toGray(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        Mat rgba = new Mat();
        Mat gray = new Mat();
        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(gray, out);
            return out;
        } catch (Throwable t) {
            Log.d(TAG, "toGray failed: " + t.getMessage());
            return null;
        } finally {
            release(rgba, gray);
        }
    }

    /**
     * Configuration options for black-and-white image processing.
     */
    public static class BwOptions {
        public enum Mode {AUTO_ADAPTIVE, OTSU_ONLY}

        public Mode mode = Mode.AUTO_ADAPTIVE;
        public boolean useClahe = true;
        public boolean removeShadows = true;
        /**
         * Adaptive window (odd). 0 = auto
         */
        public int blockSize = 0;
        /**
         * Offset for adaptiveThreshold (typ. 5–10)
         */
        public int C = 5;
    }


    /**
     * Robust B/W conversion with shadow handling.
     * Emulator: adaptiveThreshold is disabled (avoid SIGILL).
     * Real devices: gentle adaptive variant (MEAN + higher C).
     */
    public static Bitmap toBw(Bitmap src, BwOptions opt) {
        if (src == null || src.isRecycled()) return null;
        if (opt == null) opt = new BwOptions();

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat work = new Mat();
        Mat bw = new Mat();
        CLAHE clahe = null;

        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

            // --- 1) Shadow correction: gentle division-based normalization ---
            if (opt.removeShadows && !isSafeMode()) {
                int k = Math.max(15, (int) (Math.min(gray.width(), gray.height()) * 0.03));
                if (k % 2 == 0) k++;
                Mat bg = new Mat();
                Imgproc.GaussianBlur(gray, bg, new Size(k, k), 0);

                // Use floating-point arithmetic to avoid quantization artifacts
                Mat gf = new Mat(), bgf = new Mat(), norm = new Mat();
                gray.convertTo(gf, CvType.CV_32F);
                bg.convertTo(bgf, CvType.CV_32F);
                Core.max(bgf, new Scalar(1.0), bgf);          // Prevent division by 0
                Core.divide(gf, bgf, norm);                   // ~0..1
                Core.multiply(norm, new Scalar(255.0), norm); // ~0..255
                norm.convertTo(work, CvType.CV_8U);

                bg.release();
                gf.release();
                bgf.release();
                norm.release();
            } else {
                work = gray;
            }

            // --- 2) very gentle CLAHE (or leave opt.useClahe=false) ---
            if (opt.useClahe) {
                clahe = Imgproc.createCLAHE();
                clahe.setClipLimit(1.1);
                clahe.setTilesGridSize(new Size(8, 8));
                clahe.apply(work, work);
            }

            // --- 3) slight smoothing against pepper noise ---
            Imgproc.medianBlur(work, work, 3);

            boolean ok = false;

            // --- 4) Adaptive only on real devices (Emulator => Otsu) and less aggressive ---
            if (opt.mode == BwOptions.Mode.AUTO_ADAPTIVE && !isSafeMode()) {
                int bs;
                if (opt.blockSize > 0) {
                    bs = (opt.blockSize % 2 == 1) ? opt.blockSize : opt.blockSize + 1;
                } else {
                    // moderately large, guaranteed odd
                    bs = Math.max(41, (Math.min(work.width(), work.height()) / 40) | 1);
                    if (bs % 2 == 0) bs++;
                }
                int C = Math.max(2, Math.min(6, opt.C)); // smaller C reduces bleaching/fading

                try {
                    Imgproc.adaptiveThreshold(
                            work, bw, 255,
                            Imgproc.ADAPTIVE_THRESH_MEAN_C,
                            Imgproc.THRESH_BINARY,
                            bs, C
                    );
                    ok = true;
                } catch (Throwable ignore) {
                    ok = false;
                }
            }

            despeckleFast(bw);

            // --- 5) Fallback: Otsu (emulator or error) ---
            if (!ok) {
                Imgproc.threshold(work, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            }

            // --- 6) very gentle closing stabilizes characters ---
            try {
                Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
                Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, kernel);
                kernel.release();
            } catch (Throwable ignore) { /* optional */ }

            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(bw, out);
            return out;

        } catch (Throwable t) {
            Log.d(TAG, "toBw (robust) failed: " + t.getMessage());
            try {
                Mat tmpGray = new Mat(), tmpBw = new Mat();
                Utils.bitmapToMat(src, rgba);
                Imgproc.cvtColor(rgba, tmpGray, Imgproc.COLOR_RGBA2GRAY);
                Imgproc.threshold(tmpGray, tmpBw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
                Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(tmpBw, out);
                tmpGray.release();
                tmpBw.release();
                return out;
            } catch (Throwable t2) {
                Log.d(TAG, "toBw fallback failed: " + t2.getMessage());
                return null;
            }
        } finally {
            release(rgba, bw);
            if (work != gray) release(work);
            release(gray);
            if (clahe != null) {
                try {
                    clahe.collectGarbage();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Removes small speckles from a binary image using morphological operations.
     * The function processes the input binary image to eliminate noise or small artifacts,
     * leaving the major structures intact.
     *
     * @param bw Input binary image of type Mat (CV_8UC1), with pixel values of 0 or 255.
     *           It will be modified in-place to remove speckles.
     */
    private static void despeckleFast(Mat bw /* CV_8UC1, 0/255 */) {
        Mat inv = new Mat();
        Mat k3 = null;
        try {
            // Make text and speckles white so the opening operation removes them
            Core.bitwise_not(bw, inv);
            k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
            Imgproc.morphologyEx(inv, inv, Imgproc.MORPH_OPEN, k3);
            Core.bitwise_not(inv, bw);
        } finally {
            inv.release();
            if (k3 != null) k3.release();
        }
    }


    /**
     * Converts a given Bitmap image to a black-and-white (grayscale) representation
     * using default options.
     *
     * @param src the source Bitmap to be converted to black-and-white
     * @return a new Bitmap representing the black-and-white version of the source image
     */
    public static Bitmap toBw(Bitmap src) {
        return toBw(src, new BwOptions());
    }

    /**
     * Executes inference on the provided input tensor using the ONNX runtime.
     * The input tensor is expected to be in BGR format and NCHW order.
     *
     * @param inputTensor A float array representing the input tensor with a shape of [1, 3, 256, 256].
     *                    The tensor must be in BGR format and NCHW layout.
     * @return A float array containing the inference output.
     * The shape and interpretation of the output depend on the specific ONNX model.
     * @throws OrtException          If an error occurs during the inference process with the ONNX runtime.
     * @throws IllegalStateException If the ONNX runtime is not initialized before calling this method.
     */
    private static float[] runInferenceBgrNchw(float[] inputTensor) throws OrtException {
        if (ortEnv == null || ortSession == null) {
            Log.e(TAG, "ONNX Runtime not initialized. Call initOnnxRuntime(context) first.");
            throw new IllegalStateException("ONNX Runtime not initialized. Call initOnnxRuntime(context) first.");
        }

        if (onnxInputName == null) {
            synchronized (OpenCVUtils.class) {
                if (onnxInputName == null) {
                    onnxInputName = ortSession.getInputNames().iterator().next();
                }
            }
        }
        String inputName = onnxInputName;
        long[] shape = new long[]{1, 3, 256, 256};

        long start = System.nanoTime();
        try (OnnxTensor input = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputTensor), shape);
             OrtSession.Result result = ortSession.run(Collections.singletonMap(inputName, input))) {

            long elapsedNs = System.nanoTime() - start;
            double elapsedMs = elapsedNs / 1_000_000.0;
            Log.i(TAG, String.format("Elapsed: %.3f ms", elapsedMs));

            OnnxValue out0 = result.get(0);
            if (!(out0 instanceof OnnxTensor ot)) {
                throw new RuntimeException("Unexpected output type: " + out0.getClass());
            }
            long[] outShape = ot.getInfo().getShape();
            Log.i(TAG, "ONNX output shape=" + Arrays.toString(outShape));

            FloatBuffer fb = ot.getFloatBuffer();
            float[] pred = new float[fb.remaining()];
            fb.get(pred);

            // Debug: show a few values
            if (pred.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(8, pred.length); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(pred[i]);
                }
                Log.i(TAG, "ONNX raw pred[0..7]=" + sb);
            }
            return pred;
        }
    }

    /**
     * Detects and processes a model inference based on the input bitmap image.
     *
     * @param bitmap the input image in the form of a Bitmap, to be processed for model inference
     * @return a float array representing the results of the model inference
     * @throws OrtException if an error occurs during the inference process
     */
    private static float[] detectModel(Bitmap bitmap) throws OrtException {
        float[] inputTensor = fromBitmapBGR(bitmap);
        return runInferenceBgrNchw(inputTensor);
    }

    /**
     * Detects the corners of a document from the given bitmap image using an ONNX model.
     *
     * @param bitmap The input bitmap image containing the document to process.
     * @return An array of Points representing the corners of the detected document or null
     * if the detection fails or returns invalid results.
     */
    private static Point[] detectDocumentCornersWithOnnx(Bitmap bitmap) {
        Log.i(TAG, "Starting detectDocumentCornersWithOnnx()");
        try {
            float[] pred = detectModel(bitmap);
            Point[] pts = parsePrediction(pred, bitmap.getWidth(), bitmap.getHeight());
            if (pts != null) {
                Log.i(TAG, "ONNX corners OK: area=" + quadArea(pts) + ", corners=" + Arrays.toString(pts));
            } else {
                Log.w(TAG, "ONNX corners invalid → null");
            }
            return pts;
        } catch (Exception e) {
            Log.e(TAG, "ONNX inference failed", e);
            return null;
        }
    }

    /**
     * Determines if the provided points form a fallback condition based on specific coordinates.
     *
     * @param p an array of four {@code Point} objects to be evaluated
     * @param w the width to be considered in the condition
     * @param h the height to be considered in the condition
     * @return {@code true} if the points satisfy the fallback condition; {@code false} otherwise
     */
    private static boolean isFallback(Point[] p, int w, int h) {
        if (p == null || p.length != 4) return false;
        return (Math.round(p[0].x) == 100 && Math.round(p[0].y) == 100) &&
                (Math.round(p[1].x) == w - 100 && Math.round(p[1].y) == 100) &&
                (Math.round(p[2].x) == w - 100 && Math.round(p[2].y) == h - 100) &&
                (Math.round(p[3].x) == 100 && Math.round(p[3].y) == h - 100);
    }

    /**
     * Parses the prediction output array into an array of points that represent coordinates.
     *
     * @param pred The prediction array, which could contain either 8 values for 4 points
     *             or be a flattened grid of values representing 128x128 spatial resolution.
     * @param outW The width of the output space used for scaling the prediction coordinates.
     * @param outH The height of the output space used for scaling the prediction coordinates.
     * @return An array of Points representing the parsed coordinates, or null if the input
     * array is null or unsupported in length.
     */
    private static Point[] parsePrediction(float[] pred, int outW, int outH) {
        if (pred == null) return null;
        Log.i(TAG, "Pred len=" + pred.length + " → " +
                (pred.length == 4 * 128 * 128 ? "heatmap" : pred.length == 8 ? "coords8" : "unknown"));
        if (pred.length == 8) {
            Point[] pts = new Point[4];
            for (int i = 0; i < 4; i++) {
                double x = Math.max(0, Math.min(pred[2 * i] * outW, outW - 1));
                double y = Math.max(0, Math.min(pred[2 * i + 1] * outH, outH - 1));
                pts[i] = new Point(x, y);
            }
            return validateAndSort(pts, outW, outH);
        }

        if (pred.length == 4 * 128 * 128) {
            Point[] pts = predictionToPoints(pred, outW, outH);
            return validateAndSort(pts, outW, outH);
        }

        Log.w(TAG, "Unsupported ONNX output length=" + pred.length);
        return null;
    }

    /**
     * Converts a prediction heatmap into a set of points corresponding to specific coordinates
     * in the output image. The method performs peak detection in each channel of the heatmap,
     * applies subpixel refinement, rescales the coordinates to the target output resolution,
     * and validates the resulting quadrilateral for minimum area and side length constraints.
     *
     * @param pred The predicted heatmap values as a flattened array of size 4 * 128 * 128.
     *             Each channel corresponds to a specific corner of a quadrilateral.
     * @param outW The width of the target output image.
     * @param outH The height of the target output image.
     * @return An array of 4 points representing the refined and validated coordinates of the
     * quadrilateral corners in the output image. Returns null if the input prediction is
     * invalid, contains insufficient peak data, or produces a quadrilateral that fails
     * validation checks.
     */
    private static Point[] predictionToPoints(float[] pred, int outW, int outH) {
        if (pred == null || pred.length != 4 * 128 * 128) {
            Log.w(TAG, "predictionToPoints: unexpected pred length " + (pred == null ? -1 : pred.length));
            return null;
        }
        final int C = 4, H = 128, W = 128;
        Point[] pts = new Point[C];

        for (int c = 0; c < C; c++) {
            int base = c * H * W;

            int maxIdx = base;
            float maxVal = -Float.MAX_VALUE;
            for (int i = 0; i < H * W; i++) {
                float v = pred[base + i];
                if (v > maxVal) {
                    maxVal = v;
                    maxIdx = base + i;
                }
            }

            // dynamische Peak-Schwelle (robuster in Low-Light)
            float dynamicMin = Math.max(1e-5f, 0.15f * maxVal);
            if (maxVal < dynamicMin) {
                Log.w(TAG, "Heatmap peak too low for corner " + c + " → rejecting ONNX");
                return null;
            }

            int peak = maxIdx - base;
            int py = peak / W;
            int px = peak % W;

            // Subpixel-Refinement (quadratisch)
            Point2d sub = refinePeakQuadratic(pred, base, W, H, px, py);
            double fx = sub.x, fy = sub.y;

            Point pOrig = mapFromHeatmapToOrig(fx, fy, outW, outH);
            pts[c] = pOrig;
        }

        pts = sortPointsRobust(pts);
        double area = quadArea(pts);
        double imgArea = (double) outW * outH;

        // --- weichere Fläche ---
        if (area < AREA_FRAC_MIN_ONNX * imgArea) {
            Log.w(TAG, String.format(java.util.Locale.US,
                    "predictionToPoints: area too small (%.2f%%).",
                    100.0 * area / imgArea));
            return null;
        }

        // --- weichere Mindest-Seitenlänge ---
        final double minSide = SIDE_FRAC_MIN * Math.min(outW, outH);
        for (int i = 0; i < 4; i++) {
            Point a = pts[i], b = pts[(i + 1) % 4];
            if (Math.hypot(a.x - b.x, a.y - b.y) < minSide) {
                Log.w(TAG, "predictionToPoints: side too small.");
                return null;
            }
        }
        return pts;
    }

    /**
     * Validates a set of four points and sorts them into a consistent order if they form a valid
     * quadrilateral based on specified conditions. The method checks whether the quadrilateral's
     * area is significant compared to the image area, and whether the side lengths are above
     * a minimum threshold.
     *
     * @param pts  an array of four points representing a quadrilateral
     * @param outW the width of the output image
     * @param outH the height of the output image
     * @return a sorted array of four points if validation succeeds, or null if the points do not form
     * a valid quadrilateral under the given conditions
     */
    private static Point[] validateAndSort(Point[] pts, int outW, int outH) {
        if (pts == null || pts.length != 4) return null;
        pts = sortPointsRobust(pts);

        double area = quadArea(pts);
        double imgArea = (double) outW * outH;
        if (area < AREA_FRAC_MIN_ONNX * imgArea) return null;

        final double minSide = SIDE_FRAC_MIN * Math.min(outW, outH);
        for (int i = 0; i < 4; i++) {
            Point a = pts[i], b = pts[(i + 1) % 4];
            if (Math.hypot(a.x - b.x, a.y - b.y) < minSide) return null;
        }
        return pts;
    }


    /**
     * Calculates the area of a quadrilateral defined by four points.
     * The calculation is based on the Shoelace formula and assumes the points are ordered
     * in a consistent clockwise or counterclockwise manner.
     *
     * @param q An array of four {@link Point} objects representing the vertices of the quadrilateral.
     *          The order of the points must form a closed quadrilateral.
     * @return The area of the quadrilateral as a double value. The result is always non-negative.
     */
    private static double quadArea(Point[] q) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            Point a = q[i], b = q[(i + 1) % 4];
            area += (a.x * b.y - b.x * a.y);
        }
        return Math.abs(area) / 2.0;
    }

    /**
     * Refines the location of a peak in a 2D map using quadratic interpolation.
     * This method adjusts the peak location to sub-pixel accuracy based on the surrounding pixel values.
     *
     * @param mapAll The flattened array representing the 2D map.
     *               Values within this array are used for quadratic interpolation.
     * @param base   The starting index in the array, used as an offset for the map.
     * @param W      The width of the 2D map.
     * @param H      The height of the 2D map.
     * @param px     The x-coordinate of the initial peak location in the map.
     * @param py     The y-coordinate of the initial peak location in the map.
     * @return A Point2d object representing the refined peak location with sub-pixel adjustments.
     */
    private static Point2d refinePeakQuadratic(float[] mapAll, int base, int W, int H, int px, int py) {
        if (px <= 0 || py <= 0 || px >= W - 1 || py >= H - 1) return new Point2d(px, py);
        float l = mapAll[base + py * W + (px - 1)];
        float m = mapAll[base + py * W + px];
        float r = mapAll[base + py * W + (px + 1)];
        double denomX = (l - 2 * m + r);
        double dx = (denomX == 0) ? 0 : 0.5 * (l - r) / denomX;

        float t = mapAll[base + (py - 1) * W + px];
        float b = mapAll[base + (py + 1) * W + px];
        double denomY = (t - 2 * m + b);
        double dy = (denomY == 0) ? 0 : 0.5 * (t - b) / denomY;

        return new Point2d(px + dx, py + dy);
    }

    /**
     * Represents a point in a two-dimensional space.
     * This class encapsulates the x and y coordinates of the point.
     */
    private record Point2d(double x, double y) {
    }

    /**
     * Detects the corners of a document in a given image using OpenCV image processing techniques.
     * This method processes the input bitmap, applies multiple filters, and identifies contours to extract
     * the best quadrilateral representing a document.
     *
     * @param context The Android context used for saving debug images during processing.
     * @param bitmap  The input image in the form of a Bitmap, from which the document corners are to be detected.
     * @return An array of Points representing the four corners of the detected document. If no suitable document
     * corners are detected, a fallback rectangle is returned.
     */
    private static Point[] detectDocumentCornersWithOpenCV(Context context, Bitmap bitmap) {
        Log.i(TAG, "Starting detectDocumentCornersWithOpenCV()");

        Mat rgba = new Mat();
        Mat gray = new Mat();
        Mat threshold = new Mat();
        Mat morph = new Mat();
        Mat kernel = new Mat();
        Mat edges = new Mat();
        Mat edgesCopy = new Mat();
        Mat hierarchy = new Mat();
        Mat debug = new Mat();
        List<MatOfPoint> contours = new ArrayList<>();

        try {
            Utils.bitmapToMat(bitmap, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

            Imgproc.threshold(gray, threshold, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            saveDebugImage(context, threshold, "debug_threshold.png");

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(15, 15));
            Imgproc.morphologyEx(threshold, morph, Imgproc.MORPH_CLOSE, kernel);
            saveDebugImage(context, morph, "debug_morph.png");

            Imgproc.Canny(morph, edges, 50, 150);

            // Always compute adaptive edges from the (pre-smoothed) grayscale image and merge them.
            Mat edgesAuto = new Mat();
            edgesAdaptive(gray, edgesAuto);
            Core.max(edges, edgesAuto, edges);
            edgesAuto.release();

            saveDebugImage(context, edges, "debug_edges.png");

            // Low-light addition: best-of fusion with low-light preprocessing.
            boolean low;
            {
                Mat probe = new Mat();
                Imgproc.cvtColor(rgba, probe, Imgproc.COLOR_RGBA2GRAY);
                low = isLowLight(probe);
                probe.release();
            }
            if (low) {
                Mat ll = rgba.clone();
                preprocessLowLight(ll);
                Mat llGray = new Mat();
                Imgproc.cvtColor(ll, llGray, Imgproc.COLOR_RGBA2GRAY);
                Mat edges2 = new Mat();
                edgesAdaptive(llGray, edges2);
                Mat k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
                Imgproc.dilate(edges2, edges2, k3);
                Core.max(edges, edges2, edges); // Best-of fusion
                k3.release();
                saveDebugImage(context, edges, "debug_edges_lowlight.png");
                edges2.release();
                llGray.release();
                ll.release();
            }

            edgesCopy = edges.clone();
            Imgproc.findContours(edgesCopy, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            if (USE_DEBUG_IMAGES) {
                debug = Mat.zeros(edges.size(), CvType.CV_8UC3);
                try {
                    if (contours != null && !contours.isEmpty()) {
                        int maxToDraw = Math.min(contours.size(), 256);
                        for (int i = 0; i < maxToDraw; i++) {
                            MatOfPoint c = contours.get(i);
                            if (c == null || c.empty()) continue;
                            List<MatOfPoint> one = Collections.singletonList(c);
                            Imgproc.drawContours(debug, one, 0, new Scalar(0, 255, 0), 2);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "drawContours debug rendering failed: " + t.getMessage());
                }
                saveDebugImage(context, debug, "debug_contours.png");
            }

            double imgArea = rgba.width() * rgba.height();
            double bestScore = -1;
            Point[] bestQuad = null;

            for (MatOfPoint contour : contours) {
                try {
                    double area = Imgproc.contourArea(contour);
                    if (area < imgArea * 0.08) continue; // vorher 0.20

                    MatOfPoint2f curve = new MatOfPoint2f(contour.toArray());
                    MatOfPoint2f approx = new MatOfPoint2f();
                    MatOfPoint approxAsPoints = null;
                    try {
                        // slightly finer approximation
                        Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.015, true);
                        approxAsPoints = new MatOfPoint(approx.toArray());
                        boolean isConvex = Imgproc.isContourConvex(approxAsPoints);

                        if (approx.total() == 4 && isConvex) {
                            Point[] quad = approx.toArray();
                            quad = sortPointsRobust(quad);

                            double w1 = distance(quad[0], quad[1]);
                            double w2 = distance(quad[2], quad[3]);
                            double h1 = distance(quad[1], quad[2]);
                            double h2 = distance(quad[3], quad[0]);
                            double avgWidth = (w1 + w2) / 2.0;
                            double avgHeight = (h1 + h2) / 2.0;
                            double aspectRatio = avgHeight / (avgWidth + 1e-9);

                            double areaNorm = area / imgArea;
                            double rect = rectScore(quad) / 120.0;
                            double score = 0.6 * areaNorm + 0.4 * rect;

                            if (aspectRatio > 0.5 && aspectRatio < 2.5 && score > bestScore) {
                                bestScore = score;
                                bestQuad = quad;
                            }
                        }
                    } finally {
                        release(approxAsPoints);
                        release(curve, approx);
                    }
                } finally {
                    release(contour);
                }
            }

            if (bestQuad != null) {
                Log.i(TAG, "Document contour found");
                return bestQuad;
            }
            Log.w(TAG, "No suitable document contour found (OpenCV) → returning null");
            return null;
        } finally {
            release(rgba, gray, threshold, morph, kernel, edges, edgesCopy, hierarchy, debug);
        }
    }

    /**
     * Maps a point from a 128x128 heatmap coordinate system to the original image coordinate system,
     * considering the original image dimensions and potential padding.
     *
     * @param fx   the x-coordinate in the heatmap coordinate system
     * @param fy   the y-coordinate in the heatmap coordinate system
     * @param outW the width of the original image
     * @param outH the height of the original image
     * @return a Point object representing the transformed coordinates in the original image coordinate system
     */
    private static Point mapFromHeatmapToOrig(double fx, double fy, int outW, int outH) {
        // Heatmap (128x128) -> Input(256x256)
        final int INPUT = 256, HM = 128;
        final double stride = (double) INPUT / HM; // 2.0

        // IMPORTANT: use the same rounding as in letterboxBGR()
        double s = Math.min(INPUT / (double) outW, INPUT / (double) outH);
        int nw = (int) Math.round(outW * s);
        int nh = (int) Math.round(outH * s);
        int padX = (INPUT - nw) / 2;
        int padY = (INPUT - nh) / 2;

        // Account for pixel center (+0.5)
        double x256 = ((fx + 0.5) * stride) - padX;
        double y256 = ((fy + 0.5) * stride) - padY;

        double x = x256 / s;
        double y = y256 / s;

        // clamp to image bounds
        x = Math.max(0, Math.min(x, outW - 1));
        y = Math.max(0, Math.min(y, outH - 1));
        return new Point(x, y);
    }

    /**
     * Applies adaptive edge detection on the provided grayscale image and stores the result.
     * This method uses a combination of median blur, mean and standard deviation calculations,
     * and Canny edge detection to adaptively determine the edge detection thresholds.
     *
     * @param srcGray The source image in grayscale format (Mat object).
     * @param out     The output matrix (Mat object, CV_8U) where the edges will be stored.
     */
    private static void edgesAdaptive(Mat srcGray, Mat out /* CV_8U */) {
        Mat med = new Mat();
        MatOfDouble mean = new MatOfDouble(), sd = new MatOfDouble();
        try {
            Imgproc.medianBlur(srcGray, med, 3);
            Core.meanStdDev(med, mean, sd);
            double v = Core.mean(med).val[0];
            double lower = Math.max(0, (1.0 - 0.33) * v);
            double upper = Math.min(255, (1.0 + 0.33) * v);
            Imgproc.Canny(med, out, lower, upper, 3, true);
        } finally {
            med.release();
            mean.release();
            sd.release();
        }
    }

    /**
     * Detects the corners of a document present in the given bitmap image.
     * This method uses multiple techniques internally to identify the
     * best possible corner points of the document in the image.
     *
     * @param context the Android context required for certain operations, such as OpenCV initialization
     * @param bitmap  the bitmap image within which the document's corners need to be detected
     * @return an array of Point objects representing the detected corners of the document
     */
    public static Point[] detectDocumentCorners(Context context, Bitmap bitmap) {
        Log.i(TAG, "Starting detectDocumentCorners()");
        Point[] onnx = detectDocumentCornersWithOnnx(bitmap);
        Point[] cv = detectDocumentCornersWithOpenCV(context, bitmap);
        Point[] best = getBestCorners(onnx, cv, bitmap.getWidth(), bitmap.getHeight());
        if (best == null) {
            // Only apply fallback if BOTH detectors failed
            best = getFallbackRectangle(bitmap.getWidth(), bitmap.getHeight());
        }
        return best;
    }

    /**
     * Selects the best set of corner points between two provided sets of corners,
     * one detected by ONNX and the other by OpenCV, based on confidence and robustness.
     * The fallback option is triggered if neither of the sets is valid.
     *
     * @param cornersOnnx   An array of points representing the corners detected by the ONNX model.
     *                      The array must contain exactly 4 points to be considered valid.
     * @param cornersOpenCV An array of points representing the corners detected by the OpenCV model.
     *                      The array must contain exactly 4 points to be considered valid.
     * @param w             The width of the rectangle or frame.
     * @param h             The height of the rectangle or frame.
     * @return An array of 4 points representing the selected best corners.
     * This could be from ONNX or OpenCV, or a fallback rectangle if both inputs are invalid.
     */
    private static Point[] getBestCorners(Point[] cornersOnnx, Point[] cornersOpenCV, int w, int h) {
        Log.i(TAG, "getBestCorners()");
        if ((cornersOnnx == null || cornersOnnx.length != 4) && (cornersOpenCV == null || cornersOpenCV.length != 4)) {
            Log.i(TAG, "Chosen source=none");
            return null;
        }
        if (cornersOnnx == null || cornersOnnx.length != 4) {
            Log.i(TAG, "Chosen source=OpenCV (ONNX null/invalid)");
            return sortPointsRobust(cornersOpenCV);
        }
        if (cornersOpenCV == null || cornersOpenCV.length != 4) {
            Log.i(TAG, "Chosen source=ONNX (OpenCV null/invalid)");
            return sortPointsRobust(cornersOnnx);
        }

        if (isFallback(cornersOpenCV, w, h)) {
            Log.i(TAG, "OpenCV returned fallback → choosing ONNX");
            return sortPointsRobust(cornersOnnx);
        }

        cornersOnnx = sortPointsRobust(cornersOnnx);
        cornersOpenCV = sortPointsRobust(cornersOpenCV);

        double cOnnx = quadConfidence(cornersOnnx, w, h);
        double cCv = quadConfidence(cornersOpenCV, w, h);

        if (cOnnx >= cCv) {
            Log.i(TAG, String.format(java.util.Locale.US,
                    "Chosen source=ONNX (conf %.3f vs %.3f)", cOnnx, cCv));
            return cornersOnnx;
        } else {
            Log.i(TAG, String.format(java.util.Locale.US,
                    "Chosen source=OpenCV (conf %.3f vs %.3f)", cCv, cOnnx));
            return cornersOpenCV;
        }
    }

    /**
     * Sorts an array of four points in a robust manner. The points are arranged
     * in clockwise order starting from the top-left point. The top-left point
     * is determined as the point with the smallest (x + y) value. The method
     * computes the centroid of the points and uses it to sort them by angle
     * relative to the centroid, ensuring stable ordering.
     *
     * @param src the input array of points. It must contain exactly four points.
     *            If the input is null or does not contain four points, the method
     *            will return the input array unchanged.
     * @return a new array of points sorted in clockwise order starting from the
     * top-left point, or the input array if it is null or has fewer or
     * more than four points.
     */
    private static Point[] sortPointsRobust(Point[] src) {
        if (src == null || src.length != 4) return src;

        List<Point> pts = new ArrayList<>(Arrays.asList(src));

        double cx = 0, cy = 0;
        for (Point p : pts) {
            cx += p.x;
            cy += p.y;
        }
        cx /= 4.0;
        cy /= 4.0;

        final double fx = cx, fy = cy; // <- final Kopien für Lambda

        // sortiere nach Winkel um den Schwerpunkt
        pts.sort(Comparator.comparingDouble(p -> Math.atan2(p.y - fy, p.x - fx)));

        // rotiere, damit Index 0 = top-left (min x+y)
        int start = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double s = pts.get(i).x + pts.get(i).y;
            if (s < best) {
                best = s;
                start = i;
            }
        }

        Point[] out = new Point[4];
        for (int i = 0; i < 4; i++) out[i] = pts.get((start + i) % 4);
        return out; // tl, tr, br, bl
    }


    /**
     * Calculates the rectangularity score of a quadrilateral represented by an array of points.
     * The score evaluates how close the angles of the quadrilateral are to 90 degrees.
     *
     * @param q an array of four points representing the vertices of a quadrilateral, listed in order.
     * @return the computed rectangularity score, where a higher score indicates the quadrilateral is closer to a rectangle.
     */
    private static double rectScore(Point[] q) {
        double score = 0;
        for (int i = 0; i < 4; i++) {
            Point a = q[i], b = q[(i + 1) % 4], c = q[(i + 2) % 4];
            double ang = angle(b, a, c);
            double dev = Math.abs(ang - 90.0);
            score += Math.max(0, 30.0 - dev); // max 30 pro Ecke → 120 gesamt
        }
        return score;
    }

    /**
     * Calculates the angle (in degrees) formed at point a by the line segments a-b and a-c.
     *
     * @param b the first point defining the line segment a-b
     * @param a the vertex point where the angle is measured
     * @param c the second point defining the line segment a-c
     * @return the angle in degrees between the line segments a-b and a-c
     */
    private static double angle(Point b, Point a, Point c) {
        double abx = b.x - a.x, aby = b.y - a.y;
        double acx = c.x - a.x, acy = c.y - a.y;
        double num = abx * acx + aby * acy;
        double den = Math.hypot(abx, aby) * Math.hypot(acx, acy) + 1e-9;
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, num / den))));
    }

    /**
     * Calculates the confidence score of a quadrilateral based on its area, rectangularity,
     * and symmetry relative to provided width and height values.
     *
     * @param q an array of four points representing the quadrilateral. The array must have exactly four points.
     * @param w the width of the reference boundary for calculating normalized area.
     * @param h the height of the reference boundary for calculating normalized area.
     * @return a confidence score as a double value, where the score is higher for well-shaped quadrilaterals
     * meeting the criteria of area, rectangularity, and symmetry. Returns 0 if input is invalid or
     * calculated area is below the threshold.
     */
    private static double quadConfidence(Point[] q, int w, int h) {
        if (q == null || q.length != 4) return 0;
        q = sortPointsRobust(q);
        double areaFrac = quadArea(q) / (w * (double) h);
        if (areaFrac < CONF_MIN_AREA_FRAC) return 0; // vorher 3%, jetzt 0.8%

        double rect = rectScore(q) / 120.0;
        double w1 = distance(q[0], q[1]), w2 = distance(q[2], q[3]);
        double h1 = distance(q[1], q[2]), h2 = distance(q[3], q[0]);
        double sym = 1.0 - Math.min(1.0, (Math.abs(w1 - w2) + Math.abs(h1 - h2)) / (w1 + w2 + h1 + h2) + 1e-6);

        return 0.5 * areaFrac + 0.3 * rect + 0.2 * sym;
    }

    /**
     * Generates a fallback rectangle defined by four corner points,
     * adjusted based on the input width and height. The size of the
     * rectangle is calculated to be approximately 10% away from the
     * edges of the given dimensions, with a minimum margin of 20 units.
     *
     * @param width  the width of the area within which the rectangle is
     *               to be defined
     * @param height the height of the area within which the rectangle is
     *               to be defined
     * @return an array of four {@link Point} objects representing the
     * four corners of the rectangle
     */
    private static Point[] getFallbackRectangle(int width, int height) {
        int m = Math.max(20, Math.min(width, height) / 10); // ~10% Rand
        return new Point[]{
                new Point(m, m),
                new Point(width - m, m),
                new Point(width - m, height - m),
                new Point(m, height - m)
        };
    }

    /**
     * Saves a debug image to the device's external files directory.
     * This is useful for debugging purposes to visualize intermediate steps in the image processing pipeline.
     *
     * @param context  The application context for accessing the external files directory.
     * @param mat      The Mat object containing the image to be saved.
     * @param filename The name of the file to save the image as.
     */
    private static void saveDebugImage(Context context, Mat mat, String filename) {
        if (!USE_DEBUG_IMAGES) return;
        Bitmap debugBmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        try {
            Utils.matToBitmap(mat, debugBmp);
            File file = new File(context.getExternalFilesDir(null), filename);
            try (FileOutputStream out = new FileOutputStream(file)) {
                debugBmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                Log.i(TAG, "Saved debug image: " + file.getAbsolutePath());
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to save debug image", e);
        }
    }

    /**
     * Releases the provided OpenCV Mat objects to free up memory.
     * This method is a no-op for null inputs and handles exceptions if any occur during the release process.
     *
     * @param mats An array of Mat objects to be released. Null values within the array are safely ignored.
     */
    private static void release(Mat... mats) {
        if (mats == null) return;
        for (Mat m : mats) {
            if (m != null) {
                try {
                    m.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Releases all OpenCV Mat objects in the provided list to free up memory.
     * This method safely handles null elements within the list as well as null inputs.
     * Any exceptions during the release process are caught and ignored.
     *
     * @param mats A list of Mat objects to be released. If the list is null or empty, the method does nothing.
     */
    private static void releaseAll(List<Mat> mats) {
        if (mats == null) return;
        for (Mat m : mats) {
            if (m != null) {
                try {
                    m.release();
                } catch (Throwable ignore) {
                }
            }
        }
        mats.clear();
    }

    /**
     * Enhances the visual quality of the image by applying histogram equalization
     * to the luminance channel and sharpening the overall image.
     *
     * @param bgr the input image in BGR color space. The operation modifies this
     *            image in place. Must not be null or empty.
     */
    public static void autoEnhance(Mat bgr) {
        if (bgr == null || bgr.empty()) return;
        Mat lab = new Mat();
        Mat l = new Mat();
        Mat a = new Mat();
        Mat bb = new Mat();
        try {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);
            java.util.List<Mat> chans = new java.util.ArrayList<>(3);
            Core.split(lab, chans);
            l = chans.get(0);
            a = chans.get(1);
            bb = chans.get(2);
            Imgproc.equalizeHist(l, l);
            chans.set(0, l);
            chans.set(1, a);
            chans.set(2, bb);
            Core.merge(chans, lab);
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR);
            Mat blurred = new Mat();
            try {
                Imgproc.GaussianBlur(bgr, blurred, new Size(0, 0), 1.0);
                Core.addWeighted(bgr, 1.5, blurred, -0.5, 0, bgr);
            } finally {
                blurred.release();
            }
        } finally {
            try {
                lab.release();
            } catch (Throwable ignore) {
            }
            try {
                l.release();
            } catch (Throwable ignore) {
            }
            try {
                a.release();
            } catch (Throwable ignore) {
            }
            try {
                bb.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * Computes a tight target size (width/height) for the warp based on the lengths of the
     * selected quadrilateral edges. This preserves the aspect ratio of the selected area
     * when mapping to a rectangle.
     */
    private static Size computeWarpTargetSize(Point[] corners) {
        if (corners == null || corners.length != 4) {
            return new Size(1, 1);
        }
        double wTop = distance(corners[0], corners[1]);
        double wBottom = distance(corners[2], corners[3]);
        double hLeft = distance(corners[0], corners[3]);
        double hRight = distance(corners[1], corners[2]);
        int w = Math.max(1, (int) Math.round(Math.max(wTop, wBottom)));
        int h = Math.max(1, (int) Math.round(Math.max(hLeft, hRight)));
        return new Size(w, h);
    }

    /**
     * Calculates the Euclidean distance between two points.
     *
     * @param a the first point, represented as an object of type Point
     * @param b the second point, represented as an object of type Point
     * @return the distance between the two points as a double
     */
    private static double distance(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }

    /**
     * Determines if the given grayscale image is considered to be in low light.
     * <p>
     * A histogram is computed for the image, and the median intensity value is
     * calculated. If the median intensity value is below a specific threshold,
     * the image is determined to be in low light conditions.
     *
     * @param gray the input image in grayscale format (CV_8U). This matrix (Mat)
     *             represents the intensity values of the image.
     * @return true if the median intensity value of the grayscale image indicates
     * low light conditions; false otherwise.
     */
    private static boolean isLowLight(Mat gray /* CV_8U */) {
        Mat hist = new Mat();
        try {
            Imgproc.calcHist(Collections.singletonList(gray), new MatOfInt(0), new Mat(), hist, new MatOfInt(256), new MatOfFloat(0, 256));
            double cum = 0, target = gray.total() * 0.5;
            int median = 127;
            for (int i = 0; i < 256; i++) {
                cum += hist.get(i, 0)[0];
                if (cum >= target) {
                    median = i;
                    break;
                }
            }
            return median < 60; // Heuristik
        } finally {
            hist.release();
        }
    }

    /**
     * Applies preprocessing steps to enhance low-light images. This method processes the
     * input image to improve visibility and clarity under low-light conditions using techniques
     * like noise reduction, gamma correction, contrast limiting adaptive histogram equalization (CLAHE),
     * and sharpening.
     *
     * @param rgbaOrGray the input image to be preprocessed. This can either be a grayscale or RGBA image.
     *                   The same object will be modified and will contain the preprocessed output.
     */
    private static void preprocessLowLight(Mat rgbaOrGray /* in/out */) {
        Mat gray = new Mat();
        try {
            if (rgbaOrGray.channels() == 4 || rgbaOrGray.channels() == 3) {
                Imgproc.cvtColor(rgbaOrGray, gray, Imgproc.COLOR_RGBA2GRAY);
            } else {
                gray = rgbaOrGray;
            }

            try {
                Mat tmp = new Mat();
                Photo.fastNlMeansDenoising(gray, tmp, 7, 7, 21);
                tmp.copyTo(gray);
                tmp.release();
            } catch (Throwable ignore) {
            }

            Mat f = new Mat();
            gray.convertTo(f, CvType.CV_32F, 1.0 / 255.0);
            Core.pow(f, 0.75, f); // Gamma 0.75
            Core.multiply(f, new Scalar(255.0), f);
            f.convertTo(gray, CvType.CV_8U);

            CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
            clahe.apply(gray, gray);

            Mat sharp = new Mat();
            Imgproc.GaussianBlur(gray, sharp, new Size(0, 0), 1.2);
            Core.addWeighted(gray, 1.6, sharp, -0.6, 0, gray);
            sharp.release();

            if (rgbaOrGray.channels() != 1) {
                Imgproc.cvtColor(gray, rgbaOrGray, Imgproc.COLOR_GRAY2RGBA);
            } else {
                gray.copyTo(rgbaOrGray);
            }
        } finally {
            if (gray != rgbaOrGray) gray.release();
        }
    }
}
