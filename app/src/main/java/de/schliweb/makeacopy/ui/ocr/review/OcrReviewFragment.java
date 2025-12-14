package de.schliweb.makeacopy.ui.ocr.review;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.view.MinimapView;
import de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView;

import java.io.File;

/**
 * OcrReviewFragment is a subclass of Fragment that provides functionality
 * for reviewing and editing OCR (Optical Character Recognition) results. It handles
 * user interactions, scale adjustments, and autosave operations for OCR documents.
 * The fragment supports features such as inline editing, word operations (e.g., delete,
 * merge, split), and language configuration.
 */
public class OcrReviewFragment extends Fragment {

    private boolean minimapVisible = true;

    private boolean initialFitApplied = false;

    private static final String TAG = "OcrReview";

    // Holds current minimap thumbnail to allow recycling on replacement
    @Nullable
    private Bitmap minimapBitmap;

    private void dbgWarn(String msg, Throwable t) {
        if (BuildConfig.DEBUG) Log.w(TAG, msg, t);
    }

    /**
     * Applies the given scale to various UI components, ensuring that the overlay, text layer, zoom bar,
     * and zoom chip remain synchronized.
     *
     * @param s         The scale factor to be applied.
     * @param overlay   The overlay view to apply the scale to.
     * @param textLayer The text layer view to apply the scale to.
     * @param zoomBar   The zoom bar (seek bar) to update with the scaled value.
     * @param chipZoom  The chip component to display the scale percentage and update its content description.
     */
    // Centralized scale/UI application to keep overlay, text layer, zoom bar and zoom chip in sync
    private void applyScale(float s, OcrOverlayView overlay, de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer,
                            android.widget.SeekBar zoomBar, com.google.android.material.chip.Chip chipZoom) {
        if (overlay != null) overlay.setUserScale(s);
        if (textLayer != null) textLayer.setUserScale(s);
        if (zoomBar != null) {
            int prog = Math.min(200, Math.max(25, Math.round(s * 50f)));
            zoomBar.setProgress(prog);
        }
        if (chipZoom != null) {
            int pct = Math.round(s * 100f);
            chipZoom.setText(pct + "%");
            try {
                chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
            } catch (Throwable t) {
                dbgWarn("Updating zoom chip contentDescription (applyScale) failed", t);
            }
        }
    }

    /**
     * Converts a given raw value into density-independent pixels (dp) based on the display metrics of the device.
     *
     * @param v The raw value to be converted, typically provided in pixels.
     * @return The value converted to density-independent pixels (dp).
     */
    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * Loads and sets a small thumbnail bitmap into the minimap from the current scan directory,
     * preferring thumb.jpg and falling back to page.jpg. Decodes with downsampling to limit memory.
     */
    private void updateMinimapThumbnail(@NonNull MinimapView minimap) {
        try {
            String id = null;
            try {
                id = viewModel != null ? viewModel.getTargetScanId() : null;
            } catch (Throwable ignore) {
            }
            if (id == null || id.trim().isEmpty() || getContext() == null) return;
            File dir = new File(requireContext().getFilesDir(), "scans/" + id);
            File thumb = new File(dir, "thumb.jpg");
            File page = new File(dir, "page.jpg");
            File src = thumb.exists() ? thumb : (page.exists() ? page : null);
            if (src == null) {
                // Fallback: use in-memory bitmap from CropViewModel for brand-new scans (files not yet persisted)
                try {
                    CropViewModel cvm = new androidx.lifecycle.ViewModelProvider(requireActivity()).get(CropViewModel.class);
                    Bitmap bm = cvm != null ? cvm.getImageBitmap().getValue() : null;
                    if (bm != null && !bm.isRecycled()) {
                        int w0 = bm.getWidth();
                        int h0 = bm.getHeight();
                        if (w0 > 0 && h0 > 0) {
                            int maxDim0 = 512;
                            int long0 = Math.max(w0, h0);
                            float scale0 = long0 > maxDim0 ? (maxDim0 / (float) long0) : 1f;
                            int tw = Math.max(1, Math.round(w0 * scale0));
                            int th = Math.max(1, Math.round(h0 * scale0));
                            Bitmap tiny = Bitmap.createScaledBitmap(bm, tw, th, true);
                            if (tiny != null) {
                                Bitmap prev = minimapBitmap;
                                minimapBitmap = tiny;
                                minimap.setThumbnail(tiny);
                                if (prev != null && prev != tiny && !prev.isRecycled()) {
                                    try {
                                        prev.recycle();
                                    } catch (Throwable ignore2) {
                                    }
                                }
                                return; // done via fallback
                            }
                        }
                    }
                } catch (Throwable ignore2) {
                }
                return;
            }

            // Use centralized EXIF-neutral sampled decode for baked disk files
            int maxDim = 512; // cap long edge
            Bitmap bmp = de.schliweb.makeacopy.utils.ImageDecodeUtils.decodeSampled(src.getAbsolutePath(), maxDim, maxDim);
            if (bmp == null) return;

            // Swap and recycle previous
            Bitmap prev = minimapBitmap;
            minimapBitmap = bmp;
            minimap.setThumbnail(bmp);
            if (prev != null && prev != bmp && !prev.isRecycled()) {
                try {
                    prev.recycle();
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            dbgWarn("Loading minimap thumbnail failed", t);
        }
    }

    /**
     * Computes the user scale factor required to achieve a "fit to screen" effect with padding,
     * clamped to a range of [0.5 .. 4.0].
     *
     * @param container The container view used for calculating the available dimensions. If null, a default scale of 1.0 is returned.
     * @param pageW     The width of the content (e.g., page or image) to fit within the container, in pixels. Must be positive.
     * @param pageH     The height of the content (e.g., page or image) to fit within the container, in pixels. Must be positive.
     * @param padPx     The padding, in pixels, to be applied around the content when fitting to the container.
     * @return The computed scale factor in the range [0.5 .. 4.0]. Returns 1.0 if the input dimensions are invalid or the container is null.
     */
    // Computes the userScale that achieves a real "fit to screen" with padding, clamped to [0.5 .. 4.0].
    private float computeFitScale(View container, int pageW, int pageH, float padPx) {
        if (container == null) return 1f;
        int cw = container.getWidth();
        int ch = container.getHeight();
        if (cw <= 0 || ch <= 0 || pageW <= 0 || pageH <= 0) return 1f;
        float vw = cw; // actual drawing area used by overlay/text layer (no extra padding considered here)
        float vh = ch;
        float sxPad = (vw - 2f * padPx) / pageW;
        float syPad = (vh - 2f * padPx) / pageH;
        float effAbs = Math.min(sxPad, syPad);
        // Base fit used by overlay/text layers (without padding)
        float sxBase = vw / (float) pageW;
        float syBase = vh / (float) pageH;
        float base = Math.min(sxBase, syBase);
        if (base <= 0f) return 1f;
        float user = effAbs / base; // convert absolute scale to userScale multiplier
        if (user < 0.5f) user = 0.5f;
        if (user > 4f) user = 4f;
        return user;
    }

    /**
     * Converts a user-defined scale factor (userScale) into an absolute scale factor, represented as
     * view pixels per image pixels, based on the dimensions of the provided container and the page size.
     *
     * @param container The container view used for calculating the scaling. If null or has invalid dimensions,
     *                  the userScale value is returned as is.
     * @param pageW     The width of the page or content being scaled, in pixels. Must be greater than 0.
     * @param pageH     The height of the page or content being scaled, in pixels. Must be greater than 0.
     * @param userScale The scale factor set by the user, typically used to determine the zoom level or fit.
     * @return The computed absolute scale factor as a float, representing view pixels per image pixels.
     * If any parameters are invalid, the userScale is returned unchanged.
     */
    // Converts a userScale to absolute scale (view px per image px) for the given container and page size.
    private float toAbsoluteScale(View container, int pageW, int pageH, float userScale) {
        if (container == null || pageW <= 0 || pageH <= 0) return userScale;
        int cw = container.getWidth();
        int ch = container.getHeight();
        if (cw <= 0 || ch <= 0) return userScale;
        float base = Math.min(cw / Math.max(1f, (float) pageW), ch / Math.max(1f, (float) pageH));
        return userScale * base;
    }

    /**
     * Centers the viewport by resetting user offsets to (0,0), which corresponds to centered content
     * because userOffset is interpreted as a delta from base centering in our views.
     *
     * @param overlay The overlay view whose user offsets will be reset to center the viewport.
     *                If null, no operation is performed.
     */
    // Centers the viewport by resetting user offsets to (0,0), which corresponds to centered content
    // because userOffset is interpreted as a delta from base centering in our views.
    private void centerViewport(de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView overlay) {
        if (overlay != null) overlay.setUserOffset(0f, 0f);
    }

    /**
     * Centers the viewport by resetting the user offsets of the provided
     * text layer to (0, 0). This corresponds to centering the content
     * because the offsets are interpreted as a delta from the base centering
     * in the view.
     *
     * @param textLayer The text layer view whose user offsets will be reset
     *                  to center the viewport. If null, no operation is performed.
     */
    private void centerViewport(de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer) {
        if (textLayer != null) textLayer.setUserOffset(0f, 0f);
    }

    private static final long AUTOSAVE_DEBOUNCE_MS = 500L;
    private static final String STATE_MODE = "ocr_mode";
    private static final String STATE_SCALE = "ocr_scale";
    private static final String STATE_OFF_X = "ocr_off_x";
    private static final String STATE_OFF_Y = "ocr_off_y";
    private static final String STATE_MINIMAP_VISIBLE = "ocr_minimap_visible";
    private OcrReviewViewModel viewModel;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    // Editor write-back debounce handler (promoted to field for lifecycle cleanup)
    private final android.os.Handler editorHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // Dedicated background executor for autosave IO to avoid blocking the UI thread
    @Nullable
    private java.util.concurrent.ExecutorService autosaveExecutor;
    // Toolbar chips (action view) bound lazily via toolbar.post
    private android.widget.TextView chipWords;
    private android.widget.TextView chipLow;
    private android.widget.TextView chipLang;
    /**
     * A {@code Runnable} tasked with periodically saving the current state of the view model
     * to a file, ensuring data persistence and consistency. This is executed asynchronously
     * through an {@code Executor}, with main-thread operations limited to resolving the file
     * location.
     * <p>
     * The autosave process performs the following:
     * - Validates required components such as the context, the autosave executor, and the view model.
     * - Resolves the autosave file location safely on the main thread using the {@code resolveAutosaveFile}
     * method.
     * - Executes the file-saving logic on the background executor, creating required directories when
     * necessary, and calling {@code viewModel.save} to perform file I/O operations to persist data.
     * <p>
     * Error handling includes:
     * - Catching and logging exceptions during scheduling or execution through the {@code dbgWarn}
     * method to ensure the task does not disrupt application flow.
     * <p>
     * Preconditions:
     * - The context, {@code autosaveExecutor}, and {@code viewModel} must not be null.
     * - The {@code autosaveExecutor} must not be shut down.
     * <p>
     * Postconditions:
     * - If the file is resolved successfully and all prerequisites are met, the view model state
     * is saved to the file. Any directory paths required are created if they do not exist.
     * - Any exceptions encountered during execution or I/O operations are gracefully
     * logged for debugging purposes.
     */
    private final Runnable autosaveRunnable = () -> {
        try {
            if (getContext() == null || viewModel == null || autosaveExecutor == null || autosaveExecutor.isShutdown())
                return;
            // Resolve file on main thread, then perform IO on background executor
            final java.io.File file = resolveAutosaveFile();
            if (file != null) {
                autosaveExecutor.execute(() -> {
                    try {
                        java.io.File parent = file.getParentFile();
                        if (parent != null && !parent.exists()) parent.mkdirs();
                        viewModel.save(file);
                    } catch (Throwable t) {
                        dbgWarn("Autosave task error", t);
                    }
                });
            }
        } catch (Throwable t) {
            dbgWarn("Scheduling autosave failed", t);
        }
    };

    /**
     * Inflates the view for the fragment and initializes its components and observers. This method
     * sets up the user interface, binds views to logic, and prepares document data handling.
     * It also manages configuration settings for components like the toolbar, overlay, minimap,
     * and segmented controls.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container          The parent view that this fragment's UI should be attached to (if not null).
     *                           This value can be used to determine layout parameters for the inflated view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous
     *                           saved state as given here.
     * @return The root View of the fragment's layout, or null if the inflater or container is null.
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_ocr_review, container, false);
        viewModel = new ViewModelProvider(requireActivity()).get(OcrReviewViewModel.class);
        // Initialize autosave executor for this view lifecycle
        autosaveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OCRReview-Autosave");
            t.setDaemon(true);
            return t;
        });

        // Top App Bar (chips live inside its action view)
        com.google.android.material.appbar.MaterialToolbar toolbar = root.findViewById(R.id.top_app_bar);
        if (toolbar != null) {
            toolbar.setNavigationOnClickListener(v ->
                    requireActivity().getOnBackPressedDispatcher().onBackPressed()
            );
        }
        OcrOverlayView overlay = root.findViewById(R.id.ocr_overlay);
        de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer = root.findViewById(R.id.ocr_text_layer);
        SeekBar zoomBar = null;
        View overlayContainer = root.findViewById(R.id.overlay_container);
        View textModeContainer = root.findViewById(R.id.text_mode_container);
        EditText textModeEditor = root.findViewById(R.id.text_mode_editor);
        com.google.android.material.button.MaterialButtonToggleGroup segmented = root.findViewById(R.id.segmented_group);
        com.google.android.material.button.MaterialButton segLayout = root.findViewById(R.id.seg_layout);
        com.google.android.material.button.MaterialButton segText = root.findViewById(R.id.seg_text);
        com.google.android.material.chip.Chip chipZoom = root.findViewById(R.id.chip_zoom);
        // Minimap inside card placeholder
        final android.view.ViewGroup minimapCard = root.findViewById(R.id.minimap_placeholder);
        final MinimapView minimap = new MinimapView(requireContext());
        if (minimapCard != null) {
            // Ensure proper size inside card
            android.view.ViewGroup.LayoutParams lp = new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
            );
            minimap.setLayoutParams(lp);
            try {
                minimap.setContentDescription(getString(R.string.cd_minimap));
            } catch (Throwable ignore) {
            }
            minimapCard.setClickable(true);
            if (minimapCard instanceof android.widget.FrameLayout) {
                minimapCard.addView(minimap);
            } else if (minimapCard instanceof com.google.android.material.card.MaterialCardView) {
                // MaterialCardView is a FrameLayout subclass; safe to cast
                minimapCard.addView(minimap);
            }
            // Apply current visibility state
            minimapCard.setVisibility(minimapVisible ? View.VISIBLE : View.GONE);
            // Try to load thumbnail preview for minimap
            updateMinimapThumbnail(minimap);
            // Navigation from minimap back to overlay/text layer
            minimap.setOnNavigateListener(new MinimapView.OnNavigateListener() {
                @Override
                public void onCenterRequested(float contentCenterX, float contentCenterY) {
                    if (overlay == null) return;
                    OcrDoc d = null;
                    try {
                        d = viewModel.getDoc().getValue();
                    } catch (Throwable ignore) {
                    }
                    if (d == null || d.imageSize == null) return;
                    int vw2 = overlay.getWidth();
                    int vh2 = overlay.getHeight();
                    if (vw2 <= 0 || vh2 <= 0) return;
                    float baseFit = Math.min(vw2 / Math.max(1f, (float) d.imageSize.w), vh2 / Math.max(1f, (float) d.imageSize.h));
                    float eff = baseFit * overlay.getUserScale();
                    float baseOffsetX2 = (vw2 - d.imageSize.w * eff) * 0.5f;
                    float baseOffsetY2 = (vh2 - d.imageSize.h * eff) * 0.5f;
                    float desiredUserOffsetX = (vw2 * 0.5f) - (baseOffsetX2 + contentCenterX * eff);
                    float desiredUserOffsetY = (vh2 * 0.5f) - (baseOffsetY2 + contentCenterY * eff);
                    overlay.setUserOffset(desiredUserOffsetX, desiredUserOffsetY);
                    if (textLayer != null) textLayer.setUserOffset(desiredUserOffsetX, desiredUserOffsetY);
                }

                @Override
                public void onToggleZoomRequested() {
                    float target;
                    OcrDoc d = null;
                    try {
                        d = viewModel.getDoc().getValue();
                    } catch (Throwable ignore) {
                    }
                    if (d != null && d.imageSize != null) {
                        float padPx = dp(12f);
                        float fit = computeFitScale(overlay, d.imageSize.w, d.imageSize.h, padPx);
                        float cur = overlay != null ? overlay.getUserScale() : 1f;
                        // Toggle: if currently around FIT, go to 100%; else go to FIT
                        target = (Math.abs(cur - fit) < 0.05f) ? 1.0f : fit;
                    } else target = 1.0f;
                    applyScale(target, overlay, textLayer, zoomBar, chipZoom);
                    if (Math.abs(target - 1.0f) > 0.01f) {
                        centerViewport(overlay);
                        centerViewport(textLayer);
                    }
                }
            });
        }
        // Bind toolbar action view chips reliably using post()
        if (toolbar != null) {
            toolbar.post(() -> {
                try {
                    android.view.Menu menu = toolbar.getMenu();
                    android.view.MenuItem miStats = (menu != null) ? menu.findItem(R.id.action_status_chips) : null;
                    View statsView = miStats != null ? miStats.getActionView() : null;
                    this.chipWords = statsView != null ? statsView.findViewById(R.id.chip_words) : null;
                    this.chipLow = statsView != null ? statsView.findViewById(R.id.chip_low) : null;
                    this.chipLang = statsView != null ? statsView.findViewById(R.id.chip_lang) : null;
                    // Initial fill using current document, if available
                    OcrDoc docNow = null;
                    try {
                        docNow = viewModel.getDoc().getValue();
                    } catch (Throwable t) {
                        dbgWarn("Getting doc for initial status chips failed", t);
                    }
                    updateStatusChips(docNow);
                } catch (Throwable t) {
                    dbgWarn("Binding toolbar status chips failed", t);
                }
            });
        }

        final boolean[] updatingEditor = {false};

        viewModel.getDoc().observe(getViewLifecycleOwner(), doc -> {
            // Update minimap page size when document changes
            if (doc != null && doc.imageSize != null) {
                try {
                    if (minimapCard != null) {
                        // minimap variable exists in scope
                        minimap.setPageSize(doc.imageSize.w, doc.imageSize.h);
                        // Refresh thumbnail preview if available on disk
                        updateMinimapThumbnail(minimap);
                        // Provide simplified OCR boxes for optional minimap layer
                        if (doc.words != null) {
                            java.util.ArrayList<int[]> boxes = new java.util.ArrayList<>(doc.words.size());
                            for (OcrDoc.Word w : doc.words) {
                                if (w == null || w.b == null || w.b.length < 4) continue;
                                boxes.add(new int[]{w.b[0], w.b[1], w.b[2], w.b[3]});
                            }
                            minimap.setOcrBoxes(boxes);
                        } else {
                            minimap.setOcrBoxes(null);
                        }
                        // Provide confidence heatmap (binned) based on word confidences
                        try {
                            int cols = 32, rows = 32;
                            int n = cols * rows;
                            int[] tot = new int[n];
                            int[] low = new int[n];
                            if (doc.words != null && doc.imageSize != null && doc.imageSize.w > 0 && doc.imageSize.h > 0) {
                                for (OcrDoc.Word w : doc.words) {
                                    if (w == null || w.b == null || w.b.length < 4) continue;
                                    float cx = w.b[0] + (w.b[2] * 0.5f);
                                    float cy = w.b[1] + (w.b[3] * 0.5f);
                                    int ix = (int) Math.floor((cx / Math.max(1f, (float) doc.imageSize.w)) * cols);
                                    int iy = (int) Math.floor((cy / Math.max(1f, (float) doc.imageSize.h)) * rows);
                                    if (ix < 0) ix = 0;
                                    if (iy < 0) iy = 0;
                                    if (ix >= cols) ix = cols - 1;
                                    if (iy >= rows) iy = rows - 1;
                                    int idx = iy * cols + ix;
                                    tot[idx]++;
                                    if (w.c <= 0.60f) low[idx]++;
                                }
                            }
                            float[] vals = new float[n];
                            for (int i = 0; i < n; i++) {
                                vals[i] = (tot[i] > 0) ? (low[i] / (float) tot[i]) : 0f;
                            }
                            minimap.setHeatmap(cols, rows, vals);
                        } catch (Throwable t) {
                            dbgWarn("Updating minimap heatmap failed", t);
                        }
                    }
                } catch (Throwable t) {
                    dbgWarn("Updating minimap page size/boxes failed", t);
                }
            }
            // Update status chips via single helper to keep logic DRY
            updateStatusChips(doc);
            if (overlay != null) overlay.setDoc(doc);
            if (textLayer != null) textLayer.setDoc(doc);
            if (textModeEditor != null) {
                try {
                    String text = buildFullText(doc);
                    // Avoid resetting cursor if text is identical
                    String current = textModeEditor.getText() == null ? "" : textModeEditor.getText().toString();
                    if (!text.equals(current)) {
                        updatingEditor[0] = true;
                        textModeEditor.setText(text);
                        textModeEditor.setSelection(textModeEditor.getText().length());
                        updatingEditor[0] = false;
                    }
                } catch (Throwable t) {
                    dbgWarn("Failed updating TextMode editor from doc", t);
                    updatingEditor[0] = false;
                }
            }
        });

        // Bottom actions toolbar menu wiring (Undo/Redo/Export)
        com.google.android.material.appbar.MaterialToolbar bottomAppBar = root.findViewById(R.id.bottom_app_bar);
        if (bottomAppBar != null) {
            android.view.Menu menu = bottomAppBar.getMenu();
            final android.view.MenuItem miUndo = menu != null ? menu.findItem(R.id.action_undo) : null;
            final android.view.MenuItem miRedo = menu != null ? menu.findItem(R.id.action_redo) : null;
            final android.view.MenuItem miExport = menu != null ? menu.findItem(R.id.action_export) : null;

            // Enable/disable based on ViewModel
            if (miUndo != null) {
                viewModel.getCanUndo().observe(getViewLifecycleOwner(), enabled -> {
                    boolean e = Boolean.TRUE.equals(enabled);
                    try {
                        miUndo.setEnabled(e);
                    } catch (Throwable t) {
                        dbgWarn("Updating undo enabled failed", t);
                    }
                });
            }
            if (miRedo != null) {
                viewModel.getCanRedo().observe(getViewLifecycleOwner(), enabled -> {
                    boolean e = Boolean.TRUE.equals(enabled);
                    try {
                        miRedo.setEnabled(e);
                    } catch (Throwable t) {
                        dbgWarn("Updating redo enabled failed", t);
                    }
                });
            }

            bottomAppBar.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == R.id.action_undo) {
                    viewModel.undo();
                    return true;
                }
                if (id == R.id.action_redo) {
                    viewModel.redo();
                    return true;
                }
                if (id == R.id.action_export) {
                    try {
                        android.widget.Toast.makeText(requireContext(), getString(R.string.title_export), android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        dbgWarn("Export toast failed", t);
                    }
                    return true;
                }
                return false;
            });
        }

        // FAB for "Bearbeiten" (switch to Text mode)
        com.google.android.material.floatingactionbutton.FloatingActionButton fab = null;
        if (fab != null) {
            fab.setOnClickListener(v -> {
                com.google.android.material.button.MaterialButtonToggleGroup segmentedTmp = root.findViewById(R.id.segmented_group);
                if (segmentedTmp != null) {
                    segmentedTmp.check(R.id.seg_text);
                }
                EditText editor = root.findViewById(R.id.text_mode_editor);
                if (editor != null) {
                    editor.requestFocus();
                    editor.postDelayed(() -> {
                        try {
                            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                            if (imm != null)
                                imm.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                        } catch (Throwable t) {
                            dbgWarn("Showing IME failed", t);
                        }
                    }, 50);
                }
            });
        }

        final boolean[] updatingZoomBar = {false};

        // Segmented control: toggle between Layout and Text modes
        if (segmented != null) {
            com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener toggle = (group, checkedId, isChecked) -> {
                if (!isChecked) return; // react only when a button becomes checked
                boolean showLayout = checkedId == R.id.seg_layout;
                if (overlayContainer != null) overlayContainer.setVisibility(showLayout ? View.VISIBLE : View.GONE);
                if (textModeContainer != null) textModeContainer.setVisibility(showLayout ? View.GONE : View.VISIBLE);
                if (!showLayout && textModeEditor != null) {
                    textModeEditor.requestFocus();
                }
            };
            segmented.addOnButtonCheckedListener(toggle);
            // initialize depending on current checked
            try {
                int checked = segmented.getCheckedButtonId();
                if (checked == View.NO_ID && segLayout != null && segLayout.isChecked()) checked = R.id.seg_layout;
                toggle.onButtonChecked(segmented, checked, true);
            } catch (Throwable t) {
                dbgWarn("Initializing segmented control failed", t);
            }
        }

        // Text Mode editor write-back (macro-edit) with debounce to avoid undo bloat
        final Runnable applyEditorRunnable = () -> {
            try {
                if (textModeEditor == null) return;
                OcrDoc doc = viewModel.getDoc().getValue();
                if (doc == null || doc.words == null || doc.words.isEmpty()) return;
                String src = textModeEditor.getText() == null ? "" : textModeEditor.getText().toString();
                String trimmed = src.trim();
                String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
                int n = Math.min(doc.words.size(), tokens.length);
                if (n == 0) return;
                viewModel.snapshot();
                for (int i = 0; i < n; i++) {
                    OcrDoc.Word w = doc.words.get(i);
                    if (w != null) {
                        String nt = tokens[i];
                        if (!nt.equals(w.t)) {
                            w.t = nt;
                            w.e = true;
                        }
                    }
                }
                viewModel.setDoc(doc);
                scheduleAutosave();
            } catch (Throwable t) {
                dbgWarn("Applying editor changes failed", t);
            }
        };
        if (textModeEditor != null) {
            textModeEditor.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(android.text.Editable s) {
                    if (updatingEditor[0]) return;
                    if (textModeContainer != null && textModeContainer.getVisibility() != View.VISIBLE) return;
                    editorHandler.removeCallbacks(applyEditorRunnable);
                    editorHandler.postDelayed(applyEditorRunnable, 250);
                }
            });
        }

        if (overlay != null) {
            overlay.setOnWordTapListener(this::showInlineEditDialog);
            overlay.setOnWordLongPressListener(this::showContextMenu);
            overlay.setOnViewportChangedListener((scale, ox, oy) -> {
                // Update minimap viewport too (throttled via postOnAnimation)
                if (minimapCard != null) {
                    overlay.postOnAnimation(() -> {
                        try {
                            minimap.setViewport(scale, ox, oy, overlay.getWidth(), overlay.getHeight());
                        } catch (Throwable t) {
                            dbgWarn("Updating minimap viewport failed", t);
                        }
                    });
                }
                // Sync text layer and zoom bar when user pans/zooms via gestures
                if (textLayer != null) {
                    textLayer.setUserScale(scale);
                    textLayer.setUserOffset(ox, oy);
                }
                if (zoomBar != null) {
                    updatingZoomBar[0] = true;
                    int prog = Math.round(scale * 50f); // inverse of mapping below (progress 50 -> 1.0x)
                    if (prog < 25) prog = 25; // 0.5 min
                    if (prog > 200) prog = 200; // 4.0 max
                    zoomBar.setProgress(prog);
                    updatingZoomBar[0] = false;
                }
                if (chipZoom != null) {
                    try {
                        int pct = Math.round(scale * 100f);
                        chipZoom.setText(pct + "%");
                        try {
                            chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
                        } catch (Throwable t2) {
                            dbgWarn("Updating zoom chip contentDescription failed", t2);
                        }
                    } catch (Throwable t) {
                        dbgWarn("Updating zoom chip text failed", t);
                    }
                }
            });
        }

        // Zoom bar wiring: map 0..200 → userScale in [0.5 .. 4.0] (with min clamp at 0.5)
        if (zoomBar != null) {
            SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
                private void apply(int progress, boolean fromUser) {
                    if (updatingZoomBar[0]) return;
                    float s = progress / 50f; // 50 -> 1.0, 200 -> 4.0, 0 -> 0.0 (clamped below)
                    if (s < 0.5f) s = 0.5f;
                    if (textLayer != null) textLayer.setUserScale(s);
                    if (overlay != null) overlay.setUserScale(s);
                }

                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    apply(progress, fromUser);
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            };
            zoomBar.setOnSeekBarChangeListener(listener);
            // initialize
            listener.onProgressChanged(zoomBar, zoomBar.getProgress(), false);
            if (chipZoom != null) {
                try {
                    int pct = Math.round((zoomBar.getProgress() / 50f) * 100f);
                    chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
                } catch (Throwable t) {
                    dbgWarn("Updating zoom chip contentDescription (init) failed", t);
                }
            }
        }

        // Zoom chip: show popover with only 100% and Fit actions, per spec
        if (chipZoom != null) {
            chipZoom.setOnClickListener(v -> {
                androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(requireContext(), v);
                android.view.Menu m = pm.getMenu();
                final int ID_100 = 1, ID_FIT = 2, ID_TOGGLE_MINIMAP = 3;
                m.add(0, ID_100, 0, getString(R.string.zoom_100));
                m.add(0, ID_FIT, 1, getString(R.string.zoom_fit));
                // Use simple English label if string resources are unavailable
                m.add(0, ID_TOGGLE_MINIMAP, 2, (minimapVisible ? "Hide minimap" : "Show minimap"));
                pm.setOnMenuItemClickListener(item -> {
                    int id = item.getItemId();
                    float target = 1.0f;
                    if (id == ID_100) target = 1.0f;
                    else if (id == ID_FIT) {
                        // Compute real fit-to-screen user scale with small padding
                        OcrDoc d = null;
                        try {
                            d = viewModel.getDoc().getValue();
                        } catch (Throwable t) {
                            dbgWarn("Getting doc for FIT failed", t);
                        }
                        if (d != null && d.imageSize != null) {
                            float padPx = dp(12f);
                            float fit = computeFitScale(overlay, d.imageSize.w, d.imageSize.h, padPx);
                            target = fit;
                        } else {
                            target = 1.0f;
                        }
                    }

                    // Apply scale consistently across views and UI
                    applyScale(target, overlay, textLayer, zoomBar, chipZoom);

                    // Center viewport on FIT only
                    if (id == ID_FIT) {
                        centerViewport(overlay);
                        centerViewport(textLayer);
                    }
                    if (id == ID_TOGGLE_MINIMAP) {
                        try {
                            minimapVisible = !minimapVisible;
                            View card = getView() != null ? getView().findViewById(R.id.minimap_placeholder) : null;
                            if (card != null) card.setVisibility(minimapVisible ? View.VISIBLE : View.GONE);
                        } catch (Throwable ignore) {
                        }
                        return true;
                    }
                    return true;
                });
                pm.show();
            });
        }

        // One-time initial Fit after layout so start state sits perfectly
        if (overlay != null) {
            overlay.post(() -> {
                if (initialFitApplied) return;
                OcrDoc d = null;
                try {
                    d = viewModel.getDoc().getValue();
                } catch (Throwable t) {
                    dbgWarn("Getting doc for initial FIT failed", t);
                }
                if (d == null || d.imageSize == null) return;
                float padPx = dp(12f);
                float target = computeFitScale(overlay, d.imageSize.w, d.imageSize.h, padPx);
                // Apply scale consistently
                applyScale(target, overlay, textLayer, zoomBar, chipZoom);
                // Center viewport on initial FIT
                centerViewport(overlay);
                centerViewport(textLayer);
                if (zoomBar != null) {
                    updatingZoomBar[0] = true;
                    zoomBar.setProgress(Math.round(target * 50f));
                    updatingZoomBar[0] = false;
                }
                if (chipZoom != null) {
                    try {
                        int pct = Math.round(target * 100f);
                        chipZoom.setText(pct + "%");
                        try {
                            chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
                        } catch (Throwable t2) {
                            dbgWarn("Updating zoom chip contentDescription (initial fit) failed", t2);
                        }
                    } catch (Throwable t) {
                        dbgWarn("Updating zoom chip after initial fit failed", t);
                    }
                }
                try {
                    if (minimapCard != null)
                        minimap.setViewport(overlay.getUserScale(), overlay.getUserOffsetX(), overlay.getUserOffsetY(), overlay.getWidth(), overlay.getHeight());
                } catch (Throwable t) {
                    dbgWarn("Minimap initial viewport failed", t);
                }
                initialFitApplied = true;
            });
        }

        // Attempt to load existing autosave for this page (if any),
        // but only if we don't already have a non-empty document in memory.
        try {
            OcrDoc existing = null;
            try {
                existing = viewModel.getDoc().getValue();
            } catch (Throwable ignore2) {
            }
            boolean hasContent = existing != null && existing.words != null && !existing.words.isEmpty();
            if (!hasContent) {
                java.io.File f = resolveAutosaveFile();
                if (f != null && f.exists()) {
                    viewModel.load(f);
                }
            }
        } catch (Throwable ignore) {
        }

        return root;
    }

    /**
     * Resolves and returns the appropriate autosave file based on the current context and target scan ID.
     * If a valid scan ID is available, it constructs a file path specific to that scan ID.
     * Otherwise, it falls back to using a default file within the app's persistent storage.
     *
     * @return The autosave file corresponding to the current context and scan ID.
     * Returns null if the context is invalid or unavailable.
     */
    private java.io.File resolveAutosaveFile() {
        String id = null;
        try {
            id = viewModel.getTargetScanId();
        } catch (Throwable ignore) {
        }
        if (getContext() == null) return null;
        if (id != null && !id.trim().isEmpty()) {
            java.io.File dir = new java.io.File(requireContext().getFilesDir(), "scans/" + id);
            return new java.io.File(dir, "page.ocr.json");
        }
        // Fallback to app files (persistent) if no id yet
        return new java.io.File(requireContext().getFilesDir(), "review_autosave.json");
    }

    /**
     * Displays an inline edit dialog for modifying the properties of the given word.
     * The dialog includes a text input field for editing the word, along with save and cancel buttons.
     *
     * @param word the word object to be edited. Contains the current text and edit state to be updated by this dialog.
     */
    private void showInlineEditDialog(OcrDoc.Word word) {
        if (getContext() == null) return;

        // Bottom Sheet im M3-Look (über dein Overlay)
        final BottomSheetDialog sheet = new BottomSheetDialog(
                requireContext(),
                R.style.ThemeOverlay_MakeACopy_BottomSheet
        );

        // Layout (vertikal)
        android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        // TextInput
        final com.google.android.material.textfield.TextInputLayout til =
                new com.google.android.material.textfield.TextInputLayout(requireContext());
        til.setHint(getString(R.string.edit_word_title));
        til.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        til.setCounterEnabled(true);
        til.setCounterMaxLength(64);

        final com.google.android.material.textfield.TextInputEditText input =
                new com.google.android.material.textfield.TextInputEditText(requireContext());
        input.setText(word.t == null ? "" : word.t);
        if (input.getText() != null) input.setSelection(input.getText().length());
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

        til.addView(input, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(til, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Actions row
        android.widget.LinearLayout actions = new android.widget.LinearLayout(requireContext());
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.END);

        android.widget.Space spacer = new android.widget.Space(requireContext());
        actions.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, 0, 1f));

        // MaterialButton-Styles aus deiner styles.xml:
        // - Widget.MakeACopy.Button (global)
        // - Widget.MakeACopy.Button.Outlined (optional spezieller Stil)
        ContextThemeWrapper btnCtx =
                new ContextThemeWrapper(requireContext(), R.style.Widget_MakeACopy_Button_Outlined);

        MaterialButton btnCancel = new MaterialButton(btnCtx, null, 0);
        btnCancel.setText(getString(R.string.cancel));
        btnCancel.setOnClickListener(v -> sheet.dismiss());

        MaterialButton btnSave = new MaterialButton(btnCtx, null, 0);
        btnSave.setText(R.string.btn_save);
        btnSave.setOnClickListener(v -> {
            String newText = input.getText() == null ? null : input.getText().toString().trim();
            if (newText == null || newText.isEmpty()) {
                til.setError(getString(R.string.error_empty_input));
                return;
            }
            til.setError(null);
            viewModel.snapshot();
            word.t = newText;
            word.e = true;
            propagateAndAutosave();
            sheet.dismiss();
        });

        actions.addView(btnCancel);
        actions.addView(btnSave);

        android.widget.LinearLayout.LayoutParams actionsLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        root.addView(actions, actionsLp);

        sheet.setContentView(root);
        sheet.show();

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                btnSave.performClick();
                return true;
            }
            return false;
        });
    }

    /**
     * Displays a context menu for the specified word, allowing the user to perform various actions,
     * such as editing, merging, splitting, deleting, changing case, or modifying the language.
     *
     * @param word the {@link OcrDoc.Word} instance for which the context menu is displayed
     */
    private void showContextMenu(OcrDoc.Word word) {
        if (getContext() == null) return;
        String[] items = new String[]{
                getString(R.string.cm_edit),
                getString(R.string.cm_merge_next),
                getString(R.string.cm_split),
                getString(R.string.cm_delete),
                getString(R.string.cm_case),
                getString(R.string.cm_lang)
        };
        new AlertDialog.Builder(requireContext())
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0 -> showInlineEditDialog(word);
                        case 1 -> {
                            viewModel.snapshot();
                            mergeWithNext(word);
                        }
                        case 2 -> {
                            viewModel.snapshot();
                            splitWordMid(word);
                        }
                        case 3 -> {
                            viewModel.snapshot();
                            deleteWord(word);
                        }
                        case 4 -> showCaseMenu(word);
                        case 5 -> showLanguageDialog(word);
                    }
                })
                .show();
    }

    /**
     * Displays a menu to change the case of the given word. The menu provides options
     * to convert the text to uppercase, lowercase, or title case. Changes are applied
     * directly to the word and propagated, with an autosave triggered.
     *
     * @param word the word object whose text is to be modified. It contains the current
     *             text and other properties.
     */
    private void showCaseMenu(OcrDoc.Word word) {
        String[] items = new String[]{
                getString(R.string.case_upper),
                getString(R.string.case_lower),
                getString(R.string.case_title)
        };
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.cm_case)
                .setItems(items, (d, which) -> {
                    String t = word.t == null ? "" : word.t;
                    viewModel.snapshot();
                    switch (which) {
                        case 0 -> word.t = t.toUpperCase(java.util.Locale.getDefault());
                        case 1 -> word.t = t.toLowerCase(java.util.Locale.getDefault());
                        case 2 -> word.t = toTitleCase(t);
                    }
                    word.e = true;
                    propagateAndAutosave();
                })
                .show();
    }

    /**
     * Displays a dialog to allow the user to edit the language property of the given word.
     *
     * @param word the {@link OcrDoc.Word} object whose language property will be edited
     */
    private void showLanguageDialog(OcrDoc.Word word) {
        if (getContext() == null) return;
        final EditText input = new EditText(getContext());
        input.setHint(R.string.dialog_language_hint);
        input.setText(word.lang == null ? "" : word.lang);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_language_title)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.btn_save, (dlg, w) -> {
                    String v = input.getText().toString().trim();
                    viewModel.snapshot();
                    word.lang = v.isEmpty() ? null : v;
                    word.e = true;
                    propagateAndAutosave();
                })
                .show();
    }

    /**
     * Merges the given word with the succeeding word in the OCR document, updating
     * text, bounding box, and confidence score while maintaining document structure.
     * <p>
     * Adjusts the current word's text by concatenating it with the succeeding word's text.
     * Updates the bounding box and recalculates confidence using area-based weighting.
     * Removes the succeeding word from the document after merging.
     *
     * @param word The word object to be merged with its immediately succeeding word in the OCR document.
     */
    private void mergeWithNext(OcrDoc.Word word) {
        OcrDoc doc = viewModel.getDoc().getValue();
        if (doc == null || doc.words == null) return;
        int idx = indexOfWord(doc, word.id);
        if (idx < 0 || idx + 1 >= doc.words.size()) return;
        OcrDoc.Word next = doc.words.get(idx + 1);
        if (next == null) return;
        String t1 = word.t == null ? "" : word.t.trim();
        String t2 = next.t == null ? "" : next.t.trim();
        word.t = (t1.isEmpty() || t2.isEmpty()) ? (t1 + t2) : (t1 + " " + t2);
        // union bbox
        int x1 = Math.min(word.b[0], next.b[0]);
        int y1 = Math.min(word.b[1], next.b[1]);
        int r1 = Math.max(word.b[0] + word.b[2], next.b[0] + next.b[2]);
        int b1 = Math.max(word.b[1] + word.b[3], next.b[1] + next.b[3]);
        word.b[0] = x1;
        word.b[1] = y1;
        word.b[2] = Math.max(0, r1 - x1);
        word.b[3] = Math.max(0, b1 - y1);
        // area-weighted confidence based on bbox areas
        try {
            float a1 = Math.max(0f, word.b[2]) * Math.max(0f, word.b[3]);
            float a2 = Math.max(0f, next.b[2]) * Math.max(0f, next.b[3]);
            float denom = Math.max(1f, a1 + a2);
            word.c = ((word.c * a1) + (next.c * a2)) / denom;
        } catch (Throwable ignore) {
            // fallback to simple average if any issue occurs
            word.c = (word.c + next.c) * 0.5f;
        }
        word.e = true;
        // remove next
        doc.words.remove(idx + 1);
        viewModel.setDoc(doc);
        scheduleAutosave();
    }

    /**
     * Splits a given word at its approximate midpoint or nearest whitespace, creating two separate words
     * while adjusting their bounding boxes proportionally based on content distribution.
     *
     * @param word the word to be split. Its bounding box, text, and related data will be updated to reflect the split,
     *             and a new word object will be created and added to the document immediately after the original word.
     */
    private void splitWordMid(OcrDoc.Word word) {
        OcrDoc doc = viewModel.getDoc().getValue();
        if (doc == null || doc.words == null) return;
        int idx = indexOfWord(doc, word.id);
        if (idx < 0) return;
        String t = word.t == null ? "" : word.t;
        if (t.length() < 2) return; // nothing to split
        int mid = t.length() / 2;
        // try to split at nearest space to mid
        int leftSpace = t.lastIndexOf(' ', mid);
        int rightSpace = t.indexOf(' ', mid);
        int splitAt = (leftSpace >= 1) ? leftSpace : (rightSpace > 0 ? rightSpace : mid);
        String a = t.substring(0, splitAt).trim();
        String b = t.substring(splitAt).trim();
        if (a.isEmpty() || b.isEmpty()) return;
        // bbox split by x mid, proportionally by code point counts for robustness with multi-codepoint chars
        int x = word.b[0];
        int y = word.b[1];
        int w = word.b[2];
        int h = word.b[3];
        int lenA = a.codePointCount(0, a.length());
        int lenB = b.codePointCount(0, b.length());
        int totalLen = Math.max(1, lenA + lenB);
        int w1 = Math.max(1, Math.round(w * (lenA / (float) totalLen)));
        int w2 = Math.max(1, w - w1);
        word.t = a;
        word.b[2] = w1;
        word.e = true;
        OcrDoc.Word nw = new OcrDoc.Word();
        nw.id = nextId(doc);
        nw.t = b;
        nw.b = new int[]{x + w1, y, w2, h};
        nw.c = word.c;
        nw.e = true;
        nw.l = word.l;
        nw.k = word.k;
        nw.lang = word.lang;
        doc.words.add(idx + 1, nw);
        viewModel.setDoc(doc);
        scheduleAutosave();
    }

    /**
     * Deletes the specified word from the OCR document if it exists.
     *
     * @param word the word to be deleted from the OCR document
     */
    private void deleteWord(OcrDoc.Word word) {
        OcrDoc doc = viewModel.getDoc().getValue();
        if (doc == null || doc.words == null) return;
        int idx = indexOfWord(doc, word.id);
        if (idx < 0) return;
        doc.words.remove(idx);
        viewModel.setDoc(doc);
        scheduleAutosave();
    }

    /**
     * Finds the index of a word in the given OcrDoc that matches the specified ID.
     *
     * @param doc the OcrDoc object containing a list of words
     * @param id  the ID of the word to search for
     * @return the index of the word with the specified ID, or -1 if the word is not found
     */
    private int indexOfWord(OcrDoc doc, int id) {
        for (int i = 0; i < doc.words.size(); i++) {
            if (doc.words.get(i) != null && doc.words.get(i).id == id) return i;
        }
        return -1;
    }

    /**
     * Calculates the next available ID for a word in the given document
     * by determining the maximum existing ID and incrementing it by one.
     *
     * @param doc the OcrDoc instance containing a list of words for which the next ID is calculated
     * @return the next available ID as an integer
     */
    private int nextId(OcrDoc doc) {
        int max = 0;
        for (OcrDoc.Word w : doc.words) if (w != null) max = Math.max(max, w.id);
        return max + 1;
    }

    /**
     * Converts the given string to title case, where the first letter of each word is capitalized
     * and the remaining letters are in lowercase. Words are considered to be substrings separated
     * by whitespace.
     *
     * @param s the input string to be converted to title case, may be null or empty
     * @return the converted string in title case; returns an empty string if the input is null or empty
     */
    private String toTitleCase(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] parts = s.toLowerCase(java.util.Locale.getDefault()).split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.length() > 1 ? p.substring(1) : "").append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Handles the propagation of the current document within the ViewModel
     * and schedules an autosave operation if the current document is not null.
     * <p>
     * This method retrieves the current document from the ViewModel. If a document is
     * present, it updates the ViewModel with the same document and invokes the
     * method to schedule an autosave for it.
     */
    private void propagateAndAutosave() {
        OcrDoc current = viewModel.getDoc().getValue();
        if (current != null) {
            viewModel.setDoc(current);
            scheduleAutosave();
        }
    }

    /**
     * Schedules an autosave task to be executed after a specified delay.
     * If a previously scheduled autosave task exists, it is canceled before scheduling a new one.
     * The delay is determined by the constant AUTOSAVE_DEBOUNCE_MS.
     */
    private void scheduleAutosave() {
        handler.removeCallbacks(autosaveRunnable);
        handler.postDelayed(autosaveRunnable, AUTOSAVE_DEBOUNCE_MS);
    }

    /**
     * Updates the status chips to display information derived from the provided OcrDoc object.
     * This method handles the total count of words, the percentage of low-confidence words, and the most frequent
     * language detected in the OCR document. The chips are updated dynamically with this information.
     *
     * @param doc the OcrDoc object containing OCR results, including words and their related information. Can be null.
     *            If null or if the document does not contain words, the chips will display default or placeholder values.
     */
    private void updateStatusChips(@Nullable OcrDoc doc) {
        try {
            int total = (doc != null && doc.words != null) ? doc.words.size() : 0;
            int low = 0;
            java.util.HashMap<String, Integer> langCount = new java.util.HashMap<>();
            if (doc != null && doc.words != null) {
                for (OcrDoc.Word w : doc.words) {
                    if (w != null) {
                        if (w.c <= 0.60f) low++;
                        if (w.lang != null && !w.lang.trim().isEmpty()) {
                            String key = w.lang.trim();
                            langCount.put(key, 1 + (langCount.containsKey(key) ? langCount.get(key) : 0));
                        }
                    }
                }
            }
            if (chipWords != null) try {
                chipWords.setText(getString(R.string.words_count_suffix_format, total));
            } catch (Throwable ignore) {
            }
            if (chipLow != null) try {
                int pct = (total > 0) ? Math.round((low * 100f) / total) : 0;
                String txt = (total > 0) ? getString(R.string.low_percent_format, pct) : getString(R.string.low_placeholder);
                chipLow.setText(txt);
            } catch (Throwable ignore) {
            }
            if (chipLang != null) {
                String bestLang = null;
                int best = 0;
                for (java.util.Map.Entry<String, Integer> e : langCount.entrySet()) {
                    if (e.getValue() > best) {
                        best = e.getValue();
                        bestLang = e.getKey();
                    }
                }
                if (bestLang == null) bestLang = "—";
                try {
                    chipLang.setText(bestLang.toUpperCase(java.util.Locale.ROOT));
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * Constructs a full text representation from the provided OCR document by processing its lines
     * and words. If lines are available, it prioritizes their order; otherwise, it concatenates words
     * in their current order. Handles null or empty inputs gracefully.
     *
     * @param doc the OCR document containing lines and words. Can be null.
     * @return the constructed full text representation as a String. Returns an empty string if the input is null
     * or if the content cannot be processed.
     */
    private String buildFullText(@Nullable OcrDoc doc) {
        if (doc == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            // Prefer explicit line ordering if available
            if (doc.lines != null && !doc.lines.isEmpty()) {
                java.util.HashMap<Integer, OcrDoc.Word> byId = new java.util.HashMap<>();
                if (doc.words != null) {
                    for (OcrDoc.Word w : doc.words) {
                        if (w != null) byId.put(w.id, w);
                    }
                }
                for (OcrDoc.Line ln : doc.lines) {
                    if (ln == null || ln.w == null || ln.w.length == 0) continue;
                    boolean first = true;
                    for (int wid : ln.w) {
                        OcrDoc.Word w = byId.get(wid);
                        if (w == null) continue;
                        String t = w.t == null ? "" : w.t.trim();
                        if (t.isEmpty()) continue;
                        if (!first) sb.append(' ');
                        sb.append(t);
                        first = false;
                    }
                    if (sb.length() > 0) sb.append('\n');
                }
                return sb.toString().trim();
            }
            // Fallback: concatenate words in current order
            if (doc.words != null) {
                boolean first = true;
                for (OcrDoc.Word w : doc.words) {
                    if (w == null) continue;
                    String t = w.t == null ? "" : w.t.trim();
                    if (t.isEmpty()) continue;
                    if (!first) sb.append(' ');
                    sb.append(t);
                    first = false;
                }
            }
            return sb.toString();
        } catch (Throwable ignore) {
            return sb.toString();
        }
    }

    /**
     * Called when the fragment's view is destroyed.
     * <p>
     * This method is part of the fragment lifecycle and is invoked to allow
     * cleanup of resources tied to the fragment's UI, such as removing callbacks
     * and references from handlers, executors, and other UI components.
     * <p>
     * - Removes pending callbacks from the handler and editorHandler to prevent
     * memory leaks or undesired behavior after the view is destroyed.
     * - Stops background autosave tasks safely by shutting down the executor,
     * ensuring no new tasks are accepted and allowing ongoing tasks to complete
     * within a specified timeout.
     * - Releases references to toolbar chips to avoid leaking UI components tied to the action view.
     * <p>
     * Always calls the superclass implementation to ensure proper cleanup
     * in the fragment's lifecycle.
     */
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(autosaveRunnable);
        editorHandler.removeCallbacksAndMessages(null);
        // Stop background autosave tasks to avoid leaks without interrupting a running save
        try {
            if (autosaveExecutor != null) {
                autosaveExecutor.shutdown(); // no new tasks
                try {
                    autosaveExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                autosaveExecutor = null;
            }
        } catch (Throwable ignore) {
        }
        // Release toolbar chip references to avoid leaking the action view
        chipWords = null;
        chipLow = null;
        chipLang = null;
        // Recycle minimap bitmap to free memory
        try {
            if (minimapBitmap != null && !minimapBitmap.isRecycled()) {
                minimapBitmap.recycle();
            }
        } catch (Throwable ignore) {
        }
        minimapBitmap = null;
    }

    /**
     * This lifecycle method is called when the activity is paused.
     * It performs the following operations:
     * 1. Removes any pending callbacks associated with the autosaveRunnable
     * from the handler to ensure they are not executed when the activity is paused.
     * 2. Posts the autosaveRunnable back to the handler, ensuring the autosave operation
     * is scheduled immediately after removing any previous callbacks.
     * <p>
     * This ensures proper handling of the autosave functionality when transitioning
     * between different lifecycle states of the activity.
     */
    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(autosaveRunnable);
        handler.post(autosaveRunnable);
    }

    /**
     * Saves the current instance state of the activity to the provided {@link Bundle}.
     * This method is used to persist the state of UI elements and variables so they can
     * be restored when the activity is recreated.
     *
     * @param outState The {@link Bundle} in which to place the activity's state data.
     *                 This Bundle is passed to the {@code onCreate} method if the process is
     *                 killed and restarted.
     */
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        View root = getView();
        if (root == null) return;
        com.google.android.material.button.MaterialButtonToggleGroup segmented = root.findViewById(R.id.segmented_group);
        de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView overlay = root.findViewById(R.id.ocr_overlay);
        int mode = 0; // 0 = layout, 1 = text
        if (segmented != null) {
            int checked = segmented.getCheckedButtonId();
            mode = (checked == R.id.seg_text) ? 1 : 0;
        }
        outState.putInt(STATE_MODE, mode);
        if (overlay != null) {
            outState.putFloat(STATE_SCALE, overlay.getUserScale());
            outState.putFloat(STATE_OFF_X, overlay.getUserOffsetX());
            outState.putFloat(STATE_OFF_Y, overlay.getUserOffsetY());
        }
        outState.putBoolean(STATE_MINIMAP_VISIBLE, minimapVisible);
    }

    /**
     * This method is called immediately after the view has been created and binds any UI components
     * to their respective views, restoring their states if applicable. It ensures that UI elements
     * are in their appropriate states, like segmented buttons, overlays, and zoom functionality.
     * It also handles restoring scale, offsets, and visibility of certain components from the saved
     * instance state.
     *
     * @param view               The root view associated with the fragment, used to find UI components within the layout.
     * @param savedInstanceState A Bundle object containing the saved state of the fragment, which is
     *                           used to restore UI state, such as scale, offsets, visibility settings,
     *                           and mode selections.
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Apply edge-to-edge insets for the AppBar without using legacy fitsSystemWindows
        /*try {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
                int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                // Apply top inset to the Toolbar only to avoid increasing AppBarLayout's measured height
                View toolbar = v.findViewById(R.id.top_app_bar);
                if (toolbar != null && toolbar.getPaddingTop() != top) {
                    toolbar.setPadding(toolbar.getPaddingLeft(), top, toolbar.getPaddingRight(), toolbar.getPaddingBottom());
                }
                // Ensure AppBar itself has no extra top padding so it stays flush at the top
                View appBar = v.findViewById(R.id.app_bar);
                if (appBar != null && appBar.getPaddingTop() != 0) {
                    appBar.setPadding(appBar.getPaddingLeft(), 0, appBar.getPaddingRight(), appBar.getPaddingBottom());
                }
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(view);
        } catch (Throwable ignore) {
        }`*/
        if (savedInstanceState == null) return;
        try {
            com.google.android.material.button.MaterialButtonToggleGroup segmented = view.findViewById(R.id.segmented_group);
            de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView overlay = view.findViewById(R.id.ocr_overlay);
            de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer = view.findViewById(R.id.ocr_text_layer);
            android.widget.SeekBar zoomBar = null;
            com.google.android.material.chip.Chip chipZoom = view.findViewById(R.id.chip_zoom);

            int mode = savedInstanceState.getInt(STATE_MODE, 0);
            if (segmented != null) {
                segmented.check(mode == 1 ? R.id.seg_text : R.id.seg_layout);
            }

            float s = savedInstanceState.getFloat(STATE_SCALE, Float.NaN);
            float ox = savedInstanceState.getFloat(STATE_OFF_X, 0f);
            float oy = savedInstanceState.getFloat(STATE_OFF_Y, 0f);
            minimapVisible = savedInstanceState.getBoolean(STATE_MINIMAP_VISIBLE, true);
            // Apply minimap visibility to placeholder if present
            try {
                View card = view.findViewById(R.id.minimap_placeholder);
                if (card != null) card.setVisibility(minimapVisible ? View.VISIBLE : View.GONE);
            } catch (Throwable ignore) {
            }
            if (!Float.isNaN(s)) {
                // Prevent the one-time auto-fit from overriding restored state
                initialFitApplied = true;
                // Apply scale consistently
                applyScale(s, overlay, textLayer, zoomBar, chipZoom);
                // Restore pan offsets
                if (overlay != null) overlay.setUserOffset(ox, oy);
                if (textLayer != null) textLayer.setUserOffset(ox, oy);
            }
        } catch (Throwable ignore) {
        }
    }
}
