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
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
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
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * CameraFragment is responsible for handling the camera functionality and user interactions
 * within the application. It includes methods for initializing the camera, managing light
 * sensor interactions, handling orientation, capturing images, and managing flashlight
 * functionality. It interacts with CameraViewModel and CropViewModel to manage state and
 * data associated with the camera and image operations.
 * <p>
 * Fields:
 * - TAG: String constant for logging purposes.
 * - CAMERA_PERMISSION_REQUEST_CODE: Integer constant for camera permission requests.
 * - LOW_LIGHT_THRESHOLD: Threshold value for detecting low light conditions.
 * - MIN_TIME_BETWEEN_PROMPTS: Minimum time interval between showing prompts.
 * - binding: Data binding object for the fragment's layout.
 * - cameraViewModel: ViewModel to manage camera-related state.
 * - imageCapture: ImageCapture instance for handling image capture operations.
 * - cameraProvider: CameraProvider for managing camera lifecycle.
 * - camera: Camera instance representing the currently active camera.
 * - isFlashlightOn: Boolean flag indicating if the flashlight is currently on.
 * - hasFlash: Boolean flag indicating if the device has a flash available.
 * - sensorManager: SensorManager instance for managing hardware sensors.
 * - lightSensor: Sensor instance representing the device's light sensor.
 * - hasLightSensor: Boolean flag indicating if the device has a light sensor.
 * - lowLightPromptShown: Boolean flag indicating if the low-light prompt has been shown.
 * - lastPromptTime: Long value representing the timestamp of the last shown prompt.
 * - isLowLightDialogVisible: Boolean flag indicating if the low-light dialog is currently visible.
 * - requestPermissionLauncher: ActivityResultLauncher for handling runtime permissions.
 * - orientationListener: Listener for handling orientation changes.
 * - reinitScheduled: Boolean flag indicating if camera reinitialization is scheduled.
 * - cropViewModel: ViewModel for managing image cropping functionality.
 * <p>
 * Methods:
 * - onCreateView: Inflates the layout and initializes UI components for the fragment.
 * - onResume: Registers necessary lifecycle components such as the sensor listener.
 * - onPause: Unregisters lifecycle components such as the sensor listener.
 * - checkCameraPermission: Checks and requests camera permission if not already granted.
 * - getViewFinderRotation: Obtains the current rotation of the viewfinder.
 * - initializeCamera: Sets up the camera, including configuring preview and image capture use cases.
 * - handleCameraInitializationError: Handles exceptions occurring during camera initialization.
 * - captureImage: Captures an image using the camera and processes it.
 * - handleCaptureError: Handles exceptions occurring during image capture operations.
 * - displayCapturedImage: Displays the captured image using a URI.
 * - showCameraMode: Updates the UI to show camera mode.
 * - showReviewMode: Updates the UI to show image review mode.
 * - toggleFlashlight: Toggles the flashlight on or off.
 * - turnOffFlashlight: Turns off the flashlight if it is currently on.
 * - resetCamera: Resets camera components and associated states.
 * - onDestroyView: Cleans up resources when the fragment view is destroyed.
 * - initLightSensor: Initializes and checks for availability of the light sensor.
 * - showLowLightPrompt: Displays a dialog to prompt the user to turn on the flashlight in low-light conditions.
 * - onSensorChanged: Responds to changes in sensor values, such as light level changes.
 * - onAccuracyChanged: Handles changes in the accuracy of the sensors.
 */
public class CameraFragment extends Fragment implements SensorEventListener {

    // Xiaomi/Redmi torch exposure quirk mitigation
    private boolean isXiaomiTorchQuirk = false;
    private int previousExposureCompIndex = 0;
    private boolean exposureCompApplied = false;

    // Live corner preview (document trapezoid)
    private ImageAnalysis imageAnalysis;
    private java.util.concurrent.ExecutorService analysisExecutor;
    private volatile boolean analysisEnabled = false;
    private long lastAnalysisTs = 0L;

    private static final String TAG = "CameraFragment";

    // Light sensor constants
    private static final float LOW_LIGHT_THRESHOLD = 10.0f; // Lux value below which light is considered low
    private static final long MIN_TIME_BETWEEN_PROMPTS = 60000; // 1 minute

    private FragmentCameraBinding binding;
    private CameraViewModel cameraViewModel;

    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private boolean isFlashlightOn = false;
    private boolean hasFlash = false;

    // Light sensor variables
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private boolean hasLightSensor = false;
    private boolean lowLightPromptShown = false;
    private long lastPromptTime = 0;
    private boolean isLowLightDialogVisible = false; // (9) Debounce

    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> pickImageLauncher;

    // (1) Orientation listener to keep target rotation in sync
    private OrientationEventListener orientationListener;

    // (5) Prevent repeated re-initialization queues
    private boolean reinitScheduled = false;

    private CropViewModel cropViewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
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
                            int dest;
                            if (skipCropping) {
                                dest = skipOcr ? R.id.navigation_export : R.id.navigation_ocr;
                            } else {
                                dest = R.id.navigation_crop;
                            }
                            // Reset OCR state if going directly to OCR
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
                                // Fragment not in a state to navigate anymore; safe to ignore
                            }
                        }
                    }
                });

        binding = FragmentCameraBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // Detect Xiaomi/Redmi torch exposure quirk once
        isXiaomiTorchQuirk = isXiaomiTorchQuirkDevice();

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

        // Observe the image URI. Previously this triggered a confirm/review step.
        // With the improved workflow, we navigate to Crop directly on capture/pick,
        // so we no longer switch modes here to avoid re-navigation loops when returning.
        cameraViewModel.getImageUri().observe(getViewLifecycleOwner(), uri -> {
            // Intentionally left blank to avoid auto UI switches here.
        });

        // Set up scan button
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
        if (binding.buttonPickImage != null) {
            binding.buttonPickImage.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                pickImageLauncher.launch(intent);
            });
        }

        // Settings/options button: open a simple dialog with only "Skip OCR"
        if (binding.buttonCameraOptions != null) {
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
        }

        // Library entry from Camera screen (feature-gated)
        try {
            if (binding.buttonOpenLibraryCam != null) {
                if (de.schliweb.makeacopy.BuildConfig.FEATURE_SCAN_LIBRARY) {
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
                            if (isAdded() && binding != null && binding.buttonRetake != null) {
                                binding.buttonRetake.setEnabled(true);
                            }
                        }, 1000);
                    }, 100);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling retake button click: " + e.getMessage());
                if (isAdded()) {
                    UIUtils.showToast(requireContext(), getString(R.string.error_resetting_camera, e.getMessage()), Toast.LENGTH_SHORT);
                    v.setEnabled(true);
                }
            }
        });

        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);

        binding.buttonConfirm.setOnClickListener(v -> {
            if (isAdded() && getView() != null) {
                cropViewModel.setImageCropped(false);

                boolean skipOcr = false;
                boolean skipCropping = false;
                try {
                    android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                    skipOcr = prefs.getBoolean("skip_ocr", false);
                    skipCropping = prefs.getBoolean("skip_cropping", false);
                } catch (Throwable ignoreSp) {
                }

                int dest;
                if (skipCropping) {
                    dest = skipOcr ? R.id.navigation_export : R.id.navigation_ocr;
                } else {
                    dest = R.id.navigation_crop;
                }
                // Reset OCR state if going directly to OCR
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

        // Handle back: in review mode -> reset, sonst default
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

    // (3) Sensor-Registrierung lifecycle-freundlich
    @Override
    public void onResume() {
        super.onResume();
        if (hasLightSensor && sensorManager != null && lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (orientationListener != null) {
            orientationListener.enable();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (orientationListener != null) {
            orientationListener.disable();
        }
    }

    /**
     * Verifies if the application has been granted the camera permission by the user. If the
     * permission is not yet granted, it utilizes a permission launcher to request it. If the
     * permission is already granted, it updates the camera view model to reflect this status.
     * <p>
     * This method ensures that the application's camera functionality is only initialized or
     * activated when the necessary permission is obtained.
     * <p>
     * Preconditions:
     * - The fragment is added to its host activity.
     * <p>
     * Behavior:
     * - If the permission is not granted:
     * - Launches the permission request dialog using the `requestPermissionLauncher`.
     * - If the permission is granted:
     * - Updates the `cameraViewModel` to indicate that the camera permission is granted.
     */
    private void checkCameraPermission() {
        if (!isAdded()) return;

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (requestPermissionLauncher != null) {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        } else {
            if (cameraViewModel != null) {
                cameraViewModel.setCameraPermissionGranted(true);
            }
        }
    }

    // (1) Orientation direkt vom PreviewView / Display beziehen
    private int getViewFinderRotation() {
        if (binding != null && binding.viewFinder.getDisplay() != null) {
            return binding.viewFinder.getDisplay().getRotation();
        }
        return Surface.ROTATION_0;
    }

    /**
     * Retrieves the value of a system property identified by the given key.
     * If the property is not found, the specified default value is returned.
     *
     * @param key the name of the system property to retrieve
     * @param def the default value to return if the system property is not found
     * @return the value of the system property, or the default value if not found
     */
    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, def);
        } catch (Throwable t) {
            return def;
        }
    }


    private boolean isXperia1VI() {
        String model = android.os.Build.MODEL;
        if (model == null) return false;
        String m = model.toUpperCase(Locale.ROOT);
        return (m.startsWith("XQ-EC") || m.startsWith("XQ-ES"));
    }

    private static boolean isSonyModel(String... prefixes) {
        String model = android.os.Build.MODEL;
        if (model == null) return false;
        String m = model.toUpperCase(Locale.ROOT).trim();
        for (String p : prefixes) {
            if (m.startsWith(p.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Determines if the device is manufactured by Sony or has characteristics
     * commonly associated with Sony devices.
     * <p>
     * The method checks the manufacturer, brand, and model of the device for
     * indications of Sony-like characteristics. It also examines specific system
     * properties as a fallback for devices where these values might be customized
     * or modified.
     *
     * @return true if the device is determined to be Sony-like based on the
     * manufacturer, brand, model, or certain system properties;
     * false otherwise or in case of any errors.
     */
    private static boolean isSonyLike() {
        try {
            String man = android.os.Build.MANUFACTURER;
            String brand = android.os.Build.BRAND;
            String model = android.os.Build.MODEL;

            String sMan = man == null ? "" : man.toLowerCase(Locale.ROOT);
            String sBrand = brand == null ? "" : brand.toLowerCase(Locale.ROOT);
            String sModel = model == null ? "" : model.toLowerCase(Locale.ROOT);

            // Check common system properties as a fallback (custom ROMs sometimes change MANUFACTURER)
            String[] props = {
                    "ro.product.manufacturer",
                    "ro.product.vendor.manufacturer",
                    "ro.product.system.manufacturer",
                    "ro.product.odm.manufacturer"
            };
            boolean propsSaySony = false;
            for (String k : props) {
                String v = getSystemProperty(k, "");
                if (v != null && v.toLowerCase(Locale.ROOT).contains("sony")) {
                    propsSaySony = true;
                    break;
                }
            }

            return sMan.contains("sony") || sBrand.contains("sony")
                    || sModel.contains("xperia") || propsSaySony;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isXperia5IV() {
        // International variants start with XQ-CQ; Japanese carrier models: SO-54C / SOG09
        return isSonyModel("XQ-CQ", "SO-54C", "SOG09");
    }

    // Broad Sony fallback: enforce TextureView (COMPATIBLE) on Android 15+
    private boolean isSonyAndroid15Plus() {
        try {
            return android.os.Build.VERSION.SDK_INT >= 35 && isSonyLike();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean shouldForceCompatiblePreview() {
        return isPreviewBlackScreenQuirkDevice()
                || isXperia1VI()
                || isXperia5IV()
                || isSonyAndroid15Plus();
    }

    private void logStartupInfo(boolean implCompatible,
                                boolean quirkActive,
                                boolean conservativeRes,
                                android.util.Range<Integer> fpsRange) {
        try {
            String manufacturer = android.os.Build.MANUFACTURER;
            String brand = android.os.Build.BRAND;
            String model = android.os.Build.MODEL;
            int sdk = android.os.Build.VERSION.SDK_INT;

            String previewRes = "n/a";
            try {
                if (preview != null && preview.getResolutionInfo() != null) {
                    android.util.Size s = preview.getResolutionInfo().getResolution();
                    previewRes = s.getWidth() + "x" + s.getHeight();
                }
            } catch (Throwable ignored) {
            }

            String captureRes = "n/a";
            try {
                if (imageCapture != null && imageCapture.getResolutionInfo() != null) {
                    android.util.Size s = imageCapture.getResolutionInfo().getResolution();
                    captureRes = s.getWidth() + "x" + s.getHeight();
                }
            } catch (Throwable ignored) {
            }

            String captureMode = (imageCapture != null
                    && imageCapture.getCaptureMode() == ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    ? "MINIMIZE_LATENCY" : "MAXIMIZE_QUALITY";

            Log.i(TAG, "=== Camera startup info ===");
            Log.i(TAG, "Device: " + manufacturer + " / " + brand + " / " + model + " (SDK " + sdk + ")");
            Log.i(TAG, "ImplMode: " + (implCompatible ? "COMPATIBLE" : "PERFORMANCE"));
            Log.i(TAG, "Quirk active: " + quirkActive + ", conservativeRes: " + conservativeRes);
            Log.i(TAG, "Preview res: " + previewRes + ", Capture res: " + captureRes);
            Log.i(TAG, "Capture mode: " + captureMode + ", AE_TARGET_FPS_RANGE: " + fpsRange);
            Log.i(TAG, "isXperia1VI=" + isXperia1VI() + ", isXperia5IV=" + isXperia5IV()
                    + ", isHuaweiQuirk=" + isHuaweiQuirkDevice() + ", isHonorQuirk=" + isPreviewBlackScreenQuirkDevice());
            Log.i(TAG, "============================");
        } catch (Throwable t) {
            Log.w(TAG, "logStartupInfo failed: " + t.getMessage());
        }
    }

    /**
     * Extracts and returns the major version number from the EMUI version string.
     * The EMUI version string is retrieved from the system property "ro.build.version.emui".
     * If the version string is not in a recognizable format or is unavailable, -1 is returned.
     *
     * @return The major version number of EMUI, or -1 if the version string is invalid or unavailable.
     */
    private static int getEmuiMajor() {
        // Examples: "EmotionUI_10.1.0", "EmotionUI_11.0.0"
        String emui = getSystemProperty("ro.build.version.emui", "");
        if (emui == null) return -1;
        // extract first number after underscore
        int us = emui.indexOf('_');
        if (us >= 0 && us + 1 < emui.length()) {
            String rest = emui.substring(us + 1);
            String num = rest.split("\\.")[0];
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /**
     * Retrieves the major version number of the EMUI system based on system property or fallback string.
     * <p>
     * This method first attempts to get the EMUI version from the system property "ro.build.version.emui".
     * If it fails, it uses a fallback approach by analyzing the build's display string.
     *
     * @return The major version number of EMUI, or -1 if the version cannot be determined.
     */
    private static int getEmuiMajorLoose() {
        // 1) Systemproperty
        String emui = getSystemProperty("ro.build.version.emui", "");
        if (emui != null && !emui.isEmpty()) {
            int us = emui.indexOf('_');
            String rest = (us >= 0 && us + 1 < emui.length()) ? emui.substring(us + 1) : emui;
            String num = rest.split("\\.")[0];
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException ignored) {
            }
        }
        // 2) Fallback: Build.DISPLAY (Marketing-String)
        try {
            String disp = android.os.Build.DISPLAY;
            if (disp != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("EMUI\\s*([0-9]{1,2})", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(disp);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Extracts the major version number of Magic UI from system properties or build display information.
     * The method first attempts to retrieve the version from the system property "ro.build.version.magic".
     * If the property is found, it parses the major version from the string. If not, it falls back to
     * parsing the version from the `Build.DISPLAY` field using a regex pattern.
     *
     * @return The major version number of Magic UI as an integer. Returns -1 if the version cannot be determined.
     */
    private static int getMagicUiMajor() {
        String magic = getSystemProperty("ro.build.version.magic", "");
        if (magic != null && !magic.isEmpty()) {
            int us = magic.indexOf('_');
            if (us >= 0 && us + 1 < magic.length()) {
                String rest = magic.substring(us + 1);
                String num = rest.split("\\.")[0];
                try {
                    return Integer.parseInt(num);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // Fallback: try Build.DISPLAY
        try {
            String disp = android.os.Build.DISPLAY;
            if (disp != null) {
                String up = disp.toUpperCase(java.util.Locale.ROOT);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("MAGIC\\s?UI[_\\s-]?(\\d+)").matcher(up);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }


    /**
     * Determines if the device has a known quirk where the preview screen shows as a black screen.
     * Specifically checks for Huawei devices running EMUI 10 and Honor devices with certain Android and Magic UI/EMUI configurations.
     *
     * @return true if the device is identified as having the black screen preview quirk; false otherwise.
     */
    private boolean isPreviewBlackScreenQuirkDevice() {
        if (isHuaweiQuirkDevice()) return true;

        String manufacturer = android.os.Build.MANUFACTURER;
        String brand = android.os.Build.BRAND;
        if (manufacturer == null && brand == null) return false;
        boolean isHonorBrand = "HONOR".equalsIgnoreCase(manufacturer) || "HONOR".equalsIgnoreCase(brand);
        if (!isHonorBrand) return false;

        int sdk = android.os.Build.VERSION.SDK_INT;            // Android 10 == 29
        int magic = getMagicUiMajor();                         // e.g., 4 for Magic UI 4.x
        int emui = getEmuiMajor();                             // often 11 on Magic UI 4
        boolean onAndroid10 = (sdk == 29);
        boolean magicOrEmuiNew = (magic >= 4) || (emui >= 11);
        return onAndroid10 && magicOrEmuiNew;
    }

    /**
     * Determines whether the current device is a Huawei device with specific quirks.
     * It checks if the device manufacturer is Huawei, verifies the model against known Huawei
     * device families (P30 and P40 series), and considers the EMUI version for certain conditions.
     *
     * @return true if the device is a Huawei P30/P40 series device with EMUI version 10 or above,
     * or if the EMUI version cannot be determined; false otherwise.
     */
    private boolean isHuaweiQuirkDevice() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        if (manufacturer == null || model == null) return false;
        if (!manufacturer.equalsIgnoreCase("HUAWEI")) return false;

        String m = model.toUpperCase(Locale.ROOT);
        boolean isP30Fam = m.contains("ELE-") || m.contains("VOG-");               // P30 / P30 Pro
        boolean isP40Fam = m.contains("ANA-") || m.contains("ELS-") || m.contains("JNY-"); // P40 / Pro / Lite
        if (!(isP30Fam || isP40Fam)) return false;

        int emui = getEmuiMajorLoose();
        // Conservative: if we cannot determine the version (-1), prefer to fall back
        return (emui == -1) || (emui >= 10);
    }

    // Redmi/Xiaomi torch darkening quirk detection (Android 15 / HyperOS variants too)
    private boolean isXiaomiTorchQuirkDevice() {
        try {
            String manufacturer = android.os.Build.MANUFACTURER;
            String model = android.os.Build.MODEL;
            if (manufacturer == null && model == null) return false;
            String man = manufacturer != null ? manufacturer.trim().toUpperCase(Locale.ROOT) : "";
            String mod = model != null ? model.trim().toUpperCase(Locale.ROOT) : "";
            boolean isXiaomi = man.contains("XIAOMI") || man.contains("REDMI") || man.contains("POCO");
            boolean mentionsRedmi = mod.contains("REDMI") || mod.contains("POCO");
            return isXiaomi || mentionsRedmi;
        } catch (Throwable t) {
            return false;
        }
    }

    private void applyTorchExposureWorkaround(boolean torchOn) {
        if (!isXiaomiTorchQuirk) return;
        if (camera == null) return;
        try {
            ExposureState es = camera.getCameraInfo().getExposureState();
            if (es == null) return;
            android.util.Range<Integer> range = es.getExposureCompensationRange();
            if (range == null) return;

            if (torchOn) {
                // Save current value once
                try {
                    previousExposureCompIndex = es.getExposureCompensationIndex();
                } catch (Throwable ignore) {
                    // default 0
                }
                int desired = 4; // +4 tends to be ~1.0-1.3 EV on many devices
                int maxAllowed = range.getUpper() != null ? range.getUpper() : desired;
                int minAllowed = range.getLower() != null ? range.getLower() : -desired;
                int target = Math.max(minAllowed, Math.min(maxAllowed, desired));
                camera.getCameraControl().setExposureCompensationIndex(target);
                exposureCompApplied = true;
                Log.i(TAG, "Applied Xiaomi torch exposure workaround: EC=" + target + " (range=" + range + ")");
            } else {
                if (exposureCompApplied) {
                    int restore = previousExposureCompIndex;
                    camera.getCameraControl().setExposureCompensationIndex(restore);
                    exposureCompApplied = false;
                    Log.i(TAG, "Restored exposure compensation index to " + restore);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyTorchExposureWorkaround failed: " + t.getMessage());
        }
    }

    private Preview preview; // make field to update rotation later

    private boolean streamObserverAttached = false;

    private boolean alreadyReboundCompatibleOnce = false;

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

        if (binding != null && binding.cornerOverlay != null) {
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

    /**
     * Binds the camera use cases to the lifecycle and configures the preview and image capture
     * settings. Includes setup for target aspect ratio, rotation and FPS range adjustments using
     * Camera2 interop. Handles fallback to compatible preview mode if needed.
     *
     * @param forceCompatiblePreview Flag to force the use of compatible preview mode. If true, the
     *                               viewfinder's implementation mode is set to compatible; otherwise,
     *                               it is set to performance. This parameter is used during binding
     *                               retries when initial binding fails.
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindUseCases(boolean forceCompatiblePreview) {
        if (binding == null || cameraProvider == null || !isAdded()) return;

        if (!forceCompatiblePreview) alreadyReboundCompatibleOnce = false;

        // 1) Set ImplementationMode before SurfaceProvider
        if (forceCompatiblePreview) {
            binding.viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        } else {
            binding.viewFinder.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        }
        binding.viewFinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        int rotation = getViewFinderRotation();

        // Only run the PREVIEW conservatively; capture stays high-res
        boolean useConservativePreview = forceCompatiblePreview
                || isPreviewBlackScreenQuirkDevice()
                || isXperia1VI()
                || isXperia5IV()
                || isSonyAndroid15Plus();

        // 2) Set up the ResolutionSelector
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder rsBuilderPreview =
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                new androidx.camera.core.resolutionselector.AspectRatioStrategy(
                                        AspectRatio.RATIO_4_3,
                                        androidx.camera.core.resolutionselector.AspectRatioStrategy.FALLBACK_RULE_AUTO
                                )
                        );

        androidx.camera.core.resolutionselector.ResolutionSelector.Builder rsBuilderCapture =
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                new androidx.camera.core.resolutionselector.AspectRatioStrategy(
                                        AspectRatio.RATIO_4_3,
                                        androidx.camera.core.resolutionselector.AspectRatioStrategy.FALLBACK_RULE_AUTO
                                )
                        );

        // Preview in conservative mode (e.g. 1440x1080), capture prefers high-res
        if (useConservativePreview) {
            android.util.Size preferredPreview = new android.util.Size(1440, 1080);
            rsBuilderPreview.setResolutionStrategy(
                    new androidx.camera.core.resolutionselector.ResolutionStrategy(
                            preferredPreview,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
            );
        }
        // Prefer high-res capture (e.g. 4032x3024)
        android.util.Size preferredHigh = new android.util.Size(4032, 3024);
        rsBuilderCapture.setResolutionStrategy(
                new androidx.camera.core.resolutionselector.ResolutionStrategy(
                        preferredHigh,
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
        );

        androidx.camera.core.resolutionselector.ResolutionSelector previewSelector = rsBuilderPreview.build();
        androidx.camera.core.resolutionselector.ResolutionSelector captureSelector = rsBuilderCapture.build();

        Preview.Builder previewBuilder = new Preview.Builder()
                .setResolutionSelector(previewSelector)
                .setTargetRotation(rotation);

        ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(captureSelector)
                .setTargetRotation(rotation)
                .setJpegQuality(98);

        // 3) Camera2 interop: conservative FPS + high-quality pipelines
        androidx.camera.camera2.interop.Camera2Interop.Extender<Preview> pExt =
                new androidx.camera.camera2.interop.Camera2Interop.Extender<>(previewBuilder);
        androidx.camera.camera2.interop.Camera2Interop.Extender<ImageCapture> cExt =
                new androidx.camera.camera2.interop.Camera2Interop.Extender<>(captureBuilder);

        android.util.Range<Integer> fps = new android.util.Range<>(15, 30);
        pExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);

        pExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.EDGE_MODE,
                android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SHADING_MODE,
                android.hardware.camera2.CaptureRequest.SHADING_MODE_HIGH_QUALITY);

        imageCapture = captureBuilder.build();
        preview = previewBuilder.build();

        // 4) ImageAnalysis for live document overlay
        ImageAnalysis.Builder iaBuilder = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation);
        imageAnalysis = iaBuilder.build();
        // Only enable if turned on
        if (analysisEnabled) {
            ensureAnalysisExecutor();
            imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrameForCorners);
            if (binding.cornerOverlay != null) {
                binding.cornerOverlay.setVisibility(View.VISIBLE);
                binding.cornerOverlay.setCorners(null);
            }
        } else {
            try {
                imageAnalysis.clearAnalyzer();
            } catch (Throwable ignore) {
            }
            if (binding.cornerOverlay != null) {
                binding.cornerOverlay.setCorners(null);
                binding.cornerOverlay.setVisibility(View.GONE);
            }
        }

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture, imageAnalysis);

            final boolean implCompatible = forceCompatiblePreview;
            final boolean quirkActive = shouldForceCompatiblePreview();
            final boolean conservativeRes = useConservativePreview;
            logStartupInfo(implCompatible, quirkActive, conservativeRes, fps);

            // Log SurfaceRequest and then forward it to the PreviewView
            Preview.SurfaceProvider vfProvider = binding.viewFinder.getSurfaceProvider();
            preview.setSurfaceProvider(
                    ContextCompat.getMainExecutor(requireContext()),
                    request -> {
                        android.util.Size s = request.getResolution();
                        Log.i(TAG, "Preview SurfaceRequest resolution: " + s.getWidth() + "x" + s.getHeight());
                        vfProvider.onSurfaceRequested(request);
                    }
            );

            // Re-apply torch exposure workaround after (re)bind if torch is currently on
            if (isFlashlightOn) {
                applyTorchExposureWorkaround(true);
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "bindToLifecycle failed: " + e.getMessage(), e);
            // Fallback: rebind once in COMPATIBLE mode
            if (!forceCompatiblePreview) {
                bindUseCases(true);
                return;
            }
            throw e;
        }

        // 5) Orientation listener keeps preview and capture in sync
        if (orientationListener == null) {
            orientationListener = new OrientationEventListener(requireContext()) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (!isAdded() || imageCapture == null || binding == null) return;
                    int rot = getViewFinderRotation();
                    try {
                        imageCapture.setTargetRotation(rot);
                        if (preview != null) preview.setTargetRotation(rot);
                    } catch (Exception ignored) {
                    }
                }
            };
            orientationListener.enable();
        }


        // 6) Watchdog: after 3s without STREAMING → rebind in COMPATIBLE mode
        if (!streamObserverAttached) {
            binding.viewFinder.getPreviewStreamState()
                    .observe(getViewLifecycleOwner(), state -> Log.d(TAG, "Preview stream state: " + state));
            streamObserverAttached = true;
        }

        binding.viewFinder.postDelayed(() -> {
            if (!isAdded() || binding == null) return;
            PreviewView.StreamState st = binding.viewFinder.getPreviewStreamState().getValue();
            boolean streaming = (st == PreviewView.StreamState.STREAMING);
            if (!streaming) {
                if (!alreadyReboundCompatibleOnce) {
                    Log.w(TAG, "Preview watchdog: not STREAMING → rebinding in COMPATIBLE mode");
                    alreadyReboundCompatibleOnce = true;
                    bindUseCases(true);
                } else {
                    Log.e(TAG, "Still not STREAMING after COMPATIBLE rebind – please collect logs");
                    UIUtils.showToast(requireContext(), R.string.error_camera_preview_failed, Toast.LENGTH_LONG);
                }
            }
        }, 3000);
    }


    /**
     * Initializes the camera for the fragment with proper configuration and bindings.
     * This method ensures that necessary prerequisites (like binding and fragment attachment)
     * are satisfied before proceeding. It retrieves an instance of the {@link ProcessCameraProvider},
     * binds required use cases, and configures the flash functionality if available.
     * <br>
     * If the initialization is successful, the camera is set up and ready to scan. Otherwise,
     * appropriate error messages or warnings are displayed to the user.
     * <br>
     * The behavior of this method includes:
     * - Setting up camera use cases and verifying the availability of a flash unit.
     * - Displaying appropriate UI updates during initialization and completion.
     * - Handling various errors and exceptions during the camera setup process.
     * <br>
     * The execution relies on asynchronous callbacks provided by the {@link ListenableFuture}
     * to properly manage the camera provider setup.
     * <p>
     * Preconditions:
     * - The method checks if `binding` is null or if the fragment is not currently attached. If either condition is true, it skips the initialization process.
     * <p>
     * Error Handling:
     * - If the camera provider is unavailable or null, the user is notified via a toast message and an appropriate status message is displayed in the UI.
     * - If any unexpected errors occur during initialization, they are handled gracefully by calling the {@code handleCameraInitializationError(Exception e)} method.
     * <p>
     * Threading:
     * - Uses {@link ContextCompat#getMainExecutor} for executing asynchronous callback tasks on the main thread.
     * <p>
     * Flashlight:
     * - Checks for the presence of a flash unit in the camera. If available, updates the flash button visibility and its associated state.
     */
    private void initializeCamera() {
        if (binding == null || !isAdded()) {
            Log.d(TAG, "initializeCamera: Skipping - binding is null or fragment not attached");
            return;
        }
        binding.textCamera.setText(R.string.initializing_camera);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(requireContext());

        final boolean forceCompatible = shouldForceCompatiblePreview();

        cameraProviderFuture.addListener(() -> {
            try {
                if (binding == null || !isAdded() || getView() == null) return;
                cameraProvider = cameraProviderFuture.get();
                if (cameraProvider == null) {
                    UIUtils.showToast(requireContext(), R.string.error_camera_provider_null, Toast.LENGTH_SHORT);
                    binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
                    return;
                }
                bindUseCases(forceCompatible);
                hasFlash = camera.getCameraInfo().hasFlashUnit();
                if (binding.buttonFlash != null) {
                    binding.buttonFlash.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
                    isFlashlightOn = false;
                    binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
                }
                binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

                binding.viewFinder.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                        MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                        MeteringPoint pt = mpf.createPoint(event.getX(), event.getY());
                        FocusMeteringAction action = new FocusMeteringAction.Builder(
                                pt,
                                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();

                        camera.getCameraControl().startFocusAndMetering(action);

                        // Important for accessibility:
                        v.performClick();
                        return true;   // consume only the tap
                    }
                    return false;      // do not block other gestures
                });

                binding.viewFinder.setOnClickListener(v -> {
                    // optional: centered AF/AE, identical to your pre-focus

                    try {
                        MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                        MeteringPoint c = mpf.createPoint(binding.viewFinder.getWidth() / 2f, binding.viewFinder.getHeight() / 2f);
                        FocusMeteringAction a = new FocusMeteringAction.Builder(c,
                                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                        camera.getCameraControl().startFocusAndMetering(a);
                    } catch (Exception ignored) {
                    }
                });

                // Forward taps on the overlay to focus too (overlay sits above PreviewView)
                if (binding.cornerOverlay != null) {
                    binding.cornerOverlay.setOnTouchListener((ov, ev) -> {
                        if (ev.getAction() == MotionEvent.ACTION_UP && camera != null) {
                            try {
                                MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                                // Overlay shares the same bounds and FIT_CENTER as the PreviewView
                                MeteringPoint pt = mpf.createPoint(ev.getX(), ev.getY());
                                FocusMeteringAction action = new FocusMeteringAction.Builder(
                                        pt,
                                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
                                ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                                camera.getCameraControl().startFocusAndMetering(action);
                                ov.performClick();
                                return true;
                            } catch (Throwable ignored) {
                            }
                        }
                        return false;
                    });
                }

            } catch (Exception e) {
                handleCameraInitializationError(e);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    /**
     * Handles errors that occur during the initialization of the camera.
     * This method provides appropriate user feedback and retries the initialization process
     * after a short delay if conditions permit.
     *
     * @param e The exception that was thrown during camera initialization.
     */
    private void handleCameraInitializationError(Exception e) {
        if (!isAdded() || binding == null) return;

        Log.e(TAG, "Camera initialization error: " + e.getMessage());
        UIUtils.showToast(requireContext(), getString(R.string.error_initializing_camera, e.getMessage()), Toast.LENGTH_LONG);
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

        if (!reinitScheduled) { // (5) avoid multiple queueing
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

    /**
     * Captures an image using the camera
     */
    private void captureImage() {
        if (!isAdded() || binding == null) {
            Log.d(TAG, "captureImage: Skipping - fragment not attached or binding is null");
            return;
        }

        if (imageCapture == null) {
            Log.e(TAG, "captureImage: Camera not initialized");
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
            } else {
                // Internal fallback: /data/data/<pkg>/files/Pictures/MakeACopy
                File picturesInInternal = new File(requireContext().getFilesDir(), "Pictures");
                //noinspection ResultOfMethodCallIgnored
                picturesInInternal.mkdirs();
                outputDir = new File(picturesInInternal, "MakeACopy");
            }
            if (!outputDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputDir.mkdirs();
            }

            // Create file with timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis());
            File photoFile = new File(outputDir, "MakeACopy_" + timestamp + ".jpg");

            ImageCapture.OutputFileOptions outputOptions =
                    new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            Log.d(TAG, "Taking picture...");

            // Pre-focus centered (AF/AE), then trigger capture
            MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
            MeteringPoint center = mpf.createPoint(
                    binding.viewFinder.getWidth() / 2f,
                    binding.viewFinder.getHeight() / 2f
            );

            FocusMeteringAction fma = new FocusMeteringAction.Builder(
                    center,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();

            com.google.common.util.concurrent.ListenableFuture<FocusMeteringResult> fut =
                    camera.getCameraControl().startFocusAndMetering(fma);

            fut.addListener(() -> {
                try {
                    FocusMeteringResult result = fut.get(); // does not block because the listener is only invoked after completion
                    if (result != null && result.isFocusSuccessful()) {
                        binding.viewFinder.postDelayed(() -> doTakePicture(outputOptions, photoFile), 150);
                    } else {
                        doTakePicture(outputOptions, photoFile);
                    }
                } catch (Exception e) {
                    doTakePicture(outputOptions, photoFile);
                }
            }, ContextCompat.getMainExecutor(requireContext()));

        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in captureImage: " + e.getMessage(), e);
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
                        Log.d(TAG, "Image saved to: " + photoFile.getAbsolutePath() + ", size=" + photoFile.length());

                        // PATCH B: FileProvider-URI robust mit Fallback
                        Uri imageUri;
                        try {
                            imageUri = FileProvider.getUriForFile(
                                    requireContext(),
                                    BuildConfig.APPLICATION_ID + ".fileprovider",
                                    photoFile
                            );
                        } catch (IllegalArgumentException badRoot) {
                            Log.w(TAG, "FileProvider root mismatch, falling back to file:// for in-app use", badRoot);
                            imageUri = Uri.fromFile(photoFile); // Nur intern verwenden, nicht extern teilen
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

                            // Navigate to next step: Crop or OCR depending on user preference
                            try {
                                if (isAdded()) {
                                    // Reset OCR state for a fresh scan before navigating further
                                    try {
                                        OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
                                        ocrVm.resetForNewImage();
                                    } catch (Throwable t) {
                                        // best-effort reset; ignore failures
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

                                    int dest;
                                    if (skipCropping) {
                                        dest = skipOcr ? R.id.navigation_export : R.id.navigation_ocr;
                                    } else {
                                        dest = R.id.navigation_crop;
                                    }
                                    try {
                                        Navigation.findNavController(requireView()).navigate(dest);
                                    } catch (IllegalArgumentException | IllegalStateException ignored) {
                                    }
                                }
                            } catch (Throwable ignored) {
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
            if (binding.buttonScan != null) binding.buttonScan.setEnabled(!processing);
        } catch (Throwable ignored) {
        }
        try {
            if (binding.buttonPickImage != null) binding.buttonPickImage.setEnabled(!processing);
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
        if (binding.scanButtonContainer != null) {
            binding.scanButtonContainer.setVisibility(View.VISIBLE);
        }
        // Live corner preview: respect user preference
        boolean analysisPref = false;
        try {
            android.content.SharedPreferences prefs =
                    requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
            analysisPref = prefs.getBoolean("analysis_enabled", false); // Default AUS
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

    /**
     * Toggles the flashlight functionality of the camera.
     * <p>
     * This method checks the prerequisites such as the availability of the camera,
     * flashlight hardware, and the fragment's attachment to the activity. If the
     * prerequisites are satisfied, it toggles the flashlight state between on and off.
     * The UI is then updated to reflect the flashlight state, and appropriate user feedback
     * is displayed through toast messages.
     * <p>
     * Behavior:
     * - If the camera or flashlight hardware is not available, or the fragment is not added:
     * - Displays a toast message indicating that the flashlight is not available (if the fragment is added).
     * - Exits without making changes.
     * - If the prerequisites are met:
     * - Toggles the `isFlashlightOn` state.
     * - Updates the flashlight state using the camera's `enableTorch()` method.
     * - Updates the flashlight button's image to represent the current state (on/off).
     * - Displays a toast message indicating the flashlight's current state.
     * <p>
     * Exception Handling:
     * - Captures any exceptions that occur during the toggling process.
     * - Logs the error and displays a user-facing message with details of the error, if the fragment is added.
     * <p>
     * Preconditions:
     * - `camera` must not be null.
     * - The `hasFlash` property must be true.
     * - The fragment must be added to its host activity (`isAdded()` returns true).
     * <p>
     * Postconditions:
     * - The flashlight state is toggled, and the UI is updated accordingly if the prerequisites are met.
     * - No changes are made if the prerequisites are not satisfied.
     */
    private void toggleFlashlight() {
        if (camera == null || !hasFlash || !isAdded()) {
            if (isAdded()) {
                UIUtils.showToast(requireContext(), R.string.flashlight_not_available, Toast.LENGTH_SHORT);
            }
            return;
        }

        try {
            isFlashlightOn = !isFlashlightOn;
            camera.getCameraControl().enableTorch(isFlashlightOn);
            // Apply device-specific exposure workaround if needed
            applyTorchExposureWorkaround(isFlashlightOn);
            if (binding != null && binding.buttonFlash != null) {
                binding.buttonFlash.setImageResource(isFlashlightOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
                UIUtils.showToast(requireContext(), isFlashlightOn ? R.string.flashlight_on : R.string.flashlight_off, Toast.LENGTH_SHORT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling flashlight: " + e.getMessage(), e);
            if (isAdded()) {
                UIUtils.showToast(requireContext(), getString(R.string.error_toggling_flashlight, e.getMessage()), Toast.LENGTH_SHORT);
            }
        }
    }

    /**
     * Turns off the flashlight if it is currently enabled. This method ensures that the flashlight
     * state is updated and the corresponding UI elements are adjusted to reflect the off state.
     * <p>
     * Preconditions:
     * - The `camera` object is not null.
     * - The flashlight is currently on (`isFlashlightOn` is true).
     * - The fragment is added to its host activity (`isAdded()` returns true).
     * <p>
     * Behavior:
     * - Disables the flashlight using the `enableTorch(false)` method of the camera's control.
     * - Updates the `isFlashlightOn` state to false.
     * - Changes the flashlight button's icon to indicate the off state if the required UI elements are present.
     * <p>
     * Exception Handling:
     * - Captures any exceptions that occur during the process of turning off the flashlight.
     * - Logs the error with details for debugging purposes.
     * <p>
     * Postconditions:
     * - The flashlight is turned off and the UI is updated accordingly if all preconditions are met.
     * - No changes are made if the preconditions are not satisfied.
     */
    private void turnOffFlashlight() {
        if (camera != null && isFlashlightOn && isAdded()) {
            try {
                camera.getCameraControl().enableTorch(false);
                isFlashlightOn = false;
                // Restore exposure if changed
                applyTorchExposureWorkaround(false);
                if (binding != null && binding.buttonFlash != null) {
                    binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error turning off flashlight: " + e.getMessage(), e);
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
            if (bm != null && !bm.isRecycled()) {
                bm.recycle();
            }
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
            Log.e(TAG, "Error resetting camera: " + e.getMessage());
            if (isAdded()) {
                UIUtils.showToast(requireContext(), getString(R.string.error_resetting_camera, e.getMessage()), Toast.LENGTH_SHORT);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        turnOffFlashlight();
        if (sensorManager != null && lightSensor != null) {
            sensorManager.unregisterListener(this); // (3) defensive
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (orientationListener != null) {
            orientationListener.disable();
        }
        // Stop analysis and release executor
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
                Log.d(TAG, hasLightSensor ? "Light sensor available" : "Light sensor not available on this device");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing light sensor: " + e.getMessage(), e);
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

        long currentTime = System.currentTimeMillis();
        if (lowLightPromptShown || (currentTime - lastPromptTime) < MIN_TIME_BETWEEN_PROMPTS) {
            return;
        }

        try {
            isLowLightDialogVisible = true;
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.low_light_detected)
                    .setPositiveButton(R.string.flashlight_on, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            if (!isFlashlightOn && hasFlash) {
                                toggleFlashlight();
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, id) -> dialogInterface.dismiss())
                    .create();

            dialog.setOnDismissListener(d -> isLowLightDialogVisible = false);

            // Improve dark mode contrast for dialog buttons
            dialog.setOnShowListener(dlg -> de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));

            dialog.show();

            lowLightPromptShown = true;
            lastPromptTime = currentTime;
        } catch (Exception e) {
            Log.e(TAG, "Error showing low light prompt: " + e.getMessage(), e);
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
            float lightLevel = event.values[0];
            Log.d(TAG, "Light level: " + lightLevel + " lux");

            if (lightLevel < LOW_LIGHT_THRESHOLD
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
            Log.d(TAG, "Light sensor accuracy changed: " + accuracy);
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

    @androidx.annotation.VisibleForTesting
    ImageCapture getImageCaptureForTest() {
        return imageCapture;
    }

    // ===== Live document trapezoid preview support =====
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
            if (!analysisEnabled || binding == null || !isAdded()) return;
            long now = System.currentTimeMillis();
            // Throttle to ~5 FPS
            if (now - lastAnalysisTs < 180) return;
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
                    if (binding != null && binding.cornerOverlay != null) binding.cornerOverlay.setCorners(null);
                });
                return;
            }

            // Map bitmap coords to overlay coords (PreviewView with FIT_CENTER)
            android.graphics.PointF[] viewPts = mapToOverlayPoints(pts, bmp.getWidth(), bmp.getHeight());
            runOnUiThreadSafe(() -> {
                if (binding != null && binding.cornerOverlay != null) binding.cornerOverlay.setCorners(viewPts);
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


}