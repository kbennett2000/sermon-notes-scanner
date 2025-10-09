package de.schliweb.makeacopy.ui.crop;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Magnifier;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.utils.OpenCVUtils;
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
    private boolean magnifierEnabled = true;
    private float magnifierZoom = 2.5f; // 2.0..4.0
    private int magnifierSizePx = 0;
    private boolean isDraggingWithMagnifier = false;

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

        // Update absolute coordinates
        corners[index].set(x, y);

        // Update relative coordinates if dimensions are valid
        if (width > 0 && height > 0) {
            relativeCorners[index] = absoluteToRelative(x, y, width, height);
        }
    }

    // ========================= ASYNC INITIALIZATION =========================

    /**
     * Kicks off async corner initialization (idempotent/debounced).
     */
    private void initializeCornersAsync() {
        final int width = getWidth();
        final int height = getHeight();

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
     * Modifies the given corners to ensure they form a non-rectangular trapezoid
     * Creates a more natural-looking perspective effect by applying asymmetrical adjustments
     *
     * @param corners Array of 4 points to modify
     */
    private void makeNonRectangular(Point[] corners) {
        if (corners == null || corners.length != 4) {
            Log.e(TAG, "Invalid corners array in makeNonRectangular");
            return;
        }

        // Calculate the rectangle dimensions
        double rectWidth = Math.max(Math.abs(corners[1].x - corners[0].x), Math.abs(corners[2].x - corners[3].x));
        double rectHeight = Math.max(Math.abs(corners[3].y - corners[0].y), Math.abs(corners[2].y - corners[1].y));

        // Calculate aspect ratio to inform our adjustments
        double aspectRatio = rectWidth / Math.max(rectHeight, 1);
        Log.d(TAG, "Document aspect ratio: " + aspectRatio);

        // Log the original corners
        Log.d(TAG, "Original corners before making non-rectangular:");
        Log.d(TAG, "  Top-left: (" + corners[0].x + ", " + corners[0].y + ")");
        Log.d(TAG, "  Top-right: (" + corners[1].x + ", " + corners[1].y + ")");
        Log.d(TAG, "  Bottom-right: (" + corners[2].x + ", " + corners[2].y + ")");
        Log.d(TAG, "  Bottom-left: (" + corners[3].x + ", " + corners[3].y + ")");

        // Create a more natural perspective effect with asymmetrical adjustments

        // Base inset values (as percentage of dimensions)
        double baseHorizontalInset = rectWidth * 0.12; // Increased from 10% to 12%
        double baseVerticalInset = rectHeight * 0.08;

        // Use random values for natural appearance
        boolean leftSideCloser;
        double randomFactor1, randomFactor2, randomFactor3, randomFactor4, randomFactor5, randomFactor6;

        java.util.Random random = new java.util.Random();
        leftSideCloser = random.nextBoolean();
        randomFactor1 = random.nextDouble() * 0.3;
        randomFactor2 = random.nextDouble();
        randomFactor3 = random.nextDouble();
        randomFactor4 = random.nextDouble();
        randomFactor5 = random.nextDouble();
        randomFactor6 = random.nextDouble();

        // Create perspective effect - simulate viewing from slight angle
        if (leftSideCloser) {
            // Left side appears closer (right side appears farther)
            // Top-left: move slightly right and down
            corners[0].x += baseHorizontalInset * 0.7 * (1 + randomFactor1);
            corners[0].y += baseVerticalInset * 0.5 * randomFactor2;

            // Top-right: move more left and down
            corners[1].x -= baseHorizontalInset * 1.2 * (1 + randomFactor3);
            corners[1].y += baseVerticalInset * 0.8 * randomFactor4;

            // Bottom-right: move slightly left
            corners[2].x -= baseHorizontalInset * 0.3 * randomFactor5;

            // Bottom-left: move slightly right
            corners[3].x += baseHorizontalInset * 0.2 * randomFactor6;
        } else {
            // Right side appears closer (left side appears farther)
            // Top-left: move more right and down
            corners[0].x += baseHorizontalInset * 1.2 * (1 + randomFactor1);
            corners[0].y += baseVerticalInset * 0.8 * randomFactor2;

            // Top-right: move slightly left and down
            corners[1].x -= baseHorizontalInset * 0.7 * (1 + randomFactor3);
            corners[1].y += baseVerticalInset * 0.5 * randomFactor4;

            // Bottom-right: move slightly right
            corners[2].x -= baseHorizontalInset * 0.2 * randomFactor5;

            // Bottom-left: move slightly left
            corners[3].x += baseHorizontalInset * 0.3 * randomFactor6;
        }

        // For very wide documents (like receipts), exaggerate the vertical perspective
        if (aspectRatio > 1.5) {
            // Add more vertical perspective for wide documents
            double verticalAdjustment = baseVerticalInset * 0.5 * (aspectRatio - 1);

            // Make top edge appear shorter than bottom edge
            if (leftSideCloser) {
                corners[1].y += verticalAdjustment;
            } else {
                corners[0].y += verticalAdjustment;
            }
        }

        // Ensure corners stay within view boundaries
        int width = getWidth();
        int height = getHeight();
        for (int i = 0; i < 4; i++) {
            corners[i].x = Math.max(0, Math.min(corners[i].x, width));
            corners[i].y = Math.max(0, Math.min(corners[i].y, height));
        }

        // Log the modified corners
        Log.d(TAG, "Modified corners to create natural trapezoid shape:");
        Log.d(TAG, "  Top-left: (" + corners[0].x + ", " + corners[0].y + ")");
        Log.d(TAG, "  Top-right: (" + corners[1].x + ", " + corners[1].y + ")");
        Log.d(TAG, "  Bottom-right: (" + corners[2].x + ", " + corners[2].y + ")");
        Log.d(TAG, "  Bottom-left: (" + corners[3].x + ", " + corners[3].y + ")");
    }

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

        Log.d(TAG, "Trapezoid drawn with dimensions: " + getWidth() + "x" + getHeight());
    }

    /**
     * Draws user guidance hints based on the current state of the trapezoid
     *
     * @param canvas Canvas to draw on
     */
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
                hintY = height - 100; // Position near bottom
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

            // Position the hint at the bottom of the screen
            hintY = height - 100;

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
                // Check if a corner was touched
                activeCornerIndex = findCornerIndex(x, y);
                if (activeCornerIndex != -1) {
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
                    // Use updateCorner to maintain both absolute and relative coordinates
                    updateCorner(activeCornerIndex, x, y);

                    // Update magnifier position if active
                    if (isDraggingWithMagnifier && magnifier != null) {
                        PointF src = toSourceCoords(x, y);
                        try {
                            magnifier.show(src.x, src.y);
                        } catch (Throwable t) {
                            Log.w(TAG, "magnifier.show(move) failed: " + t.getMessage());
                        }
                    }

                    // Log the updated corner position
                    Log.d(TAG, "Corner " + activeCornerIndex + " moved to: (" + x + "," + y + "), " + "relative: (" + relativeCorners[activeCornerIndex][0] + "," + relativeCorners[activeCornerIndex][1] + ")");

                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
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
                isDraggingWithMagnifier = false;
                activeCornerIndex = -1;
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

        // Initialize OpenCV if needed
        if (bitmap != null && !OpenCVUtils.isInitialized()) {
            Log.d(TAG, "Initializing OpenCV for edge detection");
            OpenCVUtils.init(getContext());
        }

        // (Re)trigger async init when we have dimensions
        if (getWidth() > 0 && getHeight() > 0) {
            removeCallbacks(initCornersRunnable);
            post(initCornersRunnable);
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

    public void setMagnifierEnabled(boolean enabled) {
        this.magnifierEnabled = enabled;
        if (!enabled && magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Throwable ignore) {
            }
            magnifier = null;
        }
    }

    public void setMagnifierZoom(float zoom) {
        // clamp 2.0 .. 4.0
        float clamped = Math.max(2.0f, Math.min(4.0f, zoom));
        this.magnifierZoom = clamped;
        // Rebuild lazily
        if (magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Throwable ignore) {
            }
            magnifier = null;
        }
    }

    public void setMagnifierSizePx(int sizePx) {
        this.magnifierSizePx = Math.max(80, sizePx);
        if (magnifier != null) {
            try {
                magnifier.dismiss();
            } catch (Throwable ignore) {
            }
            magnifier = null;
        }
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
     * Verifies that the trapezoid is properly displayed and makes any necessary adjustments
     * This is a final safeguard to ensure the trapezoid is visible and correctly positioned
     */
    public void verifyTrapezoidDisplay() {
        int width = getWidth();
        int height = getHeight();
        int orientation = getResources().getConfiguration().orientation;
        String orientationName = (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) ? "portrait" : "landscape";

        Log.d(TAG, "verifyTrapezoidDisplay called: dimensions=" + width + "x" + height + ", orientation=" + orientationName + ", initialized=" + initialized);

        // Check if the view has valid dimensions
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Cannot verify trapezoid, view has invalid dimensions");
            return;
        }

        // Check if the view is initialized
        if (!initialized) {
            Log.d(TAG, "View not initialized during verification, initializing now");
            initializeCornersAsync();
            return;
        }

        // Check if the corners are within the view bounds
        boolean needsAdjustment = false;
        for (int i = 0; i < 4; i++) {
            if (corners[i].x < 0 || corners[i].x > width || corners[i].y < 0 || corners[i].y > height) {
                Log.w(TAG, "Corner " + i + " is outside view bounds: (" + corners[i].x + "," + corners[i].y + ")");
                needsAdjustment = true;
                break;
            }
        }

        // If any corner is outside the bounds, adjust all corners
        if (needsAdjustment) {
            Log.d(TAG, "Adjusting corners to fit within view bounds");

            // Clamp corners to view bounds
            for (int i = 0; i < 4; i++) {
                float oldX = corners[i].x;
                float oldY = corners[i].y;

                corners[i].x = Math.max(0, Math.min(corners[i].x, width));
                corners[i].y = Math.max(0, Math.min(corners[i].y, height));

                // Update relative coordinates
                relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, width, height);

                Log.d(TAG, "Adjusted corner " + i + ": (" + oldX + "," + oldY + ") -> (" + corners[i].x + "," + corners[i].y + ")");
            }

            // Update last dimensions
            lastWidth = width;
            lastHeight = height;

            // Force a redraw
            invalidate();
            postInvalidate();
        } else {
            Log.d(TAG, "All corners are within view bounds, no adjustment needed");
        }

        // Check if the dimensions match the last known dimensions
        if (width != lastWidth || height != lastHeight) {
            Log.d(TAG, "Dimensions changed since last update: " + lastWidth + "x" + lastHeight + " -> " + width + "x" + height + ", updating");

            // Scale corners based on relative positions
            for (int i = 0; i < 4; i++) {
                PointF newPos = relativeToAbsolute(relativeCorners[i][0], relativeCorners[i][1], width, height);
                corners[i].set(newPos.x, newPos.y);
            }

            // Update last dimensions
            lastWidth = width;
            lastHeight = height;

            // Force a redraw
            invalidate();
            postInvalidate();
        }
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