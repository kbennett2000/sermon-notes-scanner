package de.schliweb.makeacopy.ui.camera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.*;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import com.google.common.util.concurrent.ListenableFuture;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCameraBinding;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel;
import de.schliweb.makeacopy.utils.UIUtils;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The `CameraFragment` class is responsible for managing the camera operations and associated
 * UI interactions within a given fragment in an Android application. It includes functionality
 * for initializing the camera, handling light sensor inputs, managing image capture, and
 * providing a seamless user experience for scanning or capturing images.
 * <p>
 * This fragment utilizes the CameraX library along with experimental interoperability features
 * for advanced camera controls.
 * <p>
 * Key Responsibilities:
 * - Configure and bind various use cases of the camera (e.g., preview, image capture, analysis).
 * - Handle permissions for camera access and provide appropriate error messages if permissions
 * are not granted.
 * - Control the camera state and flashlight based on user interaction and environmental conditions.
 * - Provide feedback and updates to the UI to inform the user during the scanning or image capture process.
 * - Monitor sensor data for low-light conditions and suggest enabling the flashlight when necessary.
 * - Perform image analysis for tasks such as corner detection or scene luminance estimation.
 * - Manage resources effectively to prevent leaks or unnecessary memory usage.
 * <p>
 * Usage Considerations:
 * - This class requires the necessary camera permissions to be granted before initializing camera functionality.
 * - It manages concurrency and thread-safety for operations such as image capture and analysis.
 * - The UI components must be properly initialized and accessible through its `binding` property.
 * <p>
 * This fragment integrates various techniques such as light sensor monitoring, threading for
 * image analysis, and real-time updates to enhance the camera user experience in dynamic
 * application scenarios.
 */
public class CameraFragment extends Fragment implements SensorEventListener {

    private static final String TAG = "CameraFragment";

    // Light sensor constants
    private static final float LOW_LIGHT_THRESHOLD = 10.0f; // lux
    private static final long MIN_TIME_BETWEEN_PROMPTS = 60000; // ms

    private FragmentCameraBinding binding;
    private CameraViewModel cameraViewModel;
    private CropViewModel cropViewModel;

    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private Preview preview;

    private boolean isFlashlightOn = false;
    private boolean hasFlash = false;

    // Torch-EC-Adapt: store the base EC value as long as the torch is on
    private Integer baseEc = null;

    // Live corner preview (document trapezoid)
    private ImageAnalysis imageAnalysis;
    private ExecutorService analysisExecutor;
    private volatile boolean analysisEnabled = false;
    private long lastAnalysisTs = 0L;
    // Logging throttle for adaptive EC
    private long lastEcLogTs = 0L;

    // Light sensor
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private boolean hasLightSensor = false;
    private boolean lowLightPromptShown = false;
    private long lastPromptTime = 0;
    private boolean isLowLightDialogVisible = false;

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> pickImageLauncher;

    private OrientationEventListener orientationListener;

    private boolean reinitScheduled = false;
    private boolean streamObserverAttached = false;

    // Tiered binding (runtime-based escalation instead of vendor checks)
    private enum BindTier {PERF, COMPAT, COMPAT_LOWRES}

    private BindTier lastTier = null;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);
        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    Log.i(TAG, "Camera permission result: " + (isGranted ? "GRANTED" : "DENIED"));
                    if (isGranted) {
                        cameraViewModel.setCameraPermissionGranted(true);
                    } else {
                        UIUtils.showToast(requireContext(), R.string.msg_camera_permission_required, Toast.LENGTH_LONG);
                    }
                });

        // Register the image picker launcher (SAF)
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result == null || result.getData() == null) return;
                    if (result.getResultCode() != android.app.Activity.RESULT_OK) return;
                    Intent data = result.getData();
                    Uri uri = data.getData();
                    if (uri == null) return;
                    // Validate MIME type to be image/*
                    try {
                        String mime = requireContext().getContentResolver().getType(uri);
                        if (mime == null || !mime.startsWith("image/")) {
                            UIUtils.showToast(requireContext(), R.string.error_selected_file_is_not_an_image, Toast.LENGTH_SHORT);
                            return;
                        }
                    } catch (Exception ignored) {
                        // If type cannot be determined, proceed cautiously (SAF already filtered), no-op
                    }
                    try {
                        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                    } catch (Exception ignored) {
                    }
                    if (cameraViewModel != null) {
                        // Reset rotations for a new scan imported from storage
                        try {
                            if (cropViewModel != null) {
                                cropViewModel.setUserRotationDegrees(0);
                                cropViewModel.setCaptureRotationDegrees(0);
                                cropViewModel.setImageCropped(false);
                            }
                        } catch (Throwable ignored) {
                        }
                        cameraViewModel.setImagePath(null);
                        cameraViewModel.setImageUri(uri);

                        // Navigate to next step depending on preference
                        if (isAdded()) {
                            boolean skipOcr = false;
                            boolean skipCropping = false;
                            try {
                                android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                                skipOcr = prefs.getBoolean("skip_ocr", false);
                                skipCropping = prefs.getBoolean("skip_cropping", false);
                            } catch (Throwable ignoreSp) {
                            }
                            int dest = skipCropping ? (skipOcr ? R.id.navigation_export : R.id.navigation_ocr) : R.id.navigation_crop;
                            if (skipCropping && !skipOcr) {
                                try {
                                    OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
                                    ocrVm.resetForNewImage();
                                } catch (Throwable ignore) {
                                }
                            }
                            try {
                                Navigation.findNavController(requireView()).navigate(dest);
                            } catch (IllegalArgumentException | IllegalStateException ignored) {
                            }
                        }
                    }
                });

        binding = FragmentCameraBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // Verbose environment log to help diagnose device-specific issues
        logEnvironment();

        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 8);
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.scanButtonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.scanButtonContainer, 8);
            return insets;
        });

        // Init UI visibility
        showCameraMode();


        final TextView textView = binding.textCamera;
        cameraViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        // Scan
        binding.buttonScan.setOnClickListener(v -> {
            if (cameraViewModel != null && cameraViewModel.isCameraPermissionGranted().getValue() == Boolean.TRUE) {
                captureImage();
            } else {
                checkCameraPermission();
            }
        });

        // Set up flashlight button
        binding.buttonFlash.setOnClickListener(v -> toggleFlashlight());

        // Set up pick image button
        binding.buttonPickImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pickImageLauncher.launch(intent);
        });

        // Options
        binding.buttonCameraOptions.setOnClickListener(v -> {
            getParentFragmentManager().setFragmentResultListener(CameraOptionsDialogFragment.REQUEST_KEY, getViewLifecycleOwner(), (requestKey, bundle) -> {
                boolean skip = bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_SKIP_OCR, false);
                boolean analysisPref = bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_ANALYSIS_ENABLED, true);
                try {
                    android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                    prefs.edit()
                            .putBoolean("skip_ocr", skip)
                            .putBoolean("include_ocr", !skip)
                            .putBoolean("analysis_enabled", analysisPref)
                            .apply();
                } catch (Throwable ignore) {
                }
                // Apply analysis toggle immediately if we are in camera mode
                try {
                    if (binding != null && binding.viewFinder.getVisibility() == View.VISIBLE) {
                        setLiveAnalysisEnabled(analysisPref);
                    }
                } catch (Throwable ignore) {
                }
                getParentFragmentManager().clearFragmentResultListener(CameraOptionsDialogFragment.REQUEST_KEY);
            });
            CameraOptionsDialogFragment.show(getParentFragmentManager());
        });

        // Library entry from Camera screen (feature-gated)
        try {
            if (BuildConfig.FEATURE_SCAN_LIBRARY) {
                binding.buttonOpenLibraryCam.setVisibility(View.VISIBLE);
                binding.buttonOpenLibraryCam.setOnClickListener(v -> {
                    try {
                        Navigation.findNavController(requireView()).navigate(R.id.navigation_scans_library);
                    } catch (Throwable t) {
                        Log.w(TAG, "Navigation to scans library failed", t);
                    }
                });
            } else {
                binding.buttonOpenLibraryCam.setVisibility(View.GONE);
            }
        } catch (Throwable ignore) {
        }

        // Set up retake and confirm button listeners
        binding.buttonRetake.setOnClickListener(v -> {
            try {
                if (isAdded() && binding != null) {
                    v.setEnabled(false);
                    UIUtils.showToast(requireContext(), R.string.resetting_camera, Toast.LENGTH_SHORT);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        resetCamera();
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isAdded() && binding != null) {
                                binding.buttonRetake.setEnabled(true);
                            }
                        }, 1000);
                    }, 100);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling retake: " + e.getMessage());
                if (isAdded()) {
                    UIUtils.showToast(requireContext(), getString(R.string.error_resetting_camera, e.getMessage()), Toast.LENGTH_SHORT);
                    v.setEnabled(true);
                }
            }
        });

        // Confirm (continue)
        binding.buttonConfirm.setOnClickListener(v -> {
            if (!isAdded()) return;
            cropViewModel.setImageCropped(false);
            boolean skipOcr = false;
            boolean skipCropping = false;
            try {
                android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                skipOcr = prefs.getBoolean("skip_ocr", false);
                skipCropping = prefs.getBoolean("skip_cropping", false);
            } catch (Throwable ignoreSp) {
            }
            int dest = skipCropping ? (skipOcr ? R.id.navigation_export : R.id.navigation_ocr) : R.id.navigation_crop;
            if (skipCropping && !skipOcr) {
                try {
                    OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
                    ocrVm.resetForNewImage();
                } catch (Throwable ignore) {
                }
            }
            try {
                Navigation.findNavController(requireView()).navigate(dest);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
            }
        });

        // Insets (Status bar)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ViewGroup.MarginLayoutParams textParams = (ViewGroup.MarginLayoutParams) binding.textCamera.getLayoutParams();
            textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
            binding.textCamera.setLayoutParams(textParams);
            return insets;
        });

        // Camera permission
        cameraViewModel.isCameraPermissionGranted().observe(getViewLifecycleOwner(), granted -> {
            if (granted) {
                initializeCamera();
            }
        });
        checkCameraPermission();

        // Only capability detection here; registration happens in onResume (3)
        initLightSensor();

        // Handle back: in review mode -> reset, otherwise default
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding != null && binding.capturedImage.getVisibility() == View.VISIBLE) {
                    resetCamera();
                } else {
                    this.setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume: registering listeners (lightSensor=" + hasLightSensor + ")");
        if (hasLightSensor && sensorManager != null && lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (orientationListener != null) orientationListener.enable();
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.i(TAG, "onPause: unregistering listeners");
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (orientationListener != null) orientationListener.disable();
    }

    private void checkCameraPermission() {
        if (!isAdded()) return;
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (requestPermissionLauncher != null) requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        } else {
            if (cameraViewModel != null) cameraViewModel.setCameraPermissionGranted(true);
        }
    }

    private int getViewFinderRotation() {
        if (binding != null && binding.viewFinder.getDisplay() != null) {
            return binding.viewFinder.getDisplay().getRotation();
        }
        return Surface.ROTATION_0;
    }

    private void initializeCamera() {
        if (binding == null || !isAdded()) {
            Log.d(TAG, "initializeCamera: skip (not attached)");
            return;
        }
        Log.i(TAG, "initializeCamera: requesting ProcessCameraProvider...");
        binding.textCamera.setText(R.string.initializing_camera);

        ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(requireContext());
        fut.addListener(() -> {
            try {
                if (binding == null || !isAdded() || getView() == null) return;
                cameraProvider = fut.get();
                if (cameraProvider == null) {
                    UIUtils.showToast(requireContext(), R.string.error_camera_provider_null, Toast.LENGTH_SHORT);
                    binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
                    Log.w(TAG, "initializeCamera: cameraProvider is null");
                    return;
                }
                bindWithTier(BindTier.PERF);
                hasFlash = camera.getCameraInfo().hasFlashUnit();
                binding.buttonFlash.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
                isFlashlightOn = false;
                binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
                binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
                logCameraCapabilities();

                // Touch to focus
                binding.viewFinder.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                        MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                        MeteringPoint pt = mpf.createPoint(event.getX(), event.getY());
                        FocusMeteringAction action = new FocusMeteringAction.Builder(
                                pt,
                                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                        camera.getCameraControl().startFocusAndMetering(action);
                        v.performClick();
                        return true;
                    }
                    return false;
                });
                // Overlay tap → same focus
                binding.cornerOverlay.setOnTouchListener((ov, ev) -> {
                    if (ev.getAction() == MotionEvent.ACTION_UP && camera != null) {
                        try {
                            MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                            MeteringPoint pt = mpf.createPoint(ev.getX(), ev.getY());
                            FocusMeteringAction a = new FocusMeteringAction.Builder(
                                    pt,
                                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
                            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                            camera.getCameraControl().startFocusAndMetering(a);
                            ov.performClick();
                            return true;
                        } catch (Throwable ignored) {
                        }
                    }
                    return false;
                });
            } catch (Exception e) {
                handleCameraInitializationError(e);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    private void handleCameraInitializationError(Exception e) {
        if (!isAdded() || binding == null) return;
        Log.e(TAG, "Camera init error: " + e.getMessage(), e);
        UIUtils.showToast(requireContext(), getString(R.string.error_initializing_camera, e.getMessage()), Toast.LENGTH_LONG);
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
        if (!reinitScheduled) {
            reinitScheduled = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                reinitScheduled = false;
                if (isAdded() && cameraViewModel != null &&
                        Boolean.TRUE.equals(cameraViewModel.isCameraPermissionGranted().getValue())) {
                    initializeCamera();
                }
            }, 3000);
        }
    }

    // --------- Tiered binding without device checks ----------

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindWithTier(BindTier tier) {
        if (binding == null || cameraProvider == null || !isAdded()) return;
        lastTier = tier;

        // ImplMode
        boolean isSony = "sony".equalsIgnoreCase(android.os.Build.MANUFACTURER);
        PreviewView.ImplementationMode implMode = isSony ? PreviewView.ImplementationMode.COMPATIBLE
                : (tier == BindTier.PERF ? PreviewView.ImplementationMode.PERFORMANCE
                : PreviewView.ImplementationMode.COMPATIBLE);
        binding.viewFinder.setImplementationMode(implMode);
        binding.viewFinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        Log.i(TAG, "bindWithTier: implMode=" + implMode + ", scaleType=FIT_CENTER, isSony=" + isSony);

        int rotation = getViewFinderRotation();
        Log.i(TAG, "bindWithTier: tier=" + tier + ", rotation=" + toDegrees(rotation));

        // Preview selector
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder rsPrev =
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(new androidx.camera.core.resolutionselector.AspectRatioStrategy(
                                AspectRatio.RATIO_4_3,
                                androidx.camera.core.resolutionselector.AspectRatioStrategy.FALLBACK_RULE_AUTO
                        ));

        if (tier == BindTier.COMPAT_LOWRES) {
            android.util.Size preferredPreview = new android.util.Size(1280, 960);
            rsPrev.setResolutionStrategy(
                    new androidx.camera.core.resolutionselector.ResolutionStrategy(
                            preferredPreview,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
            );
        }

        // Capture selector (prefer high resolution)
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder rsCap =
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(new androidx.camera.core.resolutionselector.AspectRatioStrategy(
                                AspectRatio.RATIO_4_3,
                                androidx.camera.core.resolutionselector.AspectRatioStrategy.FALLBACK_RULE_AUTO
                        ));
        android.util.Size preferredHigh = new android.util.Size(4032, 3024);
        rsCap.setResolutionStrategy(
                new androidx.camera.core.resolutionselector.ResolutionStrategy(
                        preferredHigh,
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
        );

        Preview.Builder previewBuilder = new Preview.Builder()
                .setResolutionSelector(rsPrev.build())
                .setTargetRotation(rotation);

        ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // weniger invasive Pipeline
                .setResolutionSelector(rsCap.build())
                .setTargetRotation(rotation)
                .setJpegQuality(98);

        // Interop: konservative FPS, High-Quality Settings ok
        Camera2Interop.Extender<Preview> pExt = new Camera2Interop.Extender<>(previewBuilder);
        Camera2Interop.Extender<ImageCapture> cExt = new Camera2Interop.Extender<>(captureBuilder);

        // Some Sony devices reject sessions when an explicit AE_TARGET_FPS_RANGE is set.
        // To avoid "Unsupported set of inputs/outputs provided" (endConfigure) we skip forcing FPS on Sony.
        if (!isSony) {
            android.util.Range<Integer> fps = new android.util.Range<>(15, 30);
            pExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        } else {
            Log.i(TAG, "bindWithTier: skipping AE_TARGET_FPS_RANGE on Sony device");
        }

        // AF continuous picture is generally safe and expected; keep for all OEMs
        pExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        if (!isSony) {
            // High-quality post-processing and ZSL toggles can cause session config failures on some Sony devices.
            // Apply only on non-Sony devices.
            cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                    android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.EDGE_MODE,
                    android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.SHADING_MODE,
                    android.hardware.camera2.CaptureRequest.SHADING_MODE_HIGH_QUALITY);
            cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_ENABLE_ZSL, false);
        } else {
            Log.i(TAG, "bindWithTier: skipping HQ NR/EDGE/CCA/SHADING and ZSL toggle on Sony device");
        }

        imageCapture = captureBuilder.build();
        preview = previewBuilder.build();

        // Analyzer
        setupOrUpdateImageAnalysis(rotation);

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();

            if (false && isSony) {
                // Sony: prefer fewer outputs first to avoid stream config errors.
                // 1) Preview only
                camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview);
                setPreviewSurfaceProviderWithLog(tier);
                logResolutions("Sony bind: Preview");

                // 2) Preview + Capture
                try {
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture);
                    setPreviewSurfaceProviderWithLog(tier);
                    logResolutions("Sony bind: Preview+Capture");

                    // 3) Preview + Capture + Analysis (add analysis last)
                    try {
                        cameraProvider.unbindAll();
                        camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture, imageAnalysis);
                        setPreviewSurfaceProviderWithLog(tier);
                        logResolutions("Sony bind: Preview+Capture+Analysis");
                    } catch (IllegalArgumentException e3) {
                        Log.w(TAG, "Bind failed for Preview+Capture+Analysis on " + tier + " → keep Preview+Capture", e3);
                        cameraProvider.unbindAll();
                        camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture);
                        setPreviewSurfaceProviderWithLog(tier);
                        logResolutions("Sony fallback: Preview+Capture");
                    }
                } catch (IllegalArgumentException e2) {
                    Log.w(TAG, "Bind failed for Preview+Capture on " + tier + " → fallback Preview only", e2);
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview);
                    setPreviewSurfaceProviderWithLog(tier);
                    logResolutions("Sony fallback: Preview");
                }
            } else {
                // Non-Sony: keep existing sequence Preview → Preview+Analysis → Preview+Analysis+Capture
                // 1) Preview only
                camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview);
                setPreviewSurfaceProviderWithLog(tier);
                logResolutions("Bind: Preview");

                // 2) Preview + Analysis
                try {
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageAnalysis);
                    setPreviewSurfaceProviderWithLog(tier);
                    logResolutions("Bind: Preview+Analysis");

                    // 3) Preview + Analysis + Capture
                    try {
                        cameraProvider.unbindAll();
                        camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageAnalysis, imageCapture);
                        setPreviewSurfaceProviderWithLog(tier);
                        logResolutions("Bind: Preview+Analysis+Capture");
                    } catch (IllegalArgumentException e3) {
                        Log.w(TAG, "Bind failed for Preview+Analysis+Capture on " + tier + " → fallback Preview+Capture", e3);
                        cameraProvider.unbindAll();
                        camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture);
                        setPreviewSurfaceProviderWithLog(tier);
                        logResolutions("Fallback: Preview+Capture");
                    }
                } catch (IllegalArgumentException e2) {
                    Log.w(TAG, "Bind failed for Preview+Analysis on " + tier + " → fallback Preview only", e2);
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview);
                    setPreviewSurfaceProviderWithLog(tier);
                    logResolutions("Fallback: Preview");
                }
            }

            // OrientationListener
            if (orientationListener == null) {
                orientationListener = new OrientationEventListener(requireContext()) {
                    @Override
                    public void onOrientationChanged(int orientation) {
                        if (!isAdded() || binding == null) return;
                        int rot = getViewFinderRotation();
                        try {
                            if (imageCapture != null) imageCapture.setTargetRotation(rot);
                            if (preview != null) preview.setTargetRotation(rot);
                            if (imageAnalysis != null) imageAnalysis.setTargetRotation(rot);
                        } catch (Exception e) {
                            Log.w(TAG, "Orientation update failed: " + e.getMessage());
                        }
                    }
                };
                orientationListener.enable();
            }

            attachWatchdogs();

            // Torch EC: if the torch is already on, stay in adaptive mode
            if (isFlashlightOn) {
                // nothing else: adaptive EC runs in the analyzer
            }

        } catch (IllegalArgumentException e) {
            Log.e(TAG, "bindToLifecycle failed: " + e.getMessage(), e);
            escalateBindTier();
        }
    }

    private void setPreviewSurfaceProviderWithLog(BindTier tier) {
        Preview.SurfaceProvider vfProvider = binding.viewFinder.getSurfaceProvider();
        preview.setSurfaceProvider(ContextCompat.getMainExecutor(requireContext()),
                request -> {
                    android.util.Size s = request.getResolution();
                    Log.i(TAG, "Preview SurfaceRequest: " + s.getWidth() + "x" + s.getHeight() + " tier=" + tier);
                    vfProvider.onSurfaceRequested(request);
                });
    }

    private void logResolutions(String label) {
        try {
            String prev = "see SurfaceRequest log";
            String cap = "n/a";
            String ana = "n/a";

            if (imageCapture != null) {
                ResolutionInfo ri = imageCapture.getResolutionInfo();
                if (ri != null) {
                    cap = ri.getResolution().getWidth() + "x" + ri.getResolution().getHeight() +
                            " rot=" + ri.getRotationDegrees() + "°";
                }
            }
            if (imageAnalysis != null) {
                ResolutionInfo ri2 = imageAnalysis.getResolutionInfo();
                if (ri2 != null) {
                    ana = ri2.getResolution().getWidth() + "x" + ri2.getResolution().getHeight() +
                            " rot=" + ri2.getRotationDegrees() + "°";
                }
            }
            Log.i(TAG, "UseCase resolutions [" + label + "]: Preview=" + prev + ", Capture=" + cap + ", Analysis=" + ana);
        } catch (Throwable t) {
            Log.w(TAG, "logResolutions failed: " + t.getMessage());
        }
    }

    private void attachWatchdogs() {
        // StreamState Log
        if (!streamObserverAttached) {
            binding.viewFinder.getPreviewStreamState()
                    .observe(getViewLifecycleOwner(), (Observer<? super PreviewView.StreamState>) state ->
                            Log.d(TAG, "Preview stream state: " + state + " (tier=" + lastTier + ")"));
            streamObserverAttached = true;
        }

        // After a short delay, check if STREAMING
        binding.viewFinder.postDelayed(() -> {
            if (!isAdded() || binding == null) return;
            PreviewView.StreamState st = binding.viewFinder.getPreviewStreamState().getValue();
            if (st != PreviewView.StreamState.STREAMING) {
                Log.w(TAG, "Watchdog: not STREAMING → escalate tier");
                escalateBindTier();
            }
        }, 2500);

        // CameraState errors → erst rebind ohne Analysis versuchen
        camera.getCameraInfo().getCameraState().observe(getViewLifecycleOwner(), s -> {
            CameraState.StateError err = s.getError();
            if (err != null) {
                Log.w(TAG, "CameraState error: " + err.getCode());
                if (err.getCode() == CameraState.ERROR_STREAM_CONFIG) {
                    Log.w(TAG, "ERROR_STREAM_CONFIG → try rebind Preview+Capture (ohne Analysis) before tier escalate");
                    try {
                        cameraProvider.unbindAll();
                        camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture);
                        setPreviewSurfaceProviderWithLog(lastTier);
                        return; // Erfolg → keine Tier-Eskalation
                    } catch (Throwable ignored) {
                    }
                    escalateBindTier();
                }
            }
        });
    }

    private void escalateBindTier() {
        if (cameraProvider == null) return;
        Log.w(TAG, "Escalate tier: current=" + lastTier + ", streamState=" + (binding != null ? binding.viewFinder.getPreviewStreamState().getValue() : null));
        if (lastTier == null || lastTier == BindTier.PERF) {
            bindWithTier(BindTier.COMPAT);
        } else if (lastTier == BindTier.COMPAT) {
            bindWithTier(BindTier.COMPAT_LOWRES);
        } else {
            Log.e(TAG, "Still not streaming after COMPAT_LOWRES.");
            if (isAdded()) UIUtils.showToast(requireContext(), R.string.error_camera_preview_failed, Toast.LENGTH_LONG);
        }
    }

    // --------- Capture ---------

    private void captureImage() {
        if (!isAdded() || binding == null) {
            Log.d(TAG, "captureImage: not attached");
            return;
        }
        if (imageCapture == null) {
            Log.e(TAG, "captureImage: ImageCapture null");
            UIUtils.showToast(requireContext(), R.string.error_camera_not_initialized, Toast.LENGTH_SHORT);
            initializeCamera();
            return;
        }

        try {
            setProcessing(true);
            binding.textCamera.setText(R.string.processing_image);

            // PATCH A: robust target directory (externalFilesDir can be null; SD card / vendor-specific devices)
            File baseExt = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            File outputDir;
            if (baseExt != null) {
                // /storage/.../Android/data/<pkg>/files/Pictures/MakeACopy (also on SD if mounted)
                outputDir = new File(baseExt, "MakeACopy");
                Log.d(TAG, "captureImage: using external files dir: " + outputDir.getAbsolutePath());
            } else {
                // Internal fallback: /data/data/<pkg>/files/Pictures/MakeACopy
                File picturesInInternal = new File(requireContext().getFilesDir(), "Pictures");
                //noinspection ResultOfMethodCallIgnored
                picturesInInternal.mkdirs();
                outputDir = new File(picturesInInternal, "MakeACopy");
                Log.w(TAG, "captureImage: external files dir null, using internal: " + outputDir.getAbsolutePath());
            }
            if (!outputDir.exists()) {
                boolean mkOk = outputDir.mkdirs();
                Log.d(TAG, "captureImage: ensure output directory exists -> " + mkOk);
            }

            // Create file with timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis());
            File photoFile = new File(outputDir, "MakeACopy_" + timestamp + ".jpg");
            Log.i(TAG, "captureImage: target file=" + photoFile.getAbsolutePath() + ", rotationDeg=" + toDegrees(getViewFinderRotation()));

            ImageCapture.OutputFileOptions outputOptions =
                    new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            // short AF/AE pre-focus
            MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
            MeteringPoint center = mpf.createPoint(binding.viewFinder.getWidth() / 2f, binding.viewFinder.getHeight() / 2f);

            FocusMeteringAction fma = new FocusMeteringAction.Builder(
                    center,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();

            com.google.common.util.concurrent.ListenableFuture<FocusMeteringResult> fut =
                    camera.getCameraControl().startFocusAndMetering(fma);

            fut.addListener(() -> {
                try {
                    FocusMeteringResult result = fut.get(); // does not block because the listener is only invoked after completion
                    boolean ok = result != null && result.isFocusSuccessful();
                    Log.d(TAG, "captureImage: pre-focus result=" + ok);
                    if (ok) {
                        binding.viewFinder.postDelayed(() -> doTakePicture(outputOptions, photoFile), 150);
                    } else {
                        doTakePicture(outputOptions, photoFile);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "captureImage: pre-focus threw: " + e.getMessage());
                    doTakePicture(outputOptions, photoFile);
                }
            }, ContextCompat.getMainExecutor(requireContext()));

        } catch (Exception e) {
            Log.e(TAG, "captureImage error: " + e.getMessage(), e);
            handleCaptureError(e);
        }
    }

    private void doTakePicture(ImageCapture.OutputFileOptions outputOptions, File photoFile) {
        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Log.d(TAG, "Image saved: " + photoFile.getAbsolutePath() + ", size=" + photoFile.length());
                        Uri imageUri;
                        try {
                            imageUri = FileProvider.getUriForFile(
                                    requireContext(),
                                    BuildConfig.APPLICATION_ID + ".fileprovider",
                                    photoFile
                            );
                        } catch (IllegalArgumentException badRoot) {
                            Log.w(TAG, "FileProvider root mismatch, fallback to file://", badRoot);
                            imageUri = Uri.fromFile(photoFile);
                        }

                        if (cameraViewModel != null && isAdded()) {
                            try {
                                if (cropViewModel != null) {
                                    cropViewModel.setUserRotationDegrees(0);
                                    int captureDeg = toDegrees(getViewFinderRotation());
                                    cropViewModel.setCaptureRotationDegrees(captureDeg);
                                }
                            } catch (Throwable ignored) {
                            }
                            cameraViewModel.setImagePath(photoFile.getAbsolutePath());
                            cameraViewModel.setImageUri(imageUri);

                            try {
                                OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
                                ocrVm.resetForNewImage();
                            } catch (Throwable t) {
                            }

                            cropViewModel.setImageCropped(false);

                            boolean skipOcr = false;
                            boolean skipCropping = false;
                            try {
                                android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                                skipOcr = prefs.getBoolean("skip_ocr", false);
                                skipCropping = prefs.getBoolean("skip_cropping", false);
                            } catch (Throwable ignoreSp) {
                            }

                            int dest = skipCropping ? (skipOcr ? R.id.navigation_export : R.id.navigation_ocr) : R.id.navigation_crop;
                            try {
                                Navigation.findNavController(requireView()).navigate(dest);
                            } catch (IllegalArgumentException | IllegalStateException ignored) {
                            }
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Image capture failed: " + exception.getMessage(), exception);
                        handleCaptureError(exception);
                    }
                });
    }

    /**
     * Handles errors occurring during the image capture process by providing user feedback
     * and updating the UI to indicate the camera is ready for another action.
     *
     * @param exception The exception that was thrown during the image capture process.
     */
    private void handleCaptureError(Exception exception) {
        if (!isAdded() || binding == null) return;
        UIUtils.showToast(requireContext(), getString(R.string.error_image_capture_failed, exception.getMessage()), Toast.LENGTH_SHORT);
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
        setProcessing(false);
    }

    /**
     * Enables/disables action buttons during processing to prevent duplicate operations.
     * Specifically affects the Scan and Load (Pick Image) buttons if present.
     */
    private void setProcessing(boolean processing) {
        if (binding == null) return;
        try {
            binding.buttonScan.setEnabled(!processing);
        } catch (Throwable ignored) {
        }
        try {
            binding.buttonPickImage.setEnabled(!processing);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Configures the UI to display the camera mode and ensures the interface is
     * ready for capturing an image. This method toggles visibility for specific
     * UI elements and updates the displayed text to guide the user for scanning
     * a document. Additionally, it resets the state tracking for low-light
     * conditions to prepare for a fresh start in this mode.
     * <p>
     * Preconditions:
     * - The `binding` property must not be null.
     * <p>
     * Behavior:
     * - Shows the camera view (`viewFinder`) and hides the captured image preview
     * (`capturedImage`).
     * - Updates visibility of buttons, enabling the scan-related buttons and
     * disabling others.
     * - Adjusts visibility of `scanButtonContainer` if it exists in the binding.
     * - Sets the text to notify the user of the camera readiness for scanning.
     * - Resets the `lowLightPromptShown` variable to `false`.
     */
    private void showCameraMode() {
        if (binding == null) return;

        setProcessing(false);
        binding.viewFinder.setVisibility(View.VISIBLE);
        binding.capturedImage.setVisibility(View.GONE);
        binding.buttonContainer.setVisibility(View.GONE);
        binding.buttonScan.setVisibility(View.VISIBLE);
        binding.scanButtonContainer.setVisibility(View.VISIBLE);
        // Live corner preview: respect user preference
        boolean analysisPref = false;
        try {
            android.content.SharedPreferences prefs =
                    requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
            analysisPref = prefs.getBoolean("analysis_enabled", false); // Default OFF
        } catch (Throwable ignore) {
        }

        // Important: always use the helper (overlay + analyzer + pref sync)
        setLiveAnalysisEnabled(analysisPref);
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

        // Reset rotations for a new scan/page
        try {
            if (cropViewModel != null) {
                cropViewModel.setUserRotationDegrees(0);
                cropViewModel.setCaptureRotationDegrees(0);
            }
        } catch (Throwable ignored) {
        }

        lowLightPromptShown = false;
    }

    private void toggleFlashlight() {
        if (camera == null || !hasFlash || !isAdded()) {
            if (isAdded()) UIUtils.showToast(requireContext(), R.string.flashlight_not_available, Toast.LENGTH_SHORT);
            return;
        }
        try {
            boolean newState = !isFlashlightOn;
            Log.i(TAG, "toggleFlashlight: " + (newState ? "ON" : "OFF") + " (was=" + isFlashlightOn + ")");
            isFlashlightOn = newState;
            camera.getCameraControl().enableTorch(isFlashlightOn);
            if (!isFlashlightOn) {
                onTorchTurnedOffRestoreEc();
            }
            if (binding != null && binding.buttonFlash != null) {
                binding.buttonFlash.setImageResource(isFlashlightOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
                UIUtils.showToast(requireContext(), isFlashlightOn ? R.string.flashlight_on : R.string.flashlight_off, Toast.LENGTH_SHORT);
            }
        } catch (Exception e) {
            Log.e(TAG, "toggleFlashlight error: " + e.getMessage(), e);
            if (isAdded())
                UIUtils.showToast(requireContext(), getString(R.string.error_toggling_flashlight, e.getMessage()), Toast.LENGTH_SHORT);
        }
    }

    private void turnOffFlashlight() {
        if (camera != null && isFlashlightOn && isAdded()) {
            try {
                camera.getCameraControl().enableTorch(false);
                isFlashlightOn = false;
                onTorchTurnedOffRestoreEc();
                if (binding != null) binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
            } catch (Exception e) {
                Log.e(TAG, "turnOffFlashlight error: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Resets the camera to its initial state, preparing it for a new operation.
     * This method handles UI updates, frees up resources, and reinitializes
     * the camera if necessary.
     * <p>
     * Steps performed in this method:
     * 1. Ensures the binding is not null and the fragment is added before proceeding.
     * 2. Updates the UI by resetting the text and clearing the captured image.
     * 3. Turns off the flashlight to ensure it is disabled during the reset process.
     * 4. Safely recycles any existing bitmap from the captured image to free memory.
     * 5. Switches the camera display to its default mode.
     * 6. Clears any stored image URI in the ViewModel.
     * 7. Sets the low-light prompt state to false.
     * 8. Unbinds all previously bound use cases from the camera provider, if applicable.
     * 9. Reinitializes the camera only if the permission to access the camera is granted.
     * 10. Handles exceptions by logging the error and showing a user-friendly message.
     * <p>
     * Note: This method ensures that the camera and related resources are properly reset
     * and prepared to avoid resource leaks or inconsistent states during usage.
     */
    private void resetCamera() {
        if (binding == null || !isAdded()) return;

        binding.textCamera.setText(R.string.processing_image);

        // Turn off flashlight when resetting camera
        turnOffFlashlight();

        // Clean up bitmap safely (6)
        Drawable d = binding.capturedImage.getDrawable();
        binding.capturedImage.setImageDrawable(null);
        if (d instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }

        showCameraMode();

        if (cameraViewModel != null) {
            cameraViewModel.setImageUri(null);
            cameraViewModel.setImagePath(null);
        }

        lowLightPromptShown = false;

        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isAdded() && cameraViewModel != null &&
                            Boolean.TRUE.equals(cameraViewModel.isCameraPermissionGranted().getValue())) {
                        initializeCamera();
                    }
                });
            } else {
                if (cameraViewModel != null &&
                        Boolean.TRUE.equals(cameraViewModel.isCameraPermissionGranted().getValue())) {
                    initializeCamera();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "resetCamera error: " + e.getMessage());
            if (isAdded())
                UIUtils.showToast(requireContext(), getString(R.string.error_resetting_camera, e.getMessage()), Toast.LENGTH_SHORT);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        turnOffFlashlight();
        if (sensorManager != null && lightSensor != null) {
            sensorManager.unregisterListener(this);
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (orientationListener != null) orientationListener.disable();
        analysisEnabled = false;
        if (imageAnalysis != null) {
            try {
                imageAnalysis.clearAnalyzer();
            } catch (Throwable ignored) {
            }
            imageAnalysis = null;
        }
        if (analysisExecutor != null) {
            analysisExecutor.shutdownNow();
            analysisExecutor = null;
        }
        streamObserverAttached = false;
        binding = null;
    }

    /**
     * Initializes the light sensor for the application.
     * <p>
     * This method checks if the light sensor is available on the device and sets up
     * the required sensor references. It ensures that the light sensor manager is
     * initialized properly if the fragment is added to the activity. If the sensor
     * is unavailable or there is an error during initialization, appropriate logging
     * messages are generated.
     * <p>
     * Key Operations:
     * - Verifies if the fragment is currently added to an activity.
     * - Retrieves the sensor manager from the system context.
     * - Checks for the availability of the light sensor.
     * - Updates the sensor's availability status and logs messages accordingly.
     * - Handles exceptions gracefully and logs errors.
     * <p>
     * Preconditions:
     * - The fragment must be attached to the activity for the context to be valid.
     * <p>
     * Postconditions:
     * - The `hasLightSensor` flag indicates whether the light sensor is available.
     * - The `lightSensor` field is populated if a light sensor is present.
     * - Logs provide debugging information regarding sensor availability and errors.
     */
    private void initLightSensor() {
        if (!isAdded()) return;

        try {
            sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                hasLightSensor = (lightSensor != null);
                if (hasLightSensor) {
                    try {
                        Log.i(TAG, "Light sensor available: name=" + lightSensor.getName() + ", vendor=" + lightSensor.getVendor() + ", maxRange=" + lightSensor.getMaximumRange());
                    } catch (Throwable t) {
                        Log.i(TAG, "Light sensor available");
                    }
                } else {
                    Log.i(TAG, "Light sensor not available");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "initLightSensor error: " + e.getMessage(), e);
            hasLightSensor = false;
        }
    }

    /**
     * Shows a prompt when low-light conditions are detected, suggesting to enable the flashlight.
     * <p>
     * Debounces aggressively to avoid repeated dialogs:
     * - Only if the fragment is added and binding is available
     * - Only if no other low-light dialog is visible
     * - Only if MIN_TIME_BETWEEN_PROMPTS has elapsed since the last prompt
     * <p>
     * On positive action, toggles the flashlight if available and currently off.
     * Any exception during dialog creation/show is caught and logged.
     */
    private void showLowLightPrompt() {
        if (!isAdded() || binding == null || isLowLightDialogVisible) return;
        long now = System.currentTimeMillis();
        if (lowLightPromptShown || (now - lastPromptTime) < MIN_TIME_BETWEEN_PROMPTS) return;

        try {
            isLowLightDialogVisible = true;
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.low_light_detected)
                    .setPositiveButton(R.string.flashlight_on, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            if (!isFlashlightOn && hasFlash) toggleFlashlight();
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, id) -> dialogInterface.dismiss())
                    .create();

            dialog.setOnDismissListener(d -> isLowLightDialogVisible = false);
            dialog.setOnShowListener(dlg ->
                    de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
            dialog.show();

            lowLightPromptShown = true;
            lastPromptTime = now;
        } catch (Exception e) {
            Log.e(TAG, "showLowLightPrompt error: " + e.getMessage(), e);
            isLowLightDialogVisible = false;
        }
    }

    /**
     * Callback method that is triggered when there is a change in sensor data. Specifically, this method
     * listens for changes in the light sensor data and performs actions based on the detected light level.
     *
     * @param event the SensorEvent containing details about the sensor data, such as the sensor type
     *              and its current values. In this case, the method focuses on events from the light sensor.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lux = event.values[0];
            if (de.schliweb.makeacopy.BuildConfig.DEBUG || android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
                Log.d(TAG, "Light level: " + lux + " lux");
            }
            if (lux < LOW_LIGHT_THRESHOLD
                    && binding != null
                    && binding.viewFinder.getVisibility() == View.VISIBLE
                    && !isFlashlightOn
                    && hasFlash) {
                showLowLightPrompt();
            }
        }
    }

    /**
     * Called when the accuracy of the registered sensor has changed.
     *
     * @param sensor   The sensor for which the accuracy has changed.
     * @param accuracy The new accuracy of the sensor, represented as one of the predefined sensor accuracy constants.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_LIGHT) {
            if (de.schliweb.makeacopy.BuildConfig.DEBUG || android.util.Log.isLoggable(TAG, android.util.Log.DEBUG)) {
                Log.d(TAG, "Light sensor accuracy: " + accuracy);
            }
        }
    }

    /**
     * Converts a given surface rotation value to its corresponding degree representation.
     *
     * @param surfaceRotation the surface rotation value, typically one of the predefined constants
     *                        (e.g., Surface.ROTATION_0, Surface.ROTATION_90, etc.).
     * @return the equivalent degree value for the given rotation: 0, 90, 180, or 270. Returns 0 for unrecognized values.
     */
    private static int toDegrees(int surfaceRotation) {
        switch (surfaceRotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    // Verbose environment logging to help diagnose device-specific issues
    private void logEnvironment() {
        try {
            String versionName = BuildConfig.VERSION_NAME;
            int versionCode;
            try {
                versionCode = de.schliweb.makeacopy.BuildConfig.VERSION_CODE;
            } catch (Throwable t) {
                versionCode = -1;
            }
            String abis = Build.SUPPORTED_ABIS != null ? java.util.Arrays.toString(Build.SUPPORTED_ABIS) : "unknown";
            java.util.Locale loc = java.util.Locale.getDefault();
            boolean analysisPref = false;
            try {
                android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                analysisPref = prefs.getBoolean("analysis_enabled", false);
            } catch (Throwable ignored) {
            }
            String secPatch = null;
            try {
                secPatch = android.os.Build.VERSION.SECURITY_PATCH;
            } catch (Throwable ignored) {
            }
            Log.i(TAG,
                    "Env: app=" + versionName + " (" + versionCode + ")" +
                            ", sdk=" + Build.VERSION.SDK_INT + " (release=" + Build.VERSION.RELEASE + ", incremental=" + Build.VERSION.INCREMENTAL + ", secPatch=" + (secPatch != null ? secPatch : "-") + ")" +
                            ", brand=" + Build.BRAND +
                            ", manuf=" + Build.MANUFACTURER +
                            ", model=" + Build.MODEL +
                            ", device=" + Build.DEVICE +
                            ", product=" + Build.PRODUCT +
                            ", hardware=" + Build.HARDWARE +
                            ", board=" + Build.BOARD +
                            ", fingerprint=" + Build.FINGERPRINT +
                            ", display=" + Build.DISPLAY +
                            ", abis=" + abis +
                            ", locale=" + (loc != null ? loc.toLanguageTag() : "-") +
                            ", analysisPref=" + analysisPref);
        } catch (Throwable t) {
            Log.w(TAG, "logEnvironment failed: " + t.getMessage());
        }
    }

    private void logCameraCapabilities() {
        try {
            if (camera == null) {
                Log.i(TAG, "Capabilities: camera=null");
                return;
            }
            boolean flash = camera.getCameraInfo().hasFlashUnit();
            ExposureState es = camera.getCameraInfo().getExposureState();
            String ecInfo;
            if (es != null && es.isExposureCompensationSupported()) {
                android.util.Range<Integer> r = es.getExposureCompensationRange();
                float step = 0f;
                try {
                    step = es.getExposureCompensationStep().floatValue();
                } catch (Throwable ignored) {
                }
                ecInfo = "EC supported idx=" + es.getExposureCompensationIndex() + " range=" + r + " step=" + step;
            } else {
                ecInfo = "EC not supported";
            }
            int rotDeg = toDegrees(getViewFinderRotation());
            Log.i(TAG, "Capabilities: flash=" + flash + ", tier=" + lastTier + ", rotation=" + rotDeg + ", " + ecInfo + ", analysisEnabled=" + analysisEnabled);
        } catch (Throwable t) {
            Log.w(TAG, "logCameraCapabilities failed: " + t.getMessage());
        }
    }

    @androidx.annotation.VisibleForTesting
    ImageCapture getImageCaptureForTest() {
        return imageCapture;
    }

    // ===== Live overlay & adaptive torch exposure =====

    private void setLiveAnalysisEnabled(boolean enabled) {
        if (!isAdded()) {
            analysisEnabled = enabled;
            return;
        }
        analysisEnabled = enabled;
        try {
            android.content.SharedPreferences prefs =
                    requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("analysis_enabled", enabled).apply();
        } catch (Throwable ignore) {
        }

        if (binding != null) {
            if (enabled && binding.viewFinder.getVisibility() == View.VISIBLE) {
                binding.cornerOverlay.setVisibility(View.VISIBLE);
            } else {
                binding.cornerOverlay.setCorners(null);
                binding.cornerOverlay.setVisibility(View.GONE);
            }
        }
        try {
            if (imageAnalysis != null) {
                if (enabled) {
                    ensureAnalysisExecutor();
                    imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrameForCorners);
                } else {
                    imageAnalysis.clearAnalyzer();
                    if (analysisExecutor != null) {
                        analysisExecutor.shutdownNow();
                        analysisExecutor = null;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "setLiveAnalysisEnabled failed: " + t.getMessage());
        }
    }

    // NEU: strikt 1280x960 / YUV und 4:3
    private void setupOrUpdateImageAnalysis(int rotation) {
        ResolutionSelector analysisRs = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                        new AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
                )
                .setResolutionStrategy(
                        new ResolutionStrategy(
                                new android.util.Size(1280, 960),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                )
                .build();

        if (imageAnalysis == null) {
            imageAnalysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetRotation(rotation)
                    .setResolutionSelector(analysisRs)
                    .build();
        } else {
            try {
                imageAnalysis.setTargetRotation(rotation);
            } catch (Throwable ignored) {
            }
        }

        if (analysisEnabled) {
            ensureAnalysisExecutor();
            imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrameForCorners);
            if (binding != null) {
                binding.cornerOverlay.setVisibility(View.VISIBLE);
                binding.cornerOverlay.setCorners(null);
            }
        } else {
            try {
                imageAnalysis.clearAnalyzer();
            } catch (Throwable ignored) {
            }
            if (binding != null) {
                binding.cornerOverlay.setCorners(null);
                binding.cornerOverlay.setVisibility(View.GONE);
            }
        }
    }

    private void ensureAnalysisExecutor() {
        if (analysisExecutor == null || analysisExecutor.isShutdown()) {
            analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CornerAnalysis");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private void analyzeFrameForCorners(@NonNull ImageProxy image) {
        try {
            // 1) Adaptive torch EC based on luma, very cheap (no bitmap)
            adaptExposureIfTorch(image);

            if (!analysisEnabled || binding == null || !isAdded()) return;

            long now = System.currentTimeMillis();
            if (now - lastAnalysisTs < 180) return; // ~5–6 FPS
            lastAnalysisTs = now;

            // Convert to small upright bitmap (to reduce CPU)
            Bitmap bmp = yuvToBitmapUprightSmall(image, 720); // cap longest side ~720px
            if (bmp == null) return;

            // Init CV once
            try {
                if (!de.schliweb.makeacopy.utils.OpenCVUtils.isInitialized()) {
                    de.schliweb.makeacopy.utils.OpenCVUtils.init(requireContext().getApplicationContext());
                }
            } catch (Throwable ignored) {
            }

            org.opencv.core.Point[] pts = de.schliweb.makeacopy.utils.OpenCVUtils.detectDocumentCorners(requireContext(), bmp);
            if (pts == null || pts.length != 4) {
                // hide overlay if not found
                runOnUiThreadSafe(() -> {
                    if (binding != null) binding.cornerOverlay.setCorners(null);
                });
                return;
            }

            // Map bitmap coords to overlay coords (PreviewView with FIT_CENTER)
            android.graphics.PointF[] viewPts = mapToOverlayPoints(pts, bmp.getWidth(), bmp.getHeight());
            runOnUiThreadSafe(() -> {
                if (binding != null) binding.cornerOverlay.setCorners(viewPts);
            });
        } catch (Throwable t) {
            Log.w(TAG, "analyzeFrameForCorners failed: " + t.getMessage());
        } finally {
            try {
                image.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void runOnUiThreadSafe(Runnable r) {
        if (!isAdded()) return;
        try {
            requireActivity().runOnUiThread(r);
        } catch (Throwable ignored) {
        }
    }

    private android.graphics.PointF[] mapToOverlayPoints(org.opencv.core.Point[] src, int bmpW, int bmpH) {
        if (binding == null) return null;
        int vw = binding.viewFinder.getWidth();
        int vh = binding.viewFinder.getHeight();
        if (vw <= 0 || vh <= 0 || bmpW <= 0 || bmpH <= 0) return null;
        float sx = vw / (float) bmpW;
        float sy = vh / (float) bmpH;
        float scale = Math.min(sx, sy);
        float contentW = bmpW * scale;
        float contentH = bmpH * scale;
        float offX = (vw - contentW) * 0.5f;
        float offY = (vh - contentH) * 0.5f;
        android.graphics.PointF[] out = new android.graphics.PointF[4];
        for (int i = 0; i < 4; i++) {
            float x = (float) src[i].x;
            float y = (float) src[i].y;
            out[i] = new android.graphics.PointF(offX + x * scale, offY + y * scale);
        }
        return out;
    }

    private Bitmap yuvToBitmapUprightSmall(@NonNull ImageProxy image, int maxSize) {
        try {
            byte[] nv21 = toNv21(image);
            if (nv21 == null) return null;
            int w = image.getWidth();
            int h = image.getHeight();
            android.graphics.YuvImage yuv = new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 60, out);
            byte[] jpeg = out.toByteArray();
            out.close();
            // Decode with inSampleSize
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inSampleSize = computeSampleSize(w, h, maxSize);
            Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (raw == null) return null;
            int rot = image.getImageInfo().getRotationDegrees();
            if (rot == 0) return raw;
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(rot);
            Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            if (rotated != raw) raw.recycle();
            return rotated;
        } catch (Throwable t) {
            Log.w(TAG, "yuvToBitmapUprightSmall failed: " + t.getMessage());
            return null;
        }
    }

    private int computeSampleSize(int w, int h, int maxSize) {
        int longSide = Math.max(w, h);
        int sample = 1;
        while (longSide / sample > maxSize) sample <<= 1; // power-of-two downsampling
        return Math.max(1, sample);
    }

    private byte[] toNv21(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] out = new byte[width * height * 3 / 2];
        int offset = 0;

        // ----- Y -----
        java.nio.ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixStride = planes[0].getPixelStride();

        if (yPixStride == 1 && yRowStride == width) {
            // Fast path: tightly packed
            yBuf.rewind();
            yBuf.get(out, 0, width * height);
            offset = width * height;
        } else {
            // General path
            for (int row = 0; row < height; row++) {
                int yPos = row * yRowStride;
                for (int col = 0; col < width; col++) {
                    out[offset++] = yBuf.get(yPos + col * yPixStride);
                }
            }
        }

        // ----- UV → NV21 (VU interleaved) -----
        java.nio.ByteBuffer uBuf = planes[1].getBuffer();
        java.nio.ByteBuffer vBuf = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixStride = planes[2].getPixelStride();

        int chromaHeight = height / 2;
        int chromaWidth = width / 2;

        // There is no guaranteed tight-pack for UV, so we stay on the safe per-pixel path
        for (int row = 0; row < chromaHeight; row++) {
            int uRowStart = row * uRowStride;
            int vRowStart = row * vRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                int uIndex = uRowStart + col * uPixStride;
                int vIndex = vRowStart + col * vPixStride;
                byte u = uBuf.get(uIndex);
                byte v = vBuf.get(vIndex);
                out[offset++] = v; // NV21 = V then U
                out[offset++] = u;
            }
        }
        return out;
    }

    // ===== Adaptive exposure with torch =====

    private void adaptExposureIfTorch(@NonNull ImageProxy image) {
        if (camera == null || !isFlashlightOn) return;
        ExposureState es = camera.getCameraInfo().getExposureState();
        if (es == null || !es.isExposureCompensationSupported()) return;

        float luma = estimateSceneLuma(image); // 0..1
        float target = 0.35f, deadband = 0.03f;

        if (baseEc == null) baseEc = es.getExposureCompensationIndex();

        int currentEc = es.getExposureCompensationIndex();
        int newEc = currentEc;
        if (luma < (target - deadband)) newEc += 1;
        else if (luma > (target + deadband)) newEc -= 1;
        else return;

        android.util.Range<Integer> r = es.getExposureCompensationRange();
        newEc = Math.max(r.getLower(), Math.min(r.getUpper(), newEc));
        if (newEc != currentEc) {
            camera.getCameraControl().setExposureCompensationIndex(newEc);
            long now = System.currentTimeMillis();
            if (now - lastEcLogTs > 500) { // throttle
                lastEcLogTs = now;
                float step = 0f;
                try {
                    step = es.getExposureCompensationStep().floatValue();
                } catch (Throwable ignored) {
                }
                Log.d(TAG, "AdaptiveEC: luma=" + String.format(java.util.Locale.US, "%.3f", luma) +
                        ", idx " + currentEc + " -> " + newEc +
                        " (range=" + r + ", step=" + step + ")");
            }
        }
    }

    private void onTorchTurnedOffRestoreEc() {
        if (camera == null) return;
        ExposureState es = camera.getCameraInfo().getExposureState();
        if (es != null && baseEc != null) {
            camera.getCameraControl().setExposureCompensationIndex(baseEc);
        }
        baseEc = null;
    }

    private float estimateSceneLuma(@NonNull ImageProxy image) {
        ImageProxy.PlaneProxy y = image.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int rowStride = y.getRowStride();
        int pixStride = y.getPixelStride(); // usually 1
        int w = image.getWidth(), h = image.getHeight();
        int step = 8, count = 0;
        long sum = 0;
        for (int r = 0; r < h; r += step) {
            int base = r * rowStride;
            for (int c = 0; c < w; c += step) {
                int idx = base + c * pixStride;
                if (idx >= 0 && idx < buf.limit()) {
                    sum += (buf.get(idx) & 0xFF);
                    count++;
                }
            }
        }
        return (count == 0) ? 0f : (sum / (255f * count));
    }
}
