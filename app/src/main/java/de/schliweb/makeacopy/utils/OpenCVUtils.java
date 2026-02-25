package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import lombok.Getter;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

/**
 * Utility class for performing various operations with OpenCV.
 *
 * <p>This class cannot be instantiated.
 */
public class OpenCVUtils {
  private static final String TAG = "OpenCVUtils";

  @Getter private static boolean isInitialized = false;

  private static boolean USE_SAFE_MODE = true;
  private static boolean USE_ADAPTIVE_THRESHOLD = false;
  private static final boolean USE_DEBUG_IMAGES = false;

  /** When set to true, OpenCV-based corner detection is disabled. */
  private static boolean DISABLE_OPENCV_DETECTION = false;

  /**
   * Enables or disables OpenCV-based corner detection. When disabled, {@link
   * #detectDocumentCorners(Context, Bitmap)} will return a fallback rectangle.
   *
   * @param disable true to disable OpenCV detection, false to enable it
   */
  public static void setDisableOpenCVDetection(boolean disable) {
    DISABLE_OPENCV_DETECTION = disable;
    Log.i(TAG, "OpenCV detection " + (disable ? "disabled" : "enabled"));
  }

  /**
   * Returns whether OpenCV-based corner detection is currently disabled.
   *
   * @return true if OpenCV detection is disabled, false otherwise
   */
  public static boolean isOpenCVDetectionDisabled() {
    return DISABLE_OPENCV_DETECTION;
  }

  /**
   * Maximum edge size (in pixels) for corner detection preprocessing.
   *
   * <p>This value MUST be used consistently across all corner detection calls (live preview in
   * CameraFragment and final detection in TrapezoidSelectionView) to ensure identical results.
   * Using different values leads to inconsistent corner positions due to varying interpolation
   * artifacts during scaling.
   *
   * <p>
   */
  public static final int DETECTION_MAX_EDGE = 720;

  // ---- thresholds (tuned) ----
  private static final double CONF_MIN_AREA_FRAC = 0.008; // same lower bound for the confidence
  // Corner-angle sanity bounds: reject quads with too acute or too obtuse internal angles
  private static final double MIN_CORNER_ANGLE_DEG = 28.0; // threshold
  private static final double MAX_CORNER_ANGLE_DEG = 152.0; // avoid near-straight or reflex

  // Stricter limits only for “rectangular” candidates (OpenCV contours):
  // anything below 60° or above 120° is considered a “sharp spike” or a bent-in corner.
  private static final double MIN_RECT_CORNER_ANGLE_DEG = 60.0;
  private static final double MAX_RECT_CORNER_ANGLE_DEG = 120.0;

  private OpenCVUtils() {
    // Utility class, no instances allowed
  }

  private static boolean isSafeMode() {
    return USE_SAFE_MODE;
  }

  /**
   * Initializes OpenCV by loading the native library. This method should be called before using any
   * OpenCV functionality.
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
      isInitialized = true;
    } catch (Throwable t) {
      Log.e(TAG, "OpenCV init error", t);
    }

    return isInitialized;
  }

  /**
   * Configures the safe mode and adaptive threshold settings based on the device's specifications
   * and characteristics.
   *
   * <p>This method evaluates the device manufacturer, model, device name, and Android SDK version
   * to determine whether the device is classified as high-end or an emulator. Using this
   * evaluation, it configures the `USE_SAFE_MODE` and `USE_ADAPTIVE_THRESHOLD` flags accordingly.
   *
   * <p>Conditions for classifying a device as high-end include: - SDK version 29 or higher. - The
   * manufacturer does not contain "mediatek" or "spreadtrum". - The device name does not contain
   * "generic". - The model does not contain "emulator" or "x86"/"x86_64". - The manufacturer is
   * associated with reputable brands like Google, Samsung, or Xiaomi.
   *
   * <p>Conditions for identifying a device as an emulator include: - The device name contains
   * "emu", "x86", or "x86_64". - The model contains "sdk", "emulator", or "virtual". - The
   * manufacturer contains "genymotion".
   *
   * <p>Based on the classification: - `USE_SAFE_MODE` is enabled if the device is not high-end or
   * is identified as an emulator. - `USE_ADAPTIVE_THRESHOLD` is enabled only if the device is
   * high-end.
   *
   * <p>The method logs the safe mode and adaptive threshold configurations for debugging purposes.
   */
  private static void configureSafeMode() {
    String manufacturer = Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
    String model = Build.MODEL.toLowerCase(java.util.Locale.ROOT);
    String device = Build.DEVICE.toLowerCase(java.util.Locale.ROOT);
    String fingerprint = Build.FINGERPRINT.toLowerCase(java.util.Locale.ROOT);
    String hardware = Build.HARDWARE.toLowerCase(java.util.Locale.ROOT);
    String product = Build.PRODUCT.toLowerCase(java.util.Locale.ROOT);
    int sdk = Build.VERSION.SDK_INT;

    boolean isHighEnd =
        sdk >= 29
            && !manufacturer.contains("mediatek")
            && !manufacturer.contains("spreadtrum")
            && !device.contains("generic")
            && !model.contains("emulator")
            && !device.contains("x86")
            && !device.contains("x86_64")
            && (manufacturer.contains("google")
                || manufacturer.contains("samsung")
                || manufacturer.contains("xiaomi"));
    // Improved emulator detection: check fingerprint, hardware, and product for emulator patterns
    // This catches arm64 emulators that don't have x86 in their device name
    boolean isEmulator =
        device.contains("emu")
            || model.contains("sdk")
            || model.contains("emulator")
            || model.contains("virtual")
            || manufacturer.contains("genymotion")
            || model.contains("generator")
            || fingerprint.contains("generic")
            || fingerprint.contains("sdk")
            || fingerprint.contains("emulator")
            || hardware.contains("goldfish")
            || hardware.contains("ranchu")
            || product.contains("sdk")
            || product.contains("emulator")
            || product.contains("google_sdk");

    USE_SAFE_MODE = !isHighEnd || isEmulator;
    USE_ADAPTIVE_THRESHOLD = isHighEnd;

    Log.i(TAG, "Safe mode = " + USE_SAFE_MODE + ", AdaptiveThreshold = " + USE_ADAPTIVE_THRESHOLD);
    try {
      if (USE_SAFE_MODE) {
        // Disable aggressive SIMD/parallel optimizations that may use unsupported instructions on
        // some CPUs
        org.opencv.core.Core.setUseOptimized(false);
        org.opencv.core.Core.setNumThreads(1);
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Applies a perspective transformation to the given input matrix (image) using the specified
   * source points and maps it to a target size, ensuring the resulting perspective transformation
   * fits within the target dimensions. The function ensures safe handling of invalid inputs and
   * cleans up intermediate resources.
   *
   * @param input The input image represented as a {@code Mat} object. Must not be null or empty.
   * @param srcPoints An array of four {@code Point} objects specifying the source quadrilateral to
   *     be transformed. Must not be null and must contain exactly four points.
   * @param targetSize The target size for the output image, represented as a {@code Size} object.
   *     Specifies the dimensions (width and height) of the transformed image.
   * @return A new {@code Mat} object containing the transformed (warped) image. If an error occurs
   *     or invalid input is provided, the original input image is returned.
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
      Point[] dstPoints =
          new Point[] {
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
   * Applies a perspective correction to the given bitmap based on the specified corner points. This
   * method attempts to correct the image's perspective distortion by warping it to a target size
   * while maintaining the aspect ratio of the selected area defined by the corners. The
   * implementation uses OpenCV's warpPerspective if available and falls back to Android's
   * Matrix-based transformation if in safe mode.
   *
   * @param originalBitmap The input bitmap to which the perspective correction will be applied.
   *     This cannot be null.
   * @param corners An array of four points that represent the corners of the area to be corrected.
   *     These points must be in the order: top-left, top-right, bottom-right, bottom-left. The
   *     array must have exactly four points; otherwise, the original bitmap will be returned.
   * @return A new bitmap with the perspective correction applied. If an error occurs or the
   *     parameters are invalid, the original bitmap is returned unmodified.
   */
  public static Bitmap applyPerspectiveCorrection(Bitmap originalBitmap, Point[] corners) {
    if (corners == null || corners.length != 4) return originalBitmap;
    Mat mat = new Mat();
    try {
      Utils.bitmapToMat(originalBitmap, mat);
      // Compute a tight target size based on the selection to preserve aspect ratio of the cropped
      // area
      Size targetSize = computeWarpTargetSize(corners);
      if (!isSafeMode()) {
        Log.d(TAG, "Using OpenCV warpPerspective");
        Mat warped = warpPerspectiveSafe(mat, corners, targetSize);
        try {
          Bitmap output =
              Bitmap.createBitmap(
                  (int) targetSize.width, (int) targetSize.height, Bitmap.Config.ARGB_8888);
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
   * Applies a perspective warp transformation to a given bitmap using specified corner points and
   * produces a new bitmap with the target dimensions.
   *
   * @param srcBitmap the source bitmap to be transformed.
   * @param corners an array of four {@link Point} objects representing the corner points of the
   *     region in the source bitmap to be warped. The points should be in the order: top-left,
   *     top-right, bottom-right, bottom-left.
   * @param targetSize the dimensions of the output bitmap, specified as a {@link Size} object.
   * @return a new {@link Bitmap} object containing the perspective-warped image with the specified
   *     dimensions. If the corners array is null or does not contain exactly four points, the
   *     source bitmap is returned as-is.
   */
  private static Bitmap warpPerspectiveWithMatrix(
      Bitmap srcBitmap, Point[] corners, Size targetSize) {
    if (corners == null || corners.length != 4) return srcBitmap;

    int width = Math.max(1, (int) Math.round(targetSize.width));
    int height = Math.max(1, (int) Math.round(targetSize.height));

    float[] src =
        new float[] {
          (float) corners[0].x,
          (float) corners[0].y,
          (float) corners[1].x,
          (float) corners[1].y,
          (float) corners[2].x,
          (float) corners[2].y,
          (float) corners[3].x,
          (float) corners[3].y
        };

    float[] dst = new float[] {0, 0, width, 0, width, height, 0, height};

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
      Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);

      Mat resized = new Mat();
      Imgproc.resize(bgr, resized, new Size(256, 256), 0, 0, Imgproc.INTER_AREA);

      Mat floatImage = new Mat();
      resized.convertTo(floatImage, CvType.CV_32FC3, 1.0 / 255.0);

      List<Mat> ch = new ArrayList<>(3);
      Core.split(floatImage, ch); // B, G, R

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
      resized.release();
      return nchw;

    } finally {
      bgr.release();
      rgba.release();
    }
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

  /** Configuration options for black-and-white image processing. */
  public static class BwOptions {
    public enum Mode {
      AUTO_ADAPTIVE,
      OTSU_ONLY
    }

    public Mode mode = Mode.AUTO_ADAPTIVE;
    public boolean useClahe = true;
    public boolean removeShadows = true;

    /** Adaptive window (odd). 0 = auto */
    public int blockSize = 0;

    /** Offset for adaptiveThreshold (typ. 5–10) */
    public int C = 5;

    /**
     * Gentle mode for scripts with fine strokes and diacritics (Arabic, Persian, Hebrew). When
     * true, skips aggressive despeckle and morphological closing operations that can destroy small
     * but important character components like dots and thin strokes.
     */
    public boolean gentleMode = false;

    /**
     * Target DPI for the output image. Used to scale despeckle aggressiveness. At lower DPI,
     * despeckle is less aggressive to preserve readability. 0 = auto (assumes 300 DPI as default).
     */
    public int targetDpi = 0;
  }

  /**
   * Robust B/W conversion with shadow handling. Emulator: adaptiveThreshold is disabled (avoid
   * SIGILL). Real devices: gentle adaptive variant (MEAN + higher C).
   */
  public static Bitmap toBw(Bitmap src, BwOptions opt) {
    if (src == null || src.isRecycled()) return null;
    if (opt == null) opt = new BwOptions();

    Mat rgba = new Mat();
    Mat gray = new Mat();
    Mat work = null; // Don't create empty Mat - will be assigned below
    Mat bw = new Mat();
    CLAHE clahe = null;

    try {
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

      // --- 1) Shadow correction: division-based normalization ---
      if (opt.removeShadows && !isSafeMode()) {
        // Larger kernel captures broader illumination gradients for better shadow removal
        int k = Math.max(51, (int) (Math.min(gray.width(), gray.height()) * 0.08));
        if (k % 2 == 0) k++;
        Mat bg = new Mat();
        Imgproc.GaussianBlur(gray, bg, new Size(k, k), 0);

        // Use floating-point arithmetic to avoid quantization artifacts
        Mat gf = new Mat(), bgf = new Mat(), norm = new Mat();
        work = new Mat(); // Create work Mat only when needed
        gray.convertTo(gf, CvType.CV_32F);
        bg.convertTo(bgf, CvType.CV_32F);
        Core.max(bgf, new Scalar(1.0), bgf); // Prevent division by 0
        Core.divide(gf, bgf, norm); // ~0..1
        Core.multiply(norm, new Scalar(255.0), norm); // ~0..255
        norm.convertTo(work, CvType.CV_8U);

        bg.release();
        gf.release();
        bgf.release();
        norm.release();
      } else {
        work = gray; // work points to gray, no separate Mat needed
      }

      // --- 2) CLAHE for local contrast enhancement ---
      if (opt.useClahe) {
        clahe = Imgproc.createCLAHE();
        clahe.setClipLimit(2.0);
        clahe.setTilesGridSize(new Size(8, 8));
        clahe.apply(work, work);
      }

      // --- 3) slight smoothing against pepper noise ---
      Imgproc.medianBlur(work, work, 3);

      boolean ok = false;

      // --- 4) Adaptive threshold: Gaussian weighted for better document binarization ---
      // Detect low-resolution images (e.g. from autoscan) and use gentler parameters
      int longSide = Math.max(work.width(), work.height());
      boolean lowRes = longSide < 1500;

      if (opt.mode == BwOptions.Mode.AUTO_ADAPTIVE && !isSafeMode()) {
        int bs;
        if (opt.blockSize > 0) {
          bs = (opt.blockSize % 2 == 1) ? opt.blockSize : opt.blockSize + 1;
        } else if (lowRes) {
          // For low-res images use a larger relative block size to avoid
          // over-aggressive binarization that destroys text readability
          bs = Math.max(31, (Math.min(work.width(), work.height()) / 10) | 1);
          if (bs % 2 == 0) bs++;
        } else {
          // Larger block size captures broader context for threshold calculation
          bs = Math.max(51, (Math.min(work.width(), work.height()) / 30) | 1);
          if (bs % 2 == 0) bs++;
        }
        // Higher C keeps text strokes darker and more readable
        // For low-res images use a lower C to preserve thin strokes
        int C;
        if (lowRes) {
          C = Math.max(4, Math.min(8, opt.C > 0 ? opt.C : 5));
        } else {
          C = Math.max(8, Math.min(15, opt.C > 5 ? opt.C : 10));
        }

        try {
          Imgproc.adaptiveThreshold(
              work, bw, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, bs, C);
          ok = true;
        } catch (Throwable ignore) {
          ok = false;
        }
      }

      // --- 5) Fallback / OTSU_ONLY mode ---
      // For low-res OTSU_ONLY (s/w klassisch): use adaptive threshold instead of
      // global Otsu, because Otsu is too aggressive on low-res autoscan images
      // and destroys text readability.
      if (!ok && lowRes && !isSafeMode()) {
        int bs = Math.max(31, (Math.min(work.width(), work.height()) / 10) | 1);
        if (bs % 2 == 0) bs++;
        int C = Math.max(3, Math.min(6, opt.C > 0 ? opt.C : 4));
        try {
          Imgproc.adaptiveThreshold(
              work, bw, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, bs, C);
          ok = true;
        } catch (Throwable ignore) {
          ok = false;
        }
      }
      if (!ok) {
        Imgproc.threshold(work, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
      }

      // Despeckle after bw is guaranteed to be filled
      // Skip for gentle mode and low-res images to preserve text readability
      if (!opt.gentleMode && !lowRes) {
        despeckleFast(bw, opt.targetDpi);
      }

      // --- 6) very gentle closing stabilizes characters ---
      // Skip for gentle mode and low-res images to preserve fine character details
      if (!opt.gentleMode && !lowRes) {
        try {
          Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
          Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, kernel);
          kernel.release();
        } catch (Throwable ignore) {
          /* optional */
        }
      }

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
          // Best-effort; failure is non-critical
        }
      }
    }
  }

  /**
   * Removes small speckles from a binary image using morphological operations. The function
   * processes the input binary image to eliminate noise or small artifacts, leaving the major
   * structures intact.
   *
   * <p>The aggressiveness of despeckle is scaled based on target DPI: - At 300 DPI (reference):
   * uses 3x3 kernel and minArea=15 - At lower DPI (e.g., 72-150): uses smaller kernel (2x2) and
   * lower minArea to preserve readability - At higher DPI (e.g., 600): can use larger kernel and
   * higher minArea
   *
   * @param bw Input binary image of type Mat (CV_8UC1), with pixel values of 0 or 255. It will be
   *     modified in-place to remove speckles.
   * @param targetDpi Target DPI for the output. 0 or negative values default to 300 DPI.
   */
  private static void despeckleFast(Mat bw /* CV_8UC1, 0/255 */, int targetDpi) {
    // Reference DPI for scaling calculations
    final int REFERENCE_DPI = 300;
    int effectiveDpi = targetDpi > 0 ? targetDpi : REFERENCE_DPI;

    // Scale factor relative to reference DPI
    float dpiScale = (float) effectiveDpi / REFERENCE_DPI;

    // At low DPI (< 150), skip morphological opening entirely to preserve fine details
    // At medium DPI (150-250), use 2x2 kernel
    // At high DPI (>= 250), use 3x3 kernel
    int kernelSize;
    if (effectiveDpi < 150) {
      kernelSize = 0; // Skip morphological opening
    } else if (effectiveDpi < 250) {
      kernelSize = 2;
    } else {
      kernelSize = 3;
    }

    Mat inv = new Mat();
    Mat kernel = null;
    try {
      if (kernelSize > 0) {
        // Make text and speckles white so the opening operation removes them
        Core.bitwise_not(bw, inv);
        kernel =
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelSize, kernelSize));
        Imgproc.morphologyEx(inv, inv, Imgproc.MORPH_OPEN, kernel);
        Core.bitwise_not(inv, bw);
      }
    } finally {
      inv.release();
      if (kernel != null) kernel.release();
    }

    // Scale minArea based on DPI: at 300 DPI use 15, scale proportionally
    // At low DPI, use smaller minArea to avoid removing small but valid characters
    // Formula: minArea = 15 * (dpi/300)^2, with minimum of 4 pixels
    int minArea = Math.max(4, Math.round(15 * dpiScale * dpiScale));

    // At very low DPI (< 100), skip component removal entirely
    if (effectiveDpi >= 100) {
      removeSmallComponents(bw, minArea);
    }
  }

  /**
   * Removes connected components smaller than the specified minimum area. This helps eliminate
   * small noise artifacts that are too small to be valid characters.
   *
   * @param bw Input binary image of type Mat (CV_8UC1), with pixel values of 0 or 255. Text should
   *     be black (0) on white (255) background.
   * @param minArea Minimum area in pixels for a component to be kept.
   */
  private static void removeSmallComponents(Mat bw /* CV_8UC1, 0/255 */, int minArea) {
    if (bw == null || bw.empty() || minArea <= 0) return;

    Mat inv = new Mat();
    Mat labels = new Mat();
    Mat stats = new Mat();
    Mat centroids = new Mat();

    try {
      // Invert so text becomes white (foreground) for connectedComponents
      Core.bitwise_not(bw, inv);

      int numLabels =
          Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S);

      // Label 0 is background, start from 1
      for (int label = 1; label < numLabels; label++) {
        int area = (int) stats.get(label, Imgproc.CC_STAT_AREA)[0];
        if (area < minArea) {
          // Remove this component by setting its pixels to background (0 in inv, 255 in bw)
          int left = (int) stats.get(label, Imgproc.CC_STAT_LEFT)[0];
          int top = (int) stats.get(label, Imgproc.CC_STAT_TOP)[0];
          int width = (int) stats.get(label, Imgproc.CC_STAT_WIDTH)[0];
          int height = (int) stats.get(label, Imgproc.CC_STAT_HEIGHT)[0];

          // Clear pixels belonging to this label in the bounding box
          for (int y = top; y < top + height && y < bw.rows(); y++) {
            for (int x = left; x < left + width && x < bw.cols(); x++) {
              int[] labelVal = new int[1];
              labels.get(y, x, labelVal);
              if (labelVal[0] == label) {
                bw.put(y, x, 255); // Set to white (background)
              }
            }
          }
        }
      }
    } catch (Throwable ignore) {
      // If connectedComponents fails, skip this optimization
    } finally {
      inv.release();
      labels.release();
      stats.release();
      centroids.release();
    }
  }

  /**
   * Clears noise and small artifacts touching the image borders. This helps remove scanning
   * artifacts, edge noise, and partial characters that often appear at document edges and cause OCR
   * errors.
   *
   * @param bw Input binary image of type Mat (CV_8UC1), with pixel values of 0 or 255. It will be
   *     modified in-place to clear border-touching components.
   */
  private static void clearBorderNoise(Mat bw /* CV_8UC1, 0/255 */) {
    if (bw == null || bw.empty()) return;

    int w = bw.cols();
    int h = bw.rows();

    // Define border margin (percentage of image size)
    int marginX = Math.max(8, (int) (w * 0.015)); // 1.5% of width, min 8px
    int marginY = Math.max(8, (int) (h * 0.015)); // 1.5% of height, min 8px

    // Use submat and setTo for efficient border clearing
    // Note: SubMats are views but must still be released to avoid memory leaks
    Mat top = null, bottom = null, left = null, right = null;
    try {
      // Clear top border region
      if (marginY > 0 && marginY < h) {
        top = bw.submat(0, marginY, 0, w);
        top.setTo(new Scalar(255));
      }

      // Clear bottom border region
      if (marginY > 0 && h - marginY > 0) {
        bottom = bw.submat(h - marginY, h, 0, w);
        bottom.setTo(new Scalar(255));
      }

      // Clear left border region
      if (marginX > 0 && marginX < w) {
        left = bw.submat(0, h, 0, marginX);
        left.setTo(new Scalar(255));
      }

      // Clear right border region
      if (marginX > 0 && w - marginX > 0) {
        right = bw.submat(0, h, w - marginX, w);
        right.setTo(new Scalar(255));
      }
    } catch (Throwable ignore) {
      // Fallback: pixel-by-pixel clearing if submat fails
      for (int y = 0; y < marginY && y < h; y++) {
        for (int x = 0; x < w; x++) {
          bw.put(y, x, 255);
        }
      }
      for (int y = h - marginY; y < h; y++) {
        if (y >= 0) {
          for (int x = 0; x < w; x++) {
            bw.put(y, x, 255);
          }
        }
      }
      for (int y = 0; y < h; y++) {
        for (int x = 0; x < marginX && x < w; x++) {
          bw.put(y, x, 255);
        }
      }
      for (int y = 0; y < h; y++) {
        for (int x = w - marginX; x < w; x++) {
          if (x >= 0) {
            bw.put(y, x, 255);
          }
        }
      }
    } finally {
      // Release SubMats to avoid memory leaks
      if (top != null) top.release();
      if (bottom != null) bottom.release();
      if (left != null) left.release();
      if (right != null) right.release();
    }
  }

  /**
   * Converts a given Bitmap image to a black-and-white (grayscale) representation using default
   * options.
   *
   * @param src the source Bitmap to be converted to black-and-white
   * @return a new Bitmap representing the black-and-white version of the source image
   */
  public static Bitmap toBw(Bitmap src) {
    return toBw(src, new BwOptions());
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
    // The fallback rectangle is generated by getFallbackRectangle(width, height)
    // using a dynamic margin m = max(20, min(w,h)/10). Our previous hardcoded
    // check against 100 px missed most cases and caused false non-detections.
    int m = Math.max(20, Math.min(w, h) / 10);
    return close(p[0].x, m)
        && close(p[0].y, m)
        && close(p[1].x, w - m)
        && close(p[1].y, m)
        && close(p[2].x, w - m)
        && close(p[2].y, h - m)
        && close(p[3].x, m)
        && close(p[3].y, h - m);
  }

  // Small tolerance helper to account for integer/float conversions and rounding
  private static boolean close(double a, double b) {
    return Math.abs(a - b) <= 2.5; // ~±2.5 px tolerance
  }

  /**
   * Calculates the area of a quadrilateral defined by four points. The calculation is based on the
   * Shoelace formula and assumes the points are ordered in a consistent clockwise or
   * counterclockwise manner.
   *
   * @param q An array of four {@link Point} objects representing the vertices of the quadrilateral.
   *     The order of the points must form a closed quadrilateral.
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
   * Detects the corners of a document in a given image using OpenCV image processing techniques.
   * This method processes the input bitmap, applies multiple filters, and identifies contours to
   * extract the best quadrilateral representing a document.
   *
   * @param context The Android context used for saving debug images during processing.
   * @param bitmap The input image in the form of a Bitmap, from which the document corners are to
   *     be detected.
   * @return An array of Points representing the four corners of the detected document. If no
   *     suitable document corners are detected, a fallback rectangle is returned.
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

      // Dynamic kernel size based on image dimensions (improves detection for various document
      // sizes)
      int shortSide = Math.min(rgba.width(), rgba.height());
      int kernelSize = Math.max(5, shortSide / 50);
      if (kernelSize % 2 == 0) kernelSize++; // Ensure odd size
      Log.d(
          TAG,
          "Using dynamic kernel size: "
              + kernelSize
              + " for image "
              + rgba.width()
              + "x"
              + rgba.height());
      kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kernelSize, kernelSize));
      Imgproc.morphologyEx(threshold, morph, Imgproc.MORPH_CLOSE, kernel);
      saveDebugImage(context, morph, "debug_morph.png");

      // Adaptive Canny thresholds based on image statistics (better for varying contrast)
      double median = Core.mean(gray).val[0];
      double cannyLower = Math.max(0, 0.66 * median);
      double cannyUpper = Math.min(255, 1.33 * median);
      Log.d(
          TAG,
          String.format(
              Locale.US,
              "Adaptive Canny thresholds: lower=%.1f, upper=%.1f (median=%.1f)",
              cannyLower,
              cannyUpper,
              median));
      Imgproc.Canny(morph, edges, cannyLower, cannyUpper);

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
      Imgproc.findContours(
          edgesCopy, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

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
        double area = Imgproc.contourArea(contour);
        if (area < imgArea * 0.08) continue; // previously 0.20

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

            // First obtain the raw score; -1 means "geometrically implausible"
            double rectRaw = rectScore(quad);
            if (rectRaw < 0.0) {
              // at least one corner <60° or >120° → sharp/bent-in → skip this candidate
              continue;
            }

            double rect = rectRaw / 120.0;
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
      }

      if (bestQuad != null) {
        Log.i(TAG, "Document contour found via approxPolyDP");
        return bestQuad;
      }

      // Fallback: Try Hough lines detection when contour-based detection fails
      Log.d(TAG, "Contour detection failed, trying Hough lines fallback...");
      Point[] houghQuad = detectQuadFromHoughLines(edges, rgba.width(), rgba.height());
      if (houghQuad != null) {
        Log.i(TAG, "Document quad found via Hough lines");
        return houghQuad;
      }

      Log.w(TAG, "No suitable document contour found (OpenCV) → returning null");
      return null;
    } finally {
      // Release all contours at once instead of in the loop to avoid accessing released Mats
      for (MatOfPoint c : contours) {
        if (c != null) {
          try {
            c.release();
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        }
      }
      contours.clear();
      release(rgba, gray, threshold, morph, kernel, edges, edgesCopy, hierarchy, debug);
    }
  }

  /**
   * Applies adaptive edge detection on the provided grayscale image and stores the result. This
   * method uses a combination of median blur, mean and standard deviation calculations, and Canny
   * edge detection to adaptively determine the edge detection thresholds.
   *
   * @param srcGray The source image in grayscale format (Mat object).
   * @param out The output matrix (Mat object, CV_8U) where the edges will be stored.
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
   * Detects a quadrilateral from edge image using Hough line detection. This is a fallback method
   * when contour-based detection fails, particularly useful for documents with broken or incomplete
   * edges.
   *
   * @param edges The edge-detected image (CV_8U binary).
   * @param imgW The width of the original image.
   * @param imgH The height of the original image.
   * @return An array of 4 Points representing the document corners, or null if detection fails.
   */
  private static Point[] detectQuadFromHoughLines(Mat edges, int imgW, int imgH) {
    Mat lines = new Mat();
    try {
      // Detect lines using probabilistic Hough transform
      // Parameters: rho=1px, theta=PI/180, threshold=80, minLineLength=50, maxLineGap=10
      int minLineLength = Math.max(30, Math.min(imgW, imgH) / 10);
      int threshold = Math.max(50, minLineLength / 2);
      Imgproc.HoughLinesP(edges, lines, 1, Math.PI / 180, threshold, minLineLength, 10);

      if (lines.rows() < 4) {
        Log.d(TAG, "Hough: Not enough lines detected (" + lines.rows() + ")");
        return null;
      }
      Log.d(TAG, "Hough: Detected " + lines.rows() + " lines");

      // Classify lines into horizontal and vertical based on angle
      List<double[]> horizontalLines = new ArrayList<>();
      List<double[]> verticalLines = new ArrayList<>();

      for (int i = 0; i < lines.rows(); i++) {
        double[] line = lines.get(i, 0);
        double x1 = line[0], y1 = line[1], x2 = line[2], y2 = line[3];
        double angle = Math.toDegrees(Math.atan2(y2 - y1, x2 - x1));
        angle = ((angle % 180) + 180) % 180; // Normalize to [0, 180)

        // Horizontal: angle near 0° or 180°
        // Vertical: angle near 90°
        if (angle < 30 || angle > 150) {
          horizontalLines.add(line);
        } else if (angle > 60 && angle < 120) {
          verticalLines.add(line);
        }
      }

      Log.d(
          TAG,
          "Hough: "
              + horizontalLines.size()
              + " horizontal, "
              + verticalLines.size()
              + " vertical lines");

      if (horizontalLines.size() < 2 || verticalLines.size() < 2) {
        Log.d(TAG, "Hough: Not enough horizontal/vertical lines");
        return null;
      }

      // Sort horizontal lines by Y position (top to bottom)
      horizontalLines.sort((a, b) -> Double.compare((a[1] + a[3]) / 2, (b[1] + b[3]) / 2));
      // Sort vertical lines by X position (left to right)
      verticalLines.sort((a, b) -> Double.compare((a[0] + a[2]) / 2, (b[0] + b[2]) / 2));

      // Take the outermost lines (first and last after sorting)
      double[] topLine = horizontalLines.get(0);
      double[] bottomLine = horizontalLines.get(horizontalLines.size() - 1);
      double[] leftLine = verticalLines.get(0);
      double[] rightLine = verticalLines.get(verticalLines.size() - 1);

      // Find intersection points of the four lines
      Point tl = lineIntersection(topLine, leftLine);
      Point tr = lineIntersection(topLine, rightLine);
      Point br = lineIntersection(bottomLine, rightLine);
      Point bl = lineIntersection(bottomLine, leftLine);

      if (tl == null || tr == null || br == null || bl == null) {
        Log.d(TAG, "Hough: Could not find all intersection points");
        return null;
      }

      // Clamp points to image bounds
      tl = clampPoint(tl, imgW, imgH);
      tr = clampPoint(tr, imgW, imgH);
      br = clampPoint(br, imgW, imgH);
      bl = clampPoint(bl, imgW, imgH);

      Point[] quad = new Point[] {tl, tr, br, bl};
      quad = sortPointsRobust(quad);

      // Validate the quad
      double area = quadArea(quad);
      double imgArea = imgW * (double) imgH;
      if (area < imgArea * 0.05) { // At least 5% of image area
        Log.d(TAG, "Hough: Quad too small (area=" + area + ", min=" + (imgArea * 0.05) + ")");
        return null;
      }

      if (hasAcuteOrReflexAngles(quad)) {
        Log.d(TAG, "Hough: Quad has invalid angles");
        return null;
      }

      Log.d(TAG, "Hough: Valid quad found with area=" + area);
      return quad;

    } catch (Throwable t) {
      Log.w(TAG, "Hough line detection failed", t);
      return null;
    } finally {
      lines.release();
    }
  }

  /**
   * Calculates the intersection point of two lines defined by their endpoints.
   *
   * @param line1 First line as [x1, y1, x2, y2]
   * @param line2 Second line as [x1, y1, x2, y2]
   * @return The intersection point, or null if lines are parallel
   */
  private static Point lineIntersection(double[] line1, double[] line2) {
    double x1 = line1[0], y1 = line1[1], x2 = line1[2], y2 = line1[3];
    double x3 = line2[0], y3 = line2[1], x4 = line2[2], y4 = line2[3];

    double denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
    if (Math.abs(denom) < 1e-10) {
      return null; // Lines are parallel
    }

    double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
    double px = x1 + t * (x2 - x1);
    double py = y1 + t * (y2 - y1);

    return new Point(px, py);
  }

  /**
   * Clamps a point to be within image bounds.
   *
   * @param p The point to clamp
   * @param imgW Image width
   * @param imgH Image height
   * @return A new point clamped to [0, imgW-1] x [0, imgH-1]
   */
  private static Point clampPoint(Point p, int imgW, int imgH) {
    return new Point(Math.max(0, Math.min(p.x, imgW - 1)), Math.max(0, Math.min(p.y, imgH - 1)));
  }

  /**
   * Detects the corners of a document present in the given bitmap image. This method uses multiple
   * techniques internally to identify the best possible corner points of the document in the image.
   *
   * @param context the Android context required for certain operations, such as OpenCV
   *     initialization
   * @param bitmap the bitmap image within which the document's corners need to be detected
   * @return an array of Point objects representing the detected corners of the document
   */
  public static Point[] detectDocumentCorners(Context context, Bitmap bitmap) {
    Log.i(
        TAG,
        "Starting detectDocumentCorners() OpenCV="
            + (DISABLE_OPENCV_DETECTION ? "OFF" : "ON")
            + "]");

    Point[] cv = DISABLE_OPENCV_DETECTION ? null : detectDocumentCornersWithOpenCV(context, bitmap);
    if (cv == null) {
      cv = getFallbackRectangle(bitmap.getWidth(), bitmap.getHeight());
    }
    return cv;
  }

  /**
   * Represents the result of a detection process.
   *
   * <p>The class provides information about the detected object's corner points and a confidence
   * score. It is implemented as a record for immutability and compactness.
   *
   * @param corners An array of {@code Point} objects representing the corners of the detected
   *     object.
   * @param score A {@code double} value indicating the confidence score of the detection.
   */
  public record DetectionResult(Point[] corners, double score) {}

  /**
   * Detects the corners of a document within the provided bitmap image and calculates a confidence
   * score.
   *
   * @param context The context used for accessing resources or performing operations.
   * @param bitmap The bitmap image in which the document's corners are to be detected.
   * @return A DetectionResult object containing the detected corners as an array of Points and a
   *     confidence score indicating the reliability of the detection.
   */
  public static DetectionResult detectDocumentCornersResult(Context context, Bitmap bitmap) {
    Point[] corners = detectDocumentCorners(context, bitmap);
    double score = 0.0;
    if (corners != null && corners.length == 4 && bitmap != null) {
      try {
        // If the result is merely the standard fallback rectangle, do not assign a high score.
        // Users reported misleading values (e.g., ~84%) for the default rectangle.
        // We treat the fallback as "unknown/low confidence" and force score to 0.
        if (isFallback(corners, bitmap.getWidth(), bitmap.getHeight())) {
          score = 0.0;
        } else {
          score = quadConfidence(corners, bitmap.getWidth(), bitmap.getHeight());
        }
      } catch (Throwable ignored) {
        // Best-effort; failure is non-critical
      }
    }
    return new DetectionResult(corners, score);
  }

  /**
   * Sorts an array of four points in a robust manner. The points are arranged in clockwise order
   * starting from the top-left point. The top-left point is determined as the point with the
   * smallest (x + y) value. The method computes the centroid of the points and uses it to sort them
   * by angle relative to the centroid, ensuring stable ordering.
   *
   * @param src the input array of points. It must contain exactly four points. If the input is null
   *     or does not contain four points, the method will return the input array unchanged.
   * @return a new array of points sorted in clockwise order starting from the top-left point, or
   *     the input array if it is null or has fewer or more than four points.
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

    final double fx = cx, fy = cy; // <- final copies for lambda

    // sort by angle around the centroid
    pts.sort(Comparator.comparingDouble(p -> Math.atan2(p.y - fy, p.x - fx)));

    // rotate so that index 0 = top-left (min x+y)
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
   * Calculates a score for the given quadrilateral based on how closely its angles resemble 90
   * degrees. The method evaluates the four corners of the quadrilateral and assigns a score
   * considering angular deviations from a right angle. It rejects shapes with invalid angles, sharp
   * angles, or overly obtuse angles.
   *
   * @param q an array of four {@code Point} objects representing the vertices of the quadrilateral.
   *     The vertices are expected to be in sequential order. If null or not containing exactly 4
   *     points, the method returns -1.0.
   * @return a {@code double} value representing the score of the quadrilateral. Returns -1.0 if:
   *     the input is null, the number of vertices is not 4, any angle is invalid, or an angular
   *     threshold is violated.
   */
  private static double rectScore(Point[] q) {
    if (q == null || q.length != 4) return -1.0;

    double score = 0.0;
    for (int i = 0; i < 4; i++) {
      Point a = q[i];
      Point prev = q[(i + 3) % 4];
      Point next = q[(i + 1) % 4];

      double ang = angle(prev, a, next);
      if (Double.isNaN(ang) || Double.isInfinite(ang)) {
        return -1.0;
      }

      // Hard limit: discard acute (<60°) or extremely obtuse (>120°) corners
      if (ang < MIN_RECT_CORNER_ANGLE_DEG || ang > MAX_RECT_CORNER_ANGLE_DEG) {
        return -1.0;
      }

      double dev = Math.abs(ang - 90.0);
      double perCorner = 30.0 - dev; // perfect 90° → 30 points
      if (perCorner > 0) {
        score += perCorner;
      }
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
   * Returns true if the quadrilateral contains any corner with an acute (< MIN) or overly
   * obtuse/reflex (> MAX) internal angle. Assumes points are ordered (tl,tr,br,bl).
   */
  private static boolean hasAcuteOrReflexAngles(Point[] q) {
    if (q == null || q.length != 4) return true;
    Point[] p = sortPointsRobust(q);
    for (int i = 0; i < 4; i++) {
      Point a = p[i];
      Point prev = p[(i + 3) % 4];
      Point next = p[(i + 1) % 4];
      double ang = angle(prev, a, next);
      if (Double.isNaN(ang) || Double.isInfinite(ang)) return true;
      if (ang < MIN_CORNER_ANGLE_DEG || ang > MAX_CORNER_ANGLE_DEG) return true;
    }
    return false;
  }

  /**
   * Calculates the confidence score of a quadrilateral based on its area, rectangularity, and
   * symmetry relative to provided width and height values.
   *
   * @param q an array of four points representing the quadrilateral. The array must have exactly
   *     four points.
   * @param w the width of the reference boundary for calculating normalized area.
   * @param h the height of the reference boundary for calculating normalized area.
   * @return a confidence score as a double value, where the score is higher for well-shaped
   *     quadrilaterals meeting the criteria of area, rectangularity, and symmetry. Returns 0 if
   *     input is invalid or calculated area is below the threshold.
   */
  private static double quadConfidence(Point[] q, int w, int h) {
    if (q == null || q.length != 4) return 0;
    q = sortPointsRobust(q);
    double areaFrac = quadArea(q) / (w * (double) h);
    if (areaFrac < CONF_MIN_AREA_FRAC) return 0; // previously 3%, now 0.8%

    double rect = rectScore(q) / 120.0;
    double w1 = distance(q[0], q[1]), w2 = distance(q[2], q[3]);
    double h1 = distance(q[1], q[2]), h2 = distance(q[3], q[0]);
    double sym =
        1.0 - Math.min(1.0, (Math.abs(w1 - w2) + Math.abs(h1 - h2)) / (w1 + w2 + h1 + h2) + 1e-6);

    return 0.5 * areaFrac + 0.3 * rect + 0.2 * sym;
  }

  /**
   * Generates a fallback rectangle defined by four corner points, adjusted based on the input width
   * and height. The size of the rectangle is calculated to be approximately 10% away from the edges
   * of the given dimensions, with a minimum margin of 20 units.
   *
   * @param width the width of the area within which the rectangle is to be defined
   * @param height the height of the area within which the rectangle is to be defined
   * @return an array of four {@link Point} objects representing the four corners of the rectangle
   */
  private static Point[] getFallbackRectangle(int width, int height) {
    int m = Math.max(20, Math.min(width, height) / 10); // ~10% Rand
    return new Point[] {
      new Point(m, m),
      new Point(width - m, m),
      new Point(width - m, height - m),
      new Point(m, height - m)
    };
  }

  /**
   * Returns a simple centered fallback rectangle (as RectF) with ~10% margin on each side. This
   * mirrors the margin logic of the internal Point[] variant and is suitable as a stable model
   * reference for the FramingEngine when no detection is available.
   *
   * @param width upright image width in pixels
   * @param height upright image height in pixels
   * @return RectF representing the fallback rectangle in the same coordinate space
   */
  public static RectF getFallbackRectF(int width, int height) {
    int m = Math.max(20, Math.min(width, height) / 10); // ~10% margin
    return new RectF(m, m, width - m, height - m);
  }

  /**
   * Saves a debug image to the device's external files directory. This is useful for debugging
   * purposes to visualize intermediate steps in the image processing pipeline.
   *
   * @param context The application context for accessing the external files directory.
   * @param mat The Mat object containing the image to be saved.
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
   * Releases the provided OpenCV Mat objects to free up memory. This method is a no-op for null
   * inputs and handles exceptions if any occur during the release process.
   *
   * @param mats An array of Mat objects to be released. Null values within the array are safely
   *     ignored.
   */
  private static void release(Mat... mats) {
    if (mats == null) return;
    for (Mat m : mats) {
      if (m != null) {
        try {
          m.release();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
    }
  }

  /**
   * Enhances the visual quality of the image by applying histogram equalization to the luminance
   * channel and sharpening the overall image.
   *
   * @param bgr the input image in BGR color space. The operation modifies this image in place. Must
   *     not be null or empty.
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
        // Best-effort; failure is non-critical
      }
      try {
        l.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      try {
        a.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      try {
        bb.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
  }

  /**
   * Computes a tight target size (width/height) for the warp based on the lengths of the selected
   * quadrilateral edges. This preserves the aspect ratio of the selected area when mapping to a
   * rectangle.
   */
  private static Size computeWarpTargetSize(Point[] corners) {
    if (corners == null || corners.length != 4) {
      return new Size(1, 1);
    }
    double wTop = distance(corners[0], corners[1]);
    double wBottom = distance(corners[2], corners[3]);
    double hLeft = distance(corners[0], corners[3]);
    double hRight = distance(corners[1], corners[2]);
    // Use inclusive pixel lengths: if corners span from 0..(W-1), distance is (W-1)
    // but target size must be W to preserve identity mapping. Hence +1.
    int w = Math.max(1, (int) Math.round(Math.max(wTop, wBottom)) + 1);
    int h = Math.max(1, (int) Math.round(Math.max(hLeft, hRight)) + 1);
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
   *
   * <p>A histogram is computed for the image, and the median intensity value is calculated. If the
   * median intensity value is below a specific threshold, the image is determined to be in low
   * light conditions.
   *
   * @param gray the input image in grayscale format (CV_8U). This matrix (Mat) represents the
   *     intensity values of the image.
   * @return true if the median intensity value of the grayscale image indicates low light
   *     conditions; false otherwise.
   */
  private static boolean isLowLight(Mat gray /* CV_8U */) {
    Mat hist = new Mat();
    try {
      Imgproc.calcHist(
          Collections.singletonList(gray),
          new MatOfInt(0),
          new Mat(),
          hist,
          new MatOfInt(256),
          new MatOfFloat(0, 256));
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
   * Applies preprocessing steps to enhance low-light images. This method processes the input image
   * to improve visibility and clarity under low-light conditions using techniques like noise
   * reduction, gamma correction, contrast limiting adaptive histogram equalization (CLAHE), and
   * sharpening.
   *
   * @param rgbaOrGray the input image to be preprocessed. This can either be a grayscale or RGBA
   *     image. The same object will be modified and will contain the preprocessed output.
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
        // Best-effort; failure is non-critical
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

  /**
   * Prepares a Bitmap for OCR: robust grayscale/binary preprocessing, low-light handling, gentle
   * CLAHE, despeckle, and moderate upscaling.
   *
   * @param src input bitmap (RGBA or RGB). Must be non-null and not recycled.
   * @param binaryOutput if true, returns a binarized (black/white) image; if false, a contrasty
   *     grayscale.
   * @return ARGB_8888 bitmap suitable for Tesseract input, or null on failure.
   */
  public static Bitmap prepareForOCR(Bitmap src, boolean binaryOutput) {
    if (src == null || src.isRecycled()) return null;

    Mat rgba = new Mat();
    Mat gray = new Mat();
    Mat work = new Mat();
    Mat bw = new Mat();
    Bitmap out = null;

    try {
      // 1) Bitmap -> RGBA -> GRAY
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

      // 1b) Inversion detection: if image is predominantly dark (inverted/negative),
      //     flip it so text becomes dark-on-light (required for OCR)
      try {
        double meanVal = Core.mean(gray).val[0];
        if (meanVal < 128) {
          // Image is inverted (white text on black background) - invert it
          Core.bitwise_not(gray, gray);
          Log.d(
              TAG, "prepareForOCR: detected inverted image (mean=" + meanVal + "), auto-inverting");
        }
      } catch (Throwable ignore) {
        // If mean calculation fails, continue without inversion
      }

      // 2) Low-light handling (reuse existing utility)
      // SAFE MODE: Skip preprocessLowLight which uses convertTo (can crash on emulators)
      if (!isSafeMode() && isLowLight(gray)) {
        Mat tmp = rgba.clone();
        preprocessLowLight(tmp); // modifies in-place
        Imgproc.cvtColor(tmp, gray, Imgproc.COLOR_RGBA2GRAY);
        tmp.release();
      }

      // 3) Background normalization to suppress shadows/gradients (division by blurred background)
      //    Use floating-point math to avoid banding, then convert back to 8-bit in 'work'.
      //    SAFE MODE: Skip float conversion (convertTo) which can crash on some emulators due to
      //    unsupported SIMD instructions. Use simple copy instead.
      if (isSafeMode()) {
        Log.d(TAG, "prepareForOCR: Safe mode - skipping float normalization");
        gray.copyTo(work);
      } else {
        int k = Math.max(15, (int) (Math.min(gray.width(), gray.height()) * 0.03));
        if (k % 2 == 0) k++;
        Mat bg = new Mat();
        Imgproc.GaussianBlur(gray, bg, new Size(k, k), 0);
        Mat gf = new Mat(), bgf = new Mat(), norm = new Mat();
        try {
          gray.convertTo(gf, CvType.CV_32F);
          bg.convertTo(bgf, CvType.CV_32F);
          Core.max(bgf, new Scalar(1.0), bgf); // prevent div-by-zero
          Core.divide(gf, bgf, norm); // ~0..1
          Core.multiply(norm, new Scalar(255.0), norm); // ~0..255
          norm.convertTo(work, CvType.CV_8U);
        } finally {
          bg.release();
          gf.release();
          bgf.release();
          norm.release();
        }
      }

      // 4) Gentle denoise + CLAHE (very mild, avoids over-bleaching)
      Imgproc.medianBlur(work, work, 3);
      try {
        CLAHE clahe = Imgproc.createCLAHE(1.2, new Size(8, 8));
        clahe.apply(work, work);
        clahe.collectGarbage();
      } catch (Throwable ignore) {
        /* optional */
      }

      if (binaryOutput) {
        // NEW ROBUST PIPELINE (from scratch): deskew → Retinex norm → edge-preserving denoise →
        // Sauvola → refine → smart scale

        // 5a) Deskew (estimate skew angle and rotate to horizontal baselines)
        try {
          deskewInPlace(work); // rotates in-place and resizes 'work' as needed
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }

        // 5b) Retinex-like normalization to flatten illumination
        // SAFE MODE: Skip retinexNormalize which uses convertTo (can crash on emulators)
        // if (!isSafeMode()) {
        try {
          retinexNormalize(
              work, /*sigma*/ Math.max(15, Math.min(work.width(), work.height()) / 20));
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        // }

        // Detect low-resolution images (e.g. from autoscan) and use gentler parameters
        int ocrLongSide = Math.max(work.width(), work.height());
        boolean ocrLowRes = ocrLongSide < 1500;

        // 5c) Edge-preserving denoise: prefer fastNlMeans (grayscale) then bilateral as fallback
        //     For low-res images use lower h to preserve fine text strokes
        try {
          int denoiseH = ocrLowRes ? 5 : 10;
          Photo.fastNlMeansDenoising(
              work, work, /*h*/ denoiseH, /*templateWindowSize*/ 7, /*searchWindowSize*/ 21);
        } catch (Throwable tNl) {
          try {
            int longSide = Math.max(work.width(), work.height());
            int d = (longSide >= 2200 ? 7 : 5);
            double sigmaColor = (longSide >= 2200 ? 65 : 55);
            double sigmaSpace = (longSide >= 2200 ? 65 : 55);
            Imgproc.bilateralFilter(work, work, d, sigmaColor, sigmaSpace);
          } catch (Throwable ignore2) {
            // Best-effort; failure is non-critical
          }
        }

        // 5d) High-quality binarization: build multiple candidates and pick the best by quality
        // score
        List<Mat> candidates = new ArrayList<>();

        try {
          // Candidate A/B/C: Sauvola with varying k and window sizes (real devices only)
          if (!isSafeMode()) {
            // For low-res images use larger relative window (min/10) for broader context
            int baseWin =
                Math.max(31, ((Math.min(work.width(), work.height()) / (ocrLowRes ? 10 : 24)) | 1));
            if (baseWin % 2 == 0) baseWin++;
            int[] wins = new int[] {baseWin, Math.max(31, baseWin + 8), Math.max(31, baseWin - 8)};
            double[] ks = new double[] {0.30, 0.34, 0.40};
            for (int wv : wins) {
              for (double kv : ks) {
                try {
                  Mat m = new Mat();
                  sauvolaThreshold(work, m, wv, kv, 128.0);
                  candidates.add(m);
                } catch (Throwable ignore) {
                  // Best-effort; failure is non-critical
                }
              }
            }
          }
          // Candidate D: Otsu (robust global)
          try {
            Mat m = new Mat();
            Imgproc.threshold(work, m, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            candidates.add(m);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          // Candidate E: Adaptive mean (device only)
          if (!isSafeMode()) {
            try {
              Mat m = new Mat();
              int bs =
                  Math.max(
                      31, ((Math.min(work.width(), work.height()) / (ocrLowRes ? 10 : 32)) | 1));
              if (bs % 2 == 0) bs++;
              int adaptC = ocrLowRes ? 3 : 5;
              Imgproc.adaptiveThreshold(
                  work, m, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, bs, adaptC);
              candidates.add(m);
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          }
          // Candidate F: Wolf binarization (better for uneven illumination, device only)
          if (!isSafeMode()) {
            int wolfWin =
                Math.max(31, ((Math.min(work.width(), work.height()) / (ocrLowRes ? 10 : 24)) | 1));
            if (wolfWin % 2 == 0) wolfWin++;
            double[] wolfKs = new double[] {0.25, 0.35, 0.45};
            for (double kv : wolfKs) {
              try {
                Mat m = new Mat();
                wolfThreshold(work, m, wolfWin, kv);
                candidates.add(m);
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
            }
          }
          // Candidate G: NICK binarization (better for low contrast, device only)
          if (!isSafeMode()) {
            int nickWin =
                Math.max(31, ((Math.min(work.width(), work.height()) / (ocrLowRes ? 10 : 24)) | 1));
            if (nickWin % 2 == 0) nickWin++;
            double[] nickKs = new double[] {-0.10, -0.14, -0.20};
            for (double kv : nickKs) {
              try {
                Mat m = new Mat();
                nickThreshold(work, m, nickWin, kv);
                candidates.add(m);
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
            }
          }
          // Pick best by lowest score
          double bestScore = Double.POSITIVE_INFINITY;
          int bestIdx = -1;
          for (int i = 0; i < candidates.size(); i++) {
            Mat m = candidates.get(i);
            double s = scoreBwQuality(m);
            if (s < bestScore) {
              bestScore = s;
              bestIdx = i;
            }
          }
          if (bestIdx >= 0) {
            candidates.get(bestIdx).copyTo(bw);
          } else {
            // Fallback: simple Otsu
            Imgproc.threshold(work, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
          }
        } finally {
          // release all candidates except the chosen one (bw already copied)
          for (Mat m : candidates) {
            try {
              m.release();
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
          }
        }

        // 5e) Post-binarization refinement
        //     - despeckle small salt/pepper
        //     - micro closing to reconnect thin strokes
        //     - connected components cleanup with dynamic thresholds
        //     Skip all post-processing for low-res images to preserve text readability
        if (!ocrLowRes) {
          try {
            despeckleFast(bw, 0); // Use default DPI (300) for OCR preparation
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          try {
            Mat kClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));
            Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, kClose);
            kClose.release();
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          try {
            int area = bw.rows() * bw.cols();
            int minArea = Math.max(10, area / 15000);
            int minHeight = Math.max(3, Math.min(10, Math.max(bw.rows(), bw.cols()) / 170));
            removeSmallComponents(bw, minArea, minHeight);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        }

        // 5f) Super-resolution scaling for small text (~24-32 px target glyph height)
        // Uses Lanczos interpolation + adaptive sharpening for best OCR quality
        try {
          int targetGlyphPx = 28; // slightly higher target for better recognition
          boolean scaled = superResolutionUpscale(bw, targetGlyphPx, /*maxScale*/ 2.5);
          if (!scaled) {
            // Fallback: ensure minimum resolution if glyph estimation failed
            int medH = estimateMedianComponentHeight(bw);
            if (medH <= 0) {
              ensureMinTextScaleLanczos(bw, /*minLongSide*/ 1900, /*scaleMax*/ 2.2);
            }
          }
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }

        // 7) Resize back to original input dimensions to keep API contract with callers/tests
        if (bw.cols() != src.getWidth() || bw.rows() != src.getHeight()) {
          Mat resized = new Mat();
          Imgproc.resize(
              bw, resized, new Size(src.getWidth(), src.getHeight()), 0, 0, Imgproc.INTER_AREA);
          bw.release();
          bw = resized;
        }
        // -> ARGB_8888
        out = Bitmap.createBitmap(bw.cols(), bw.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(bw, out);
        return out;
      } else {
        // 5b) Grayscale path (no hard threshold; good for already clean scans)
        // very light unsharp to increase edge contrast
        try {
          Mat blurred = new Mat();
          Imgproc.GaussianBlur(work, blurred, new Size(0, 0), 1.0);
          Core.addWeighted(work, 1.5, blurred, -0.5, 0, work);
          blurred.release();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }

        ensureMinTextScale(work, /*minLongSide*/ 1800, /*scaleMax*/ 2.0);

        // Resize back to original dimensions to preserve size
        if (work.cols() != src.getWidth() || work.rows() != src.getHeight()) {
          Mat resized = new Mat();
          Imgproc.resize(
              work, resized, new Size(src.getWidth(), src.getHeight()), 0, 0, Imgproc.INTER_AREA);
          work.release();
          work = resized;
        }

        out = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(work, out);
        return out;
      }

    } catch (Throwable t) {
      Log.e(TAG, "prepareForOCR failed", t);
      return null;
    } finally {
      release(rgba, gray, work, bw);
    }
  }

  /**
   * Ensures sufficient resolution for OCR by upscaling if the long side is below a threshold. Uses
   * INTER_CUBIC for quality; caps the scale to avoid memory blowups.
   */
  private static void ensureMinTextScale(
      Mat singleChannel /* CV_8U */, int minLongSide, double scaleMax) {
    int w = singleChannel.cols(), h = singleChannel.rows();
    int longSide = Math.max(w, h);
    if (longSide >= minLongSide) return;

    double scale = Math.min(scaleMax, (double) minLongSide / longSide);
    int nw = Math.max(1, (int) Math.round(w * scale));
    int nh = Math.max(1, (int) Math.round(h * scale));
    Mat tmp = new Mat();
    Imgproc.resize(singleChannel, tmp, new Size(nw, nh), 0, 0, Imgproc.INTER_CUBIC);
    tmp.copyTo(singleChannel);
    tmp.release();
  }

  /**
   * Ensures sufficient resolution for OCR using Lanczos interpolation with sharpening. Higher
   * quality than ensureMinTextScale for text upscaling.
   *
   * @param singleChannel the single-channel matrix (CV_8U) to upscale
   * @param minLongSide minimum length for the longer side
   * @param scaleMax maximum allowed scale factor
   */
  private static void ensureMinTextScaleLanczos(
      Mat singleChannel /* CV_8U */, int minLongSide, double scaleMax) {
    int w = singleChannel.cols(), h = singleChannel.rows();
    int longSide = Math.max(w, h);
    if (longSide >= minLongSide) return;

    double scale = Math.min(scaleMax, (double) minLongSide / longSide);
    int nw = Math.max(1, (int) Math.round(w * scale));
    int nh = Math.max(1, (int) Math.round(h * scale));

    Mat tmp = new Mat();
    // Use INTER_LANCZOS4 for highest quality text upscaling
    Imgproc.resize(singleChannel, tmp, new Size(nw, nh), 0, 0, Imgproc.INTER_LANCZOS4);

    // Apply sharpening to enhance text edges
    try {
      sharpenForOCR(tmp);
    } catch (Throwable ignore) {
      /* sharpening is optional */
    }

    tmp.copyTo(singleChannel);
    tmp.release();
  }

  /**
   * Prepares the given bitmap for OCR processing quickly and robustly using OpenCV. The method
   * applies a series of image preprocessing steps such as grayscale conversion, light enhancement,
   * noise reduction, binarization, and rescaling to ensure the image is optimized for OCR engines
   * like Tesseract, while maintaining efficiency.
   *
   * @param src The input bitmap to be prepared for OCR. Must not be null or recycled.
   * @return A processed bitmap in ARGB_8888 format optimized for OCR, or null if an error occurs or
   *     if the input bitmap is invalid (null or recycled).
   */
  public static Bitmap prepareForOCRQuick(Bitmap src) {
    if (src == null || src.isRecycled()) return null;

    Mat rgba = new Mat();
    Mat gray = new Mat();
    Mat bw = new Mat();
    Bitmap out = null;

    try {
      // 1) RGBA -> GRAY
      Utils.bitmapToMat(src, rgba);
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);

      // 2) Gently support low-light (no harsh brightening)
      if (isLowLight(gray)) {
        try {
          CLAHE clahe = Imgproc.createCLAHE(1.0, new Size(8, 8)); // very mild
          clahe.apply(gray, gray);
          clahe.collectGarbage();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }

      // 3) Light denoising
      Imgproc.medianBlur(gray, gray, 3);

      // 4) Otsu (no adaptive artifacts)
      Imgproc.threshold(gray, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

      // 5) Remove small disturbances
      despeckleFast(bw, 0); // Use default DPI (300) for OCR preparation

      // 5b) Clear border noise to remove edge artifacts that cause OCR errors
      clearBorderNoise(bw);

      // 6) Light upscaling if too small (max. ~1.6x)
      upscaleIfNeeded(bw, /*minLongSidePx*/ 1400, /*maxScale*/ 1.6);

      // 7) Resize back to original dimensions expected by callers/tests
      if (bw.cols() != src.getWidth() || bw.rows() != src.getHeight()) {
        Mat resized = new Mat();
        Imgproc.resize(
            bw, resized, new Size(src.getWidth(), src.getHeight()), 0, 0, Imgproc.INTER_AREA);
        bw.release();
        bw = resized;
      }
      // -> ARGB_8888 for Tesseract
      out = Bitmap.createBitmap(bw.cols(), bw.rows(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(bw, out);
      return out;

    } catch (Throwable t) {
      Log.e("OpenCVUtils", "prepareForOCRQuick failed", t);
      return null;
    } finally {
      release(rgba, gray, bw);
    }
  }

  /**
   * Upscales the given single-channel matrix if its longer side is smaller than the specified
   * minimum length. The scaling factor is determined based on the provided maximum scale and the
   * ratio between the desired minimum long side and the current long side. Uses Lanczos
   * interpolation for highest quality upscaling, followed by optional sharpening.
   *
   * @param singleChannel the single-channel matrix (CV_8U) that may be upscaled
   * @param minLongSide the minimum length for the longer side of the matrix
   * @param maxScale the maximum allowed scaling factor
   */
  private static void upscaleIfNeeded(
      Mat singleChannel /*CV_8U*/, int minLongSide, double maxScale) {
    int w = singleChannel.cols(), h = singleChannel.rows();
    int longSide = Math.max(w, h);
    if (longSide >= minLongSide) return;

    double scale = Math.min(maxScale, (double) minLongSide / longSide);
    int nw = Math.max(1, (int) Math.round(w * scale));
    int nh = Math.max(1, (int) Math.round(h * scale));

    Mat tmp = new Mat();
    // Use INTER_LANCZOS4 for highest quality upscaling (better than INTER_CUBIC for text)
    Imgproc.resize(singleChannel, tmp, new Size(nw, nh), 0, 0, Imgproc.INTER_LANCZOS4);

    // Apply mild sharpening to enhance text edges after upscaling
    try {
      sharpenForOCR(tmp);
    } catch (Throwable ignore) {
      /* sharpening is optional */
    }

    tmp.copyTo(singleChannel);
    tmp.release();
  }

  /**
   * Super-resolution upscaling optimized for small text in OCR. Uses Lanczos interpolation with
   * adaptive sharpening based on estimated glyph size. This method is more aggressive than
   * upscaleIfNeeded and targets a specific glyph height.
   *
   * @param singleChannel the single-channel matrix (CV_8U) to upscale
   * @param targetGlyphHeight the target median glyph height in pixels (typically 24-32 for OCR)
   * @param maxScale maximum allowed scale factor to prevent memory issues
   * @return true if upscaling was applied, false otherwise
   */
  private static boolean superResolutionUpscale(
      Mat singleChannel /*CV_8U*/, int targetGlyphHeight, double maxScale) {
    int medH = estimateMedianComponentHeight(singleChannel);
    if (medH <= 0 || medH >= targetGlyphHeight) return false;

    double scale = Math.min(maxScale, (double) targetGlyphHeight / medH);
    if (scale <= 1.05) return false;

    int w = singleChannel.cols(), h = singleChannel.rows();
    int nw = Math.max(1, (int) Math.round(w * scale));
    int nh = Math.max(1, (int) Math.round(h * scale));

    Mat tmp = new Mat();
    try {
      // Use INTER_LANCZOS4 for highest quality upscaling
      Imgproc.resize(singleChannel, tmp, new Size(nw, nh), 0, 0, Imgproc.INTER_LANCZOS4);

      // Apply adaptive sharpening - stronger for larger scale factors
      double sharpenStrength = Math.min(1.5, 0.5 + (scale - 1.0) * 0.5);
      sharpenForOCRAdaptive(tmp, sharpenStrength);

      tmp.copyTo(singleChannel);
      return true;
    } catch (Throwable t) {
      Log.w(TAG, "superResolutionUpscale failed", t);
      return false;
    } finally {
      tmp.release();
    }
  }

  /**
   * Applies mild unsharp masking to enhance text edges for OCR. Uses a small Gaussian blur and
   * subtracts it from the original to sharpen.
   *
   * @param gray single-channel CV_8U image to sharpen in-place
   */
  private static void sharpenForOCR(Mat gray) {
    sharpenForOCRAdaptive(gray, 1.0);
  }

  /**
   * Applies adaptive unsharp masking with configurable strength. Formula: sharpened = original +
   * strength * (original - blurred)
   *
   * @param gray single-channel CV_8U image to sharpen in-place
   * @param strength sharpening strength (0.5 = mild, 1.0 = normal, 1.5 = strong)
   */
  private static void sharpenForOCRAdaptive(Mat gray, double strength) {
    if (strength <= 0) return;

    // SAFE MODE: Skip sharpening which uses convertTo (can crash on emulators due to
    // unsupported SIMD instructions in OpenCV's parallel_for_ / Mat::convertTo)
    if (isSafeMode()) {
      Log.d(TAG, "sharpenForOCRAdaptive: Safe mode - skipping sharpening");
      return;
    }

    Mat blurred = new Mat();
    Mat sharpened = new Mat();
    try {
      // Small kernel for fine detail preservation
      Imgproc.GaussianBlur(gray, blurred, new Size(3, 3), 0);

      // Convert to float for precise arithmetic
      Mat grayF = new Mat();
      Mat blurredF = new Mat();
      gray.convertTo(grayF, CvType.CV_32F);
      blurred.convertTo(blurredF, CvType.CV_32F);

      // sharpened = original + strength * (original - blurred)
      Mat diff = new Mat();
      Core.subtract(grayF, blurredF, diff);
      Core.multiply(diff, new Scalar(strength), diff);
      Core.add(grayF, diff, sharpened);

      // Clamp to valid range and convert back
      Core.min(sharpened, new Scalar(255), sharpened);
      Core.max(sharpened, new Scalar(0), sharpened);
      sharpened.convertTo(gray, CvType.CV_8U);

      diff.release();
      grayF.release();
      blurredF.release();
    } finally {
      blurred.release();
      sharpened.release();
    }
  }

  // --- Heuristics for Robust binarization quality and component analysis ---

  /**
   * Scores a binarized image: lower is better. Penalizes excessive white coverage and many tiny
   * blobs. Note: Binary images have text=0 (black) and background=255 (white).
   * connectedComponentsWithStats treats non-zero pixels as foreground, so we must invert first.
   */
  private static double scoreBwQuality(Mat bw /* CV_8UC1 0/255, text=0, bg=255 */) {
    Mat inv = new Mat();
    Mat labels = new Mat();
    Mat stats = new Mat();
    Mat centroids = new Mat();
    try {
      int rows = bw.rows(), cols = bw.cols();
      if (rows <= 0 || cols <= 0) return Double.POSITIVE_INFINITY;
      int area = rows * cols;
      int white = Core.countNonZero(bw);
      double whiteFrac = Math.min(1.0, Math.max(0.0, white / (double) area));

      // Invert so text becomes white (foreground) for connectedComponents
      Core.bitwise_not(bw, inv);
      int n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S);
      int comp = Math.max(0, n - 1);
      int small = 0;
      int minArea = Math.max(12, area / 12000);
      for (int i = 1; i < n; i++) {
        int ai = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
        int hi = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
        if (ai < minArea || hi < 3) small++;
      }
      double smallRatio = (comp > 0) ? (double) small / comp : 1.0;
      double emptyPenalty = (comp == 0) ? 0.5 : 0.0;
      return smallRatio + whiteFrac * 0.6 + emptyPenalty;
    } catch (Throwable t) {
      return Double.POSITIVE_INFINITY;
    } finally {
      inv.release();
      labels.release();
      stats.release();
      centroids.release();
    }
  }

  /**
   * Removes connected components below given size/height thresholds (keeps punctuation by using
   * tiny limits). Note: Binary images have text=0 (black) and background=255 (white).
   * connectedComponentsWithStats treats non-zero pixels as foreground, so we must invert first.
   */
  private static void removeSmallComponents(
      Mat bw /* CV_8UC1 0/255, text=0, bg=255 */, int minArea, int minHeight) {
    Mat inv = new Mat();
    Mat labels = new Mat();
    Mat stats = new Mat();
    Mat centroids = new Mat();
    Mat mask = new Mat();
    try {
      // Invert so text becomes white (foreground) for connectedComponents
      Core.bitwise_not(bw, inv);
      int n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S);
      for (int i = 1; i < n; i++) {
        int ai = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
        int hi = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
        if (ai < minArea || hi < minHeight) {
          Core.compare(labels, new Scalar(i), mask, Core.CMP_EQ);
          bw.setTo(new Scalar(255), mask); // set to background (white)
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    } finally {
      inv.release();
      labels.release();
      stats.release();
      centroids.release();
      mask.release();
    }
  }

  /**
   * Estimates median height of text components to guide scaling; returns -1 if not available. Note:
   * Binary images have text=0 (black) and background=255 (white). connectedComponentsWithStats
   * treats non-zero pixels as foreground, so we must invert first.
   */
  private static int estimateMedianComponentHeight(Mat bw /* CV_8UC1 0/255, text=0, bg=255 */) {
    Mat inv = new Mat();
    Mat labels = new Mat();
    Mat stats = new Mat();
    Mat centroids = new Mat();
    try {
      // Invert so text becomes white (foreground) for connectedComponents
      Core.bitwise_not(bw, inv);
      int n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S);
      if (n <= 1) return -1;
      int rows = bw.rows(), cols = bw.cols();
      int imgArea = rows * cols;
      int minArea = Math.max(12, imgArea / 20000);
      int maxArea = Math.max(minArea + 1, imgArea / 5);
      List<Integer> heights = new ArrayList<>();
      for (int i = 1; i < n; i++) {
        int ai = (int) stats.get(i, Imgproc.CC_STAT_AREA)[0];
        int hi = (int) stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
        int wi = (int) stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
        if (ai < minArea || ai > maxArea) continue;
        if (hi < 3 || hi > rows * 0.6) continue;
        if (wi < 2 || wi > cols * 0.6) continue;
        heights.add(hi);
      }
      if (heights.isEmpty()) return -1;
      Collections.sort(heights);
      return heights.get(heights.size() / 2);
    } catch (Throwable t) {
      return -1;
    } finally {
      inv.release();
      labels.release();
      stats.release();
      centroids.release();
    }
  }

  // ===== New helpers for Robust pipeline =====

  /**
   * Estimates page skew (in degrees) and rotates the image content in-place to correct it. Uses
   * Hough transform on Canny edges; constrained to small angles to avoid over-rotation.
   */
  private static void deskewInPlace(Mat gray /* CV_8U */) {
    try {
      Mat edges = new Mat();
      Imgproc.Canny(gray, edges, 50, 150);
      Mat lines = new Mat();
      // Use standard HoughLines for robust angle estimation
      Imgproc.HoughLines(
          edges,
          lines,
          1,
          Math.PI / 180.0,
          Math.max(120, (int) (0.02 * Math.max(gray.rows(), gray.cols()))));
      double angleDeg = 0.0;
      if (lines.rows() > 0) {
        List<Double> angles = new ArrayList<>();
        for (int i = 0; i < Math.min(lines.rows(), 200); i++) {
          double[] v = lines.get(i, 0);
          double theta = v[1];
          double deg = Math.toDegrees(theta) - 90.0; // convert to line angle about horizontal
          if (deg < -45) deg += 180; // normalize
          if (deg > 45) deg -= 180;
          if (Math.abs(deg) <= 18.0) angles.add(deg);
        }
        if (!angles.isEmpty()) {
          Collections.sort(angles);
          angleDeg = angles.get(angles.size() / 2);
        }
      }
      edges.release();
      lines.release();
      if (Math.abs(angleDeg) > 0.3 && Math.abs(angleDeg) <= 18.0) {
        Point center = new Point(gray.cols() / 2.0, gray.rows() / 2.0);
        Mat rot = Imgproc.getRotationMatrix2D(center, -angleDeg, 1.0);
        Mat rotated = new Mat();
        Imgproc.warpAffine(
            gray,
            rotated,
            rot,
            gray.size(),
            Imgproc.INTER_LINEAR,
            Core.BORDER_CONSTANT,
            new Scalar(255));
        rotated.copyTo(gray);
        rot.release();
        rotated.release();
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Retinex-like illumination normalization: out = 255 * (log(gray+1) - log(blur(gray)+1)) scaled
   * to 0..1. sigma controls the blur kernel radius used for background estimation.
   */
  private static void retinexNormalize(Mat gray /* CV_8U */, int sigma) {
    int k = Math.max(3, (sigma | 1));
    Mat blur = new Mat();
    Mat f = new Mat();
    Mat fb = new Mat();
    Mat logI = new Mat();
    Mat logB = new Mat();
    Mat diff = new Mat();
    try {
      Imgproc.GaussianBlur(gray, blur, new Size(k, k), 0);
      gray.convertTo(f, CvType.CV_32F);
      blur.convertTo(fb, CvType.CV_32F);
      Core.add(f, new Scalar(1.0), f);
      Core.add(fb, new Scalar(1.0), fb);
      Core.log(f, logI);
      Core.log(fb, logB);
      Core.subtract(logI, logB, diff);
      Core.normalize(diff, diff, 0, 255, Core.NORM_MINMAX);
      diff.convertTo(gray, CvType.CV_8U);
    } finally {
      blur.release();
      f.release();
      fb.release();
      logI.release();
      logB.release();
      diff.release();
    }
  }

  /**
   * Sauvola local adaptive thresholding.
   *
   * @param src8u grayscale CV_8U
   * @param dst output binary CV_8U (0/255)
   * @param win odd window size for local statistics
   * @param k typically in [0.2, 0.5]
   * @param R dynamic range of standard deviation (typically 128 or 255)
   */
  private static void sauvolaThreshold(Mat src8u, Mat dst, int win, double k, double R) {
    if (win % 2 == 0) win++;
    int btype = CvType.CV_32F;
    Mat f = new Mat();
    Mat mean = new Mat();
    Mat sq = new Mat();
    Mat meanSq = new Mat();
    Mat var = new Mat();
    Mat stddev = new Mat();
    Mat thresh = new Mat();
    Mat mask = new Mat();
    try {
      src8u.convertTo(f, btype);
      Imgproc.boxFilter(f, mean, btype, new Size(win, win));
      Core.multiply(f, f, sq);
      Imgproc.boxFilter(sq, meanSq, btype, new Size(win, win));
      // var = E[x^2] - (E[x])^2
      Core.multiply(mean, mean, var);
      Core.subtract(meanSq, var, var);
      Core.max(var, new Scalar(0.0), var);
      Core.sqrt(var, stddev);

      // thresh = mean * (1 + k*((std/R) - 1))
      Mat stdDivR = new Mat();
      Core.divide(stddev, new Scalar(R), stdDivR);
      Mat tmp = new Mat();
      Core.subtract(stdDivR, new Scalar(1.0), tmp);
      Core.multiply(tmp, new Scalar(k), tmp);
      Core.add(tmp, new Scalar(1.0), tmp);
      Core.multiply(mean, tmp, thresh);

      // compare f > thresh -> 255 else 0
      Core.compare(f, thresh, mask, Core.CMP_GT);
      dst.create(src8u.size(), CvType.CV_8U);
      dst.setTo(new Scalar(0));
      dst.setTo(new Scalar(255), mask);
    } finally {
      f.release();
      mean.release();
      sq.release();
      meanSq.release();
      var.release();
      stddev.release();
      thresh.release();
      mask.release();
    }
  }

  /**
   * Wolf local adaptive thresholding. Similar to Sauvola but uses the global maximum standard
   * deviation as R, making it more robust for images with uneven illumination. Formula: T(x,y) =
   * mean * (1 + k * ((stddev / R) - 1)) where R = max(stddev) globally
   *
   * @param src8u grayscale CV_8U
   * @param dst output binary CV_8U (0/255)
   * @param win odd window size for local statistics
   * @param k typically in [0.2, 0.5]
   */
  private static void wolfThreshold(Mat src8u, Mat dst, int win, double k) {
    if (win % 2 == 0) win++;
    int btype = CvType.CV_32F;
    Mat f = new Mat();
    Mat mean = new Mat();
    Mat sq = new Mat();
    Mat meanSq = new Mat();
    Mat var = new Mat();
    Mat stddev = new Mat();
    Mat thresh = new Mat();
    Mat mask = new Mat();
    try {
      src8u.convertTo(f, btype);
      Imgproc.boxFilter(f, mean, btype, new Size(win, win));
      Core.multiply(f, f, sq);
      Imgproc.boxFilter(sq, meanSq, btype, new Size(win, win));
      // var = E[x^2] - (E[x])^2
      Core.multiply(mean, mean, var);
      Core.subtract(meanSq, var, var);
      Core.max(var, new Scalar(0.0), var);
      Core.sqrt(var, stddev);

      // Wolf's key difference: R = max(stddev) globally instead of fixed constant
      Core.MinMaxLocResult mmr = Core.minMaxLoc(stddev);
      double R = Math.max(1.0, mmr.maxVal); // avoid division by zero

      // Also compute global minimum pixel value for Wolf's full formula
      // Wolf formula: T = (1 - k) * mean + k * minVal + k * (stddev / R) * (mean - minVal)
      // Simplified variant closer to Sauvola: T = mean * (1 + k * ((stddev / R) - 1))
      Mat stdDivR = new Mat();
      Core.divide(stddev, new Scalar(R), stdDivR);
      Mat tmp = new Mat();
      Core.subtract(stdDivR, new Scalar(1.0), tmp);
      Core.multiply(tmp, new Scalar(k), tmp);
      Core.add(tmp, new Scalar(1.0), tmp);
      Core.multiply(mean, tmp, thresh);

      // compare f > thresh -> 255 else 0
      Core.compare(f, thresh, mask, Core.CMP_GT);
      dst.create(src8u.size(), CvType.CV_8U);
      dst.setTo(new Scalar(0));
      dst.setTo(new Scalar(255), mask);

      stdDivR.release();
      tmp.release();
    } finally {
      f.release();
      mean.release();
      sq.release();
      meanSq.release();
      var.release();
      stddev.release();
      thresh.release();
      mask.release();
    }
  }

  /**
   * NICK (Niblack Improved Contrast K-factor) local adaptive thresholding. An improved version of
   * Niblack that handles low contrast regions better. Formula: T(x,y) = mean + k * sqrt(stddev^2 +
   * mean^2) This avoids the issue of Niblack producing noise in uniform regions.
   *
   * @param src8u grayscale CV_8U
   * @param dst output binary CV_8U (0/255)
   * @param win odd window size for local statistics
   * @param k typically in [-0.2, -0.1] (negative values for dark text on light background)
   */
  private static void nickThreshold(Mat src8u, Mat dst, int win, double k) {
    if (win % 2 == 0) win++;
    int btype = CvType.CV_32F;
    Mat f = new Mat();
    Mat mean = new Mat();
    Mat sq = new Mat();
    Mat meanSq = new Mat();
    Mat var = new Mat();
    Mat thresh = new Mat();
    Mat mask = new Mat();
    try {
      src8u.convertTo(f, btype);
      Imgproc.boxFilter(f, mean, btype, new Size(win, win));
      Core.multiply(f, f, sq);
      Imgproc.boxFilter(sq, meanSq, btype, new Size(win, win));
      // var = E[x^2] - (E[x])^2
      Core.multiply(mean, mean, var);
      Core.subtract(meanSq, var, var);
      Core.max(var, new Scalar(0.0), var);

      // NICK formula: T = mean + k * sqrt(var + mean^2)
      // This is equivalent to: T = mean + k * sqrt(stddev^2 + mean^2)
      Mat meanSquared = new Mat();
      Core.multiply(mean, mean, meanSquared);
      Mat sumVarMeanSq = new Mat();
      Core.add(var, meanSquared, sumVarMeanSq);
      Mat sqrtTerm = new Mat();
      Core.sqrt(sumVarMeanSq, sqrtTerm);

      // thresh = mean + k * sqrtTerm
      Mat kTimesRoot = new Mat();
      Core.multiply(sqrtTerm, new Scalar(k), kTimesRoot);
      Core.add(mean, kTimesRoot, thresh);

      // compare f > thresh -> 255 else 0
      Core.compare(f, thresh, mask, Core.CMP_GT);
      dst.create(src8u.size(), CvType.CV_8U);
      dst.setTo(new Scalar(0));
      dst.setTo(new Scalar(255), mask);

      meanSquared.release();
      sumVarMeanSq.release();
      sqrtTerm.release();
      kTimesRoot.release();
    } finally {
      f.release();
      mean.release();
      sq.release();
      meanSq.release();
      var.release();
      thresh.release();
      mask.release();
    }
  }

  // --- Lightweight text orientation estimation (preview-time) ---

  /**
   * Result of {@link #estimateTextOrientation(Bitmap)}. bucket is 0 or 90 (degrees), representing a
   * coarse orientation class. confidence in [0..1], where higher means a clearer separation.
   *
   * @param bucketDeg 0 or 90
   * @param confidence 0..1
   */
  public record OrientationEstimate(int bucketDeg, double confidence) {
    public OrientationEstimate(int bucketDeg, double confidence) {
      this.bucketDeg = (bucketDeg == 90) ? 90 : 0;
      this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
  }

  /**
   * Estimate whether text runs mostly horizontally (0° bucket) or vertically (90° bucket).
   * Heuristic: Compare summed absolute Scharr/Sobel gradient magnitudes in X vs. Y. Horizontal text
   * lines produce stronger horizontal edge responses → |Gy| > |Gx|.
   *
   * @param uprightSmall an upright bitmap (already rotated by CameraX rotationDegrees), ideally <=
   *     720 px long side
   * @return OrientationEstimate with bucket 0 or 90 and a confidence 0..1
   */
  public static OrientationEstimate estimateTextOrientation(Bitmap uprightSmall) {
    try {
      if (uprightSmall == null || uprightSmall.getWidth() < 8 || uprightSmall.getHeight() < 8) {
        return new OrientationEstimate(0, 0.0);
      }
      // Convert to grayscale Mat
      Mat rgba = new Mat();
      Utils.bitmapToMat(uprightSmall, rgba);
      Mat gray = new Mat();
      Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
      rgba.release();

      // Downscale to speed up and reduce noise
      int maxSide = 512;
      int w = gray.cols(), h = gray.rows();
      int longSide = Math.max(w, h);
      if (longSide > maxSide) {
        double scale = (double) maxSide / longSide;
        Imgproc.resize(
            gray,
            gray,
            new Size(
                Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))),
            0,
            0,
            Imgproc.INTER_AREA);
      }

      // Light denoise and normalization
      Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);

      // Use Scharr if available, fallback to Sobel
      Mat gx = new Mat();
      Mat gy = new Mat();
      try {
        Imgproc.Scharr(gray, gx, CvType.CV_16S, 1, 0);
        Imgproc.Scharr(gray, gy, CvType.CV_16S, 0, 1);
      } catch (Throwable t) {
        Imgproc.Sobel(gray, gx, CvType.CV_16S, 1, 0, 3);
        Imgproc.Sobel(gray, gy, CvType.CV_16S, 0, 1, 3);
      }
      Mat agx = new Mat();
      Mat agy = new Mat();
      Core.convertScaleAbs(gx, agx);
      Core.convertScaleAbs(gy, agy);
      gx.release();
      gy.release();

      Scalar sumX = Core.sumElems(agx);
      Scalar sumY = Core.sumElems(agy);
      double sx = sumX.val[0];
      double sy = sumY.val[0];
      agx.release();
      agy.release();
      gray.release();

      double total = sx + sy;
      if (total <= 1e-3) {
        // Blank or too uniform
        return new OrientationEstimate(0, 0.0);
      }

      // If vertical gradient energy (Gy) dominates, we assume horizontal lines → 0° bucket
      int bucket = (sy >= sx) ? 0 : 90;
      double diff = Math.abs(sy - sx);
      double conf = Math.min(1.0, Math.max(0.0, diff / (total + 1e-6)));
      // Slightly compress confidence to be conservative
      conf = Math.max(0.0, Math.min(1.0, conf * 0.9));
      return new OrientationEstimate(bucket, conf);
    } catch (Throwable t) {
      return new OrientationEstimate(0, 0.0);
    }
  }
}
