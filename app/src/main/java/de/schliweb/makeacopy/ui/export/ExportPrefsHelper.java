package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import android.content.SharedPreferences;
import de.schliweb.makeacopy.utils.PageFormat;
import de.schliweb.makeacopy.utils.PdfCreator;
import de.schliweb.makeacopy.utils.PdfQualityPreset;
import de.schliweb.makeacopy.utils.jpeg.JpegExportOptions;
import lombok.experimental.UtilityClass;

/**
 * Helper class that centralizes reading export-related SharedPreferences. Extracted from
 * ExportFragment to reduce repetitive preference access code.
 *
 * <p>This class cannot be instantiated.
 */
@UtilityClass
final class ExportPrefsHelper {

  private static final String PREFS_NAME = "export_options";

  static SharedPreferences getPrefs(Context context) {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  static boolean isIncludeOcr(Context context) {
    return getPrefs(context).getBoolean("include_ocr", false);
  }

  static boolean isExportAsJpeg(Context context) {
    return getPrefs(context).getBoolean("export_as_jpeg", false);
  }

  static boolean isSkipOcr(Context context) {
    return getPrefs(context).getBoolean("skip_ocr", false);
  }

  static String getPdfBwMode(Context context) {
    return getPrefs(context).getString("pdf_bw_mode", null);
  }

  static boolean isGrayscaleFromPdfMode(Context context) {
    return "GRAYSCALE".equalsIgnoreCase(getPdfBwMode(context));
  }

  static String getPdfPreset(Context context) {
    return getPrefs(context).getString("pdf_preset", null);
  }

  static String getPageFormatSaved(Context context) {
    return getPrefs(context).getString("page_format", null);
  }

  static PageFormat resolvePageFormat(Context context) {
    return PageFormat.fromName(getPageFormatSaved(context), PageFormat.FIT_TO_IMAGE);
  }

  static PdfQualityPreset resolvePreset(Context context, int pageCount) {
    String presetSaved = getPdfPreset(context);
    PdfQualityPreset def = (pageCount > 1) ? PdfQualityPreset.STANDARD : PdfQualityPreset.HIGH;
    return PdfQualityPreset.fromName(presetSaved, def);
  }

  static PdfCreator.BwMode resolveBwMode(Context context) {
    String bw = getPdfBwMode(context);
    if ("CLASSIC".equalsIgnoreCase(bw)) return PdfCreator.BwMode.CLASSIC;
    if ("ROBUST".equalsIgnoreCase(bw)) return PdfCreator.BwMode.ROBUST;
    if ("OCR_ROBUST".equalsIgnoreCase(bw)) return PdfCreator.BwMode.OCR_ROBUST;
    return null;
  }

  /**
   * Resolves effective grayscale and BW flags based on the pdf_bw_mode preference and the preset.
   *
   * @return a boolean array: [0] = convertGrayEffective, [1] = convertBwEffective
   */
  static boolean[] resolveGrayAndBwFlags(
      Context context, boolean presetForceGrayscale, boolean viewModelGrayscale) {
    String pdfModeSel = getPdfBwMode(context);
    String bwModeSaved = pdfModeSel;
    boolean convertBw =
        ("ROBUST".equalsIgnoreCase(bwModeSaved) || "CLASSIC".equalsIgnoreCase(bwModeSaved));
    boolean convertGray = presetForceGrayscale || viewModelGrayscale;

    if ("GRAYSCALE".equalsIgnoreCase(pdfModeSel)) {
      convertGray = true;
      convertBw = false;
    } else if ("CLASSIC".equalsIgnoreCase(pdfModeSel) || "ROBUST".equalsIgnoreCase(pdfModeSel)) {
      convertGray = false;
      convertBw = true;
    } else if ("OCR_ROBUST".equalsIgnoreCase(pdfModeSel)) {
      convertGray = false;
      convertBw = false;
    }
    return new boolean[] {convertGray, convertBw};
  }

  static JpegExportOptions.Mode resolveJpegMode(Context context) {
    String saved = getPrefs(context).getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name());
    try {
      return JpegExportOptions.Mode.valueOf(saved);
    } catch (IllegalArgumentException ex) {
      return JpegExportOptions.Mode.AUTO;
    }
  }

  static boolean isPendingAddPage(Context context) {
    return getPrefs(context).getBoolean("pending_add_page", false);
  }

  static void clearPendingAddPage(Context context) {
    getPrefs(context).edit().putBoolean("pending_add_page", false).apply();
  }

  static void setPendingAddPage(Context context) {
    getPrefs(context).edit().putBoolean("pending_add_page", true).apply();
  }
}
