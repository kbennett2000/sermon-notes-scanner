package de.schliweb.makeacopy.ui.crop;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCropBinding;
import de.schliweb.makeacopy.ui.camera.CameraViewModel;
import de.schliweb.makeacopy.utils.FeatureFlags;
import de.schliweb.makeacopy.utils.OpenCVUtils;

/**
 * The CropFragment class is a user interface component responsible for handling image cropping operations.
 * It manages the cropping process through interaction with the CropViewModel and CameraViewModel.
 * The fragment provides a cropping UI, handles user input for cropping or resetting the image,
 * and dynamically adjusts UI elements for system insets.
 */
public class CropFragment extends Fragment {
    private static final String TAG = "CropFragment";
    private static final long CROP_A11Y_COOLDOWN_MS = 10000L;
    private FragmentCropBinding binding;
    private CropViewModel cropViewModel;
    private CameraViewModel cameraViewModel;
    // Track last applied user rotation to compute deltas for rotating the trapezoid selection
    private int lastUserRotationDeg = 0;
    // Guard: when we update the VM bitmap due to a user rotation, skip handling the immediate
    // imageBitmap observer callback to avoid re-triggering edge detection in the overlay.
    private boolean skipNextBitmapObserver = false;
    // A11y: one-shot post-capture announcement
    private boolean cropA11yAnnounced = false;
    private long cropA11yLastAnnounceTs = 0L;

    /**
     * Inflates the layout for this fragment and initializes all necessary components including
     * ViewModels, UI bindings, and View listeners. It also observes various LiveData objects
     * to dynamically update the UI based on changes in app state.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container          If non-null, this is the parent view that the fragment's UI should be attached to.
     *                           The fragment should not add the view itself, but this can be used to generate
     *                           the LayoutParams of the view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous
     *                           saved state as given here.
     * @return The root View for the fragment's UI, or null if the fragment does not provide a UI.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        OpenCVUtils.init(requireContext());
        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);
        binding = FragmentCropBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // Wire the Magnifier source view: compute and pass image->overlay matrix once layout/bitmap ready
        // Try immediately; if sizes are 0 we'll retry after bitmap/layout
        tryUpdateMagnifierMapping();

        cropViewModel.getText().observe(getViewLifecycleOwner(), binding.textCrop::setText);

        ViewCompat.setOnApplyWindowInsetsListener(binding.cropButtonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.cropButtonContainer, 8);
            return insets;
        });

        // Initial UI-Mode
        showCropMode();
        // Ensure hint text in overlay avoids bottom UI
        binding.getRoot().post(this::updateTrapezoidHintInset);

        // Back button: return to Camera for a fresh scan
        binding.buttonBack.setOnClickListener(v -> {
            try {
                // Reset state for a fresh scan
                cropViewModel.setImageCropped(false);
                cropViewModel.setUserRotationDegrees(0);
                cropViewModel.setCaptureRotationDegrees(0);
                // Clear current image references so Camera shows live preview
                if (cameraViewModel != null) {
                    cameraViewModel.setImageUri(null);
                    cameraViewModel.setImagePath(null);
                }
            } catch (Throwable ignored) {
            }
            // Navigate back to Camera
            Navigation.findNavController(requireView()).navigate(R.id.navigation_camera);
        });

        // Crop-Button
        binding.buttonCrop.setOnClickListener(v -> performCrop());

        // Rotation buttons (now available pre-crop)
        binding.buttonRotateLeft.setOnClickListener(v -> cropViewModel.rotateLeft());
        binding.buttonRotateRight.setOnClickListener(v -> cropViewModel.rotateRight());

        // Bitmap-Change
        cropViewModel.getImageBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (skipNextBitmapObserver) {
                // Skip handling caused by our own rotation writeback to prevent retriggering edge detection
                skipNextBitmapObserver = false;
                return;
            }
            if (bitmap != null) {
                if (Boolean.TRUE.equals(cropViewModel.isImageCropped().getValue())) {
                    android.util.Log.d(TAG, "[CROP_LOG] imageBitmap observer: isCropped=true, navigating. bmp=" + bitmap.getWidth() + "x" + bitmap.getHeight());
                    navigateAfterCrop();
                } else {
                    showCropMode();
                    Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(bitmap);
                    binding.imageToCrop.setImageBitmap(safe);
                    binding.trapezoidSelection.setImageBitmap(safe);
                    lastUserRotationDeg = 0; // reset rotation baseline for selection sync
                    // Disable rotation while edge detection likely runs, then re-enable shortly
                    setRotationButtonsEnabled(false);
                    binding.trapezoidSelection.postDelayed(() -> setRotationButtonsEnabled(true), 600);
                    // Update hint inset to avoid overlapping the rotation bar
                    binding.getRoot().post(this::updateTrapezoidHintInset);
                    // With a new bitmap, recompute and wire the magnifier mapping
                    tryUpdateMagnifierMapping();

                    // Post-capture: announce detection summary once in Accessibility Mode
                    maybeAnnounceCropDetectionOnce(safe);

                    // Dev-Overlay: zeige modelRect im Crop-Screen, wenn Logging-Flag aktiv
                    if (FeatureFlags.isFramingLoggingEnabled()) {
                        binding.cropDevOverlay.setVisibility(View.VISIBLE);
                        // Nach Layout-Pass mappen, falls Größen noch 0 sind
                        binding.cropDevOverlay.post(() -> updateDevOverlayForBitmap(safe));
                    } else {
                        try {
                            binding.cropDevOverlay.setModelRect(null);
                            binding.cropDevOverlay.setDebugText(null);
                            binding.cropDevOverlay.setVisibility(View.GONE);
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
        });

        cropViewModel.isImageCropped().observe(getViewLifecycleOwner(), isCropped -> {
            if (Boolean.TRUE.equals(isCropped) && cropViewModel.getImageBitmap().getValue() != null) {
                navigateAfterCrop();
            }
        });
        // React to rotation changes while in crop mode: rotate the original image and update previews
        cropViewModel.getUserRotationDegrees().observe(getViewLifecycleOwner(), degObj -> {
            if (Boolean.TRUE.equals(cropViewModel.isImageCropped().getValue())) return;
            Bitmap original = cropViewModel.getOriginalImageBitmap().getValue();
            if (original == null) original = cropViewModel.getImageBitmap().getValue();
            if (original == null) return;
            int deg = degObj == null ? 0 : ((degObj % 360) + 360) % 360;
            Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(original);
            try {
                if (deg != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(deg);
                    Bitmap rotated = android.graphics.Bitmap.createBitmap(safe, 0, 0, safe.getWidth(), safe.getHeight(), m, true);
                    if (rotated != null) safe = rotated;
                }
            } catch (Throwable ignore) {
            }
            // Update both the view and the VM bitmap; rotate trapezoid selection with the image
            binding.imageToCrop.setImageBitmap(safe);
            int delta = (deg - lastUserRotationDeg);
            // Normalize delta to [-270, 270] equivalent CW degrees
            delta = ((delta % 360) + 360) % 360;
            try {
                int correctedDelta = (360 - (delta % 360) + 360) % 360; // invert to match bitmap rotation direction
                binding.trapezoidSelection.setImageBitmapWithRotation(safe, correctedDelta);
            } catch (Throwable t) {
                // Fallback: set normally if new API fails
                binding.trapezoidSelection.setImageBitmap(safe);
            }
            lastUserRotationDeg = deg;
            // Prevent the subsequent imageBitmap observer from re-initializing edges due to our own writeback
            skipNextBitmapObserver = true;
            cropViewModel.setImageBitmap(safe);
            tryUpdateMagnifierMapping();

            // Dev-Overlay im Crop: nach Rotation neu mappen, wenn Flag aktiv
            if (de.schliweb.makeacopy.utils.FeatureFlags.isFramingLoggingEnabled()) {
                final Bitmap safeFinal = safe;
                binding.cropDevOverlay.post(() -> updateDevOverlayForBitmap(safeFinal));
            }
        });

        cameraViewModel.getImageUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri == null) return;
            // Do not reload the original image if we already have a cropped image.
            Boolean alreadyCropped = cropViewModel.isImageCropped().getValue();
            if (Boolean.TRUE.equals(alreadyCropped)) {
                // Keep the current cropped bitmap in the CropViewModel.
                return;
            }
            loadImageFromUri(uri);
        });

        // Window Insets
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustTextViewTopMarginForStatusBar(binding.textCrop, 8);
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.cropButtonContainer, 80);
            return insets;
        });

        return root;
    }

    private boolean isAccessibilityModeEnabled() {
        try {
            android.content.Context ctx = requireContext();
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
            return prefs.getBoolean(de.schliweb.makeacopy.ui.camera.CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void maybeAnnounceCropDetectionOnce(@NonNull Bitmap bmp) {
        if (!isAccessibilityModeEnabled()) return;
        long now = System.currentTimeMillis();
        if (cropA11yAnnounced && (now - cropA11yLastAnnounceTs) < CROP_A11Y_COOLDOWN_MS) return;
        cropA11yAnnounced = true;
        cropA11yLastAnnounceTs = now;
        new Thread(() -> {
            try {
                // Ensure OpenCV is ready
                de.schliweb.makeacopy.utils.OpenCVUtils.init(requireContext().getApplicationContext());
            } catch (Throwable ignore) {
            }
            de.schliweb.makeacopy.utils.OpenCVUtils.DetectionResult det = null;
            try {
                det = de.schliweb.makeacopy.utils.OpenCVUtils.detectDocumentCornersResult(requireContext(), bmp);
            } catch (Throwable ignore) {
            }
            boolean hasValid = (det != null && det.corners() != null && det.corners().length == 4);

            // Use the same score as in the CameraFragment for A11y outputs
            double scoreForA11y = 0.0;
            try {
                scoreForA11y = (det != null) ? det.score() : 0.0; // 0..1
            } catch (Throwable ignore) {
            }
            final int percent = Math.max(0, Math.min(100, (int) Math.round(scoreForA11y * 100.0)));
            if (!isAdded() || binding == null) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (hasValid) {
                    announceText(getString(R.string.a11y_crop_summary_detected, percent));
                } else {
                    // Be polite: only a short non-intrusive note
                    announceText(getString(R.string.a11y_crop_summary_no_doc));
                }
            });
        }, "CropA11yAnnounce").start();
    }

    private void announceText(@NonNull CharSequence text) {
        if (binding == null) return;
        View root = binding.getRoot();
        root.setContentDescription(text);
        de.schliweb.makeacopy.utils.A11yUtils.announce(root, text);
    }

    private void tryUpdateMagnifierMapping() {
        if (binding == null) return;
        final Bitmap bmp = cropViewModel != null ? cropViewModel.getImageBitmap().getValue() : null;
        final ImageView imageView = binding.imageToCrop;
        final View overlay = binding.trapezoidSelection;
        if (bmp == null) return;
        overlay.post(() -> ensureMagnifierMapping(bmp, imageView, overlay, 0));
    }

    private void ensureMagnifierMapping(Bitmap bitmap, ImageView imageView, View overlay, int attempt) {
        if (!isAdded()) return;
        int ow = overlay.getWidth();
        int oh = overlay.getHeight();
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        if (ow <= 0 || oh <= 0 || vw <= 0 || vh <= 0) {
            if (attempt < 6) {
                overlay.postDelayed(() -> ensureMagnifierMapping(bitmap, imageView, overlay, attempt + 1), 50);
            }
            return;
        }
        // Use the ImageView as magnifier source view and rely on robust screen-space mapping
        binding.trapezoidSelection.setMagnifierSourceView(imageView, null);
    }

    /**
     * Loads an image from the provided URI, sets the image URI and bitmaps in the crop view model,
     * and handles any errors if the image cannot be loaded.
     *
     * @param uri The URI of the image to be loaded.
     */
    private void loadImageFromUri(Uri uri) {
        String path = cameraViewModel != null && cameraViewModel.getImagePath() != null
                ? cameraViewModel.getImagePath().getValue() : null;
        Bitmap bitmap = de.schliweb.makeacopy.utils.ImageLoader.decode(requireContext(), path, uri);
        if (bitmap != null) {
            cropViewModel.setImageUri(uri);
            cropViewModel.setImageBitmap(bitmap);
            cropViewModel.setOriginalImageBitmap(bitmap);
        } else {
            // Error Handling: show friendly message
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), getString(R.string.error_displaying_image, "decode failed"), android.widget.Toast.LENGTH_SHORT);
        }
    }

    /**
     * Performs the cropping operation on the current image bitmap.
     * <p>
     * This method checks if a valid image bitmap exists in the cropViewModel. If no bitmap
     * is present, the method exits early. It ensures OpenCV is initialized, transforming the
     * corners of a trapezoid selection from view coordinates to image coordinates if available.
     * Using OpenCV utilities, the method applies a perspective correction based on the transformed
     * trapezoid corners. If the perspective correction operation produces a valid cropped bitmap,
     * it updates the cropViewModel with the new cropped bitmap and marks the image as cropped.
     * <p>
     * Key operations:
     * - Verifies a bitmap is available in the cropViewModel.
     * - Initializes OpenCV if it has not yet been initialized.
     * - Retrieves and transforms trapezoid selection corners, if present.
     * - Performs perspective correction using OpenCV to generate a cropped bitmap.
     * - Updates the cropViewModel with the new cropped image and sets the cropped flag to true.
     */
    private void performCrop() {
        final String LP = "[CROP_LOG] ";
        // The image currently shown in the ImageView (display-safe, possibly scaled)
        Bitmap displayedBitmap = cropViewModel.getImageBitmap().getValue();
        if (displayedBitmap == null) {
            android.util.Log.w(TAG, LP + "performCrop: No bitmap available");
            return;
        }
        if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(requireContext());

        // Use the full-resolution original, rotated by the current user rotation, as crop source.
        // Rationale: The trapezoid selection and preview are shown with the user rotation applied.
        // To ensure that the perspective correction matches what the user sees (and to avoid
        // quality loss by re-rotating later), we bake the user rotation into the source used
        // for cropping. originalImageBitmap itself remains unmodified and is only used as input here.
        Bitmap fullResSource = null;
        try {
            Bitmap orig = cropViewModel.getOriginalImageBitmap().getValue();
            Integer ur = cropViewModel.getUserRotationDegrees().getValue();
            int userDeg = ur == null ? 0 : ((ur % 360) + 360) % 360;
            if (orig != null && !orig.isRecycled()) {
                if (userDeg != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(userDeg);
                    fullResSource = android.graphics.Bitmap.createBitmap(orig, 0, 0, orig.getWidth(), orig.getHeight(), m, true);
                    android.util.Log.d(TAG, LP + "performCrop: applying user rotation " + userDeg + "° to full-res source");
                } else {
                    fullResSource = orig;
                    android.util.Log.d(TAG, LP + "performCrop: user rotation 0°, using original full-res source");
                }
            }
        } catch (Throwable ignore) {
        }
        if (fullResSource == null) {
            // Fallback: crop the currently displayed bitmap (might be scaled)
            fullResSource = displayedBitmap;
        }

        android.util.Log.d(TAG, LP + "performCrop: displayed=" + displayedBitmap.getWidth() + "x" + displayedBitmap.getHeight()
                + ", source(full-res?)=" + fullResSource.getWidth() + "x" + fullResSource.getHeight());

        // 1) Get corners in displayed-bitmap image coordinates
        org.opencv.core.Point[] imgCornersDisplay = null;
        try {
            org.opencv.core.Point[] viewCorners = binding.trapezoidSelection.getCorners();
            imgCornersDisplay = de.schliweb.makeacopy.utils.CoordinateTransformUtils.transformViewToImageCoordinates(
                    viewCorners, displayedBitmap, binding.imageToCrop);
            android.util.Log.d(TAG, LP + "performCrop: transformed corners (display)=" + (imgCornersDisplay != null ? java.util.Arrays.toString(imgCornersDisplay) : "null"));
        } catch (Throwable t) {
            android.util.Log.w(TAG, LP + "performCrop: corner transform failed: " + t.getMessage());
        }
        // Guard: if we have no corners, do not proceed to crop; avoid forwarding the original image
        if (imgCornersDisplay == null) {
            android.util.Log.w(TAG, LP + "performCrop: No corners available; aborting crop and staying in crop UI");
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), getString(R.string.adjust_the_trapezoid_corners_to_select_the_area_to_crop_then_tap_the_crop_button), android.widget.Toast.LENGTH_SHORT);
            return;
        }

        // 2) If we are using a higher-resolution source than displayed, scale corners accordingly
        org.opencv.core.Point[] cornersForSource;
        if (fullResSource != displayedBitmap) {
            float sx = fullResSource.getWidth() / (float) displayedBitmap.getWidth();
            float sy = fullResSource.getHeight() / (float) displayedBitmap.getHeight();
            cornersForSource = new org.opencv.core.Point[4];
            for (int i = 0; i < 4; i++) {
                cornersForSource[i] = new org.opencv.core.Point(imgCornersDisplay[i].x * sx, imgCornersDisplay[i].y * sy);
            }
            android.util.Log.d(TAG, LP + String.format(java.util.Locale.US,
                    "performCrop: scaling corners to full-res (sx=%.4f, sy=%.4f) → %s", sx, sy, java.util.Arrays.toString(cornersForSource)));
        } else {
            cornersForSource = imgCornersDisplay;
        }

        long t0 = android.os.SystemClock.uptimeMillis();
        Bitmap croppedBitmap = OpenCVUtils.applyPerspectiveCorrection(fullResSource, cornersForSource);
        long dt = android.os.SystemClock.uptimeMillis() - t0;
        if (croppedBitmap != null) {
            android.util.Log.d(TAG, LP + "performCrop: cropped size=" + croppedBitmap.getWidth() + "x" + croppedBitmap.getHeight() + ", took=" + dt + "ms");
            // Hide/stop overlay to avoid further edge detection while we navigate away
            try {
                binding.trapezoidSelection.setVisibility(View.GONE);
                binding.trapezoidSelection.setImageBitmap(null);
            } catch (Throwable ignore) {
            }
            // Prevent the imageBitmap observer from re-initializing edge detection for this write-back
            skipNextBitmapObserver = true;
            // Write cropped bitmap first, then mark as cropped so isImageCropped observer can navigate using the new bitmap
            cropViewModel.setImageBitmap(croppedBitmap);
            cropViewModel.setImageCropped(true);
        } else {
            android.util.Log.w(TAG, LP + "performCrop: OpenCV returned null cropped bitmap (took=" + dt + "ms)");
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), getString(R.string.error_displaying_image, "crop failed"), android.widget.Toast.LENGTH_SHORT);
        }
    }

    /**
     * Configures the UI for cropping mode by adjusting the visibility of relevant views
     * and updating instructional text for the user.
     * <p>
     * The method prepares the fragment for the initial cropping workflow by:
     * - Hiding the cropped image preview.
     * - Displaying the image to be cropped.
     * - Showing the trapezoid selection area, if available.
     * - Making the cropping button container visible.
     * - Hiding the general button container.
     * - Setting an instructional message prompting the user to adjust the trapezoid corners
     * and proceed with cropping.
     */
    private void showCropMode() {
        binding.croppedImage.setVisibility(View.GONE);
        binding.imageToCrop.setVisibility(View.VISIBLE);
        binding.trapezoidSelection.setVisibility(View.VISIBLE);
        binding.cropButtonContainer.setVisibility(View.VISIBLE);
        binding.rotationButtonBar.setVisibility(View.VISIBLE);

        // Ensure the trapezoid overlay always renders above the bottom button container while remaining non-clickable
        // (so taps still pass through to the buttons). This guards against OEM/CL quirks even if XML elevation is ignored.
        try {
            ViewCompat.setElevation(binding.trapezoidSelection, 100f);
            ViewCompat.setTranslationZ(binding.trapezoidSelection, 100f);
            // Ensure draw order is on top even if ConstraintLayout reorders children
            binding.trapezoidSelection.bringToFront();
        } catch (Throwable ignored) {
        }

        // Ensure in crop mode the cropped_image (when later shown) would anchor to button_container to avoid overlap
        // Ensure overlay hints avoid bottom controls
        binding.getRoot().post(this::updateTrapezoidHintInset);
        binding.textCrop.setText(R.string.adjust_the_trapezoid_corners_to_select_the_area_to_crop_then_tap_the_crop_button);
    }

    private void navigateAfterCrop() {
        if (!isAdded() || getView() == null) return;
        try {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
            boolean skipOcr = prefs.getBoolean("skip_ocr", false);
            int dest = skipOcr ? R.id.navigation_export : R.id.navigation_ocr;
            Navigation.findNavController(requireView()).navigate(dest);
        } catch (Throwable ignored) {
        }
    }

    private void setRotationButtonsEnabled(boolean enabled) {
        if (binding == null) return;
        binding.buttonRotateLeft.setEnabled(enabled);
        binding.buttonRotateLeft.setAlpha(enabled ? 1f : 0.5f);
        binding.buttonRotateRight.setEnabled(enabled);
        binding.buttonRotateRight.setAlpha(enabled ? 1f : 0.5f);
    }

    private void updateTrapezoidHintInset() {
        if (binding == null) return;
        int inset = 0;
        View bar = binding.rotationButtonBar;
        if (bar.getVisibility() == View.VISIBLE) {
            int h = bar.getHeight();
            int margins = 0;
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
                margins = mlp.bottomMargin + mlp.topMargin;
            }
            inset = h + margins;
        }
        // Add a little breathing room
        float density = binding.getRoot().getResources().getDisplayMetrics().density;
        inset += (int) (8 * density + 0.5f);
        try {
            binding.trapezoidSelection.setBottomUiInsetPx(inset);
        } catch (Throwable ignored) {
        }
    }


    @Override
    public void onResume() {
        super.onResume();
        if (Boolean.TRUE.equals(cropViewModel.isImageLoaded().getValue()) && Boolean.FALSE.equals(cropViewModel.isImageCropped().getValue())) {
            showCropMode();
            Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
            if (bitmap != null) {
                Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(bitmap);
                binding.trapezoidSelection.setImageBitmap(safe);
            }
            // Re-apply inset in case system UI or rotations changed sizes
            binding.getRoot().post(this::updateTrapezoidHintInset);
        }
    }

    @Override
    public void onDestroyView() {
        try {
            if (binding != null && binding.cropDevOverlay != null) {
                binding.cropDevOverlay.setModelRect(null);
                binding.cropDevOverlay.setDebugText(null);
            }
        } catch (Throwable ignore) {
        }
        super.onDestroyView();
        binding = null;
    }

    // --- Dev overlay (framing logging) in the CropFragment ---
    private void updateDevOverlayForBitmap(@androidx.annotation.NonNull Bitmap bmp) {
        if (binding == null) return;
        if (!de.schliweb.makeacopy.utils.FeatureFlags.isFramingLoggingEnabled()) return;
        try {
            RectF fb = OpenCVUtils.getFallbackRectF(bmp.getWidth(), bmp.getHeight());
            RectF vr = mapBitmapToOverlayRect(fb, bmp.getWidth(), bmp.getHeight());
            if (vr != null) {
                binding.cropDevOverlay.setModelRect(vr);
                // In the crop screen, marking the model frame is sufficient
                binding.cropDevOverlay.setDebugText(null);
            }
        } catch (Throwable ignore) {
        }
    }

    private RectF mapBitmapToOverlayRect(RectF src, int bmpW, int bmpH) {
        if (binding == null || src == null) return null;
        int vw = binding.cropDevOverlay.getWidth();
        int vh = binding.cropDevOverlay.getHeight();
        if (vw <= 0 || vh <= 0 || bmpW <= 0 || bmpH <= 0) return null;
        float sx = vw / (float) bmpW;
        float sy = vh / (float) bmpH;
        float scale = Math.min(sx, sy);
        float contentW = bmpW * scale;
        float contentH = bmpH * scale;
        float offX = (vw - contentW) * 0.5f;
        float offY = (vh - contentH) * 0.5f;
        return new RectF(
                offX + src.left * scale,
                offY + src.top * scale,
                offX + src.right * scale,
                offY + src.bottom * scale
        );
    }
}
