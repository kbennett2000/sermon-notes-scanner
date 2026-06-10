/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentExportBinding;
import de.schliweb.makeacopy.ui.camera.CameraViewModel;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel;
import de.schliweb.makeacopy.utils.export.*;
import de.schliweb.makeacopy.utils.image.*;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.infra.SessionIds;
import de.schliweb.makeacopy.utils.ocr.*;
import de.schliweb.makeacopy.utils.ui.A11yUtils;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import de.schliweb.makeacopy.utils.ui.ViewSizeUtils;
import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;

/**
 * ExportFragment is the multi-page <em>page hub</em> and temporary terminus screen. It manages the
 * captured pages (filmstrip add/reorder/remove), renders a preview of the active page, surfaces the
 * per-page OCR indicator, and persists newly added pages into the {@link
 * de.schliweb.makeacopy.data.CompletedScansRegistry} via {@link ScanPersister}.
 *
 * <p>All export OUTPUT (PDF / JPEG / ZIP / TXT / share / inbox) was removed in slice F1c-2: this
 * fork emits a songbird import JSON from a dedicated edit screen (F4–F6), not document files. The
 * recognized OCR text itself is shown on the OCR / review screen, not here.
 */
@AndroidEntryPoint
public class ExportFragment extends Fragment {

  @Inject javax.inject.Provider<OCRHelper> ocrHelperProvider;
  private static final String TAG = "ExportFragment";
  // Main thread handler for safe UI updates
  private final android.os.Handler mainHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  // Track last pages count to avoid repetitive announcements
  private int lastPagesCount = -1;
  private FragmentExportBinding binding;
  private ExportViewModel exportViewModel;
  private CropViewModel cropViewModel;
  private OCRViewModel ocrViewModel;
  private CameraViewModel cameraViewModel;
  // Multipage session (v1 increment)
  private de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel exportSessionViewModel;
  private int activeSessionPageIndex = -1;
  private Bitmap lastFreshMultipagePreviewBitmap;
  private String lastFreshMultipagePageId;
  private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
  private int previewRenderGeneration = 0;

  /**
   * A BroadcastReceiver to handle updates from OCR processing jobs. This receiver listens for
   * broadcasts containing information about the OCR processing status and updates the session data
   * accordingly.
   *
   * <p>The receiver performs the following tasks: - Extracts the associated page ID and success
   * status from the received Intent. - If the processing was successful, updates the session data
   * using the OCR result. - If the processing failed, displays a user notification indicating the
   * failure.
   *
   * <p>It expects the following extras in the received Intent: - {@link
   * de.schliweb.makeacopy.jobs.OcrBackgroundJobs#EXTRA_PAGE_ID}: A String representing the ID of
   * the page that was processed. This is used to associate the OCR result with the correct session.
   * - {@link de.schliweb.makeacopy.jobs.OcrBackgroundJobs#EXTRA_SUCCESS}: A boolean indicating
   * whether the OCR processing was successful.
   *
   * <p>In case of any exceptions during the session update, a warning message is logged.
   */
  private final android.content.BroadcastReceiver ocrUpdateReceiver =
      new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
          if (intent == null) return;
          String id =
              intent.getStringExtra(de.schliweb.makeacopy.jobs.OcrBackgroundJobs.EXTRA_PAGE_ID);
          boolean success =
              intent.getBooleanExtra(
                  de.schliweb.makeacopy.jobs.OcrBackgroundJobs.EXTRA_SUCCESS, false);
          if (id == null) return;
          if (success) {
            try {
              SessionOcrUpdater.applyOcrResultToSession(
                  requireContext(), exportSessionViewModel, id);
            } catch (Exception e) {
              Log.w(TAG, "Failed to update session after OCR job", e);
            }
          } else {
            UIUtils.showToast(
                requireContext(), getString(R.string.ocr_processing_failed), Toast.LENGTH_SHORT);
          }
        }
      };

  private de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter pagesAdapter;
  private boolean ocrReceiverRegistered = false;

  /**
   * Posts a runnable to the main thread only if the Fragment is still added and the view binding
   * exists. If called on the main thread, runs immediately; otherwise posts to main.
   */
  private void postToUiSafe(@NonNull Runnable action) {
    if (!isAdded() || binding == null) return;
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      action.run();
    } else {
      mainHandler.post(
          () -> {
            if (!isAdded() || binding == null) return;
            action.run();
          });
    }
  }

  /**
   * Renders the preview image for the currently active page, applying the same on-screen preview
   * processing used elsewhere, and updates the edit-crop overlay and OCR badge.
   *
   * @param source The source bitmap image to be processed and displayed in the preview.
   */
  private void renderPreview(Bitmap source) {
    if (binding == null || source == null) return;
    Context ctx = getContext();
    if (ctx == null) return;
    Context appContext = ctx.getApplicationContext();
    final int generation = ++previewRenderGeneration;
    previewExecutor.execute(
        () -> {
          Bitmap out = BitmapUtils.processForPreview(source, appContext);
          mainHandler.post(
              () -> {
                if (!isAdded() || binding == null || generation != previewRenderGeneration) return;
                binding.documentPreview.setImageBitmap(out);
                binding.documentPreview.setVisibility(View.VISIBLE);
                updateEditCropOverlayVisibility();
                updatePreviewOcrBadge();
              });
        });
  }

  /**
   * Updates the OCR badge overlay shown on top of the document preview, mirroring the per-page OCR
   * indicator used in the filmstrip ({@link
   * de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter}).
   *
   * <p>Behavior:
   *
   * <ul>
   *   <li>Hidden when the preview is not visible or no active page can be determined.
   *   <li>Shows {@code [OCR]} on a green background when the active page has an existing OCR text
   *       file on disk.
   *   <li>Shows {@code [⚠]} on an orange background when OCR is missing; tapping the badge then
   *       enqueues a background OCR run for the active page via {@link #runInlineOcrForPage(int)}.
   * </ul>
   */
  /** F4: true when at least one page has a readable OCR text artifact (gates the Continue action). */
  private static boolean hasAnyPageOcr(
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages) {
    if (pages == null) return false;
    for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
      if (s == null) continue;
      String ocrPath = s.ocrTextPath();
      if (ocrPath != null) {
        try {
          File f = new File(ocrPath);
          if (f.exists() && f.isFile()) return true;
        } catch (Throwable ignore) {
          // best-effort
        }
      }
    }
    return false;
  }

  private void updatePreviewOcrBadge() {
    if (binding == null || binding.previewOcrBadge == null) return;
    android.widget.TextView badge = binding.previewOcrBadge;
    try {
      // Hide while no preview image is shown.
      if (binding.documentPreview == null
          || binding.documentPreview.getVisibility() != View.VISIBLE) {
        badge.setVisibility(View.GONE);
        badge.setOnClickListener(null);
        return;
      }
      int idx = findActivePageIndex();
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          (exportSessionViewModel != null) ? exportSessionViewModel.getPages().getValue() : null;
      // Single-page hot workflow without session entry: no badge to show.
      if (idx < 0 || pages == null || idx >= pages.size()) {
        badge.setVisibility(View.GONE);
        badge.setOnClickListener(null);
        return;
      }
      de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(idx);
      if (s == null) {
        badge.setVisibility(View.GONE);
        badge.setOnClickListener(null);
        return;
      }
      String ocrPath = s.ocrTextPath();
      boolean hasOcr = false;
      if (ocrPath != null) {
        try {
          File f = new File(ocrPath);
          hasOcr = f.exists() && f.isFile();
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      if (hasOcr) {
        badge.setText("[OCR]");
        badge.setBackgroundColor(0x8032CD32); // semi green
        badge.setOnClickListener(null);
      } else {
        badge.setText("[⚠]");
        badge.setBackgroundColor(0x80FFA500); // semi orange
        final int pos = idx;
        badge.setOnClickListener(v -> runInlineOcrForPage(pos));
      }
      badge.setVisibility(View.VISIBLE);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
      badge.setVisibility(View.GONE);
      badge.setOnClickListener(null);
    }
  }

  /**
   * Toggles the visibility of the Re-Edit overlay (FR #72) on the document preview.
   *
   * <p>The overlay is shown when:
   *
   * <ul>
   *   <li>the document preview is currently visible,
   *   <li>the {@link CropViewModel} holds the previously accepted trapezoid corners, and
   *   <li>the original image source (path or URI) for the active page is still reachable via {@link
   *       CameraViewModel}.
   * </ul>
   *
   * In V1 this effectively limits Re-Edit to the freshly captured / imported single-page workflow.
   * Multi-page Re-Edit per page is tracked as a follow-up issue.
   */
  private void updateEditCropOverlayVisibility() {
    if (binding == null || binding.buttonEditCrop == null) return;
    boolean show = false;
    try {
      if (cropViewModel != null
          && cameraViewModel != null
          && binding.documentPreview.getVisibility() == View.VISIBLE) {
        boolean hasCorners = cropViewModel.getLastAcceptedCornersOriginal().getValue() != null;
        String path =
            cameraViewModel.getImagePath() != null
                ? cameraViewModel.getImagePath().getValue()
                : null;
        Uri u =
            cameraViewModel.getImageUri() != null ? cameraViewModel.getImageUri().getValue() : null;
        boolean hasOriginal = (path != null && !path.isEmpty()) || u != null;
        show = hasCorners && hasOriginal && isActivePageReEditable();
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    binding.buttonEditCrop.setVisibility(show ? View.VISIBLE : View.GONE);
  }

  /**
   * FR #72 V1: Re-Edit is only possible for the page whose original capture is still tracked by
   * {@link CameraViewModel} — that is effectively the most recently captured/imported page (its
   * {@code inMemoryBitmap} is the very last entry in the session pages list). For any other page we
   * have neither the original source path nor the persisted corners, so editing it would re- crop
   * the wrong source. We therefore only enable Re-Edit when the currently previewed bitmap matches
   * the last entry in the session, or when there is only one page (no filmstrip).
   *
   * <p>FR #72 multi-page (sibling helper {@link #findActivePageIndex()}): the index lookup below
   * locates the active page even when it is not the last one, used by the Re-Edit confirm path to
   * update the correct session slot.
   */

  /**
   * FR #72 multi-page: returns the index in {@code ExportSessionViewModel.pages} whose {@code
   * inMemoryBitmap()} matches the currently previewed bitmap. Returns {@code -1} when unknown (no
   * session, no preview, or no match — e.g. single-page hot workflow with no session entry yet, in
   * which case the legacy "page 0" fallback is appropriate).
   */
  private int findActivePageIndex() {
    try {
      if (exportSessionViewModel == null || exportViewModel == null) return -1;
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          exportSessionViewModel.getPages().getValue();
      if (pages == null || pages.isEmpty()) return -1;
      Bitmap curPreview = exportViewModel.getDocumentBitmap().getValue();
      if (curPreview == null) return -1;
      for (int i = 0; i < pages.size(); i++) {
        de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(i);
        if (s != null && s.inMemoryBitmap() == curPreview) return i;
      }
      return -1;
    } catch (Throwable ignore) {
      return -1;
    }
  }

  private boolean isActivePageReEditable() {
    // FR #72 V1.3: in the single-page workflow, updateEditCropOverlayVisibility() already requires
    // persisted corners and a reachable original source, so bitmap identity must not decide
    // editability. Preview/rotation/filter paths may legitimately create a new Bitmap instance. In
    // multi-page workflows, keep the strict identity check so selecting another filmstrip page
    // hides
    // the Edit overlay.
    try {
      if (exportViewModel == null || cropViewModel == null) return false;
      Bitmap cur = exportViewModel.getDocumentBitmap().getValue();
      if (cur == null) return false;
      int n = 0;
      if (exportSessionViewModel != null) {
        List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
            exportSessionViewModel.getPages().getValue();
        n = (pages == null) ? 0 : pages.size();
      }
      if (n <= 1) return true;
      if (activeSessionPageIndex < 0 || activeSessionPageIndex >= n) return false;
      if (lastFreshMultipagePageId == null) return false;
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          exportSessionViewModel.getPages().getValue();
      if (pages == null || activeSessionPageIndex >= pages.size()) return false;
      de.schliweb.makeacopy.ui.export.session.CompletedScan activePage =
          pages.get(activeSessionPageIndex);
      if (activePage == null || !lastFreshMultipagePageId.equals(activePage.id())) return false;
      Bitmap fresh = cropViewModel.getLastFreshPageBitmap();
      if (fresh != null) return cur == fresh;
      return false;
    } catch (Throwable ignore) {
      return false;
    }
  }

  /**
   * Creates and initializes the view hierarchy associated with this fragment. This method handles
   * view inflation, view model setup, and event listeners for the multi-page page hub.
   *
   * @param inflater The LayoutInflater object that can be used to inflate any views in the
   *     fragment.
   * @param container If non-null, this is the parent view that the fragment's UI should be attached
   *     to. The fragment should not add the view itself, but this can be used to generate the
   *     LayoutParams of the view.
   * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous
   *     saved state as given here.
   * @return The root view of the fragment's layout that has been created and initialized.
   */
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    binding = FragmentExportBinding.inflate(inflater, container, false);
    View root = binding.getRoot();

    Context context = requireContext();

    // ViewModel
    exportViewModel = new ViewModelProvider(this).get(ExportViewModel.class);

    // Back button: navigate to OCR (if not skipping OCR) or Crop (if skipping OCR)
    View backBtn = root.findViewById(R.id.button_back);
    if (backBtn != null) {
      backBtn.setOnClickListener(
          v -> {
            // Delegate to the same back handling as system Back to ensure identical behavior
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
          });
    }

    cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
    ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
    cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

    // Ensure we have a bitmap if arriving here directly (skipping Crop/OCR)
    if (cropViewModel.getImageBitmap().getValue() == null) {
      Context ctxInit = getContext();
      if (ctxInit != null) {
        String path =
            cameraViewModel.getImagePath() != null
                ? cameraViewModel.getImagePath().getValue()
                : null;
        Uri u =
            cameraViewModel.getImageUri() != null ? cameraViewModel.getImageUri().getValue() : null;
        Bitmap bmp = ImageLoader.decode(ctxInit, path, u);
        if (bmp != null) {
          cropViewModel.setImageBitmap(bmp);
        }
      }
    }

    // FR #72 — Edit-Overlay: re-enter CropFragment to adjust the trapezoid for the
    // currently displayed preview page. The overlay is only shown when the original
    // image source for the active page is reachable (single-page hot workflow in V1):
    // a known image path / URI in CameraViewModel and previously persisted corners
    // in CropViewModel.
    if (binding.buttonEditCrop != null) {
      binding.buttonEditCrop.setOnClickListener(
          v -> {
            android.graphics.PointF[] lastCorners =
                cropViewModel.getLastAcceptedCornersOriginal().getValue();
            String path =
                cameraViewModel.getImagePath() != null
                    ? cameraViewModel.getImagePath().getValue()
                    : null;
            Uri origUri =
                cameraViewModel.getImageUri() != null
                    ? cameraViewModel.getImageUri().getValue()
                    : null;
            boolean hasOriginal = (path != null && !path.isEmpty()) || origUri != null;
            if (!hasOriginal || lastCorners == null || !isActivePageReEditable()) {
              UIUtils.showToast(
                  requireContext(),
                  getString(R.string.edit_crop_original_unavailable),
                  Toast.LENGTH_SHORT);
              return;
            }
            // Mark Re-Edit entry so CropFragment can:
            //   - reload the original from disk (the in-memory original was nulled here),
            //   - pre-populate the trapezoid with lastAcceptedCornersOriginal,
            //   - on confirm/back, pop directly back to Export instead of advancing to OCR.
            cropViewModel.setCameFromExport(true);
            cropViewModel.setImageCropped(false);
            // FR #72 multi-page: remember which session page is being re-edited so the
            // confirm path can update the correct page instead of hardcoded index 0.
            cropViewModel.setReEditPageIndex(findActivePageIndex());
            try {
              // FR #72 — use forward navigate (not popBackStack) so the existing Export
              // entry is preserved on the back stack. On confirm/back the Re-Edit flow can
              // then popBackStack(navigation_export, false) and the very same Export
              // instance (with its observers) receives the updated cropped bitmap and
              // re-renders the preview correctly.
              Navigation.findNavController(v).navigate(R.id.navigation_crop);
            } catch (Throwable t) {
              Log.w(TAG, "Re-Edit navigation failed", t);
              cropViewModel.setCameFromExport(false);
              cropViewModel.setReEditPageIndex(-1);
            }
          });
      // Visibility is recomputed whenever the preview is rendered; default hidden.
      updateEditCropOverlayVisibility();
    }

    // Multipage session setup (v1 increment) - use Activity scope so it survives navigation
    exportSessionViewModel =
        new ViewModelProvider(requireActivity())
            .get(de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel.class);
    pagesAdapter =
        new de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter(
            new de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter.Callbacks() {
              @Override
              public void onRemoveClicked(int position) {
                if (!isAdded()) return;
                // Confirm removal to avoid accidental fat-finger deletions (analog to back
                // navigation)
                androidx.appcompat.app.AlertDialog dialog =
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.confirm_remove_page_title))
                        .setMessage(getString(R.string.confirm_remove_page_message))
                        .setPositiveButton(
                            R.string.confirm,
                            (dialogInterface, which) -> {
                              if (exportSessionViewModel == null) return;
                              List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                                  exportSessionViewModel.getPages().getValue();
                              int n = (cur == null) ? 0 : cur.size();
                              if (position < 0 || position >= n) return;
                              exportSessionViewModel.removeAt(position);
                              // A11y: announce removal
                              View v = getView();
                              if (isAdded() && v != null) {
                                A11yUtils.announce(v, getString(R.string.page_removed));
                              }
                            })
                        .setNegativeButton(
                            R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                        .create();
                dialog.setOnShowListener(
                    dlg ->
                        DialogUtils.improveAlertDialogButtonContrastForNight(
                            dialog, requireContext()));
                dialog.show();
              }

              @Override
              public void onRemoveConfirmed(int position) {
                if (!isAdded() || exportSessionViewModel == null) return;
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                    exportSessionViewModel.getPages().getValue();
                int n = (cur == null) ? 0 : cur.size();
                if (position < 0 || position >= n) return;
                exportSessionViewModel.removeAt(position);
                View v = getView();
                if (isAdded() && v != null) {
                  A11yUtils.announce(v, getString(R.string.page_removed));
                }
              }

              @Override
              public void onPageClicked(int position) {
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                    exportSessionViewModel.getPages().getValue();
                if (cur == null || position < 0 || position >= cur.size()) return;
                de.schliweb.makeacopy.ui.export.session.CompletedScan sel = cur.get(position);
                if (sel == null) return;
                activeSessionPageIndex = position;
                int[] sz =
                    ViewSizeUtils.sizeOrDefault(
                        binding != null ? binding.documentPreview : null, 2048, 2048);
                int reqW = sz[0];
                int reqH = sz[1];
                Bitmap bmp = BitmapUtils.loadPreviewBitmapForCompletedScan(sel, reqW, reqH);
                if (bmp != null) {
                  exportViewModel.setDocumentBitmap(bmp);
                  exportViewModel.setDocumentReady(true);
                }
              }

              @Override
              public void onReorder(int fromPosition, int toPosition) {
                if (activeSessionPageIndex == fromPosition) {
                  activeSessionPageIndex = toPosition;
                } else if (fromPosition < activeSessionPageIndex
                    && activeSessionPageIndex <= toPosition) {
                  activeSessionPageIndex--;
                } else if (toPosition <= activeSessionPageIndex
                    && activeSessionPageIndex < fromPosition) {
                  activeSessionPageIndex++;
                }
                exportSessionViewModel.move(fromPosition, toPosition);
                // A11y: announce new position (1-based)
                View rootV = getView();
                if (isAdded() && rootV != null) {
                  A11yUtils.announce(
                      rootV, getString(R.string.page_moved_to_position, toPosition + 1));
                }
              }

              @Override
              public void onOcrRequested(int position) {
                runInlineOcrForPage(position);
              }
            });
    androidx.recyclerview.widget.LinearLayoutManager lm =
        new androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false);
    binding.pagesRecycler.setLayoutManager(lm);
    binding.pagesRecycler.setAdapter(pagesAdapter);

    // F4: proceed to the edit screen. Enabled only when >=1 page has OCR text (gated in the pages
    // observer below).
    binding.buttonContinue.setOnClickListener(
        v -> {
          try {
            Navigation.findNavController(requireView()).navigate(R.id.navigation_edit);
          } catch (IllegalArgumentException | IllegalStateException ignored) {
            // destination unavailable — no-op
          }
        });

    // Enable drag & drop reordering via ItemTouchHelper (horizontal)
    androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback cb =
        new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.LEFT
                | androidx.recyclerview.widget.ItemTouchHelper.RIGHT,
            0) {
          @Override
          public boolean onMove(
              @NonNull androidx.recyclerview.widget.RecyclerView recyclerView,
              @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
              @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            return pagesAdapter.onItemMove(from, to);
          }

          @Override
          public void onSwiped(
              @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
              int direction) {
            // no-op (we don't support swipe to dismiss here)
          }

          @Override
          public boolean isLongPressDragEnabled() {
            // Long-press on the item starts drag
            return true;
          }
        };
    new androidx.recyclerview.widget.ItemTouchHelper(cb)
        .attachToRecyclerView(binding.pagesRecycler);
    // Observe pages to update UI
    exportSessionViewModel
        .getPages()
        .observe(
            getViewLifecycleOwner(),
            pages -> {
              pagesAdapter.submitList(pages);
              int n = (pages == null) ? 0 : pages.size();
              // Show filmstrip only when there are actually more than one page
              binding.pagesContainer.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
              // Show "Clear all" only when more than one page exists
              // Trash icon is always visible; only enabled when there are multiple pages
              binding.buttonClearPages.setVisibility(View.VISIBLE);
              binding.buttonClearPages.setEnabled(n > 1);
              // If current preview points to a removed page, auto-select a remaining one
              Bitmap curPreview = exportViewModel.getDocumentBitmap().getValue();
              boolean found = false;
              if (curPreview != null && pages != null) {
                for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
                  if (s != null && s.inMemoryBitmap() == curPreview) {
                    found = true;
                    break;
                  }
                }
                if (!found
                    && lastFreshMultipagePageId != null
                    && curPreview == lastFreshMultipagePreviewBitmap) {
                  for (int i = 0; i < pages.size(); i++) {
                    de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(i);
                    if (s != null && lastFreshMultipagePageId.equals(s.id())) {
                      activeSessionPageIndex = i;
                      found = true;
                      break;
                    }
                  }
                }
              }
              if (!found && pages != null && !pages.isEmpty()) {
                de.schliweb.makeacopy.ui.export.session.CompletedScan first = pages.get(0);
                if (first != null) {
                  int[] sz =
                      ViewSizeUtils.sizeOrDefault(
                          binding != null ? binding.documentPreview : null, 2048, 2048);
                  int reqW = sz[0];
                  int reqH = sz[1];
                  Bitmap bmp = BitmapUtils.loadPreviewBitmapForCompletedScan(first, reqW, reqH);
                  if (bmp != null) {
                    activeSessionPageIndex = 0;
                    exportViewModel.setDocumentBitmap(bmp);
                    if (n <= 1) {
                      try {
                        cropViewModel.setLastFreshPageBitmap(bmp);
                      } catch (Throwable ignore) {
                        // Best-effort; failure is non-critical
                      }
                    }
                    exportViewModel.setDocumentReady(true);
                  }
                }
              }
              // Accessibility: Announce updated page count when it changes
              if (isAdded() && n != lastPagesCount) {
                lastPagesCount = n;
                View rootView = getView();
                if (rootView != null) {
                  String msg = getString(R.string.pages_count_announcement, n);
                  A11yUtils.announce(rootView, msg);
                }
              }
              // Refresh OCR badge overlay on the preview (mirrors filmstrip badge state).
              updatePreviewOcrBadge();
              // F4: gate "Continue" on at least one page having OCR text.
              binding.buttonContinue.setEnabled(hasAnyPageOcr(pages));
            });
    // Initialize or update pages based on current state and pending add-page flag
    Bitmap initBmp = cropViewModel.getImageBitmap().getValue();
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> currentPages =
        exportSessionViewModel.getPages().getValue();
    int curSize = (currentPages == null) ? 0 : currentPages.size();

    boolean pendingAdd = ExportPrefsHelper.isPendingAddPage(context);
    if (curSize == 0) {
      // First time opening Export in this session: seed with current cropped bitmap if available
      if (initBmp != null) {
        int userDeg = 0;
        Integer vDeg = cropViewModel.getUserRotationDegrees().getValue();
        if (vDeg != null) userDeg = ((vDeg % 360) + 360) % 360;
        de.schliweb.makeacopy.ui.export.session.CompletedScan initial =
            new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                java.util.UUID.randomUUID().toString(),
                null,
                userDeg,
                null,
                null,
                null,
                System.currentTimeMillis(),
                initBmp.getWidth(),
                initBmp.getHeight(),
                initBmp,
                1,
                "metadata");
        // Align the session id used by Review autosave to this export session id
        if (FeatureFlags.isOcrReviewEnabled() && isAdded()) {
          Context c = getContext();
          if (c != null) {
            SessionIds.setCurrentScanId(c.getApplicationContext(), initial.id());
          }
        }
        activeSessionPageIndex = 0;
        exportSessionViewModel.setInitial(initial);
        // FR #72 V1.3: mark this bitmap as the "fresh" page (re-editable). Older pages
        // selected later via the filmstrip will have a different bitmap identity and will
        // therefore not show the Edit overlay.
        try {
          cropViewModel.setLastFreshPageBitmap(initBmp);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        // Persist initial page so it appears in the registry as well
        persistCompletedScanAsync(initial);
      } else {
        exportSessionViewModel.setInitial(null);
      }
    } else if (pendingAdd) {
      // User initiated adding another page and returned here after new capture/crop
      if (initBmp != null) {
        // Avoid adding duplicates if the same bitmap reference is already present
        boolean alreadyPresent = false;
        for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : currentPages) {
          if (s != null && s.inMemoryBitmap() == initBmp) {
            alreadyPresent = true;
            break;
          }
        }
        if (!alreadyPresent) {
          int userDeg = 0;
          Integer v2 = cropViewModel.getUserRotationDegrees().getValue();
          if (v2 != null) userDeg = ((v2 % 360) + 360) % 360;
          de.schliweb.makeacopy.ui.export.session.CompletedScan added =
              new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                  java.util.UUID.randomUUID().toString(),
                  null,
                  userDeg,
                  null,
                  null,
                  null,
                  System.currentTimeMillis(),
                  initBmp.getWidth(),
                  initBmp.getHeight(),
                  initBmp,
                  1,
                  "metadata");
          // Keep SessionIds aligned to the last added page (so Review autosave per-page stays
          // consistent)
          if (FeatureFlags.isOcrReviewEnabled() && isAdded()) {
            Context c2 = getContext();
            if (c2 != null) {
              SessionIds.setCurrentScanId(c2.getApplicationContext(), added.id());
            }
          }
          activeSessionPageIndex = curSize;
          exportSessionViewModel.add(added);
          // FR #72 V1.3: the newly added page is the fresh one (its original capture is still
          // tracked by CameraViewModel). Mark it for the Edit-overlay identity check.
          try {
            cropViewModel.setLastFreshPageBitmap(initBmp);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          // Persist this newly added page into the CompletedScans registry (Insert-Hook)
          persistCompletedScanAsync(added);
        }
      }
      // Clear the flag regardless to prevent re-adding on future opens
      ExportPrefsHelper.clearPendingAddPage(context);
    }
    binding.buttonAddPage.setOnClickListener(
        v -> {
          // Directly open the Completed Scans picker (no dialog)
          ArrayList<String> already = new ArrayList<>();
          List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
              exportSessionViewModel.getPages().getValue();
          if (cur != null) {
            for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : cur) {
              if (s != null && s.id() != null) already.add(s.id());
            }
          }
          Bundle args = new Bundle();
          args.putStringArrayList(
              de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment
                  .ARG_ALREADY_SELECTED_IDS,
              already);
          try {
            Navigation.findNavController(requireView())
                .navigate(R.id.navigation_completed_scans_picker, args);
          } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Best-effort; failure is non-critical
          }
        });
    binding.buttonClearPages.setOnClickListener(
        v -> {
          androidx.appcompat.app.AlertDialog dialog =
              new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                  .setTitle(getString(R.string.confirm_clear_pages_title))
                  .setMessage(getString(R.string.confirm_clear_pages_message))
                  .setPositiveButton(
                      R.string.confirm,
                      (dialogInterface, which) -> {
                        // Reset to initial single page
                        Bitmap bmp = exportViewModel.getDocumentBitmap().getValue();
                        de.schliweb.makeacopy.ui.export.session.CompletedScan one = null;
                        if (bmp != null) {
                          one =
                              new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                  UUID.randomUUID().toString(),
                                  null,
                                  0,
                                  null,
                                  null,
                                  null,
                                  System.currentTimeMillis(),
                                  bmp.getWidth(),
                                  bmp.getHeight(),
                                  bmp,
                                  1,
                                  "metadata");
                        }
                        exportSessionViewModel.setInitial(one);
                      })
                  .setNegativeButton(
                      R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                  .create();
          dialog.setOnShowListener(
              dlg ->
                  DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
          dialog.show();
        });

    // Listen for results from CompletedScansPickerFragment
    getParentFragmentManager()
        .setFragmentResultListener(
            de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment.RESULT_KEY,
            getViewLifecycleOwner(),
            (requestKey, bundle) -> {
              java.util.ArrayList<String> ids =
                  bundle.getStringArrayList(
                      de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment
                          .RESULT_IDS);
              if (ids == null || ids.isEmpty()) return;
              Context ctx2 = getContext();
              if (ctx2 == null) return;
              // Resolve from registry
              java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> all =
                  de.schliweb.makeacopy.data.CompletedScansRegistry.get(ctx2)
                      .listAllOrderedByDateDesc();
              java.util.Map<String, de.schliweb.makeacopy.ui.export.session.CompletedScan> byId =
                  new java.util.HashMap<>();
              for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : all) {
                if (s != null && s.id() != null) byId.put(s.id(), s);
              }
              java.util.ArrayList<de.schliweb.makeacopy.ui.export.session.CompletedScan> picked =
                  new java.util.ArrayList<>();
              for (String id : ids) {
                de.schliweb.makeacopy.ui.export.session.CompletedScan s = byId.get(id);
                if (s != null) picked.add(s);
              }
              if (!picked.isEmpty()) {
                // Sort by creation timestamp ascending to maintain chronological order when adding
                // multiple pages
                picked.sort(
                    java.util.Comparator.comparingLong(
                        de.schliweb.makeacopy.ui.export.session.CompletedScan::createdAt));
                exportSessionViewModel.addAll(picked);
                UIUtils.showToast(
                    requireContext(),
                    getString(R.string.added_pages_from_registry, picked.size()),
                    Toast.LENGTH_SHORT);
              }
            });

    // Back-Handling
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(
            getViewLifecycleOwner(),
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                // If multipage session is active (>1 pages), ask for confirmation to delete all
                // pages
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
                    exportSessionViewModel != null
                        ? exportSessionViewModel.getPages().getValue()
                        : null;
                int n = (pages == null) ? 0 : pages.size();
                if (n > 1) {
                  androidx.appcompat.app.AlertDialog dialog =
                      new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                          .setTitle(getString(R.string.confirm_clear_multipage_title))
                          .setMessage(getString(R.string.confirm_clear_multipage_message))
                          .setPositiveButton(
                              R.string.confirm,
                              (dialogInterface, which) -> {
                                // Clear all pages in the session before leaving
                                if (exportSessionViewModel != null)
                                  exportSessionViewModel.setInitial(null);
                                // Reset camera/crop state and navigate back to camera
                                cameraViewModel.setImageUri(null);
                                cropViewModel.setImageCropped(false);
                                cropViewModel.setImageBitmap(null);
                                cropViewModel.setOriginalImageBitmap(null);
                                cropViewModel.setImageLoaded(false);
                                // Also clear pending add flag to avoid unintended re-adding on next
                                // open
                                ExportPrefsHelper.clearPendingAddPage(requireContext());
                                View fragmentView = getView();
                                if (fragmentView == null) return;
                                NavOptions navOptions =
                                    new NavOptions.Builder()
                                        .setPopUpTo(R.id.navigation_camera, true)
                                        .build();
                                Navigation.findNavController(fragmentView)
                                    .navigate(R.id.navigation_camera, null, navOptions);
                              })
                          .setNegativeButton(
                              R.string.cancel,
                              (dialogInterface, which) -> {
                                dialogInterface.dismiss(); // stay on Export
                              })
                          .create();
                  dialog.setOnShowListener(
                      dlg ->
                          DialogUtils.improveAlertDialogButtonContrastForNight(
                              dialog, requireContext()));
                  dialog.show();
                  return;
                }
                // Default behavior (single/zero page): clear session, reset and navigate back
                if (exportSessionViewModel != null) exportSessionViewModel.setInitial(null);
                cameraViewModel.setImageUri(null);
                cropViewModel.setImageCropped(false);
                cropViewModel.setImageBitmap(null);
                cropViewModel.setOriginalImageBitmap(null);
                cropViewModel.setImageLoaded(false);
                NavOptions navOptions =
                    new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
                Navigation.findNavController(requireView())
                    .navigate(R.id.navigation_camera, null, navOptions);
              }
            });

    binding.buttonAddScan.setOnClickListener(
        v -> {
          Context ctx3 = getContext();
          if (ctx3 != null) {
            ExportPrefsHelper.setPendingAddPage(ctx3);
          }
          cameraViewModel.setImageUri(null);
          cropViewModel.setImageCropped(false);
          cropViewModel.setImageBitmap(null);
          cropViewModel.setOriginalImageBitmap(null);
          cropViewModel.setImageLoaded(false);
          NavOptions navOptions =
              new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
          Navigation.findNavController(requireView())
              .navigate(R.id.navigation_camera, null, navOptions);
        });

    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          UIUtils.adjustTextViewTopMarginForStatusBar(binding.textExport, 8);
          return insets;
        });

    exportViewModel
        .isDocumentReady()
        .observe(
            getViewLifecycleOwner(),
            ready -> {
              binding.textExport.setText(
                  ready
                      ? R.string.document_ready_for_export
                      : R.string.no_document_ready_process_ocr_first);
            });

    exportViewModel
        .getDocumentBitmap()
        .observe(
            getViewLifecycleOwner(),
            bitmap -> {
              if (bitmap != null) {
                markSinglePageBitmapFreshForReEdit(bitmap);
                renderPreview(bitmap);
              } else {
                binding.documentPreview.setVisibility(View.INVISIBLE);
                updatePreviewOcrBadge();
              }
            });

    checkDocumentReady();

    return root;
  }

  private void markSinglePageBitmapFreshForReEdit(Bitmap bitmap) {
    if (bitmap == null || cropViewModel == null || exportSessionViewModel == null) return;
    try {
      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          exportSessionViewModel.getPages().getValue();
      int n = (pages == null) ? 0 : pages.size();
      if (n <= 1) {
        cropViewModel.setLastFreshPageBitmap(bitmap);
        lastFreshMultipagePreviewBitmap = null;
        lastFreshMultipagePageId = null;
        return;
      }

      // FR #72 V1.3: in multi-page mode only the last freshly scanned/imported page is
      // re-editable. Rotation and preview/filter rendering can replace that page's preview bitmap
      // with derived Bitmap instances, so keep the fresh marker moving along that exact preview
      // chain. Do not mark arbitrary filmstrip selections.
      Bitmap fresh = cropViewModel.getLastFreshPageBitmap();
      if (activeSessionPageIndex < 0 || activeSessionPageIndex >= n) return;
      de.schliweb.makeacopy.ui.export.session.CompletedScan activePage =
          pages.get(activeSessionPageIndex);
      Bitmap activePageBitmap = activePage != null ? activePage.inMemoryBitmap() : null;
      boolean activePageIsFresh =
          activePage != null
              && lastFreshMultipagePageId != null
              && lastFreshMultipagePageId.equals(activePage.id());
      if (activePageIsFresh
          && ((fresh != null && fresh == activePageBitmap)
              || (lastFreshMultipagePreviewBitmap != null
                  && fresh == lastFreshMultipagePreviewBitmap))) {
        cropViewModel.setLastFreshPageBitmap(bitmap);
        lastFreshMultipagePreviewBitmap = bitmap;
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Checks and determines whether the document is ready. This method evaluates the current state of
   * the cropped bitmap and OCR text, updates the corresponding fields in the exportViewModel, and
   * sets the document ready status accordingly.
   *
   * <p>- Retrieves the cropped bitmap from the cropViewModel. If it exists, it sets the document
   * bitmap in the exportViewModel. - Extracts the OCR text from the current state. If available, it
   * updates the exportViewModel with the extracted OCR text. - Updates the document ready status in
   * the exportViewModel. The document is considered ready if the document bitmap is not null.
   */
  private void checkDocumentReady() {
    // Only consider the document ready if the image has been cropped (perspective-corrected)
    Boolean isCropped = cropViewModel.isImageCropped().getValue();
    Bitmap maybeBitmap = cropViewModel.getImageBitmap().getValue();

    if (Boolean.TRUE.equals(isCropped) && maybeBitmap != null) {
      Bitmap bmp = maybeBitmap;
      Integer v = cropViewModel.getUserRotationDegrees().getValue();
      int userDeg = v == null ? 0 : ((v % 360) + 360) % 360;

      // Re-Edit produces a fresh preview bitmap before returning to Export. Session/page observers
      // can briefly replace ExportViewModel's current bitmap with another instance, so the stable
      // signal is the explicit fresh Re-Edit marker plus its stored bitmap reference.
      Bitmap freshReEditBitmap = cropViewModel.getLastFreshPageBitmap();
      boolean keepFreshReEditBitmap =
          cropViewModel.isLastFreshPageBitmapFromReEdit() && freshReEditBitmap != null;

      if (keepFreshReEditBitmap) {
        bmp = freshReEditBitmap;
        Log.d(
            TAG,
            "[EXPORT_LOG] checkDocumentReady: keeping fresh Re-Edit bitmap as-is, user rotation="
                + userDeg
                + "°");
      } else if (userDeg != 0) {
        Log.d(
            TAG,
            "[EXPORT_LOG] checkDocumentReady: applying user rotation="
                + userDeg
                + "° to cropped bitmap before preview");
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(userDeg);
        Bitmap rotated =
            android.graphics.Bitmap.createBitmap(
                bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != null) bmp = rotated;
      } else {
        Log.d(
            TAG,
            "[EXPORT_LOG] checkDocumentReady: user rotation is 0°, using cropped bitmap as-is");
      }

      if (!keepFreshReEditBitmap) {
        try {
          cropViewModel.setLastFreshPageBitmap(bmp);
          List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
              exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
          int n = (pages == null) ? 0 : pages.size();
          if (n > 1 && activeSessionPageIndex == n - 1) {
            lastFreshMultipagePreviewBitmap = bmp;
            de.schliweb.makeacopy.ui.export.session.CompletedScan activePage =
                pages != null
                        && activeSessionPageIndex >= 0
                        && activeSessionPageIndex < pages.size()
                    ? pages.get(activeSessionPageIndex)
                    : null;
            lastFreshMultipagePageId = activePage != null ? activePage.id() : null;
          } else {
            lastFreshMultipagePreviewBitmap = null;
            lastFreshMultipagePageId = null;
          }
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }

      exportViewModel.setDocumentBitmap(bmp);
      exportViewModel.setDocumentReady(true);
    } else {
      // Prevent treating the original, un-cropped image as ready
      exportViewModel.setDocumentBitmap(null);
      exportViewModel.setDocumentReady(false);
    }

    // OCR text (if present) can still be shown/prepared independently
    String ocrText = getOcrTextFromState();
    if (ocrText != null) exportViewModel.setOcrText(ocrText);
  }

  // Insert-Hook implementation: persist a newly added CompletedScan to app storage and registry
  private void persistCompletedScanAsync(de.schliweb.makeacopy.ui.export.session.CompletedScan s) {
    if (s == null || s.id() == null || s.inMemoryBitmap() == null) return;
    final android.content.Context appContext = requireContext().getApplicationContext();
    final String id = s.id();
    // Respect user preference: Skip OCR (export only)
    boolean skipOcrPref = ExportPrefsHelper.isSkipOcr(requireContext());
    // Capture current in-memory OCR text/words at call time unless Skip OCR is enabled
    final String ocrTextAtCall = skipOcrPref ? null : getOcrTextFromState();
    final java.util.List<RecognizedWord> ocrWordsAtCall =
        skipOcrPref ? null : getOcrWordsFromState();
    new Thread(
            () -> {
              try {
                // Persist scan (page.jpg, thumb.jpg, and optional OCR artifacts) and insert into
                // registry
                de.schliweb.makeacopy.ui.export.session.CompletedScan persisted =
                    ScanPersister.persist(appContext, s, ocrTextAtCall, ocrWordsAtCall);

                // Update current session item so the filmstrip badge reflects OCR immediately
                final String finalOcrPath = persisted.ocrTextPath();
                final String finalOcrFormat = persisted.ocrFormat();
                postToUiSafe(
                    () -> {
                      List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
                          exportSessionViewModel.getPages().getValue();
                      if (cur == null) return;
                      for (int i = 0; i < cur.size(); i++) {
                        de.schliweb.makeacopy.ui.export.session.CompletedScan it = cur.get(i);
                        if (it != null && id.equals(it.id())) {
                          de.schliweb.makeacopy.ui.export.session.CompletedScan updated =
                              new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                  it.id(),
                                  persisted.filePath(),
                                  it.rotationDeg(),
                                  finalOcrPath,
                                  finalOcrFormat,
                                  it.thumbPath() != null ? it.thumbPath() : persisted.thumbPath(),
                                  it.createdAt(),
                                  it.widthPx(),
                                  it.heightPx(),
                                  it.inMemoryBitmap(),
                                  persisted.schemaVersion(),
                                  persisted.orientationMode());
                          exportSessionViewModel.updateAt(i, updated);
                          break;
                        }
                      }
                    });
              } catch (Exception e) {
                Log.w(TAG, "Persist scan failed", e);
              }
            })
        .start();
  }

  /**
   * Enqueues a background OCR job for the given session page. The filmstrip and preview badge are
   * updated when the job broadcasts completion (see {@link #ocrUpdateReceiver}).
   */
  private void runInlineOcrForPage(int position) {
    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur =
        exportSessionViewModel.getPages().getValue();
    if (cur == null || position < 0 || position >= cur.size()) return;
    de.schliweb.makeacopy.ui.export.session.CompletedScan s = cur.get(position);
    if (s == null) return;
    // Enqueue background OCR job for this page id. UI will be updated when the job broadcasts
    // completion.
    UIUtils.showToast(
        requireContext(), getString(R.string.ocr_processing_started), Toast.LENGTH_SHORT);
    de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState st = ocrViewModel.getState().getValue();
    String lang = (st != null && st.language() != null) ? st.language() : null;
    // If user hasn't visited the OCR screen, fall back to a sensible system-based default
    if (lang == null || lang.trim().isEmpty()) {
      lang = OCRUtils.resolveEffectiveLanguage(lang);
    }
    de.schliweb.makeacopy.jobs.OcrBackgroundJobs.enqueueReprocess(
        requireContext().getApplicationContext(), s.id(), lang, () -> ocrHelperProvider.get());
  }

  @Override
  public void onStart() {
    super.onStart();
    if (!ocrReceiverRegistered) {
      android.content.Context app = requireContext().getApplicationContext();
      android.content.IntentFilter filter =
          new android.content.IntentFilter(
              de.schliweb.makeacopy.jobs.OcrBackgroundJobs.ACTION_OCR_UPDATED);
      int flags = androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED;
      try {
        androidx.core.content.ContextCompat.registerReceiver(app, ocrUpdateReceiver, filter, flags);
        ocrReceiverRegistered = true;
      } catch (IllegalArgumentException | SecurityException e) {
        Log.w(TAG, "Failed to register OCR update receiver", e);
      }
    }
  }

  @Override
  public void onStop() {
    if (ocrReceiverRegistered) {
      android.content.Context app = requireContext().getApplicationContext();
      try {
        app.unregisterReceiver(ocrUpdateReceiver);
      } catch (IllegalArgumentException | SecurityException e) {
        Log.w(TAG, "Failed to unregister OCR update receiver", e);
      }
      ocrReceiverRegistered = false;
    }
    super.onStop();
  }

  @Override
  public void onDestroyView() {
    previewRenderGeneration++;
    super.onDestroyView();
    binding = null;
  }

  @Override
  public void onDestroy() {
    previewExecutor.shutdownNow();
    super.onDestroy();
  }

  /**
   * Retrieves the effective OCR text from the current state managed by the `ocrViewModel`.
   *
   * <p>This method returns the reviewed text if available, otherwise the original OCR text. This
   * ensures that any edits made in the Review screen are used when persisting the scan.
   *
   * @return The effective OCR text (reviewed if available, otherwise original), or null if the
   *     state is unavailable.
   */
  private String getOcrTextFromState() {
    OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
    return (s != null) ? s.getEffectiveText() : null;
  }

  /**
   * Retrieves the effective list of recognized words from the current OCR state.
   *
   * <p>This method returns the reviewed words if available, otherwise the original OCR words. This
   * ensures that any edits made in the Review screen are used when persisting the scan.
   *
   * @return The effective list of recognized words (reviewed if available, otherwise original), or
   *     null if the state is unavailable.
   */
  private List<RecognizedWord> getOcrWordsFromState() {
    OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
    return (s != null) ? s.getEffectiveWords() : null;
  }
}
