package de.schliweb.makeacopy.ui.crop;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Magnifier;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.utils.OpenCVUtils;
import lombok.Getter;
import lombok.Setter;
import org.opencv.core.Point;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Custom view for selecting a trapezoid area on an image
 * Allows the user to drag the corners of the trapezoid to adjust the selection
 */
public class TrapezoidSelectionView extends View {
    private static final String TAG = "TrapezoidSelectionView";
    private static final int CORNER_RADIUS = 35; // Increased radius of the corner handles for better visibility
    private static final int CORNER_TOUCH_RADIUS = 70; // Increased touch area for easier interaction
    private static final long ANIMATION_DURATION = 300; // Animation duration in milliseconds

    // When true, the next setImageBitmap() call will skip re-initializing corners.
    // Used when we rotate the selection ourselves to keep it in sync with image rotation.
    private boolean suppressInitOnce = false;

    private Paint trapezoidPaint; // Paint for the trapezoid lines
    private Paint cornerPaint; // Paint for the corner handles
    private Paint activePaint; // Paint for the active corner handle
    private Paint backgroundPaint; // Paint for the semi-transparent background
    private Paint hintPaint; // Paint for the hint text
    private Paint hintBackgroundPaint; // Paint for the hint text background
    private PointF[] corners; // The four corners of the trapezoid
    private PointF[] animationStartCorners; // Starting positions for corner animation
    private PointF[] animationEndCorners; // Target positions for corner animation
    private float animationProgress = 1.0f; // Animation progress (0.0 to 1.0)
    private long animationStartTime; // Start time of the animation
    private boolean isAnimating = false; // Flag to track if animation is in progress

    private float[][] relativeCorners; // Corners as percentages of view dimensions [i][0]=x%, [i][1]=y%
    private int activeCornerIndex = -1; // Index of the currently active (touched) corner

    private boolean initialized = false; // Flag to track if corners have been initialized
    private int initializationAttempts = 0; // Counter for initialization attempts
    private int lastWidth = 0; // Last known width of the view
    private int lastHeight = 0; // Last known height of the view

    private Bitmap imageBitmap = null; // The image bitmap for edge detection
    // Track last bitmap dimensions to avoid redundant re-inits
    private int lastBitmapWidth = -1;
    private int lastBitmapHeight = -1;

    // ==== Async corner detection state ====
    private final ExecutorService cornerExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CornerDetect");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    @Nullable
    private Future<?> cornerTask;
    private volatile int requestedInitSeq = 0; // debouncing/cancellation token

    // Debounced initialization runnable to avoid synchronous heavy work in onSizeChanged
    private final Runnable initCornersRunnable = new Runnable() {
        @Override
        public void run() {
            if (getWidth() <= 0 || getHeight() <= 0) return;
            try {
                initializeCornersAsync();
            } catch (Throwable t) {
                Log.e(TAG, "initializeCorners runnable failed", t);
            }
        }
    };

    // Magnifier (precision loupe) plumbing
    @Nullable
    private View magnifierSourceView;
    @Nullable
    private Matrix overlayToSource; // inverse from imageToOverlay matrix
    @Nullable
    private Magnifier magnifier;
    private final boolean magnifierEnabled = true;
    private final float magnifierZoom = 2.5f; // 2.0..4.0
    private int magnifierSizePx = 0;
    private boolean isDraggingWithMagnifier = false;

    // === Debug/diagnostics ===
    @Setter
    private boolean debugLogsEnabled = false;     // enable verbose logs
    private boolean debugOverlayEnabled = false;  // draw overlay with rects
    @Nullable
    private java.util.List<Rect> lastExclusionRects = null; // last applied system gesture exclusion rects

    // Public toggles for edge-glide configuration
    // === Edge-glide state & config ===
    @Getter
    @Setter
    private boolean edgeGlideEnabled = false; // default OFF; user/dev configurable
    private boolean isEdgeGlide = false;      // true while pointer is gliding along left/right edge
    private boolean edgeGlidePending = false; // true while waiting for engage delay
    private long edgeGlideEligibleSinceMs = 0L; // timestamp when first became eligible to engage
    private boolean edgeGlideLockLeft = true; // which edge is locked when gliding (true=left, false=right)
    private float glideY = 0f;                // accumulated Y while locked to edge
    private float lastRawY = Float.NaN;       // last rawY for delta computation
    // Configurable epsilons/delay
    private float edgeSnapEnterEpsDp = 3f;    // enter threshold in dp (soft)
    private int edgeGlideEngageDelayMs = 120; // engage delay in ms

    // Convert dp to px for entry epsilon
    private float edgeSnapEnterEpsPx() {
        return getResources().getDisplayMetrics().density * edgeSnapEnterEpsDp;
    }

    private float edgeSnapExitEpsPx() {
        return edgeSnapEnterEpsPx() * 2f;
    }

    public void setEdgeSnapEpsDp(float dp) {
        if (dp < 0f) dp = 0f;
        this.edgeSnapEnterEpsDp = dp;
    }

    public float getEdgeSnapEpsDp() {
        return this.edgeSnapEnterEpsDp;
    }

    public void setEdgeGlideEngageDelayMs(int ms) {
        if (ms < 0) ms = 0;
        this.edgeGlideEngageDelayMs = ms;
    }

    // ===== User-adjustment/auto-init suppression state =====
    private boolean isUserAdjusting = false;           // true while user is dragging a handle or shortly after release
    private int autoInitIdleDelayMs = 400;             // idle time after release before auto-init may run
    @Nullable
    private Runnable adjustIdleClearRunnable = null;   // clears isUserAdjusting after idle

    public void setAutoInitIdleDelayMs(int ms) {
        if (ms < 0) ms = 0;
        this.autoInitIdleDelayMs = ms;
    }

    private void cancelAdjustIdle() {
        if (adjustIdleClearRunnable != null) {
            try {
                removeCallbacks(adjustIdleClearRunnable);
            } catch (Throwable ignore) {
            }
            adjustIdleClearRunnable = null;
        }
    }

    private void scheduleAdjustIdleClear() {
        cancelAdjustIdle();
        adjustIdleClearRunnable = new Runnable() {
            @Override
            public void run() {
                isUserAdjusting = false;
                if (debugLogsEnabled) Log.d(TAG, "User adjusting ended (idle)");
                adjustIdleClearRunnable = null;
            }
        };
        try {
            postDelayed(adjustIdleClearRunnable, autoInitIdleDelayMs);
        } catch (Throwable ignore) {
        }
    }

    // Public toggles for debugging
    public void setDebugOverlayEnabled(boolean enabled) {
        this.debugOverlayEnabled = enabled;
        invalidate();
    }

    public TrapezoidSelectionView(Context context) {
        super(context);
        init();
    }

    public TrapezoidSelectionView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TrapezoidSelectionView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Initialize the corners array
        corners = new PointF[4];
        for (int i = 0; i < 4; i++) {
            corners[i] = new PointF();
        }

        // Initialize animation corner arrays
        animationStartCorners = new PointF[4];
        animationEndCorners = new PointF[4];
        for (int i = 0; i < 4; i++) {
            animationStartCorners[i] = new PointF();
            animationEndCorners[i] = new PointF();
        }

        // Initialize the relative corners array (as percentages of view dimensions)
        relativeCorners = new float[4][2];

        // Initialize the paints with enhanced visual appearance
        trapezoidPaint = new Paint();
        trapezoidPaint.setColor(Color.rgb(255, 102, 0)); // Bright orange for better visibility on most backgrounds
        trapezoidPaint.setStrokeWidth(10); // Thicker line for better visibility
        trapezoidPaint.setStyle(Paint.Style.STROKE);
        trapezoidPaint.setAntiAlias(true);
        // Add shadow effect to make the outline stand out more
        trapezoidPaint.setShadowLayer(5.0f, 2.0f, 2.0f, Color.BLACK);

        cornerPaint = new Paint();
        cornerPaint.setColor(Color.rgb(255, 102, 0)); // Matching orange color
        cornerPaint.setStyle(Paint.Style.FILL);
        cornerPaint.setAntiAlias(true);
        // Add shadow effect to make corners stand out more
        cornerPaint.setShadowLayer(5.0f, 2.0f, 2.0f, Color.BLACK);

        activePaint = new Paint();
        activePaint.setColor(Color.rgb(255, 255, 0)); // Bright yellow for active corner
        activePaint.setStyle(Paint.Style.FILL);
        activePaint.setAntiAlias(true);
        // Add glow effect for active corner
        activePaint.setShadowLayer(8.0f, 0.0f, 0.0f, Color.rgb(255, 255, 100));

        // Initialize the background paint for the semi-transparent overlay
        backgroundPaint = new Paint();
        // Use a gradient overlay that's more visible but less intrusive
        backgroundPaint.setColor(Color.argb(60, 0, 150, 255)); // Semi-transparent blue with higher saturation
        backgroundPaint.setStyle(Paint.Style.FILL);
        backgroundPaint.setAntiAlias(true);

        // Initialize the hint text paint
        hintPaint = new Paint();
        hintPaint.setColor(Color.WHITE);
        hintPaint.setTextSize(40); // Large text size for visibility
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setAntiAlias(true);

        // Initialize the hint background paint
        hintBackgroundPaint = new Paint();
        hintBackgroundPaint.setColor(Color.argb(180, 0, 0, 0)); // Semi-transparent black
        hintBackgroundPaint.setStyle(Paint.Style.FILL);
        hintBackgroundPaint.setAntiAlias(true);

        // Initialize default magnifier size in px (approx 140dp)
        if (magnifierSizePx == 0) {
            float density = getResources().getDisplayMetrics().density;
            magnifierSizePx = (int) (140 * density + 0.5f);
        }

        Log.d(TAG, "TrapezoidSelectionView initialized with user guidance");
    }

    /**
     * Updates the system gesture exclusion regions to minimize interference from system gestures
     * when interacting with crop handles. This method is applicable for API level 29 and above.
     * <p>
     * The method defines small rectangular regions around the four corner handles of the crop overlay.
     * These regions are excluded from edge system gestures, ensuring that touch interactions in
     * these areas are not intercepted by system edge gestures.
     * <p>
     * If the dimensions of the view are not valid or corners are not defined, the system gesture
     * exclusion regions are cleared.
     * <p>
     * Any exceptions thrown during the execution of this method are silently ignored to prevent
     * unintended crashes or disruptions.
     */
    private void updateSystemGestureExclusion() {
        try {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                setSystemGestureExclusionRects(java.util.Collections.emptyList());
                return;
            }

            float density = getResources().getDisplayMetrics().density;
            int pad = (int) (24f * density + 0.5f); // default handle padding
            int activePad = (int) (40f * density + 0.5f); // larger while dragging

            // On newer Android (SDK>=34, Android 14/15), the back-edge gesture is wider/more aggressive on Pixels.
            // Use broader exclusion strips there; keep legacy widths on older SDKs to avoid over-excluding.
            final boolean modernBackGesture = (Build.VERSION.SDK_INT >= 34);
            // Always-on small side strips to reduce chance of edge gestures eating DOWN near edges
            int alwaysOnSideStrip = (int) ((modernBackGesture ? 40f : 24f) * density + 0.5f);
            // Larger strips while actively dragging
            int draggingSideStrip = (int) ((modernBackGesture ? 112f : 72f) * density + 0.5f);

            java.util.ArrayList<android.graphics.Rect> rects = new java.util.ArrayList<>();

            // Exclude around handles (bigger for active one while dragging)
            if (corners != null) {
                for (int i = 0; i < 4; i++) {
                    float cx = corners[i].x;
                    float cy = corners[i].y;
                    int p = (i == activeCornerIndex && activeCornerIndex != -1) ? activePad : pad;
                    int left = Math.max(0, Math.round(cx - p));
                    int top = Math.max(0, Math.round(cy - p));
                    int right = Math.min(w, Math.round(cx + p));
                    int bottom = Math.min(h, Math.round(cy + p));
                    if (right > left && bottom > top) {
                        rects.add(new android.graphics.Rect(left, top, right, bottom));
                    }
                }
            }

            // Always-on thin strips at the extreme left/right of the view to protect initial touches
            rects.add(new android.graphics.Rect(
                    0,
                    0,
                    Math.min(w, alwaysOnSideStrip),
                    h
            ));
            rects.add(new android.graphics.Rect(
                    Math.max(0, w - alwaysOnSideStrip),
                    0,
                    w,
                    h
            ));

            // Additionally, while dragging, exclude thin strips along the displayed image edges
            if (activeCornerIndex != -1) {
                android.graphics.RectF img = getDisplayedImageRectF(w, h);
                if (img != null) {
                    int strip = (int) (16f * density + 0.5f);
                    // Top
                    rects.add(new android.graphics.Rect(
                            Math.max(0, Math.round(img.left)),
                            Math.max(0, Math.round(img.top - strip)),
                            Math.min(w, Math.round(img.right)),
                            Math.max(0, Math.round(img.top + strip))
                    ));
                    // Bottom
                    rects.add(new android.graphics.Rect(
                            Math.max(0, Math.round(img.left)),
                            Math.min(h, Math.round(img.bottom - strip)),
                            Math.min(w, Math.round(img.right)),
                            Math.min(h, Math.round(img.bottom + strip))
                    ));
                    // Left (image edge)
                    rects.add(new android.graphics.Rect(
                            Math.max(0, Math.round(img.left - strip)),
                            Math.max(0, Math.round(img.top)),
                            Math.max(0, Math.round(img.left + strip)),
                            Math.min(h, Math.round(img.bottom))
                    ));
                    // Right (image edge)
                    rects.add(new android.graphics.Rect(
                            Math.min(w, Math.round(img.right - strip)),
                            Math.max(0, Math.round(img.top)),
                            Math.min(w, Math.round(img.right + strip)),
                            Math.min(h, Math.round(img.bottom))
                    ));
                }

                // Also exclude along the view's extreme left/right edges to counter system back gestures (wider while dragging)
                rects.add(new android.graphics.Rect(
                        0,
                        0,
                        Math.min(w, draggingSideStrip),
                        h
                ));
                rects.add(new android.graphics.Rect(
                        Math.max(0, w - draggingSideStrip),
                        0,
                        w,
                        h
                ));
            }

            setSystemGestureExclusionRects(rects);
            // Keep a copy for debug overlay and optional logging
            lastExclusionRects = new java.util.ArrayList<>(rects);
            if (debugLogsEnabled) {
                Log.d(TAG, "updateSystemGestureExclusion applied rects=" + rects.size());
            }
        } catch (Throwable ignore) {
        }
    }

    /**
     * Converts absolute coordinates to relative coordinates (percentages of view dimensions)
     *
     * @param x      X coordinate in pixels
     * @param y      Y coordinate in pixels
     * @param width  View width
     * @param height View height
     * @return Array with relative coordinates [x%, y%]
     */
    private float[] absoluteToRelative(float x, float y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return new float[]{0, 0};
        }
        return new float[]{x / width, y / height};
    }

    /**
     * Converts relative coordinates to absolute coordinates (pixels)
     *
     * @param relX   X coordinate as percentage of width (0.0-1.0)
     * @param relY   Y coordinate as percentage of height (0.0-1.0)
     * @param width  View width
     * @param height View height
     * @return PointF with absolute coordinates in pixels
     */
    private PointF relativeToAbsolute(float relX, float relY, int width, int height) {
        return new PointF(relX * width, relY * height);
    }

    /**
     * Clamp a point to the displayed image bounds using FIT_CENTER logic.
     * If no bitmap is set, clamps to the view bounds.
     * Returns a float[2] array with clamped x,y in view pixels.
     */
    private float[] clampToImageBounds(float x, float y, int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return new float[]{x, y};
        }
        if (imageBitmap == null || imageBitmap.isRecycled()) {
            float cx = Math.max(0f, Math.min(x, viewWidth));
            float cy = Math.max(0f, Math.min(y, viewHeight));
            return new float[]{cx, cy};
        }
        int bw = imageBitmap.getWidth();
        int bh = imageBitmap.getHeight();
        if (bw <= 0 || bh <= 0) {
            float cx = Math.max(0f, Math.min(x, viewWidth));
            float cy = Math.max(0f, Math.min(y, viewHeight));
            return new float[]{cx, cy};
        }
        float bitmapAspect = (float) bw / (float) bh;
        float viewAspect = (float) viewWidth / (float) viewHeight;
        float scale;
        float offsetX = 0f;
        float offsetY = 0f;
        if (bitmapAspect > viewAspect) {
            // letterboxed: horizontal fit
            scale = (float) viewWidth / (float) bw;
            offsetY = (viewHeight - (bh * scale)) / 2f;
        } else {
            // pillarboxed: vertical fit
            scale = (float) viewHeight / (float) bh;
            offsetX = (viewWidth - (bw * scale)) / 2f;
        }
        float left = offsetX;
        float top = offsetY;
        float right = offsetX + bw * scale;
        float bottom = offsetY + bh * scale;
        // Optionally keep a 1px inset to keep handle fully visible
        // float inset = 0f;
        float cx = Math.max(left, Math.min(x, right));
        float cy = Math.max(top, Math.min(y, bottom));
        return new float[]{cx, cy};
    }

    /**
     * Returns the displayed image rect in view coordinates using FIT_CENTER logic.
     * If there is no bitmap, returns the full view rect.
     */
    @Nullable
    private android.graphics.RectF getDisplayedImageRectF(int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) return null;
        if (imageBitmap == null || imageBitmap.isRecycled()) {
            return new android.graphics.RectF(0, 0, viewWidth, viewHeight);
        }
        int bw = imageBitmap.getWidth();
        int bh = imageBitmap.getHeight();
        if (bw <= 0 || bh <= 0) {
            return new android.graphics.RectF(0, 0, viewWidth, viewHeight);
        }
        float bitmapAspect = (float) bw / (float) bh;
        float viewAspect = (float) viewWidth / (float) viewHeight;
        float scale;
        float offsetX = 0f;
        float offsetY = 0f;
        if (bitmapAspect > viewAspect) {
            scale = (float) viewWidth / (float) bw;
            offsetY = (viewHeight - (bh * scale)) / 2f;
        } else {
            scale = (float) viewHeight / (float) bh;
            offsetX = (viewWidth - (bw * scale)) / 2f;
        }
        float left = offsetX;
        float top = offsetY;
        float right = offsetX + bw * scale;
        float bottom = offsetY + bh * scale;
        return new android.graphics.RectF(left, top, right, bottom);
    }

    /**
     * Updates both absolute and relative coordinates for a corner
     *
     * @param index Corner index (0-3)
     * @param x     X coordinate in pixels
     * @param y     Y coordinate in pixels
     */
    private void updateCorner(int index, float x, float y) {
        if (index < 0 || index >= 4) return;

        int width = getWidth();
        int height = getHeight();

        // Clamp the target position to the displayed image bounds (or view bounds if image unknown)
        float[] clamped = clampToImageBounds(x, y, width, height);
        float cx = clamped[0];
        float cy = clamped[1];

        // Update absolute coordinates
        corners[index].set(cx, cy);

        // Update relative coordinates if dimensions are valid
        if (width > 0 && height > 0) {
            relativeCorners[index] = absoluteToRelative(cx, cy, width, height);
        }
        // Keep gesture exclusion rects in sync while corners move
        updateSystemGestureExclusion();
    }

    // ========================= ASYNC INITIALIZATION =========================

    /**
     * Kicks off async corner initialization (idempotent/debounced).
     */
    private void initializeCornersAsync() {
        final int width = getWidth();
        final int height = getHeight();

        if (isUserAdjusting) {
            if (debugLogsEnabled)
                Log.d(TAG, "initializeCornersAsync: suppressed (user adjusting), will retry after idle");
            // coalesce via initCornersRunnable with a delay
            removeCallbacks(initCornersRunnable);
            try {
                postDelayed(initCornersRunnable, autoInitIdleDelayMs);
            } catch (Throwable ignore) {
            }
            return;
        }

        Log.d(TAG, "initializeCornersAsync called, dimensions: " + width + "x" + height + ", attempt: " + (++initializationAttempts));

        if (width == 0 || height == 0) {
            Log.w(TAG, "Cannot initialize corners, view has zero dimensions");
            removeCallbacks(initCornersRunnable);
            postDelayed(this::initializeCornersAsync, 100);
            return;
        }

        // Cancel any running task
        if (cornerTask != null) {
            cornerTask.cancel(true);
            cornerTask = null;
        }
        final int seq = ++requestedInitSeq;
        final Bitmap bmp = imageBitmap; // capture

        cornerTask = cornerExec.submit(() -> {
            long start = android.os.SystemClock.uptimeMillis();
            try {
                Point[] resultViewCorners = null;

                if (bmp != null && OpenCVUtils.isInitialized()) {
                    // cheap pre-scale for detection
                    Bitmap work = bmp;
                    int maxEdge = 1280; // budget-friendly
                    int bw = bmp.getWidth(), bh = bmp.getHeight();
                    float s = Math.min(1f, maxEdge / (float) Math.max(bw, bh));
                    if (s < 1f) {
                        work = Bitmap.createScaledBitmap(bmp, Math.round(bw * s), Math.round(bh * s), true);
                    }

                    // time budget (~600 ms)
                    final long budgetMs = 600;
                    resultViewCorners = detectCornersWithBudget(work, s, width, height, budgetMs);
                    if (work != bmp) work.recycle();
                }

                if (Thread.currentThread().isInterrupted()) return;

                if (resultViewCorners == null) {
                    // heuristic fallback
                    resultViewCorners = fallbackCorners(width, height, bmp);
                }

                final Point[] cornersFinal = resultViewCorners;
                post(() -> {
                    if (seq != requestedInitSeq) return;
                    if (cornersFinal != null && cornersFinal.length == 4) {
                        for (int i = 0; i < 4; i++) {
                            updateCorner(i, (float) cornersFinal[i].x, (float) cornersFinal[i].y);
                        }
                    } else {
                        setDefaultCorners(width, height);
                    }
                    lastWidth = width;
                    lastHeight = height;
                    initialized = true;
                    invalidate();
                });
            } catch (Throwable t) {
                Log.e(TAG, "Async corner init failed", t);
                post(() -> {
                    if (seq != requestedInitSeq) return;
                    setDefaultCorners(width, height);
                    lastWidth = width;
                    lastHeight = height;
                    initialized = true;
                    invalidate();
                });
            } finally {
                long dur = android.os.SystemClock.uptimeMillis() - start;
                Log.d(TAG, "initializeCornersAsync finished in " + dur + "ms");
            }
        });
    }

    /**
     * Detect corners under a time budget; returns view-space points or null.
     */
    @Nullable
    private Point[] detectCornersWithBudget(Bitmap work, float scaleToOrig, int viewW, int viewH, long budgetMs) {
        long t0 = android.os.SystemClock.uptimeMillis();
        try {
            org.opencv.core.Point[] imgCorners = OpenCVUtils.detectDocumentCorners(getContext(), work);
            if (imgCorners == null || imgCorners.length != 4) return null;

            // back-project to original image scale if we downscaled
            if (scaleToOrig < 1f) {
                double inv = 1.0 / scaleToOrig;
                for (org.opencv.core.Point p : imgCorners) {
                    p.x *= inv;
                    p.y *= inv;
                }
            }

            // Transform to view-space using existing helper
            Point[] viewPts = transformImageToViewCoordinates(imgCorners, imageBitmap != null ? imageBitmap : work);
            if (viewPts == null || viewPts.length != 4) return null;

            // Validate quickly
            if (!validateViewCoordinates(viewPts, viewW, viewH)) {
                adjustViewCoordinates(viewPts, viewW, viewH);
            }
            return viewPts;
        } catch (Throwable e) {
            Log.w(TAG, "detectCornersWithBudget: detection failed", e);
            return null;
        } finally {
            long dt = android.os.SystemClock.uptimeMillis() - t0;
            if (dt > budgetMs) Log.w(TAG, "Corner detection over budget: " + dt + "ms");
        }
    }

    /**
     * Builds a heuristic trapezoid in view space (used as fallback).
     */
    private Point[] fallbackCorners(int viewW, int viewH, @Nullable Bitmap bmp) {
        Point[] pts = new Point[4];
        pts[0] = new Point(viewW * 0.1, viewH * 0.1);
        pts[1] = new Point(viewW * 0.9, viewH * 0.1);
        pts[2] = new Point(viewW * 0.9, viewH * 0.9);
        pts[3] = new Point(viewW * 0.1, viewH * 0.9);
        return pts;
    }

    private void setDefaultCorners(int width, int height) {
        updateCorner(0, width * 0.1f, height * 0.1f);
        updateCorner(1, width * 0.9f, height * 0.1f);
        updateCorner(2, width * 0.9f, height * 0.9f);
        updateCorner(3, width * 0.1f, height * 0.9f);
        Log.d(TAG, "Corners set to default");
    }

    // ========================= END ASYNC =========================

    /**
     * Transforms coordinates from image space to view space with enhanced accuracy and robustness
     *
     * @param imageCoordinates Array of points in image coordinates
     * @param bitmap           The image bitmap
     * @return Array of points in view coordinates
     */
    private Point[] transformImageToViewCoordinates(Point[] imageCoordinates, Bitmap bitmap) {
        if (imageCoordinates == null || bitmap == null) {
            Log.w(TAG, "Null parameters in transformImageToViewCoordinates");
            return createDefaultViewCoordinates();
        }

        if (imageCoordinates.length != 4) {
            Log.w(TAG, "Expected 4 coordinates, got " + imageCoordinates.length);
            return createDefaultViewCoordinates();
        }

        // Get the dimensions
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int bitmapWidth = bitmap.getWidth();
        int bitmapHeight = bitmap.getHeight();

        if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
            Log.e(TAG, "Invalid dimensions for coordinate transformation: " + "viewWidth=" + viewWidth + ", viewHeight=" + viewHeight + ", bitmapWidth=" + bitmapWidth + ", bitmapHeight=" + bitmapHeight);
            return createDefaultViewCoordinates();
        }

        // Get the current orientation for logging
        int orientation = getResources().getConfiguration().orientation;
        boolean isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        Log.d(TAG, "Current orientation: " + (isPortrait ? "portrait" : "landscape"));

        // Use CoordinateTransformUtils for the core transformation
        Point[] viewCoordinates = de.schliweb.makeacopy.utils.CoordinateTransformUtils.transformImageToViewCoordinates(imageCoordinates, bitmap, viewWidth, viewHeight);

        if (viewCoordinates == null) {
            Log.w(TAG, "CoordinateTransformUtils returned null, using default coordinates");
            return createDefaultViewCoordinates();
        }

        // Apply additional processing specific to TrapezoidSelectionView

        // Apply small inset to avoid edge cases
        double insetFactor = 0.01; // 1% inset
        for (int i = 0; i < viewCoordinates.length; i++) {
            double viewX = viewCoordinates[i].x;
            double viewY = viewCoordinates[i].y;

            if (viewX <= 0) viewX = viewWidth * insetFactor;
            if (viewX >= viewWidth) viewX = viewWidth * (1 - insetFactor);
            if (viewY <= 0) viewY = viewHeight * insetFactor;
            if (viewY >= viewHeight) viewY = viewHeight * (1 - insetFactor);

            viewCoordinates[i] = new Point(viewX, viewY);

            Log.d(TAG, "Processed point " + i + ": (" + imageCoordinates[i].x + "," + imageCoordinates[i].y + ") -> (" + viewX + "," + viewY + ")");
        }

        // Validate the transformed coordinates
        if (!validateViewCoordinates(viewCoordinates, viewWidth, viewHeight)) {
            Log.w(TAG, "Transformed coordinates failed validation, using adjusted coordinates");
            adjustViewCoordinates(viewCoordinates, viewWidth, viewHeight);
        }

        return viewCoordinates;
    }

    /**
     * Creates intelligent default view coordinates when transformation fails
     * Chooses an appropriate template based on view dimensions and orientation
     *
     * @return Array of 4 points forming a default trapezoid
     */
    private Point[] createDefaultViewCoordinates() {
        int width = getWidth();
        int height = getHeight();

        // Use default values if dimensions are invalid
        if (width <= 0) width = 1000;
        if (height <= 0) height = 1000;

        // Get the current orientation
        int orientation = getResources().getConfiguration().orientation;
        boolean isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;

        // Calculate aspect ratio to determine document type
        float aspectRatio = (float) width / height;

        // Choose template based on orientation and aspect ratio
        String templateType;
        if (isPortrait) {
            if (aspectRatio < 0.7f) {
                templateType = "RECEIPT"; // Tall and narrow (receipt)
            } else if (aspectRatio < 0.9f) {
                templateType = "PORTRAIT_DOCUMENT"; // Standard portrait document
            } else {
                templateType = "SQUARE_DOCUMENT"; // Nearly square document
            }
        } else {
            if (aspectRatio > 1.8f) {
                templateType = "WIDE_DOCUMENT"; // Very wide document (panorama)
            } else if (aspectRatio > 1.3f) {
                templateType = "LANDSCAPE_DOCUMENT"; // Standard landscape document
            } else {
                templateType = "SQUARE_DOCUMENT"; // Nearly square document
            }
        }

        Log.d(TAG, "Creating default coordinates with template: " + templateType + ", orientation: " + (isPortrait ? "portrait" : "landscape") + ", aspect ratio: " + aspectRatio);

        // Create a random number generator with a seed based on dimensions
        // This ensures consistent randomization for the same view size
        java.util.Random random = new java.util.Random(width * 31L + height);

        // Base inset values (as percentage of dimensions)
        double baseInsetX = width * 0.1;
        double baseInsetY = height * 0.1;

        // Create the coordinates array
        Point[] defaultCoordinates = new Point[4];

        // Apply template-specific adjustments
        switch (templateType) {
            case "RECEIPT":
                // Narrow at top, wider at bottom (typical receipt shape)
                defaultCoordinates[0] = new Point(width * 0.3, height * 0.1); // Top-left
                defaultCoordinates[1] = new Point(width * 0.7, height * 0.1); // Top-right
                defaultCoordinates[2] = new Point(width * 0.8, height * 0.9); // Bottom-right
                defaultCoordinates[3] = new Point(width * 0.2, height * 0.9); // Bottom-left
                break;

            case "PORTRAIT_DOCUMENT":
                // Slightly trapezoidal portrait document
                defaultCoordinates[0] = new Point(width * 0.2, height * 0.15); // Top-left
                defaultCoordinates[1] = new Point(width * 0.8, height * 0.1); // Top-right
                defaultCoordinates[2] = new Point(width * 0.85, height * 0.9); // Bottom-right
                defaultCoordinates[3] = new Point(width * 0.15, height * 0.85); // Bottom-left
                break;

            case "LANDSCAPE_DOCUMENT":
                // Slightly trapezoidal landscape document
                defaultCoordinates[0] = new Point(width * 0.15, height * 0.2); // Top-left
                defaultCoordinates[1] = new Point(width * 0.9, height * 0.15); // Top-right
                defaultCoordinates[2] = new Point(width * 0.85, height * 0.85); // Bottom-right
                defaultCoordinates[3] = new Point(width * 0.1, height * 0.8); // Bottom-left
                break;

            case "WIDE_DOCUMENT":
                // Very wide document with perspective
                defaultCoordinates[0] = new Point(width * 0.1, height * 0.25); // Top-left
                defaultCoordinates[1] = new Point(width * 0.9, height * 0.2); // Top-right
                defaultCoordinates[2] = new Point(width * 0.95, height * 0.8); // Bottom-right
                defaultCoordinates[3] = new Point(width * 0.05, height * 0.75); // Bottom-left
                break;

            case "SQUARE_DOCUMENT":
            default:
                // Slightly trapezoidal square document
                defaultCoordinates[0] = new Point(width * 0.2, height * 0.2); // Top-left
                defaultCoordinates[1] = new Point(width * 0.8, height * 0.15); // Top-right
                defaultCoordinates[2] = new Point(width * 0.85, height * 0.85); // Bottom-right
                defaultCoordinates[3] = new Point(width * 0.15, height * 0.8); // Bottom-left
                break;
        }

        // Add slight randomization for more natural appearance (±5%)
        for (int i = 0; i < 4; i++) {
            double randomFactorX = 1.0 + (random.nextDouble() - 0.5) * 0.1; // ±5%
            double randomFactorY = 1.0 + (random.nextDouble() - 0.5) * 0.1; // ±5%

            // Apply randomization while keeping points within reasonable bounds
            double newX = defaultCoordinates[i].x * randomFactorX;
            double newY = defaultCoordinates[i].y * randomFactorY;

            // Ensure points stay within view bounds with small margin
            newX = Math.max(width * 0.05, Math.min(width * 0.95, newX));
            newY = Math.max(height * 0.05, Math.min(height * 0.95, newY));

            defaultCoordinates[i] = new Point(newX, newY);
        }

        Log.d(TAG, "Created intelligent default coordinates:");
        Log.d(TAG, "  Top-left: (" + defaultCoordinates[0].x + ", " + defaultCoordinates[0].y + ")");
        Log.d(TAG, "  Top-right: (" + defaultCoordinates[1].x + ", " + defaultCoordinates[1].y + ")");
        Log.d(TAG, "  Bottom-right: (" + defaultCoordinates[2].x + ", " + defaultCoordinates[2].y + ")");
        Log.d(TAG, "  Bottom-left: (" + defaultCoordinates[3].x + ", " + defaultCoordinates[3].y + ")");

        return defaultCoordinates;
    }

    /**
     * Updates the animation progress and corner positions
     */
    private void updateAnimation() {
        if (!isAnimating) {
            return;
        }

        // Calculate animation progress based on elapsed time
        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - animationStartTime;
        animationProgress = Math.min(1.0f, (float) elapsedTime / ANIMATION_DURATION);

        // Use ease-in-out interpolation for smoother animation
        float interpolatedProgress = interpolateEaseInOut(animationProgress);

        // Interpolate between start and end positions
        for (int i = 0; i < 4; i++) {
            corners[i].x = animationStartCorners[i].x + (animationEndCorners[i].x - animationStartCorners[i].x) * interpolatedProgress;
            corners[i].y = animationStartCorners[i].y + (animationEndCorners[i].y - animationStartCorners[i].y) * interpolatedProgress;
        }

        // Check if animation is complete
        if (animationProgress >= 1.0f) {
            isAnimating = false;

            // Update relative corners after animation completes
            int width = getWidth();
            int height = getHeight();
            if (width > 0 && height > 0) {
                for (int i = 0; i < 4; i++) {
                    relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, width, height);
                }
            }

            Log.d(TAG, "Animation completed");
        } else {
            // Continue animation in the next frame
            invalidate();
        }
    }

    /**
     * Applies an ease-in-out interpolation to create smoother animation
     *
     * @param t Linear progress (0.0 to 1.0)
     * @return Interpolated progress
     */
    private float interpolateEaseInOut(float t) {
        // Cubic ease-in-out: t^2 * (3 - 2t)
        return t * t * (3 - 2 * t);
    }

    /**
     * Validates that the view coordinates form a valid quadrilateral
     *
     * @param coordinates Array of 4 points
     * @param viewWidth   Width of the view
     * @param viewHeight  Height of the view
     * @return true if coordinates are valid, false otherwise
     */
    private boolean validateViewCoordinates(Point[] coordinates, int viewWidth, int viewHeight) {
        if (coordinates == null || coordinates.length != 4) {
            return false;
        }

        // Check if all points are within view bounds
        for (Point p : coordinates) {
            if (p.x < 0 || p.x > viewWidth || p.y < 0 || p.y > viewHeight) {
                Log.w(TAG, "Point outside view bounds: (" + p.x + "," + p.y + ")");
                return false;
            }
        }

        // Check if the quadrilateral has reasonable area (at least 10% of view)
        double area = Math.abs((coordinates[0].x * (coordinates[1].y - coordinates[3].y) + coordinates[1].x * (coordinates[2].y - coordinates[0].y) + coordinates[2].x * (coordinates[3].y - coordinates[1].y) + coordinates[3].x * (coordinates[0].y - coordinates[2].y)) / 2.0);

        double viewArea = viewWidth * viewHeight;
        double areaRatio = area / viewArea;

        if (areaRatio < 0.1) {
            Log.w(TAG, "Quadrilateral area too small: " + areaRatio + " of view area");
            return false;
        }

        return true;
    }

    /**
     * Adjusts view coordinates to ensure they form a valid quadrilateral
     *
     * @param coordinates Array of 4 points to adjust
     * @param viewWidth   Width of the view
     * @param viewHeight  Height of the view
     */
    private void adjustViewCoordinates(Point[] coordinates, int viewWidth, int viewHeight) {
        if (coordinates == null || coordinates.length != 4) {
            return;
        }

        // Calculate center of the view
        double centerX = viewWidth / 2.0;
        double centerY = viewHeight / 2.0;

        // Calculate reasonable inset (15% of the smaller dimension)
        double inset = Math.min(viewWidth, viewHeight) * 0.15;

        // Adjust each point to be within bounds and form a reasonable trapezoid
        // Top-left
        coordinates[0].x = Math.max(inset, Math.min(centerX - inset, coordinates[0].x));
        coordinates[0].y = Math.max(inset, Math.min(centerY - inset, coordinates[0].y));

        // Top-right
        coordinates[1].x = Math.max(centerX + inset, Math.min(viewWidth - inset, coordinates[1].x));
        coordinates[1].y = Math.max(inset, Math.min(centerY - inset, coordinates[1].y));

        // Bottom-right
        coordinates[2].x = Math.max(centerX + inset, Math.min(viewWidth - inset, coordinates[2].x));
        coordinates[2].y = Math.max(centerY + inset, Math.min(viewHeight - inset, coordinates[2].y));

        // Bottom-left
        coordinates[3].x = Math.max(inset, Math.min(centerX - inset, coordinates[3].x));
        coordinates[3].y = Math.max(centerY + inset, Math.min(viewHeight - inset, coordinates[3].y));

        Log.d(TAG, "Adjusted coordinates to ensure valid quadrilateral");
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Invalidate magnifier on size/orientation change (rebuild lazily on next drag)
        if (magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Throwable ignore) {
            }
        }
        magnifier = null;
        // Update system gesture exclusion rects for new size
        updateSystemGestureExclusion();

        // Get the current orientation
        int orientation = getResources().getConfiguration().orientation;
        String orientationName = (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) ? "portrait" : "landscape";

        Log.d(TAG, "onSizeChanged: " + w + "x" + h + " (was " + oldw + "x" + oldh + "), orientation: " + orientationName);

        // Initialize corners when the view size is first determined
        if (!initialized) {
            Log.d(TAG, "Scheduling corners initialization via posted runnable");
            // Debounce any previous requests and post a fresh one
            removeCallbacks(initCornersRunnable);
            post(initCornersRunnable);
        } else if ((w != oldw || h != oldh) && w > 0 && h > 0) {
            // cancel any pending detection and reschedule/guard
            if (cornerTask != null) {
                cornerTask.cancel(true);
                cornerTask = null;
            }
            requestedInitSeq++; // invalidate older tasks

            // Proportional scaling of existing corners
            Log.d(TAG, "Size changed, scaling corners proportionally");
            for (int i = 0; i < 4; i++) {
                PointF newPos = relativeToAbsolute(relativeCorners[i][0], relativeCorners[i][1], w, h);
                corners[i].set(newPos.x, newPos.y);
            }

            // Update last known dimensions
            lastWidth = w;
            lastHeight = h;

            // Force a redraw with the scaled corners
            invalidate();
            postInvalidate();

            // Option: bei starkem AR-Wechsel neu erkennen (billiger & stabiler)
            if (oldw > 0 && oldh > 0) {
                float oldAspect = oldw / (float) oldh;
                float newAspect = w / (float) h;
                if (Math.abs(oldAspect - newAspect) > 0.5f) {
                    removeCallbacks(initCornersRunnable);
                    post(initCornersRunnable);
                }
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Cancel any pending initialization callbacks to avoid running after detach
        removeCallbacks(initCornersRunnable);
        if (cornerTask != null) {
            cornerTask.cancel(true);
            cornerTask = null;
        }
        requestedInitSeq++;
        if (magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Throwable ignore) {
            }
            magnifier = null;
        }
        magnifierSourceView = null;
        overlayToSource = null;
        isDraggingWithMagnifier = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!initialized) {
            Log.d(TAG, "onDraw called but not initialized yet, dimensions: " + getWidth() + "x" + getHeight());
            return;
        }

        // Update animation if in progress
        if (isAnimating) {
            updateAnimation();
        }

        // Create a path for the trapezoid
        Path path = new Path();
        path.moveTo(corners[0].x, corners[0].y);
        path.lineTo(corners[1].x, corners[1].y);
        path.lineTo(corners[2].x, corners[2].y);
        path.lineTo(corners[3].x, corners[3].y);
        path.close();

        // Draw the semi-transparent background inside the trapezoid
        canvas.drawPath(path, backgroundPaint);

        // Draw the trapezoid outline
        canvas.drawPath(path, trapezoidPaint);

        // Draw the corner handles
        for (int i = 0; i < 4; i++) {
            // While the magnifier is active, do not draw the active corner as a yellow filled circle.
            if (isDraggingWithMagnifier && i == activeCornerIndex) {
                // Skip drawing the active handle to avoid a yellow circle in the magnifier; the white crosshair suffices.
                continue;
            }
            Paint paint = (i == activeCornerIndex) ? activePaint : cornerPaint;
            canvas.drawCircle(corners[i].x, corners[i].y, CORNER_RADIUS, paint);
        }

        // Draw a simple crosshair at active corner while dragging (pairs well with magnifier)
        if (isDraggingWithMagnifier && activeCornerIndex != -1) {
            float cx = corners[activeCornerIndex].x;
            float cy = corners[activeCornerIndex].y;
            Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            crossPaint.setColor(Color.WHITE);
            crossPaint.setStrokeWidth(3f);
            // Outer subtle shadow for visibility on bright backgrounds
            crossPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
            float len = CORNER_RADIUS + 20f;
            // Horizontal line
            canvas.drawLine(cx - len, cy, cx + len, cy, crossPaint);
            // Vertical line
            canvas.drawLine(cx, cy - len, cx, cy + len, crossPaint);
        }


        // Draw corner indices (avoid drawing the active corner's digit while magnifier is active so it doesn't appear inside the loupe)
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(40);
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 4; i++) {
            if (isDraggingWithMagnifier && i == activeCornerIndex) {
                continue; // skip active corner digit to keep the loupe clean (only white crosshair visible)
            }
            canvas.drawText(String.valueOf(i), corners[i].x, corners[i].y + 15, textPaint);
        }

        // Draw user guidance hints
        drawUserGuidance(canvas);

        // Debug overlay (image rect + exclusion rects + hit areas)
        if (debugOverlayEnabled) {
            drawDebugOverlay(canvas);
        }

        Log.d(TAG, "Trapezoid drawn with dimensions: " + getWidth() + "x" + getHeight());
    }

    private void drawDebugOverlay(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // Draw displayed image rect
        RectF img = getDisplayedImageRectF(w, h);
        if (img != null) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f);
            p.setColor(Color.argb(220, 0, 200, 0)); // green
            canvas.drawRect(img, p);
        }

        // Draw system gesture exclusion rects
        if (lastExclusionRects != null && !lastExclusionRects.isEmpty()) {
            Paint p2 = new Paint(Paint.ANTI_ALIAS_FLAG);
            p2.setStyle(Paint.Style.STROKE);
            p2.setStrokeWidth(2f);
            p2.setColor(Color.argb(220, 220, 0, 0)); // red
            for (Rect r : lastExclusionRects) {
                canvas.drawRect(r, p2);
            }
        }

        // Draw active handle touch area
        if (activeCornerIndex != -1) {
            float cx = corners[activeCornerIndex].x;
            float cy = corners[activeCornerIndex].y;
            Paint p3 = new Paint(Paint.ANTI_ALIAS_FLAG);
            p3.setStyle(Paint.Style.STROKE);
            p3.setStrokeWidth(2f);
            p3.setColor(Color.CYAN);
            canvas.drawCircle(cx, cy, CORNER_TOUCH_RADIUS, p3);
        }

        // Draw edge-glide state text
        if (isEdgeGlide) {
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTextSize(36f);
            tp.setShadowLayer(4f, 0f, 0f, Color.BLACK);
            canvas.drawText("[edge-glide]", 20f, h - 40f, tp);
        }
    }

    /**
     * Draws user guidance hints based on the current state of the trapezoid
     *
     * @param canvas Canvas to draw on
     */
    private int bottomUiInsetPx = 0;

    /**
     * Sets the bottom UI inset in pixels to keep hints clear of overlaid controls (e.g., rotation bar).
     */
    public void setBottomUiInsetPx(int insetPx) {
        if (insetPx < 0) insetPx = 0;
        if (this.bottomUiInsetPx != insetPx) {
            this.bottomUiInsetPx = insetPx;
            invalidate();
        }
    }

    private static int dp(Context ctx, int dp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }

    private void drawUserGuidance(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        // Don't draw hints if dimensions are invalid
        if (width <= 0 || height <= 0) {
            return;
        }

        // Determine the hint to show based on the current state
        String hint;
        float hintY;

        if (activeCornerIndex != -1) {
            // Show corner-specific hints when a corner is being dragged
            switch (activeCornerIndex) {
                case 0: // Top-left
                    hint = "Drag to adjust top-left corner";
                    break;
                case 1: // Top-right
                    hint = "Drag to adjust top-right corner";
                    break;
                case 2: // Bottom-right
                    hint = "Drag to adjust bottom-right corner";
                    break;
                case 3: // Bottom-left
                    hint = "Drag to adjust bottom-left corner";
                    break;
                default:
                    hint = "Drag corners to adjust document selection";
                    break;
            }

            // Position the hint near the active corner but not too close
            float cornerX = corners[activeCornerIndex].x;
            float cornerY = corners[activeCornerIndex].y;

            // Determine position based on which corner is active
            float hintX = width / 2; // Center horizontally by default

            // Position hint at the bottom of the screen for top corners
            // and at the top of the screen for bottom corners
            if (activeCornerIndex < 2) { // Top corners
                int baseBottomOffset = Math.max(100, bottomUiInsetPx + dp(getContext(), 12));
                hintY = height - baseBottomOffset; // Position above bottom UI
            } else { // Bottom corners
                hintY = 100; // Position near top
            }

            // Draw the hint
            drawHintText(canvas, hint, hintX, hintY);

        } else {
            // No corner is active, show general guidance

            // Check if the trapezoid is close to a rectangle
            boolean isNearlyRectangular = isNearlyRectangular();

            if (isNearlyRectangular) {
                hint = "Adjust corners to match document edges";
            } else {
                hint = "Drag any corner to fine-tune selection";
            }

            // Position the hint at the bottom of the screen, considering bottom UI inset
            int baseBottomOffset = Math.max(100, bottomUiInsetPx + dp(getContext(), 12));
            hintY = height - baseBottomOffset;

            // Draw the hint
            drawHintText(canvas, hint, width / 2, hintY);

            // Draw indicators for corners that might need adjustment
            highlightCornersNeedingAdjustment(canvas);
        }
    }

    /**
     * Draws a hint text with a background for better visibility
     *
     * @param canvas Canvas to draw on
     * @param text   Text to display
     * @param x      X coordinate (center of text)
     * @param y      Y coordinate (baseline of text)
     */
    private void drawHintText(Canvas canvas, String text, float x, float y) {
        // Measure text dimensions
        android.graphics.Rect textBounds = new android.graphics.Rect();
        hintPaint.getTextBounds(text, 0, text.length(), textBounds);

        // Calculate background rectangle with padding
        int padding = 20;
        android.graphics.RectF bgRect = new android.graphics.RectF(x - textBounds.width() / 2 - padding, y - textBounds.height() - padding, x + textBounds.width() / 2 + padding, y + padding);

        // Draw rounded rectangle background
        canvas.drawRoundRect(bgRect, 15, 15, hintBackgroundPaint);

        // Draw text
        canvas.drawText(text, x, y, hintPaint);
    }

    /**
     * Highlights corners that might need adjustment based on the trapezoid shape
     *
     * @param canvas Canvas to draw on
     */
    private void highlightCornersNeedingAdjustment(Canvas canvas) {
        // This is a simplified implementation that highlights corners
        // that are too close to each other or to the edges

        int width = getWidth();
        int height = getHeight();

        // Minimum distance from edges (5% of dimension)
        float minEdgeDistance = Math.min(width, height) * 0.05f;

        // Check each corner
        for (int i = 0; i < 4; i++) {
            boolean needsAdjustment = corners[i].x < minEdgeDistance || corners[i].x > width - minEdgeDistance || corners[i].y < minEdgeDistance || corners[i].y > height - minEdgeDistance;

            // Check if too close to edges

            // If corner needs adjustment, highlight it
            if (needsAdjustment) {
                Paint highlightPaint = new Paint();
                highlightPaint.setColor(Color.YELLOW);
                highlightPaint.setStrokeWidth(3);
                highlightPaint.setStyle(Paint.Style.STROKE);
                highlightPaint.setAntiAlias(true);

                // Draw a pulsating circle
                long time = System.currentTimeMillis() % 1000;
                float pulseRadius = CORNER_RADIUS + 5 + (float) (Math.sin(time / 1000.0 * 2 * Math.PI) * 5);

                canvas.drawCircle(corners[i].x, corners[i].y, pulseRadius, highlightPaint);
            }
        }
    }

    /**
     * Checks if the trapezoid is nearly rectangular
     *
     * @return true if the trapezoid is nearly rectangular, false otherwise
     */
    private boolean isNearlyRectangular() {
        // Calculate slopes of the top and bottom edges
        double topSlope = Math.abs((corners[1].y - corners[0].y) / (corners[1].x - corners[0].x + 0.0001));
        double bottomSlope = Math.abs((corners[2].y - corners[3].y) / (corners[2].x - corners[3].x + 0.0001));

        // Calculate slopes of the left and right edges
        double leftSlope = Math.abs((corners[3].y - corners[0].y) / (corners[3].x - corners[0].x + 0.0001));
        double rightSlope = Math.abs((corners[2].y - corners[1].y) / (corners[2].x - corners[1].x + 0.0001));

        // Check if the slopes are similar (indicating a rectangle)
        return Math.abs(topSlope - bottomSlope) < 0.1 && Math.abs(leftSlope - rightSlope) < 0.1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Prime exclusion even before we know if a handle is active
                updateSystemGestureExclusion();
                if (debugLogsEnabled) {
                    Log.d(TAG, "ACTION_DOWN x=" + x + ", y=" + y + ", rawX=" + event.getRawX() + ", rawY=" + event.getRawY());
                }
                // Check if a corner was touched
                activeCornerIndex = findCornerIndex(x, y);
                if (activeCornerIndex != -1) {
                    // Mark that the user is adjusting and cancel any pending idle-clear
                    isUserAdjusting = true;
                    cancelAdjustIdle();
                    // Prevent parents (e.g., ViewPager/ScrollView) from intercepting during drag
                    try {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } catch (Throwable ignore) {
                    }

                    // Edge-glide initial state
                    isEdgeGlide = false;
                    edgeGlidePending = false;
                    edgeGlideEligibleSinceMs = 0L;
                    lastRawY = event.getRawY();
                    glideY = corners[activeCornerIndex].y;

                    // Expand gesture exclusion while dragging
                    updateSystemGestureExclusion();

                    // Initialize and show magnifier if enabled and source is set
                    ensureMagnifier();
                    if (magnifier != null) {
                        PointF src = toSourceCoords(x, y);
                        try {
                            magnifier.show(src.x, src.y);
                        } catch (Throwable t) {
                            Log.w(TAG, "magnifier.show failed: " + t.getMessage());
                        }
                        isDraggingWithMagnifier = true;
                    }
                    invalidate();
                    return true;
                } else {
                    invalidate();
                    return false;
                }

            case MotionEvent.ACTION_MOVE:
                // Move the active corner
                if (activeCornerIndex != -1) {
                    // Still adjusting while dragging; ensure idle-clear is cancelled
                    isUserAdjusting = true;
                    cancelAdjustIdle();
                    // Keep parents from intercepting during drag and keep gesture exclusion updated
                    try {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } catch (Throwable ignore) {
                    }
                    updateSystemGestureExclusion();

                    // Edge-glide handling along left/right image edges
                    RectF img = getDisplayedImageRectF(getWidth(), getHeight());
                    float tx = x;
                    float ty = y;
                    if (img != null) {
                        // Soft Edge-Glide: only engage when the pointer is actually OUTSIDE the image rect
                        // by more than the enter epsilon. While inside, never force-lock X to the edge.
                        if (edgeGlideEnabled) {
                            float enterEps = edgeSnapEnterEpsPx();
                            float exitEps = edgeSnapExitEpsPx();

                            boolean outsideLeft = x < (img.left - enterEps);
                            boolean outsideRight = x > (img.right + enterEps);
                            boolean insideWithMargin = x >= (img.left + exitEps) && x <= (img.right - exitEps);

                            long now = android.os.SystemClock.uptimeMillis();

                            if (isEdgeGlide) {
                                // While gliding, keep X locked to the chosen edge and accumulate Y by rawY deltas
                                float lockX = edgeGlideLockLeft ? img.left : img.right;
                                if (!Float.isNaN(lastRawY)) {
                                    float dy = event.getRawY() - lastRawY;
                                    glideY += dy;
                                }
                                lastRawY = event.getRawY();
                                tx = lockX;
                                ty = glideY;

                                // Exit gliding when clearly back inside (beyond exit hysteresis)
                                if (insideWithMargin) {
                                    isEdgeGlide = false;
                                    edgeGlidePending = false;
                                    edgeGlideEligibleSinceMs = 0L;
                                    if (debugLogsEnabled) Log.d(TAG, "Exit edge-glide (back inside), x=" + x);
                                }
                            } else {
                                // Not yet gliding. Consider eligibility only if really outside.
                                boolean eligible = outsideLeft || outsideRight;
                                if (eligible) {
                                    // Start or continue pending timer
                                    if (!edgeGlidePending) {
                                        edgeGlidePending = true;
                                        edgeGlideEligibleSinceMs = now;
                                        edgeGlideLockLeft = outsideLeft; // remember which edge we will lock to
                                        if (debugLogsEnabled)
                                            Log.d(TAG, "Edge-glide eligible (" + (outsideLeft ? "left" : "right") + ") starting delay at x=" + x);
                                    }
                                    // Engage after delay
                                    if (edgeGlidePending && (now - edgeGlideEligibleSinceMs) >= edgeGlideEngageDelayMs) {
                                        isEdgeGlide = true;
                                        edgeGlidePending = false;
                                        glideY = corners[activeCornerIndex].y; // start from current handle y
                                        lastRawY = event.getRawY();
                                        if (debugLogsEnabled)
                                            Log.d(TAG, "Enter edge-glide after delay (" + (edgeGlideLockLeft ? "left" : "right") + ") at x=" + x);
                                        // Lock immediately on engage
                                        float lockX = edgeGlideLockLeft ? img.left : img.right;
                                        tx = lockX;
                                        ty = glideY;
                                    }
                                } else {
                                    // No longer eligible -> cancel pending
                                    if (edgeGlidePending) {
                                        edgeGlidePending = false;
                                        edgeGlideEligibleSinceMs = 0L;
                                        if (debugLogsEnabled)
                                            Log.d(TAG, "Cancel edge-glide pending (pointer not outside), x=" + x);
                                    }
                                }
                            }
                        }
                    }

                    // Use updateCorner to maintain both absolute and relative coordinates
                    updateCorner(activeCornerIndex, tx, ty);

                    // Update magnifier position if active
                    if (isDraggingWithMagnifier && magnifier != null) {
                        PointF src = toSourceCoords(tx, ty);
                        try {
                            magnifier.show(src.x, src.y);
                        } catch (Throwable t) {
                            Log.w(TAG, "magnifier.show(move) failed: " + t.getMessage());
                        }
                    }

                    // Log the updated corner position
                    if (debugLogsEnabled) {
                        Log.d(TAG, "MOVE activeCorner=" + activeCornerIndex +
                                ", x/y=(" + x + "," + y + "), tx/ty=(" + tx + "," + ty + ")" +
                                ", rel=(" + relativeCorners[activeCornerIndex][0] + "," + relativeCorners[activeCornerIndex][1] + ")" +
                                (isEdgeGlide ? " [edge-glide]" : ""));
                    }

                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (debugLogsEnabled) {
                    Log.d(TAG, (event.getAction() == MotionEvent.ACTION_UP ? "ACTION_UP" : "ACTION_CANCEL") +
                            " activeCorner=" + activeCornerIndex +
                            ", x=" + x + ", y=" + y + ", rawX=" + event.getRawX() + ", rawY=" + event.getRawY());
                }
                // Release the active corner
                if (activeCornerIndex != -1) {
                    // Ensure relative coordinates are updated when touch ends
                    updateCorner(activeCornerIndex, corners[activeCornerIndex].x, corners[activeCornerIndex].y);
                    Log.d(TAG, "Touch released, final corner " + activeCornerIndex + " position: " + "(" + corners[activeCornerIndex].x + "," + corners[activeCornerIndex].y + ")");
                }
                // Dismiss magnifier if shown
                if (magnifier != null && isDraggingWithMagnifier) {
                    try {
                        magnifier.dismiss();
                    } catch (Throwable t) {
                        Log.w(TAG, "magnifier.dismiss failed: " + t.getMessage());
                    }
                }
                // Allow parents to intercept again and shrink gesture exclusion back to normal
                try {
                    getParent().requestDisallowInterceptTouchEvent(false);
                } catch (Throwable ignore) {
                }
                isDraggingWithMagnifier = false;
                activeCornerIndex = -1;
                // Reset edge-glide state
                isEdgeGlide = false;
                edgeGlidePending = false;
                edgeGlideEligibleSinceMs = 0L;
                lastRawY = Float.NaN;
                // Keep user-adjusting true for a short idle window to suppress auto re-detection
                scheduleAdjustIdleClear();
                updateSystemGestureExclusion();
                invalidate();
                break;
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        // Call the super implementation to ensure proper accessibility handling
        return super.performClick();
    }

    /**
     * Find the index of the corner that was touched
     *
     * @param x X coordinate of the touch point
     * @param y Y coordinate of the touch point
     * @return Index of the touched corner, or -1 if no corner was touched
     */
    private int findCornerIndex(float x, float y) {
        for (int i = 0; i < 4; i++) {
            float dx = x - corners[i].x;
            float dy = y - corners[i].y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);

            if (distance < CORNER_TOUCH_RADIUS) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Get the corners of the trapezoid as OpenCV Points
     *
     * @return Array of 4 OpenCV Points representing the corners
     */
    public Point[] getCorners() {
        Point[] points = new Point[4];
        for (int i = 0; i < 4; i++) {
            points[i] = new Point(corners[i].x, corners[i].y);
        }
        return points;
    }

    /**
     * Set the image bitmap for edge detection
     *
     * @param bitmap The image bitmap
     */
    public void setImageBitmap(Bitmap bitmap) {
        this.imageBitmap = bitmap;
        Log.d(TAG, "Image bitmap set: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
        // Update last bitmap dimensions; if unchanged and already initialized, avoid redundant auto-init
        if (bitmap != null) {
            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            boolean sameDims = (bw == lastBitmapWidth && bh == lastBitmapHeight);
            lastBitmapWidth = bw;
            lastBitmapHeight = bh;
            if (sameDims && initialized) {
                if (debugLogsEnabled)
                    Log.d(TAG, "setImageBitmap: same bitmap dimensions and already initialized → skip re-init");
                invalidate();
                return;
            }
        } else {
            lastBitmapWidth = -1;
            lastBitmapHeight = -1;
        }

        // If the bitmap is null, do not (re)start corner initialization. Just cancel pending work and redraw.
        if (bitmap == null) {
            // Cancel any pending or running initialization tasks
            try {
                removeCallbacks(initCornersRunnable);
                if (cornerTask != null) {
                    cornerTask.cancel(true);
                    cornerTask = null;
                }
                requestedInitSeq++; // invalidate any in-flight tasks
            } catch (Throwable ignore) {
            }
            invalidate();
            return;
        }

        // Initialize OpenCV if needed
        if (!OpenCVUtils.isInitialized()) {
            Log.d(TAG, "Initializing OpenCV for edge detection");
            OpenCVUtils.init(getContext());
        }

        // (Re)trigger async init when we have dimensions (unless suppressed once)
        if (getWidth() > 0 && getHeight() > 0) {
            // If the user is currently adjusting, suppress auto-init for now
            if (isUserAdjusting) {
                suppressInitOnce = true;
                if (debugLogsEnabled) Log.d(TAG, "setImageBitmap: suppressing auto-init during user adjustment");
                invalidate();
                return;
            }
            removeCallbacks(initCornersRunnable);
            if (!suppressInitOnce) {
                post(initCornersRunnable);
            } else {
                suppressInitOnce = false;
                invalidate();
            }
        }
    }

    /**
     * Rotates the current trapezoid corners around the view center by the given clockwise degrees
     * and sets the new image bitmap without re-running corner initialization.
     * If the view is not yet initialized, this falls back to a normal setImageBitmap call.
     */
    public void setImageBitmapWithRotation(@NonNull Bitmap rotatedBitmap, int degreesCw) {
        // Normalize degrees to [0,360)
        int deg = ((degreesCw % 360) + 360) % 360;

        // If view not ready, just set normally
        if (!initialized || getWidth() <= 0 || getHeight() <= 0) {
            setImageBitmap(rotatedBitmap);
            return;
        }

        // If no actual rotation, just swap bitmap without re-detecting
        if (deg == 0) {
            suppressInitOnce = true;
            setImageBitmap(rotatedBitmap);
            invalidate();
            return;
        }

        try {
            // Preconditions: need current image bitmap to map between view<->image
            Bitmap curBmp = this.imageBitmap != null ? this.imageBitmap : rotatedBitmap;
            if (curBmp == null || curBmp.isRecycled()) {
                // Fallback: rotate in view space
                rotateCornersAroundViewCenter(deg);
                suppressInitOnce = true;
                setImageBitmap(rotatedBitmap);
                invalidate();
                return;
            }

            int viewW = getWidth();
            int viewH = getHeight();
            int imgW = curBmp.getWidth();
            int imgH = curBmp.getHeight();

            // 1) View -> Image coordinates for current corners
            org.opencv.core.Point[] viewPts = new org.opencv.core.Point[4];
            for (int i = 0; i < 4; i++) viewPts[i] = new org.opencv.core.Point(corners[i].x, corners[i].y);
            org.opencv.core.Point[] imgPts = de.schliweb.makeacopy.utils.CoordinateTransformUtils
                    .transformViewToImageCoordinates(viewPts, curBmp, viewW, viewH);
            if (imgPts == null) throw new IllegalStateException("transformViewToImageCoordinates returned null");

            // 2) Rotate image points around image center and translate to new top-left (0,0)
            double rad = Math.toRadians(deg);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            double cx = imgW / 2.0;
            double cy = imgH / 2.0;

            // Rotate the original image rectangle's corners to compute bounds translation
            double[][] rect = new double[][]{{0, 0}, {imgW, 0}, {imgW, imgH}, {0, imgH}};
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (double[] p : rect) {
                double dx = p[0] - cx;
                double dy = p[1] - cy;
                double rx = cx + dx * cos + dy * sin;      // CW
                double ry = cy + (-dx * sin + dy * cos);
                if (rx < minX) minX = rx;
                if (ry < minY) minY = ry;
                if (rx > maxX) maxX = rx;
                if (ry > maxY) maxY = ry;
            }
            double transX = -minX;
            double transY = -minY;
            int newImgW = (int) Math.round(maxX - minX);
            int newImgH = (int) Math.round(maxY - minY);

            org.opencv.core.Point[] imgPtsRot = new org.opencv.core.Point[4];
            for (int i = 0; i < 4; i++) {
                double dx = imgPts[i].x - cx;
                double dy = imgPts[i].y - cy;
                double rx = cx + dx * cos + dy * sin;
                double ry = cy + (-dx * sin + dy * cos);
                // Translate into rotated-bitmap coordinate system (top-left at 0,0)
                imgPtsRot[i] = new org.opencv.core.Point(rx + transX, ry + transY);
            }

            // 3) Image -> View coordinates using the rotated bitmap's dimensions
            // We don't need the actual ImageView; mapping uses viewW/viewH with FIT_CENTER assumption
            // Create a lightweight stub bitmap size using rotatedBitmap/newImgW,newImgH; prefer rotatedBitmap if valid
            Bitmap basis = rotatedBitmap != null ? rotatedBitmap : Bitmap.createBitmap(Math.max(1, newImgW), Math.max(1, newImgH), Bitmap.Config.ARGB_8888);
            org.opencv.core.Point[] viewPtsNew = de.schliweb.makeacopy.utils.CoordinateTransformUtils
                    .transformImageToViewCoordinates(imgPtsRot, basis, viewW, viewH);
            if (viewPtsNew == null) throw new IllegalStateException("transformImageToViewCoordinates returned null");

            // 4) Update absolute and relative corners
            for (int i = 0; i < 4; i++) {
                corners[i].x = (float) viewPtsNew[i].x;
                corners[i].y = (float) viewPtsNew[i].y;
            }
            for (int i = 0; i < 4; i++) {
                relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, viewW, viewH);
            }

            // 5) Suppress re-init and set new bitmap
            suppressInitOnce = true;
            setImageBitmap(rotatedBitmap);
            invalidate();
        } catch (Throwable t) {
            Log.w(TAG, "setImageBitmapWithRotation: precise mapping failed, falling back to view-space rotation: " + t.getMessage());
            // Fallback: rotate selection around view center
            rotateCornersAroundViewCenter(deg);
            suppressInitOnce = true;
            setImageBitmap(rotatedBitmap);
            invalidate();
        }
    }

    private void rotateCornersAroundViewCenter(int degreesCw) {
        int deg = ((degreesCw % 360) + 360) % 360;
        if (deg == 0) return;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        double rad = Math.toRadians(deg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        for (int i = 0; i < 4; i++) {
            float dx = corners[i].x - cx;
            float dy = corners[i].y - cy;
            float nx = (dx * cos + dy * sin);
            float ny = (-dx * sin + dy * cos);
            corners[i].x = cx + nx;
            corners[i].y = cy + ny;
        }
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < 4; i++) {
            relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, w, h);
        }
    }

    // ===== Magnifier API (public) =====
    public void setMagnifierSourceView(@NonNull View imageContentView, @Nullable Matrix imageToOverlayMatrix) {
        this.magnifierSourceView = imageContentView;
        if (imageToOverlayMatrix != null) {
            Matrix inv = new Matrix();
            if (imageToOverlayMatrix.invert(inv)) {
                this.overlayToSource = inv;
            } else {
                this.overlayToSource = null;
                Log.w(TAG, "Failed to invert imageToOverlayMatrix; falling back to screen-space transforms.");
            }
        } else {
            this.overlayToSource = null;
        }
        // Rebuild magnifier lazily on next drag
        if (magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Throwable ignore) {
            }
        }
        magnifier = null;
    }

    // ===== Magnifier helpers (private) =====
    private void ensureMagnifier() {
        if (magnifier == null && magnifierSourceView != null && magnifierEnabled) {
            try {
                Magnifier.Builder builder = new Magnifier.Builder(magnifierSourceView)
                        .setInitialZoom(magnifierZoom)
                        .setSize(magnifierSizePx, magnifierSizePx)
                        .setDefaultSourceToMagnifierOffset(0, -(int) (magnifierSizePx * 0.75f));
                magnifier = builder.build();
            } catch (Throwable t) {
                Log.w(TAG, "Failed to create Magnifier: " + t.getMessage());
                magnifier = null;
            }
        }
    }

    private PointF toSourceCoords(float overlayX, float overlayY) {
        if (overlayToSource != null) {
            float[] pts = new float[]{overlayX, overlayY};
            overlayToSource.mapPoints(pts);
            return new PointF(pts[0], pts[1]);
        }
        if (magnifierSourceView != null) {
            int[] srcLoc = new int[2];
            int[] ovlLoc = new int[2];
            magnifierSourceView.getLocationOnScreen(srcLoc);
            this.getLocationOnScreen(ovlLoc);
            float screenX = overlayX + ovlLoc[0];
            float screenY = overlayY + ovlLoc[1];
            return new PointF(screenX - srcLoc[0], screenY - srcLoc[1]);
        }
        return new PointF(overlayX, overlayY);
    }

    /**
     * Force the view to be visible and properly initialized
     * This can be called from outside to ensure the view is in the correct state
     * It's particularly important after orientation changes
     */
    public void forceVisibleAndInitialized() {
        Log.d(TAG, "forceVisibleAndInitialized called");

        // Check if the view is attached to a window
        boolean isAttached = isAttachedToWindow();
        Log.d(TAG, "View is " + (isAttached ? "attached" : "not attached") + " to window");

        // Log current dimensions and orientation
        int currentWidth = getWidth();
        int currentHeight = getHeight();
        int orientation = getResources().getConfiguration().orientation;
        String orientationName = (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) ? "portrait" : "landscape";
        Log.d(TAG, "Current dimensions: " + currentWidth + "x" + currentHeight + ", orientation: " + orientationName);

        // Force the view to be visible
        setVisibility(View.VISIBLE);

        // Bring to front to ensure it's on top of the view hierarchy
        bringToFront();

        // Request layout to ensure the view is properly laid out
        requestLayout();

        // If the view is not attached or has zero dimensions, we need to use ViewTreeObserver
        // to wait until the view is properly laid out before initializing or scaling corners
        if (!isAttached || currentWidth == 0 || currentHeight == 0) {
            Log.d(TAG, "View not attached or has zero dimensions, using ViewTreeObserver");

            // Store the current orientation for later comparison
            final int initialOrientation = orientation;

            // Use ViewTreeObserver to wait for layout to complete
            getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    // Remove the listener to prevent multiple calls
                    getViewTreeObserver().removeOnGlobalLayoutListener(this);

                    // Get the current dimensions and orientation after layout
                    int newWidth = getWidth();
                    int newHeight = getHeight();
                    int newOrientation = getResources().getConfiguration().orientation;
                    String newOrientationName = (newOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) ? "portrait" : "landscape";

                    Log.d(TAG, "ViewTreeObserver.onGlobalLayout: dimensions=" + newWidth + "x" + newHeight + ", orientation=" + newOrientationName);

                    // Check if we have valid dimensions now
                    if (newWidth > 0 && newHeight > 0) {
                        // Handle initialization or scaling based on the current state
                        if (!initialized) {
                            Log.d(TAG, "Initializing corners after layout completion");
                            initializeCornersAsync();
                        } else if (newWidth != lastWidth || newHeight != lastHeight || newOrientation != initialOrientation) {
                            // Dimensions or orientation have changed, scale corners
                            Log.d(TAG, "Scaling corners after layout completion: " + lastWidth + "x" + lastHeight + " -> " + newWidth + "x" + newHeight + ", orientation: " + (initialOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ? "portrait" : "landscape") + " -> " + newOrientationName);

                            // Scale each corner based on its relative position
                            for (int i = 0; i < 4; i++) {
                                PointF newPos = relativeToAbsolute(relativeCorners[i][0], relativeCorners[i][1], newWidth, newHeight);
                                corners[i].set(newPos.x, newPos.y);
                            }

                            // Update the last known dimensions
                            lastWidth = newWidth;
                            lastHeight = newHeight;

                            // Log the scaled corners
                            Log.d(TAG, "Corners after scaling for orientation change: " + "(" + corners[0].x + "," + corners[0].y + "), " + "(" + corners[1].x + "," + corners[1].y + "), " + "(" + corners[2].x + "," + corners[2].y + "), " + "(" + corners[3].x + "," + corners[3].y + ")");
                        }

                        // Force a redraw
                        invalidate();
                        postInvalidate();
                    } else {
                        // Still don't have valid dimensions, try again with a post
                        Log.w(TAG, "Still have invalid dimensions after layout, scheduling retry");
                        post(() -> forceVisibleAndInitialized());
                    }
                }
            });

            // Also schedule a fallback in case ViewTreeObserver doesn't trigger
            postDelayed(() -> {
                if (getWidth() > 0 && getHeight() > 0 && !initialized) {
                    Log.d(TAG, "Fallback initialization after delay");
                    initializeCornersAsync();
                }
            }, 500);

            return; // Exit early, the rest will be handled in the OnGlobalLayoutListener
        }

        // If we reach here, the view is attached and has valid dimensions

        // Check if we need to initialize corners or just ensure they're properly scaled
        if (!initialized) {
            Log.d(TAG, "View not initialized, initializing corners directly");
            initializeCornersAsync();
        } else if (currentWidth > 0 && currentHeight > 0 && (currentWidth != lastWidth || currentHeight != lastHeight)) {
            // Dimensions have changed (likely due to rotation), scale corners
            Log.d(TAG, "Dimensions changed from " + lastWidth + "x" + lastHeight + " to " + currentWidth + "x" + currentHeight + ", scaling corners");

            // Scale each corner based on its relative position
            for (int i = 0; i < 4; i++) {
                PointF newPos = relativeToAbsolute(relativeCorners[i][0], relativeCorners[i][1], currentWidth, currentHeight);
                corners[i].set(newPos.x, newPos.y);
            }

            // Update the last known dimensions
            lastWidth = currentWidth;
            lastHeight = currentHeight;

            // Log the scaled corners
            Log.d(TAG, "Corners after scaling for orientation change: " + "(" + corners[0].x + "," + corners[0].y + "), " + "(" + corners[1].x + "," + corners[1].y + "), " + "(" + corners[2].x + "," + corners[2].y + "), " + "(" + corners[3].x + "," + corners[3].y + ")");
        } else {
            Log.d(TAG, "View already initialized with correct dimensions, ensuring visibility");
        }

        // Force immediate invalidation
        invalidate();
        postInvalidate();

        // Schedule multiple checks to verify the view state after delays
        // This helps catch issues that might occur during the layout process
        for (int delay : new int[]{100, 300, 500, 1000}) {
            final int checkDelay = delay;
            postDelayed(() -> {
                boolean isStillAttached = isAttachedToWindow();
                boolean isVisible = getVisibility() == View.VISIBLE;
                boolean hasValidDimensions = getWidth() > 0 && getHeight() > 0;
                int currentOrientation = getResources().getConfiguration().orientation;

                Log.d(TAG, "View state verification after " + checkDelay + "ms: " + "attached=" + isStillAttached + ", " + "visible=" + isVisible + ", " + "dimensions=" + getWidth() + "x" + getHeight() + ", " + "orientation=" + (currentOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT ? "portrait" : "landscape") + ", " + "initialized=" + initialized);

                if (!isVisible || !hasValidDimensions) {
                    Log.w(TAG, "View is still not in correct state after " + checkDelay + "ms, applying emergency fixes");

                    // Emergency fixes
                    setVisibility(View.VISIBLE);
                    requestLayout();

                    if (!initialized && hasValidDimensions) {
                        initializeCornersAsync();
                    } else if (initialized && hasValidDimensions && (getWidth() != lastWidth || getHeight() != lastHeight)) {
                        // Dimensions have changed again, rescale corners
                        Log.d(TAG, "Dimensions changed again in verification check, rescaling corners");
                        for (int i = 0; i < 4; i++) {
                            PointF newPos = relativeToAbsolute(relativeCorners[i][0], relativeCorners[i][1], getWidth(), getHeight());
                            corners[i].set(newPos.x, newPos.y);
                        }
                        lastWidth = getWidth();
                        lastHeight = getHeight();
                    }

                    invalidate();
                    postInvalidate();
                }
            }, delay);
        }
    }
}