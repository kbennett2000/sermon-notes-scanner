package de.schliweb.makeacopy.utils.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import de.schliweb.makeacopy.R;
import lombok.experimental.UtilityClass;

/** Small dialog-related helpers to reduce UI code duplication. */
@UtilityClass
public final class DialogUtils {

  /**
   * In dark mode, some AlertDialog button colors can be low contrast depending on theme. This
   * method adjusts button text colors to white to improve readability. Safe to call on dialog's
   * onShow.
   */
  public static void improveAlertDialogButtonContrastForNight(
      androidx.appcompat.app.AlertDialog dialog, Context ctx) {
    if (dialog == null || ctx == null) return;
    try {
      int nightModeFlags =
          ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
      if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
        try {
          int white = androidx.core.content.ContextCompat.getColor(ctx, android.R.color.white);
          if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
            dialog
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(white);
          }
          if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog
                .getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(white);
          }
          if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(white);
          }
        } catch (Exception ignored) {
          // Best-effort; failure is non-critical
        }
      }
    } catch (Throwable ignored) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Shows the cleanup settings dialog for completed scans. Reads and writes policy configuration
   * from/to SharedPreferences ({@code cache_cleanup_prefs}).
   *
   * @param ctx the context (typically from a Fragment's {@code requireContext()})
   */
  public static void showCleanupSettingsDialog(Context ctx) {
    if (ctx == null) return;
    SharedPreferences prefs = ctx.getSharedPreferences("cache_cleanup_prefs", Context.MODE_PRIVATE);

    String[] policyKeys = {"NONE", "MAX_AGE", "MAX_COUNT", "MAX_STORAGE", "COMBINED"};
    String[] policyLabels = {
      ctx.getString(R.string.cleanup_policy_none),
      ctx.getString(R.string.cleanup_policy_max_age),
      ctx.getString(R.string.cleanup_policy_max_count),
      ctx.getString(R.string.cleanup_policy_max_storage),
      ctx.getString(R.string.cleanup_policy_combined)
    };

    String currentPolicy = prefs.getString("completed_scans_cleanup_policy", "NONE");
    int currentIdx = 0;
    for (int i = 0; i < policyKeys.length; i++) {
      if (policyKeys[i].equals(currentPolicy)) {
        currentIdx = i;
        break;
      }
    }

    LinearLayout layout = new LinearLayout(ctx);
    layout.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
    layout.setPadding(pad, pad, pad, pad);

    TextView labelPolicy = new TextView(ctx);
    labelPolicy.setText(R.string.cleanup_policy);
    layout.addView(labelPolicy);

    Spinner spinner = new Spinner(ctx);
    ArrayAdapter<String> spinnerAdapter =
        new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_item, policyLabels);
    spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    spinner.setAdapter(spinnerAdapter);
    spinner.setSelection(currentIdx);
    layout.addView(spinner);

    TextView labelAge = new TextView(ctx);
    labelAge.setText(R.string.cleanup_max_age_days);
    layout.addView(labelAge);
    EditText inputAge = new EditText(ctx);
    inputAge.setInputType(InputType.TYPE_CLASS_NUMBER);
    inputAge.setText(String.valueOf(prefs.getInt("completed_scans_max_age_days", 30)));
    layout.addView(inputAge);

    TextView labelCount = new TextView(ctx);
    labelCount.setText(R.string.cleanup_max_count);
    layout.addView(labelCount);
    EditText inputCount = new EditText(ctx);
    inputCount.setInputType(InputType.TYPE_CLASS_NUMBER);
    inputCount.setText(String.valueOf(prefs.getInt("completed_scans_max_count", 100)));
    layout.addView(inputCount);

    TextView labelStorage = new TextView(ctx);
    labelStorage.setText(R.string.cleanup_max_storage_mb);
    layout.addView(labelStorage);
    EditText inputStorage = new EditText(ctx);
    inputStorage.setInputType(InputType.TYPE_CLASS_NUMBER);
    inputStorage.setText(String.valueOf(prefs.getInt("completed_scans_max_storage_mb", 500)));
    layout.addView(inputStorage);

    final androidx.appcompat.app.AlertDialog dialog =
        new androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.scan_cleanup_settings)
            .setView(layout)
            .setPositiveButton(
                R.string.ok,
                (d, w) -> {
                  int selectedIdx = spinner.getSelectedItemPosition();
                  String selectedPolicy = policyKeys[selectedIdx];
                  SharedPreferences.Editor editor = prefs.edit();
                  editor.putString("completed_scans_cleanup_policy", selectedPolicy);
                  try {
                    editor.putInt(
                        "completed_scans_max_age_days",
                        Integer.parseInt(inputAge.getText().toString().trim()));
                  } catch (NumberFormatException ignore) {
                    // keep existing
                  }
                  try {
                    editor.putInt(
                        "completed_scans_max_count",
                        Integer.parseInt(inputCount.getText().toString().trim()));
                  } catch (NumberFormatException ignore) {
                    // keep existing
                  }
                  try {
                    editor.putInt(
                        "completed_scans_max_storage_mb",
                        Integer.parseInt(inputStorage.getText().toString().trim()));
                  } catch (NumberFormatException ignore) {
                    // keep existing
                  }
                  editor.apply();
                  UIUtils.showToast(ctx, R.string.cleanup_settings_saved, Toast.LENGTH_SHORT);
                })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    dialog.setOnShowListener(
        d -> {
          try {
            improveAlertDialogButtonContrastForNight(dialog, ctx);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
        });
    dialog.show();
  }
}
