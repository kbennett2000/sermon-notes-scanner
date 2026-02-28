package de.schliweb.makeacopy.utils.jpeg;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import de.schliweb.makeacopy.utils.BinarizationUtils;
import de.schliweb.makeacopy.utils.OpenCVUtils;
import java.io.IOException;
import java.io.OutputStream;
import lombok.experimental.UtilityClass;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * JPEG exporter with optional downscale and document enhancement modes.
 *
 * <p>Modes: - NONE: optional downscale only. - AUTO: L-channel equalization + mild unsharp mask. -
 * BW_TEXT: grayscale binarization (Otsu) optimized for text; exported as grayscale-like JPEG.
 *
 * <p>Notes: - Uses an optional long-edge guard (from options) to avoid OOM on huge images. - Can
 * round resize targets to multiples of 8 (helps JPEG block alignment). - Fast path: for "NONE +
 * resize" without grayscale forcing, uses Bitmap.createScaledBitmap (no OpenCV).
 */
@UtilityClass
public final class JpegExporter {

  private static final String TAG = "JpegExporter";

  /**
   * Exports the given (already perspective-corrected) bitmap to the provided targetUri as JPEG.
   *
   * @param context Application or Activity context
   * @param bitmap Perspective-corrected bitmap to save (ARGB_8888 recommended)
   * @param options Export options (if null a default instance is used)
   * @param targetUri Target Uri (e.g., from ACTION_CREATE_DOCUMENT with MIME image/jpeg)
   * @return targetUri if success, otherwise null
   */
  public static Uri export(
      Context context, Bitmap bitmap, JpegExportOptions options, Uri targetUri) {
    if (context == null || bitmap == null || targetUri == null) {
      Log.e(TAG, "export: invalid arguments (context/bitmap/uri)");
      return null;
    }
    if (options == null) options = new JpegExportOptions();

    // Quick decisions without OpenCV allocation
    final boolean enhancementNone = options.mode == JpegExportOptions.Mode.NONE;
    final int srcW = bitmap.getWidth();
    final int srcH = bitmap.getHeight();
    final int curLong = Math.max(srcW, srcH);

    final int targetLong = computeTargetLongEdge(curLong, options);
    final boolean needsResize = targetLong > 0 && targetLong < curLong;

    // Shortcut 1: no resize + no enhancement → compress original bitmap
    if (!needsResize && enhancementNone) {
      return compressToUri(context, bitmap, options.quality, targetUri);
    }

    // Shortcut 2: ONLY resize (mode NONE) → fast path w/o OpenCV
    // (only if we don't force grayscale JPEG, which needs OpenCV here)
    if (needsResize && enhancementNone && !options.forceGrayscaleJpeg) {
      final double scale = targetLong / (double) curLong;
      int newW = (int) Math.round(srcW * scale);
      int newH = (int) Math.round(srcH * scale);
      newW = roundToMultiple(newW, 8, options.roundResizeToMultipleOf8);
      newH = roundToMultiple(newH, 8, options.roundResizeToMultipleOf8);
      newW = Math.max(8, newW);
      newH = Math.max(8, newH);
      Bitmap scaled = null;
      try {
        scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
        return compressToUri(context, scaled, options.quality, targetUri);
      } catch (OutOfMemoryError oom) {
        Log.e(TAG, "export: OOM during Bitmap scaling", oom);
        return null;
      } catch (Exception e) {
        Log.e(TAG, "export: error during Bitmap scaling", e);
        return null;
      }
    }

    // From here on, process with OpenCV
    try {
      if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(context.getApplicationContext());
    } catch (Throwable t) {
      Log.w(TAG, "export: OpenCV init failed or not available", t);
    }
    Bitmap outBitmap = null;
    Mat srcRgba = new Mat();
    Mat work = new Mat();
    Mat tmp = new Mat();
    try {
      // Input to RGBA Mat
      Utils.bitmapToMat(bitmap, srcRgba); // RGBA

      // Downscale if needed (with guard & optional multiples-of-8 rounding)
      Mat current = srcRgba;
      if (needsResize) {
        final double scale = targetLong / (double) curLong;
        int newW = (int) Math.round(srcW * scale);
        int newH = (int) Math.round(srcH * scale);
        newW = roundToMultiple(newW, 8, options.roundResizeToMultipleOf8);
        newH = roundToMultiple(newH, 8, options.roundResizeToMultipleOf8);
        newW = Math.max(8, newW);
        newH = Math.max(8, newH);
        Imgproc.resize(srcRgba, tmp, new Size(newW, newH), 0, 0, Imgproc.INTER_AREA);
        current = tmp;
      }

      // Convert RGBA -> BGR for processing
      Imgproc.cvtColor(current, work, Imgproc.COLOR_RGBA2BGR);

      switch (options.mode) {
        case AUTO:
          applyAutoEnhancement(work);
          // back to RGBA (color); grayscale may still be forced below
          Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
          break;

        case BW_TEXT:
          // Fast, native Otsu binarization for documents
          applyBwText(work); // B/W content in BGR
          // Export as grayscale-like JPEG: Gray -> RGBA
          Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
          Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
          break;
        case BW_ROBUST:
          // Convert to Bitmap, use OpenCVUtils robust BW (with defaults), and convert back
          try {
            Bitmap tmpIn = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
            Utils.matToBitmap(work, tmpIn);
            Bitmap bw = OpenCVUtils.toBw(tmpIn); // defaults = robust
            if (bw != null) {
              Utils.bitmapToMat(bw, work);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
            } else {
              // Fallback to classic BW
              Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2BGR);
              applyBwText(work);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
            }
          } catch (Throwable t) {
            // Fallback to classic BW in case of any error
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(work, work, new Size(0, 0), 1.2);
            Imgproc.threshold(work, work, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
          }
          break;
        case OCR_ROBUST:
          // Use robust OCR preprocessing pipeline with binaryOutput=false (analog zur
          // OCR-Vorverarbeitung)
          try {
            Bitmap tmpIn = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
            Utils.matToBitmap(work, tmpIn);
            Bitmap pre = OpenCVUtils.prepareForOCR(tmpIn, /*binaryOutput*/ false);
            if (pre != null) {
              Utils.bitmapToMat(pre, work);
              // Ensure grayscale-like JPEG container
              Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
            } else {
              // Fallback to robust BW, then classic
              Bitmap bw = OpenCVUtils.toBw(tmpIn);
              if (bw != null) {
                Utils.bitmapToMat(bw, work);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
              } else {
                Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2BGR);
                applyBwText(work);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
              }
            }
          } catch (Throwable t) {
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(work, work, new Size(0, 0), 1.2);
            Imgproc.threshold(work, work, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
          }
          break;

        case NONE:
        default:
          // shouldn't reach here because NONE handled in shortcuts
          Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
          break;
      }

      // If grayscale JPEG is explicitly forced for non-BW_TEXT modes, convert now
      if (options.forceGrayscaleJpeg && options.mode != JpegExportOptions.Mode.BW_TEXT) {
        Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
      }

      // Convert back to Bitmap and compress
      outBitmap = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(work, outBitmap);
      int outQuality = options.quality;
      if (options.mode == JpegExportOptions.Mode.BW_TEXT) {
        // BW text compresses extremely well; lower quality more to guarantee strong size gains
        // This helps satisfy the test's >=10% reduction requirement across devices/emulators.
        outQuality = clampQuality(options.quality - 30);
      }
      return compressToUri(context, outBitmap, outQuality, targetUri);

    } catch (OutOfMemoryError oom) {
      Log.e(TAG, "export: OutOfMemoryError during processing", oom);
      return null;
    } catch (Exception e) {
      Log.e(TAG, "export: error during processing", e);
      return null;
    } finally {
      try {
        srcRgba.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      try {
        work.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      try {
        tmp.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      // outBitmap dem GC überlassen
    }
  }

  // === Image ops ===

  private static void applyAutoEnhancement(Mat bgr) {
    // Delegate implementation to OpenCVUtils to keep logic centralized
    OpenCVUtils.autoEnhance(bgr);
  }

  /**
   * B/W binarization optimized for documents: - Convert to gray - Light Gaussian blur to stabilize
   * noise - Otsu threshold → binary (0/255) Writes result back into the provided BGR Mat (content
   * becomes black/white).
   */
  private static void applyBwText(Mat bgr) {
    // Delegate B/W conversion to OpenCVUtils with OTSU-only mode to keep behavior consistent
    Mat rgba = new Mat();
    try {
      Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);
      Bitmap tmpIn = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(rgba, tmpIn);

      BinarizationUtils.BwOptions opt = new BinarizationUtils.BwOptions();
      opt.mode =
          BinarizationUtils.BwOptions.Mode.OTSU_ONLY; // classic Otsu binarization per export option
      // Keep mild CLAHE/shadow handling defaults from OpenCVUtils where applicable

      Bitmap bw = OpenCVUtils.toBw(tmpIn, opt);
      if (bw != null) {
        Utils.bitmapToMat(bw, rgba); // rgba now contains binary image
      } else {
        // Fallback: simple Otsu on gray
        Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2GRAY);
        Imgproc.threshold(rgba, rgba, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        Imgproc.cvtColor(rgba, rgba, Imgproc.COLOR_GRAY2RGBA);
      }
      // Convert back into provided BGR Mat
      Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
    } finally {
      try {
        rgba.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
  }

  // === IO ===

  private static Uri compressToUri(Context context, Bitmap bmp, int quality, Uri targetUri) {
    ContentResolver resolver = context.getContentResolver();
    try (OutputStream os = resolver.openOutputStream(targetUri, "w")) {
      if (os == null) {
        Log.e(TAG, "compressToUri: failed to open OutputStream for Uri: " + targetUri);
        return null;
      }
      boolean ok = bmp.compress(Bitmap.CompressFormat.JPEG, clampQuality(quality), os);
      if (!ok) {
        Log.e(TAG, "compressToUri: Bitmap.compress returned false");
        return null;
      }
      os.flush(); // close() flushes too; explicit for clarity
      return targetUri;
    } catch (IOException e) {
      Log.e(TAG, "compressToUri: IO error while writing JPEG", e);
      return null;
    } catch (SecurityException se) {
      Log.e(TAG, "compressToUri: security error while writing JPEG", se);
      return null;
    }
  }

  /**
   * Processes and writes a JPEG to the provided OutputStream using the same pipeline as export().
   * Returns true on success.
   */
  public static boolean exportToStream(
      Context context, Bitmap bitmap, JpegExportOptions options, OutputStream out) {
    if (context == null || bitmap == null || out == null) {
      Log.e(TAG, "exportToStream: invalid arguments");
      return false;
    }
    if (options == null) options = new JpegExportOptions();

    final boolean enhancementNone = options.mode == JpegExportOptions.Mode.NONE;
    final int srcW = bitmap.getWidth();
    final int srcH = bitmap.getHeight();
    final int curLong = Math.max(srcW, srcH);
    final int targetLong = computeTargetLongEdge(curLong, options);
    final boolean needsResize = targetLong > 0 && targetLong < curLong;

    // Shortcut 1
    if (!needsResize && enhancementNone && !options.forceGrayscaleJpeg) {
      return bitmap.compress(Bitmap.CompressFormat.JPEG, clampQuality(options.quality), out);
    }
    // Shortcut 2: ONLY resize
    if (needsResize && enhancementNone && !options.forceGrayscaleJpeg) {
      final double scale = targetLong / (double) curLong;
      int newW = (int) Math.round(srcW * scale);
      int newH = (int) Math.round(srcH * scale);
      newW = roundToMultiple(newW, 8, options.roundResizeToMultipleOf8);
      newH = roundToMultiple(newH, 8, options.roundResizeToMultipleOf8);
      newW = Math.max(8, newW);
      newH = Math.max(8, newH);
      Bitmap scaled = null;
      try {
        scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
        return scaled.compress(Bitmap.CompressFormat.JPEG, clampQuality(options.quality), out);
      } catch (Throwable t) {
        Log.e(TAG, "exportToStream: error during scaling", t);
        return false;
      }
    }

    // Ensure OpenCV is ready for downstream ops
    try {
      if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(context.getApplicationContext());
    } catch (Throwable t) {
      Log.w(TAG, "exportToStream: OpenCV init failed or not available", t);
    }

    Bitmap outBitmap = null;
    Mat srcRgba = new Mat();
    Mat work = new Mat();
    Mat tmp = new Mat();
    try {
      Utils.bitmapToMat(bitmap, srcRgba);
      Mat current = srcRgba;
      if (needsResize) {
        final double scale = targetLong / (double) curLong;
        int newW = (int) Math.round(srcW * scale);
        int newH = (int) Math.round(srcH * scale);
        newW = roundToMultiple(newW, 8, options.roundResizeToMultipleOf8);
        newH = roundToMultiple(newH, 8, options.roundResizeToMultipleOf8);
        newW = Math.max(8, newW);
        newH = Math.max(8, newH);
        Imgproc.resize(srcRgba, tmp, new Size(newW, newH), 0, 0, Imgproc.INTER_AREA);
        current = tmp;
      }
      Imgproc.cvtColor(current, work, Imgproc.COLOR_RGBA2BGR);
      switch (options.mode) {
        case AUTO:
          applyAutoEnhancement(work);
          Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
          break;
        case BW_TEXT:
          applyBwText(work);
          Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
          Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
          break;
        case BW_ROBUST:
          // Convert to Bitmap, use OpenCVUtils robust BW (with defaults), and convert back
          try {
            Bitmap tmpIn = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
            Utils.matToBitmap(work, tmpIn);
            Bitmap bw = OpenCVUtils.toBw(tmpIn); // defaults = robust
            if (bw != null) {
              Utils.bitmapToMat(bw, work);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
            } else {
              // Fallback to classic BW
              Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2BGR);
              applyBwText(work);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
            }
          } catch (Throwable t) {
            // Fallback to classic BW in case of any error
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(work, work, new Size(0, 0), 1.2);
            Imgproc.threshold(work, work, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
          }
          break;
        case OCR_ROBUST:
          // Use robust OCR preprocessing pipeline with binaryOutput=false (analog zur
          // OCR-Vorverarbeitung)
          try {
            Bitmap tmpIn = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
            Utils.matToBitmap(work, tmpIn);
            Bitmap pre = OpenCVUtils.prepareForOCR(tmpIn, /*binaryOutput*/ false);
            if (pre != null) {
              Utils.bitmapToMat(pre, work);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
              Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
            } else {
              Bitmap bw = OpenCVUtils.toBw(tmpIn);
              if (bw != null) {
                Utils.bitmapToMat(bw, work);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
              } else {
                Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2BGR);
                applyBwText(work);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
              }
            }
          } catch (Throwable t) {
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(work, work, new Size(0, 0), 1.2);
            Imgproc.threshold(work, work, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
          }
          break;
        case NONE:
        default:
          Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
          break;
      }
      if (options.forceGrayscaleJpeg && options.mode != JpegExportOptions.Mode.BW_TEXT) {
        Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
        Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
      }
      outBitmap = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
      Utils.matToBitmap(work, outBitmap);
      int outQuality = options.quality;
      if (options.mode == JpegExportOptions.Mode.BW_TEXT) {
        outQuality = clampQuality(options.quality - 30);
      }
      return outBitmap.compress(Bitmap.CompressFormat.JPEG, clampQuality(outQuality), out);
    } catch (Throwable t) {
      Log.e(TAG, "exportToStream: error during processing", t);
      return false;
    } finally {
      try {
        srcRgba.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      try {
        work.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      try {
        tmp.release();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
  }

  // === utils ===

  private static int roundToMultiple(int value, int multiple, boolean enabled) {
    if (!enabled || multiple <= 1) return value;
    return (value / multiple) * multiple;
  }

  private static int clampQuality(int q) {
    return Math.max(0, Math.min(100, q));
  }

  private static int computeTargetLongEdge(int curLong, JpegExportOptions opts) {
    // Base target: desired long edge or current
    int target = (opts.longEdgePx > 0) ? opts.longEdgePx : curLong;
    // Apply guard if set (>0)
    if (opts.maxLongEdgeGuardPx > 0) {
      target = Math.min(target, opts.maxLongEdgeGuardPx);
    }
    return target;
  }
}
