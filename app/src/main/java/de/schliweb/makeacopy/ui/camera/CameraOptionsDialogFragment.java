package de.schliweb.makeacopy.ui.camera;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;

/**
 * A DialogFragment implementation that provides camera-specific options and configurations.
 * It serves as a UI interface for users to select or adjust camera settings before capturing
 * or processing images.
 */
public class CameraOptionsDialogFragment extends DialogFragment {

    public static final String REQUEST_KEY = "camera_options";
    public static final String BUNDLE_SKIP_OCR = "skip_ocr";
    public static final String BUNDLE_ANALYSIS_ENABLED = "analysis_enabled";
    public static final String BUNDLE_SKIP_CROPPING = "skip_cropping";

    public static void show(@NonNull FragmentManager fm) {
        new CameraOptionsDialogFragment().show(fm, "CameraOptionsDialogFragment");
    }

    /**
     * Shares relevant logs and environment information for debugging purposes.
     * Depending on the size of the logs, the data is either shared inline as plain text
     * or written to a temporary file and shared as an attachment through a FileProvider.
     * The logs include application metadata, device details, and the latest process-specific logcat output.
     *
     * @param ctx The Android context used for file operations, creating intents, and accessing resources.
     */
    private void shareLogs(@NonNull Context ctx) {
        try {
            String header = buildEnvHeader(ctx);
            String logs = collectLogcatForThisProcess();
            if (logs == null) logs = "";
            String body = header + "\n\n" + logs;

            // Binder safety thresholds
            final int INLINE_MAX_CHARS = 48 * 1024; // ~48 KiB safe inline payload
            final int FILE_MAX_CHARS = 1024 * 1024; // cap file to ~1 MiB

            if (body.length() <= INLINE_MAX_CHARS) {
                // Share inline as text
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_logs_subject));
                share.putExtra(Intent.EXTRA_TEXT, body);
                startActivity(Intent.createChooser(share, ctx.getString(R.string.share_logs_chooser_title)));
                return;
            }

            // For large payloads write to a temp file in cache and share via FileProvider
            String displayNote = "[saved as attachment due to size]";
            String content = body;
            if (content.length() > FILE_MAX_CHARS) {
                content = content.substring(content.length() - FILE_MAX_CHARS);
                content = header + "\n\n[truncated to last " + (FILE_MAX_CHARS / 1024) + " KB]\n\n" + content;
            }

            java.io.File cacheDir = new java.io.File(ctx.getCacheDir(), "debug");
            if (!cacheDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            }
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                    .format(System.currentTimeMillis());
            java.io.File outFile = new java.io.File(cacheDir, "makeacopy-logs-" + ts + ".txt");
            java.io.FileOutputStream fos = null;
            try {
                fos = new java.io.FileOutputStream(outFile);
                byte[] data = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                fos.write(data);
                fos.flush();
            } finally {
                if (fos != null) try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }

            androidx.core.content.FileProvider.getUriForFile(ctx, BuildConfig.APPLICATION_ID + ".fileprovider", outFile);
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(ctx, BuildConfig.APPLICATION_ID + ".fileprovider", outFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_logs_subject));
            share.putExtra(Intent.EXTRA_TEXT, displayNote);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, ctx.getString(R.string.share_logs_chooser_title)));
        } catch (Throwable t) {
            Toast.makeText(ctx, ctx.getString(R.string.share_logs_error, t.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Builds and returns a string containing detailed environment information for debugging purposes.
     * The data includes application version, device details, system properties, and user preferences.
     *
     * @param ctx The Android context from which system and application-specific information is retrieved.
     * @return A string containing the environment header information, which includes details such as
     * app version, device info, locale, and time. If an error occurs while retrieving this data,
     * a partial or default header is returned instead.
     */
    private String buildEnvHeader(Context ctx) {
        StringBuilder sb = new StringBuilder();

        String versionName;
        long versionCode;
        try {
            android.content.pm.PackageManager pm = ctx.getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageInfo(ctx.getPackageName(), 0);
            versionName = pi.versionName;
            versionCode = pi.getLongVersionCode();
        } catch (Exception e) {
            versionName = "unknown";
            versionCode = -1L;
        }

        try {
            java.util.Locale loc = java.util.Locale.getDefault();
            String abis = Build.SUPPORTED_ABIS != null ? java.util.Arrays.toString(Build.SUPPORTED_ABIS) : "unknown";
            boolean analysisPref = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
                    .getBoolean("analysis_enabled", false);
            sb.append("MakeACopy logs\n");
            sb.append("App: ").append(versionName).append(" (code ").append(versionCode).append(")\n");
            sb.append("SDK: ").append(android.os.Build.VERSION.SDK_INT)
                    .append(" | Brand: ").append(android.os.Build.BRAND)
                    .append(" | Manuf: ").append(android.os.Build.MANUFACTURER)
                    .append(" | Model: ").append(android.os.Build.MODEL)
                    .append(" | Device: ").append(android.os.Build.DEVICE)
                    .append("\n");
            sb.append("Display: ").append(android.os.Build.DISPLAY).append(" | ABIs: ").append(abis).append("\n");
            sb.append("Locale: ").append(loc != null ? loc.toLanguageTag() : "-")
                    .append(" | Analysis enabled: ").append(analysisPref).append("\n");
            sb.append("Time: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US)
                    .format(System.currentTimeMillis())).append("\n");
            sb.append("Process: pid=").append(android.os.Process.myPid()).append(" package=").append(BuildConfig.APPLICATION_ID).append("\n");
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /**
     * Collects and returns the logcat output specific to the current process.
     * This method first attempts to fetch logs using the modern logcat command with PID filtering.
     * If this fails, it falls back to collecting all logs and filtering them by application-specific
     * identifiers and tags.
     *
     * @return A string containing the logcat output for the current process. If no relevant logs
     * are found or an error occurs, an empty string is returned.
     */
    private String collectLogcatForThisProcess() {
        StringBuilder out = new StringBuilder();
        java.io.BufferedReader reader = null;
        try {
            int pid = android.os.Process.myPid();
            // Prefer modern logcat with --pid support
            String[] cmd = new String[]{"logcat", "-d", "--pid", String.valueOf(pid), "-v", "time"};
            try {
                Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
                proc.waitFor();
                if (out.length() > 0) return out.toString();
            } catch (Throwable ignore) {
            }
            // Fallback: dump all and filter by app id/tag if possible
            out.setLength(0);
            Process proc2 = new ProcessBuilder("logcat", "-d", "-v", "time").redirectErrorStream(true).start();
            reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc2.getInputStream()));
            String line2;
            String appId = BuildConfig.APPLICATION_ID;
            while ((line2 = reader.readLine()) != null) {
                if (line2.contains(appId) || line2.contains("CameraFragment") || line2.contains("MakeACopy")) {
                    out.append(line2).append('\n');
                }
            }
            proc2.waitFor();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (reader != null) reader.close();
            } catch (Throwable ignored2) {
            }
        }
        return out.toString();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        View view = inflater.inflate(R.layout.dialog_camera_options, null);

        CheckBox cbSkip = view.findViewById(R.id.dialog_checkbox_skip_ocr);
        CheckBox cbSkipCropping = view.findViewById(R.id.dialog_checkbox_skip_cropping);
        CheckBox cbAnalysis = view.findViewById(R.id.dialog_checkbox_analysis_enabled);

        SharedPreferences prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
        boolean skipOcr = prefs.getBoolean("skip_ocr", false);
        boolean skipPerspective = prefs.getBoolean("skip_cropping", false);
        boolean analysisEnabled = prefs.getBoolean("analysis_enabled", false);
        cbSkip.setChecked(skipOcr);
        if (cbSkipCropping != null) cbSkipCropping.setChecked(skipPerspective);
        if (cbAnalysis != null) cbAnalysis.setChecked(analysisEnabled);

        // Wire up the Share Logs button placed under the options
        View shareBtn = view.findViewById(R.id.button_share_logs);
        if (shareBtn != null) {
            shareBtn.setOnClickListener(v -> shareLogs(ctx));
        }

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.btn_options)
                .setView(view)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    boolean skip = cbSkip.isChecked();
                    boolean skipCropping = cbSkipCropping != null && cbSkipCropping.isChecked();
                    boolean analysis = cbAnalysis != null && cbAnalysis.isChecked();

                    // Persist and keep legacy/new flags in sync
                    prefs.edit()
                            .putBoolean("skip_ocr", skip)
                            .putBoolean("include_ocr", !skip)
                            .putBoolean("skip_cropping", skipCropping)
                            .putBoolean("analysis_enabled", analysis)
                            .apply();

                    Bundle result = new Bundle();
                    result.putBoolean(BUNDLE_SKIP_OCR, skip);
                    result.putBoolean(BUNDLE_SKIP_CROPPING, skipCropping);
                    result.putBoolean(BUNDLE_ANALYSIS_ENABLED, analysis);
                    getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
                })
                .create();

        // Improve button contrast for night mode if utility exists (best-effort)
        dialog.setOnShowListener(dlg -> {
            try {
                de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, ctx);
            } catch (Throwable ignore) {
            }
        });

        return dialog;
    }
}
