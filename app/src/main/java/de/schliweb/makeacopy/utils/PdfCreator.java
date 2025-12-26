package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The PdfCreator class provides static methods for generating searchable PDFs from bitmap images
 * and recognized OCR words. It supports advanced options such as grayscale or black-and-white
 * conversion, DPI scaling, and robust font fallback for text rendering. This class allows
 * creation of both single-page and multi-page searchable PDFs.
 */
public class PdfCreator {
    private static final String TAG = "PdfCreator";
    // Text sizing (relative to OCR box height in image space)
    private static final float TEXT_SIZE_RATIO = 0.70f;
    private static final float MIN_FONT_PT = 2f; // lower bound for tiny boxes

    /**
     * Creates a searchable PDF document from a bitmap and a list of recognized words. The resulting PDF
     * includes an image of the provided bitmap and overlays it with a text layer derived from the recognized
     * words for searchability. The output PDF is written to the provided URI with specified image quality
     * and optional grayscale conversion.
     *
     * @param context            the Android context, used to access system resources and file utilities
     * @param bitmap             the bitmap to place into the PDF as an image
     * @param words              the list of recognized words to overlay as a text layer for searchability
     * @param outputUri          the URI of the output file where the generated PDF will be stored
     * @param jpegQuality        the compression quality of the bitmap within the PDF (0-100)
     * @param convertToGrayscale a flag indicating whether to convert the image to grayscale
     * @return the URI of the generated searchable PDF
     */
    public static Uri createSearchablePdf(Context context, Bitmap bitmap, List<RecognizedWord> words, Uri outputUri, int jpegQuality, boolean convertToGrayscale) {
        // Backward-compatible overload: no black-and-white flag -> false
        return createSearchablePdf(context, bitmap, words, outputUri, jpegQuality, convertToGrayscale, false, 300);
    }

    /**
     * Creates a searchable PDF document from a bitmap and a list of recognized words. The PDF contains
     * an image representation of the provided bitmap and includes a searchable text layer based on the
     * recognized words. The resulting file is saved to the specified output URI.
     *
     * @param context             the Android context, used to access system resources and file utilities
     * @param bitmap              the bitmap to include in the PDF as an image
     * @param words               a list of recognized words to overlay as a searchable text layer
     * @param outputUri           the URI where the generated PDF will be saved
     * @param jpegQuality         the compression quality (0-100) for the bitmap image in the PDF
     * @param convertToGrayscale  specifies whether the bitmap image should be converted to grayscale
     * @param convertToBlackWhite specifies whether the bitmap image should be converted to black-and-white
     * @param targetDpi           the target DPI (dots per inch) at which the image should be rendered in the PDF
     * @return the URI of the generated searchable PDF file
     */
    public static Uri createSearchablePdf(Context context, Bitmap bitmap, List<RecognizedWord> words, Uri outputUri, int jpegQuality, boolean convertToGrayscale, boolean convertToBlackWhite, int targetDpi) {
        // Back-compat: default to ROBUST when BW is requested
        return createSearchablePdf(context, bitmap, words, outputUri, jpegQuality, convertToGrayscale, convertToBlackWhite, targetDpi, convertToBlackWhite ? BwMode.ROBUST : null);
    }

    /**
     * Creates a searchable PDF from the given bitmap and recognized text words, and writes it to the specified URI.
     * The method allows customization of image processing, PDF properties, and OCR text handling.
     *
     * @param context             The application context used for PDF library initialization and resource access.
     * @param bitmap              The input bitmap image to be included in the PDF.
     * @param words               A list of recognized words with their coordinates, used to create the text layer in the PDF.
     * @param outputUri           The URI where the generated PDF will be saved.
     * @param jpegQuality         The quality of the JPEG compression for the image in the range of 0-100 (100 for lossless).
     * @param convertToGrayscale  True to convert the image to grayscale before adding it to the PDF.
     * @param convertToBlackWhite True to convert the image to black-and-white using thresholding techniques.
     * @param targetDpi           The target resolution (dots per inch) for the output image in the PDF.
     * @param bwMode              The black-and-white conversion mode (e.g., robust or simple thresholding). Ignored if {@code convertToBlackWhite} is false.
     * @return The URI pointing to the created PDF, or null if the PDF creation failed.
     */
    public static Uri createSearchablePdf(Context context, Bitmap bitmap, List<RecognizedWord> words, Uri outputUri, int jpegQuality, boolean convertToGrayscale, boolean convertToBlackWhite, int targetDpi, BwMode bwMode) {
        Log.d(TAG, "createSearchablePdf: uri=" + outputUri + ", words=" + (words == null ? 0 : words.size()));
        if (bitmap == null || outputUri == null) return null;

        try {
            PDFBoxResourceLoader.init(context);
            try {
                OpenCVUtils.init(context);
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            Log.e(TAG, "PDFBox init failed", t);
            return null;
        }

        Bitmap prepared = null;
        try {
            prepared = processImageForPdf(bitmap, convertToGrayscale, convertToBlackWhite, targetDpi, bwMode);
            if (prepared == null) {
                Log.e(TAG, "Image preparation via OpenCV failed");
                return null;
            }

            try (PDDocument document = new PDDocument()) {
                try {
                    document.getDocument().setVersion(1.5f);
                } catch (Throwable ignore) {
                }
                document.getDocumentInformation().setCreator("MakeACopy");
                document.getDocumentInformation().setProducer("MakeACopy");

                PDRectangle pageSize = PDRectangle.A4;
                float pageW = pageSize.getWidth();
                float pageH = pageSize.getHeight();

                PDPage page = new PDPage(pageSize);
                // Harmonize page boxes to avoid viewer-specific cropping/offset interpretations
                try {
                    page.setMediaBox(pageSize);
                    page.setCropBox(pageSize);
                    page.setBleedBox(pageSize);
                    page.setTrimBox(pageSize);
                    page.setArtBox(pageSize);
                } catch (Throwable ignore) {
                }
                document.addPage(page);

                // Fit image into page while preserving aspect ratio (letterboxing if needed)
                float scale = calculateScale(prepared.getWidth(), prepared.getHeight(), pageW, pageH);
                float drawW = prepared.getWidth() * scale;
                float drawH = prepared.getHeight() * scale;
                float offsetX = (pageW - drawW) / 2f;
                float offsetY = (pageH - drawH) / 2f;

                float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
                PDImageXObject pdImg = (jpegQuality < 100) ? JPEGFactory.createFromImage(document, prepared, q) : LosslessFactory.createFromImage(document, prepared);

                // Load embedded fonts with fallbacks (file-based; subset-embedded by default)
                List<PDFont> fonts = loadFontsWithFallbacks(document, context);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    // 1) Draw image in page coordinates
                    cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);

                    // 2) Draw OCR text in the EXACT SAME transform as the image
                    if (words != null && !words.isEmpty()) {
                        cs.saveGraphicsState();
                        cs.transform(new Matrix(scale, 0, 0, scale, offsetX, offsetY)); // identical CTM
                        // Normalize OCR boxes from source bitmap space to prepared bitmap space if needed
                        List<RecognizedWord> normWords;
                        if (bitmap.getWidth() != prepared.getWidth() || bitmap.getHeight() != prepared.getHeight()) {
                            float sxImg = (float) prepared.getWidth() / (float) bitmap.getWidth();
                            float syImg = (float) prepared.getHeight() / (float) bitmap.getHeight();
                            normWords = new ArrayList<>(words.size());
                            for (RecognizedWord w : words) {
                                normWords.add(w.transform(sxImg, syImg, 0f, 0f).clipTo(prepared.getWidth(), prepared.getHeight()));
                            }
                        } else {
                            normWords = words;
                        }
                        Log.d(TAG, "createSearchablePdf: " + normWords.size() + " OCR words");
                        // now output text in IMAGE coordinates (0..imgW / 0..imgH)
                        addTextLayerImageSpace(cs, normWords, fonts, prepared.getWidth(), prepared.getHeight());
                        cs.restoreGraphicsState();
                    }
                }

                try (OutputStream os = context.getContentResolver().openOutputStream(outputUri)) {
                    if (os == null) {
                        Log.e(TAG, "createSearchablePdf: openOutputStream returned null");
                        return null;
                    }
                    document.save(os);
                }
                return outputUri;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating PDF", e);
            return null;
        } finally {
            if (prepared != null && prepared != bitmap) {
                try {
                    prepared.recycle();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /**
     * Loads a list of PDF fonts with fallback mechanisms based on provided font files.
     * The method attempts to load specified fonts from the application's assets. If none
     * of the specified fonts are found or successfully loaded, it falls back to a default
     * font configuration, using Helvetica as a last resort.
     *
     * @param document the PDF document instance where fonts will be loaded into
     * @param context  the Android context used for asset access and caching
     * @return a list of PDFont objects, including the successfully loaded fonts and any fallbacks
     */
    private static List<PDFont> loadFontsWithFallbacks(PDDocument document, Context context) {
        List<PDFont> fonts = new ArrayList<>();
        // Minimal & memory-efficient:
        // - Removed Symbols2 & CJKtc (largest memory hogs)
        // - File-based loading avoids MemoryTTFDataStream
        //   Note: In pdfbox-android, the File overload is subset-embedded by default.
        String[] candidates = new String[]{"fonts/NotoSans-Regular.ttf",          // Latin
                "fonts/NotoSansThai-Regular.ttf",      // Thai
                "fonts/NotoSansCJKsc-Regular.ttf",     // CJK (Han) – one font is sufficient for the invisible layer
                "fonts/NotoNaskhArabic-Regular.ttf",   // Arabic (optional)
                "fonts/NotoSansDevanagari-Regular.ttf" // Indic (optional)
        };
        for (String path : candidates) {
            try {
                File f = copyAssetToCache(context, path);
                Log.d(TAG, "Loading font (file-based): " + path + " (" + f.length() + " bytes)");
                fonts.add(PDType0Font.load(document, f)); // subset-embedded by default
            } catch (Exception exception) {
                Log.w(TAG, "Font not found: " + path);
                Log.w(TAG, "Exception: " + exception.getMessage());
            }
        }
        if (fonts.isEmpty()) {
            // Last resort: try the base NotoSans; if missing, fall back to Helvetica (not embedded)
            try {
                File f = copyAssetToCache(context, "fonts/NotoSans-Regular.ttf");
                fonts.add(PDType0Font.load(document, f)); // subset-embedded
            } catch (Exception e) {
                Log.w(TAG, "No embedded font found, falling back to Helvetica (not embedded)");
                fonts.add(PDType1Font.HELVETICA);
            }
        }
        return fonts;
    }

    /**
     * Copies a file from the assets directory to the cache directory. If the file already exists
     * in the cache directory and has a non-zero length, it is returned as-is. Otherwise, the file
     * is copied from the assets directory to the cache directory.
     *
     * @param ctx       the context used to access the assets and cache directories
     * @param assetPath the relative path of the asset to be copied
     * @return the file object pointing to the copied file in the cache directory
     * @throws java.io.IOException if an I/O error occurs during copying
     */
    private static File copyAssetToCache(Context ctx, String assetPath) throws java.io.IOException {
        File out = new File(ctx.getCacheDir(), new File(assetPath).getName());
        if (out.exists() && out.length() > 0) return out;
        try (InputStream in = ctx.getAssets().open(assetPath); FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
        }
        return out;
    }

    /**
     * Writes text to a PDF page content stream with support for font fallbacks.
     * Ensures that the text is displayed using the most suitable font from the provided list of fonts.
     * If no suitable font is found for a character, it substitutes the character with the Unicode replacement character (�).
     *
     * @param cs       the {@link PDPageContentStream} used to draw the text
     * @param token    the text string to be displayed
     * @param fontSize the font size for rendering the text
     * @param fonts    a {@link List} of {@link PDFont} objects to try for displaying the text. The order of fonts in the list determines the priority of fallback.
     * @throws Exception             if there is an error during text drawing or font operations
     * @throws IllegalStateException if the fonts list is null or empty
     */
    private static void showTextWithFallbacks(PDPageContentStream cs, String token, float fontSize, List<PDFont> fonts) throws Exception {
        if (token == null || token.isEmpty()) return;
        if (fonts == null || fonts.isEmpty()) throw new IllegalStateException("No fonts available");

        final int len = token.length();
        int i = 0;
        while (i < len) {
            // 1) Find a suitable font for this code point
            int cp = token.codePointAt(i);
            String ch = new String(Character.toChars(cp));

            int fontIdx = -1;
            for (int f = 0; f < fonts.size(); f++) {
                try {
                    fonts.get(f).encode(ch); // probe-encode
                    fontIdx = f;
                    break;
                } catch (Exception ignore) {
                }
            }
            if (fontIdx < 0) {
                // Fallback: replace with � using the first font
                cs.setFont(fonts.get(0), fontSize);
                try {
                    cs.showText("\uFFFD");
                } catch (Exception ignore) {
                    // Ignore if even that fails
                }
                i += Character.charCount(cp);
                continue;
            }

            // 2) Collect a run using the same font
            StringBuilder run = new StringBuilder();
            run.append(ch);
            int j = i + Character.charCount(cp);
            while (j < len) {
                int cp2 = token.codePointAt(j);
                String ch2 = new String(Character.toChars(cp2));
                try {
                    fonts.get(fontIdx).encode(ch2);
                    run.append(ch2);
                    j += Character.charCount(cp2);
                } catch (Exception notThisFont) {
                    break; // different font required → end current run
                }
            }

            // 3) Output the run (robustly guarded)
            cs.setFont(fonts.get(fontIdx), fontSize);
            try {
                cs.showText(run.toString());
            } catch (Exception e) {
                // If the entire run fails, at least output a replacement glyph
                try {
                    cs.showText("\uFFFD");
                } catch (Exception ignore) {
                    // worst case: ignore completely
                }
            }

            i = j;
        }
    }

    /**
     * Adds a text layer to the PDF page content stream using image-space coordinates.
     * This method processes a list of recognized words, validates their bounding boxes, clusters them into lines,
     * and renders them as invisible but selectable text on the PDF document.
     *
     * @param cs          The content stream of the PDF page where the text layer will be added.
     * @param words       The list of recognized words that contain text and their respective bounding boxes.
     * @param fonts       A list of fonts used to render the text. The method handles font fallbacks if necessary.
     * @param imageWidth  The width of the image, used to clamp and transform bounding box coordinates.
     * @param imageHeight The height of the image, used to clamp and transform bounding box coordinates.
     * @throws Exception If there is an error while adding the text layer to the content stream.
     */
    private static void addTextLayerImageSpace(PDPageContentStream cs, List<RecognizedWord> words, List<PDFont> fonts, int imageWidth, int imageHeight) throws Exception {
        if (words == null || words.isEmpty()) return;

        // --- 1) Clean and validate ---
        List<RecognizedWord> clean = new ArrayList<>(words.size());
        for (RecognizedWord w : words) {
            if (w == null || w.getBoundingBox() == null) continue;
            RectF b = new RectF(w.getBoundingBox());

            // Replace NaN/Inf with 0
            if (!Float.isFinite(b.left)) b.left = 0f;
            if (!Float.isFinite(b.top)) b.top = 0f;
            if (!Float.isFinite(b.right)) b.right = 0f;
            if (!Float.isFinite(b.bottom)) b.bottom = 0f;

            // Ensure normal ordering
            if (b.right < b.left) {
                float t = b.left;
                b.left = b.right;
                b.right = t;
            }
            if (b.bottom < b.top) {
                float t = b.top;
                b.top = b.bottom;
                b.bottom = t;
            }

            // Clip to image bounds
            b.left = clamp(b.left, 0f, imageWidth);
            b.right = clamp(b.right, 0f, imageWidth);
            b.top = clamp(b.top, 0f, imageHeight);
            b.bottom = clamp(b.bottom, 0f, imageHeight);

            // Discard if too small/empty
            if (b.width() <= 0.5f || b.height() <= 0.5f) continue;

            // Use a copy with the clipped rect
            clean.add(new RecognizedWord(w.getText(), b, w.getConfidence()));
        }
        if (clean.isEmpty()) return;

        // Compute dynamic tolerance based on median word height.
        // Words within this Y-distance are considered on the same line.
        final float lineToleranceY = Math.max(6f, medianHeight(clean));

        // Stable tie-breaker: original index
        java.util.IdentityHashMap<RecognizedWord, Integer> idx = new java.util.IdentityHashMap<>();
        for (int i = 0; i < clean.size(); i++) idx.put(clean.get(i), i);

        // --- 2) Sort words by Y-center first, then by X (left-to-right) ---
        // This ensures we process words top-to-bottom, left-to-right
        clean.sort((a, b) -> {
            float yCenterA = (a.getBoundingBox().top + a.getBoundingBox().bottom) * 0.5f;
            float yCenterB = (b.getBoundingBox().top + b.getBoundingBox().bottom) * 0.5f;
            int c = Float.compare(yCenterA, yCenterB);
            if (c != 0) return c;
            c = Float.compare(a.getBoundingBox().left, b.getBoundingBox().left);
            if (c != 0) return c;
            return Integer.compare(idx.get(a), idx.get(b));
        });

        // --- 3) Cluster into lines using proximity-based grouping ---
        // Words are on the same line if their Y-centers are within lineToleranceY
        // of the line's reference Y (first word's Y-center in that line)
        List<List<RecognizedWord>> lines = new ArrayList<>();
        for (RecognizedWord w : clean) {
            float yCenter = (w.getBoundingBox().top + w.getBoundingBox().bottom) * 0.5f;

            // Try to find an existing line where this word fits
            boolean added = false;
            for (List<RecognizedWord> line : lines) {
                if (line.isEmpty()) continue;
                // Use the average Y-center of the line for comparison
                float lineYSum = 0f;
                for (RecognizedWord lw : line) {
                    lineYSum += (lw.getBoundingBox().top + lw.getBoundingBox().bottom) * 0.5f;
                }
                float lineYAvg = lineYSum / line.size();

                if (Math.abs(yCenter - lineYAvg) <= lineToleranceY) {
                    line.add(w);
                    added = true;
                    break;
                }
            }

            if (!added) {
                // Start a new line
                List<RecognizedWord> newLine = new ArrayList<>();
                newLine.add(w);
                lines.add(newLine);
            }
        }

        // Sort lines by their average Y position (top to bottom)
        lines.sort((lineA, lineB) -> {
            float avgYA = 0f, avgYB = 0f;
            for (RecognizedWord w : lineA) avgYA += (w.getBoundingBox().top + w.getBoundingBox().bottom) * 0.5f;
            for (RecognizedWord w : lineB) avgYB += (w.getBoundingBox().top + w.getBoundingBox().bottom) * 0.5f;
            avgYA /= lineA.size();
            avgYB /= lineB.size();
            return Float.compare(avgYA, avgYB);
        });

        // --- 4) Render: line L→R or R→L (for RTL scripts); invisible/selectable ---
        for (List<RecognizedWord> line : lines) {
            if (line.isEmpty()) continue;

            // Detect if this line is predominantly RTL (Arabic/Persian/Hebrew)
            boolean isRtl = isRtlLine(line);

            // within the line: left→right for LTR, right→left for RTL
            line.sort((a, b) -> {
                int c;
                if (isRtl) {
                    // RTL: sort right→left (descending X)
                    c = Float.compare(b.getBoundingBox().left, a.getBoundingBox().left);
                } else {
                    // LTR: sort left→right (ascending X)
                    c = Float.compare(a.getBoundingBox().left, b.getBoundingBox().left);
                }
                if (c != 0) return c;
                c = Float.compare(a.getBoundingBox().top, b.getBoundingBox().top);
                if (c != 0) return c;
                return Integer.compare(idx.get(a), idx.get(b));
            });

            float medianH = medianHeight(line);                // px in image
            float fontSize = Math.max(MIN_FONT_PT, medianH * TEXT_SIZE_RATIO);

            for (RecognizedWord w : line) {
                String tokenRaw = safeText(w.getText());
                if (tokenRaw.trim().isEmpty()) continue;

                // NFC + trailing space improves selection/copy
                String token = java.text.Normalizer.normalize(tokenRaw + " ", java.text.Normalizer.Form.NFC);

                RectF b = w.getBoundingBox();
                float boxH = b.height();

                // Baseline roughly at the lower quarter of the box area (image Y increases downward)
                float baselineImgY = b.bottom + boxH * 0.25f;

                // Invert Y (image space → PDF Y-up)
                float x_img = clamp(b.left, 0f, imageWidth);
                float y_img = clamp((imageHeight - baselineImgY), 0f, imageHeight);

                cs.beginText();
                try {
                    cs.setRenderingMode(RenderingMode.NEITHER); // unsichtbar aber auswählbar
                    cs.setTextMatrix(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(x_img, y_img));
                    showTextWithFallbacks(cs, token, fontSize, fonts);
                } finally {
                    cs.endText();
                }
            }
        }
    }

    /**
     * Calculates the scale factor required to fit an image within a given page size while maintaining its aspect ratio.
     *
     * @param imageWidth  the width of the image
     * @param imageHeight the height of the image
     * @param pageWidth   the width of the page
     * @param pageHeight  the height of the page
     * @return the scale factor to fit the image within the page dimensions
     */
    private static float calculateScale(int imageWidth, int imageHeight, float pageWidth, float pageHeight) {
        float sx = pageWidth / imageWidth;
        float sy = pageHeight / imageHeight;
        return Math.min(sx, sy);
    }

    /**
     * Processes an image to prepare it for inclusion in a PDF by resizing, and optionally converting it to grayscale or
     * black and white (BW) based on the parameters provided.
     *
     * @param original  The original Bitmap image to process. Must not be null.
     * @param toGray    If true, the image will be converted to grayscale.
     * @param toBw      If true, the image will be converted to black and white. The conversion respects the specified bwMode.
     *                  If both toGray and toBw are true, the image will be converted to black and white.
     * @param targetDpi The target DPI (dots per inch) for the processed image. Determines the resizing scale for the image.
     *                  If targetDpi is less than or equal to 0, a default DPI of 300 will be used.
     * @param bwMode    The mode for black-and-white conversion. If null, the default "ROBUST" mode will be used. Other modes
     *                  determine the specific method of B&W processing.
     * @return A processed Bitmap object resized to match the target DPI dimensions and optionally converted to grayscale
     * or black and white. Returns null if the original Bitmap is null or if an error occurs during conversion.
     */
    private static Bitmap processImageForPdf(Bitmap original, boolean toGray, boolean toBw, int targetDpi, BwMode bwMode) {
        if (original == null) return null;

        int[] a4px = a4PixelsForDpi(targetDpi <= 0 ? 300 : targetDpi);
        int maxW = a4px[0];
        int maxH = a4px[1];

        boolean preScaled = Math.abs(original.getWidth() - maxW) <= 1 && Math.abs(original.getHeight() - maxH) <= 1;

        Bitmap base = original;

        if (!preScaled) {
            float scale = 1f;
            if (original.getWidth() > maxW || original.getHeight() > maxH) {
                float sw = (float) maxW / original.getWidth();
                float sh = (float) maxH / original.getHeight();
                scale = Math.min(sw, sh);
            }

            if (scale < 1f) {
                int w = Math.max(1, Math.round(original.getWidth() * scale));
                int h = Math.max(1, Math.round(original.getHeight() * scale));
                base = Bitmap.createScaledBitmap(original, w, h, true);
            }
        }

        if (toBw) {
            Bitmap viaCv;
            if (bwMode == null || bwMode == BwMode.ROBUST) {
                viaCv = OpenCVUtils.toBw(base);
            } else {
                OpenCVUtils.BwOptions opt = new OpenCVUtils.BwOptions();
                opt.mode = OpenCVUtils.BwOptions.Mode.OTSU_ONLY;
                opt.useClahe = false;
                opt.removeShadows = false;
                viaCv = OpenCVUtils.toBw(base, opt);
            }
            if (base != original) {
                try {
                    base.recycle();
                } catch (Throwable ignore) {
                }
            }
            return viaCv; // may be null → caller will handle
        }

        // OCR-robust preprocessing for PDF: use binary output to ensure B/W embedding (preview may remain grayscale)
        if (bwMode == BwMode.OCR_ROBUST && !toBw) {
            Bitmap pre = OpenCVUtils.prepareForOCR(base, /*binaryOutput*/ false);
            if (base != original) {
                try {
                    base.recycle();
                } catch (Throwable ignore) {
                }
            }
            if (pre != null) return pre;
            // Fallback: keep base as-is
            return base;
        }

        if (!toGray) return base;

        Bitmap viaCvGray = OpenCVUtils.toGray(base);
        if (base != original) {
            try {
                base.recycle();
            } catch (Throwable ignore) {
            }
        }
        return viaCvGray; // may be null → caller will handle
    }

    /**
     * Calculates the pixel dimensions of an A4 paper size at a given DPI (dots per inch).
     *
     * @param dpi the resolution in dots per inch used to calculate the dimensions.
     * @return an array of two integers where the first element is the width in pixels
     * and the second element is the height in pixels.
     */
    private static int[] a4PixelsForDpi(int dpi) {
        // A4 size in inches: 8.27 x 11.69
        int w = Math.max(1, Math.round(8.27f * dpi));
        int h = Math.max(1, Math.round(11.69f * dpi));
        return new int[]{w, h};
    }

    /**
     * Calculates the median height of bounding boxes from a list of recognized words.
     *
     * @param line a list of RecognizedWord objects, each containing a bounding box with a height value
     * @return the median height of the bounding boxes; returns 0 if the list is empty
     */
    private static float medianHeight(List<RecognizedWord> line) {
        List<Float> heights = new ArrayList<>();
        for (RecognizedWord w : line) heights.add(w.getBoundingBox().height());
        Collections.sort(heights);
        int n = heights.size();
        if (n == 0) return 0;
        if (n % 2 == 1) return heights.get(n / 2);
        return (heights.get(n / 2 - 1) + heights.get(n / 2)) / 2f;
    }

    /**
     * Ensures that the given text is sanitized by replacing all control characters,
     * except for carriage return, new line, and tab, with a space.
     *
     * @param t the input text to sanitize; can be null.
     * @return the sanitized text, or an empty string if the input is null.
     */
    private static String safeText(String t) {
        if (t == null) return "";
        return t.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ");
    }

    /**
     * Restricts a given value to lie within the specified minimum and maximum bounds.
     *
     * @param v   The value to be clamped.
     * @param min The minimum bound to which the value can be clamped.
     * @param max The maximum bound to which the value can be clamped.
     * @return The clamped value, lying between the specified minimum and maximum bounds.
     */
    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Determines if a line of recognized words is predominantly RTL (Right-to-Left) script.
     * This is used for BiDi sorting in PDF text layers to ensure correct reading order
     * for Arabic, Persian, Hebrew, and other RTL scripts.
     *
     * @param line The list of recognized words in the line.
     * @return true if the line contains predominantly RTL characters, false otherwise.
     */
    private static boolean isRtlLine(List<RecognizedWord> line) {
        if (line == null || line.isEmpty()) return false;

        int rtlCount = 0;
        int ltrCount = 0;

        for (RecognizedWord w : line) {
            String text = w.getText();
            if (text == null) continue;

            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                byte directionality = Character.getDirectionality(cp);

                // RTL scripts: Arabic, Hebrew, etc.
                if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                        directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                        directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
                        directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
                    rtlCount++;
                }
                // LTR scripts: Latin, etc.
                else if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT ||
                        directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING ||
                        directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
                    ltrCount++;
                }
                // Neutral characters (numbers, punctuation) are ignored

                i += Character.charCount(cp);
            }
        }

        // Line is RTL if it has more RTL characters than LTR characters
        return rtlCount > ltrCount;
    }

    /**
     * Creates a searchable PDF from the given bitmaps and recognized words per page.
     *
     * @param context            the application context
     * @param bitmaps            a list of bitmaps representing the pages of the PDF
     * @param perPageWords       a list of lists containing recognized words for each page
     * @param outputUri          the URI where the generated PDF will be saved
     * @param jpegQuality        the JPEG quality for image compression (0-100)
     * @param convertToGrayscale a flag indicating if the images should be converted to grayscale
     * @return the URI of the created searchable PDF
     */
    public static Uri createSearchablePdf(Context context, List<Bitmap> bitmaps, List<List<RecognizedWord>> perPageWords, Uri outputUri, int jpegQuality, boolean convertToGrayscale) {
        // Backward-compatible: no BW flag
        return createSearchablePdf(context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, false);
    }

    /**
     * Creates a searchable PDF file from a list of image bitmaps and their corresponding recognized words.
     *
     * @param context             the application context used for accessing system resources
     * @param bitmaps             the list of bitmaps representing pages of the PDF
     * @param perPageWords        the list of lists, where each inner list contains recognized words for a corresponding page
     * @param outputUri           the URI where the output PDF file will be written
     * @param jpegQuality         the quality (0-100) for compressing images in the PDF
     * @param convertToGrayscale  whether to convert images to grayscale before including them in the PDF
     * @param convertToBlackWhite whether to convert images to black-and-white before including them in the PDF
     * @return the URI of the generated searchable PDF file
     */
    public static Uri createSearchablePdf(Context context, List<Bitmap> bitmaps, List<List<RecognizedWord>> perPageWords, Uri outputUri, int jpegQuality, boolean convertToGrayscale, boolean convertToBlackWhite) {
        // Default 300 dpi behavior
        return createSearchablePdf(context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, convertToBlackWhite, 300, null);
    }

    /**
     * Creates a searchable PDF document from the given list of bitmaps and recognized text data.
     * The resulting PDF is written to the specified output URI. Options such as grayscale conversion,
     * black-and-white conversion, JPEG quality, and target DPI are supported for customization.
     *
     * @param context             The application context, required for accessing resources and file paths.
     * @param bitmaps             A list of bitmap images representing the pages of the document.
     * @param perPageWords        A list containing recognized words for each corresponding bitmap page.
     * @param outputUri           The URI where the generated PDF will be saved.
     * @param jpegQuality         The quality of JPEG compression (0-100) for the bitmap images.
     * @param convertToGrayscale  If true, the bitmaps will be converted to grayscale before addition to the PDF.
     * @param convertToBlackWhite If true, the bitmaps will be converted to black-and-white using the default mode.
     * @param targetDpi           The target resolution in DPI for the bitmap images in the PDF.
     * @param listener            A listener to track and report the progress of the PDF creation process.
     * @return The URI of the generated searchable PDF.
     */
    public static Uri createSearchablePdf(Context context, List<Bitmap> bitmaps, List<List<RecognizedWord>> perPageWords, Uri outputUri, int jpegQuality, boolean convertToGrayscale, boolean convertToBlackWhite, int targetDpi, ProgressListener listener) {
        // Back-compat: default to ROBUST when BW is requested
        return createSearchablePdf(context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, convertToBlackWhite, targetDpi, listener, convertToBlackWhite ? BwMode.ROBUST : null);
    }

    /**
     * Creates a searchable PDF from a collection of bitmap images and their corresponding recognized words.
     *
     * @param context             the application context required for resource initialization and file handling
     * @param bitmaps             a list of bitmap images representing the pages of the PDF
     * @param perPageWords        a list containing recognized words for each page, where each sublist corresponds to the text on a specific page
     * @param outputUri           the URI where the resulting PDF will be saved
     * @param jpegQuality         the quality level for encoding images into the PDF as a percentage (0-100); lower values result in more compression
     * @param convertToGrayscale  a flag indicating whether the images should be converted to grayscale
     * @param convertToBlackWhite a flag indicating whether the images should be converted to black-and-white
     * @param targetDpi           the target resolution in dots per inch (DPI) for the PDF pages
     * @param listener            a progress listener to receive updates on the processing of pages
     * @param bwMode              the black-and-white processing mode to be used if convertToBlackWhite is true
     * @return the URI of the generated PDF file, or null if an error occurred
     */
    public static Uri createSearchablePdf(Context context, List<Bitmap> bitmaps, List<List<RecognizedWord>> perPageWords, Uri outputUri, int jpegQuality, boolean convertToGrayscale, boolean convertToBlackWhite, int targetDpi, ProgressListener listener, BwMode bwMode) {
        if (bitmaps == null || bitmaps.isEmpty() || outputUri == null) return null;
        try {
            PDFBoxResourceLoader.init(context);
            try {
                OpenCVUtils.init(context);
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            Log.e(TAG, "PDFBox init failed", t);
            return null;
        }
        try (PDDocument document = new PDDocument()) {
            try {
                document.getDocument().setVersion(1.5f);
            } catch (Throwable ignore) {
            }
            document.getDocumentInformation().setCreator("MakeACopy");
            document.getDocumentInformation().setProducer("MakeACopy");

            PDRectangle pageSize = PDRectangle.A4;
            float pageW = pageSize.getWidth();
            float pageH = pageSize.getHeight();

            // Load fonts once (file-based; subset-embedded)
            List<PDFont> fonts = loadFontsWithFallbacks(document, context);

            int total = bitmaps.size();
            for (int i = 0; i < bitmaps.size(); i++) {
                Bitmap src = bitmaps.get(i);
                if (src == null) {
                    if (listener != null) try {
                        listener.onPageProcessed(i + 1, total);
                    } catch (Throwable ignore) {
                    }
                    continue; // skip nulls defensively
                }
                Bitmap prepared = null;
                try {
                    prepared = processImageForPdf(src, convertToGrayscale, convertToBlackWhite, targetDpi, bwMode);
                    if (prepared == null) {
                        Log.e(TAG, "Image preparation via OpenCV failed for page " + (i + 1));
                        return null;
                    }

                    PDPage page = new PDPage(pageSize);
                    // Harmonize page boxes to avoid viewer-specific cropping/offset interpretations
                    try {
                        page.setMediaBox(pageSize);
                        page.setCropBox(pageSize);
                        page.setBleedBox(pageSize);
                        page.setTrimBox(pageSize);
                        page.setArtBox(pageSize);
                    } catch (Throwable ignore) {
                    }
                    document.addPage(page);

                    float scale = calculateScale(prepared.getWidth(), prepared.getHeight(), pageW, pageH);
                    float drawW = prepared.getWidth() * scale;
                    float drawH = prepared.getHeight() * scale;
                    float offsetX = (pageW - drawW) / 2f;
                    float offsetY = (pageH - drawH) / 2f;

                    float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
                    PDImageXObject pdImg = (jpegQuality < 100) ? JPEGFactory.createFromImage(document, prepared, q) : LosslessFactory.createFromImage(document, prepared);

                    try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                        cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);
                        List<RecognizedWord> words = (perPageWords != null && i < perPageWords.size()) ? perPageWords.get(i) : null;
                        if (words != null && !words.isEmpty()) {
                            cs.saveGraphicsState();
                            cs.transform(new Matrix(scale, 0, 0, scale, offsetX, offsetY));
                            // Normalize OCR boxes from source bitmap space to prepared bitmap space if needed
                            List<RecognizedWord> normWords;
                            if (src.getWidth() != prepared.getWidth() || src.getHeight() != prepared.getHeight()) {
                                float sxImg = (float) prepared.getWidth() / (float) src.getWidth();
                                float syImg = (float) prepared.getHeight() / (float) src.getHeight();
                                normWords = new ArrayList<>(words.size());
                                for (RecognizedWord w : words) {
                                    normWords.add(w.transform(sxImg, syImg, 0f, 0f).clipTo(prepared.getWidth(), prepared.getHeight()));
                                }
                            } else {
                                normWords = words;
                            }
                            addTextLayerImageSpace(cs, normWords, fonts, prepared.getWidth(), prepared.getHeight());
                            cs.restoreGraphicsState();
                        }
                    }
                    if (listener != null) {
                        try {
                            listener.onPageProcessed(i + 1, total);
                        } catch (Throwable ignore) {
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error rendering page " + (i + 1), e);
                    return null;
                } finally {
                    if (prepared != null && prepared != src) {
                        try {
                            prepared.recycle();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }

            try (OutputStream os = context.getContentResolver().openOutputStream(outputUri)) {
                if (os == null) {
                    Log.e(TAG, "createSearchablePdf(multi): openOutputStream returned null");
                    return null;
                }
                document.save(os);
            }
            return outputUri;
        } catch (Exception e) {
            Log.e(TAG, "Error creating multi-page PDF", e);
            return null;
        }
    }

    public enum BwMode {ROBUST, CLASSIC, OCR_ROBUST}

    /**
     * An interface for listening to progress updates during a multi-step process,
     * such as processing pages, files, or any sequential tasks.
     * <p>
     * Implementations of this interface should provide behavior to handle updates
     * when a single step or unit of work is completed, including information about
     * the progress relative to the total work.
     */
    public interface ProgressListener {
        void onPageProcessed(int pageIndex, int totalPages);
    }
}
