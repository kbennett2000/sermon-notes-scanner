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
 * A dialog fragment that provides options for configuring camera settings such as OCR skipping,
 * cropping, and analysis enabling. This fragment saves user choices persistently and communicates
 * results back to the parent fragment.
 * <p>
 * Constants:
 * - REQUEST_KEY: The key used for returning results via FragmentResult API.
 * - BUNDLE_SKIP_OCR: The bundle key representing whether OCR skipping is enabled.
 * - BUNDLE_ANALYSIS_ENABLED: The bundle key representing the state of analysis enablement.
 * - BUNDLE_SKIP_CROPPING: The bundle key representing whether cropping is skipped.
 * <p>
 * Methods:
 * - show(FragmentManager fm): Static utility method to display the dialog fragment.
 * - onCreateDialog(Bundle savedInstanceState): Inflates and initializes the dialog's UI, attaches
 * functionality for saving choices, and improves button appearance for night mode, if applicable.
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
     * Prepares and shares application logs via an intent. This method collects logging information,
     * adds environment details, ensures the content size is within transaction limits, and invokes
     * a share intent to allow the user to share the generated log information.
     *
     * @param ctx The context used to access resources, build environment details, display messages,
     *            and start the sharing intent.
     */
    private void shareLogs(@NonNull Context ctx) {
        try {
            String header = buildEnvHeader(ctx);
            String logs = collectLogcatForThisProcess();
            if (logs == null) logs = "";
            String body = header + "\n\n" + logs;
            // Safety: cap to ~200 KB to avoid intent transaction limits
            final int MAX_LEN = 200 * 1024;
            if (body.length() > MAX_LEN) {
                body = body.substring(body.length() - MAX_LEN);
                header = header + "\n\n[truncated to last " + (MAX_LEN / 1024) + " KB]";
                body = header + "\n\n" + body;
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.share_logs_subject));
            share.putExtra(Intent.EXTRA_TEXT, body);
            startActivity(Intent.createChooser(share, ctx.getString(R.string.share_logs_chooser_title)));
        } catch (Throwable t) {
            Toast.makeText(ctx, ctx.getString(R.string.share_logs_error, t.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Constructs and returns a header string containing detailed environment and application information.
     * The header includes app version, device details, locale, current time, and other relevant data.
     *
     * @param ctx The Android context used to fetch shared preferences or system-related configurations.
     * @return A string representing the environment header with application and device metadata.
     */
    private String buildEnvHeader(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            String versionName = BuildConfig.VERSION_NAME;
            int versionCode = BuildConfig.VERSION_CODE;
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
