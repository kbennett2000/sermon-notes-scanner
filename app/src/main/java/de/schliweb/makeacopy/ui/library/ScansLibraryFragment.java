package de.schliweb.makeacopy.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.library.CollectionsRepository;
import de.schliweb.makeacopy.data.library.ExistingScansIndexer;
import de.schliweb.makeacopy.data.library.ScanEntity;
import de.schliweb.makeacopy.data.library.ScansRepository;
import de.schliweb.makeacopy.utils.FeatureFlags;
import java.util.List;
import javax.inject.Inject;

/**
 * A Fragment that displays a library of scans. Provides functionality to manage and view scanned
 * entities. It includes features like displaying the list of scans, indexing existing scans, and
 * navigating to scan details or other collections.
 *
 * <p>This fragment relies on the {@link ScansAdapter} for displaying scan data and interacts with
 * the scan repository to fetch or index scan data.
 *
 * <p>Features: - Displays a list of scans with details. - Indicates empty state when no scans are
 * available. - Provides navigation to other collections. - Supports re-indexing of existing scans
 * to populate the library dynamically.
 *
 * <p>Lifecycle: - Initializes views and adapters in `onCreateView`. - Retrieves collection data
 * during runtime using `loadDataAsync`.
 *
 * <p>Navigation: - Navigates to scan details when an item in the list is clicked. - Provides a
 * button to navigate to other collections.
 *
 * <p>Data Handling: - Loads scan data asynchronously. - Uses `LibraryServiceLocator` to fetch scan
 * data from the repository. - Dynamically updates the RecyclerView through `ScansAdapter`.
 *
 * <p>Configuration Dependencies: - Requires `BuildConfig.FEATURE_SCAN_LIBRARY` to enable scan
 * library functionality. - Provides feedback to users via Toast messages when features are
 * unavailable or actions complete.
 */
@AndroidEntryPoint
public class ScansLibraryFragment extends Fragment {

  @Inject ScansRepository scansRepository;
  @Inject CollectionsRepository collectionsRepository;

  private RecyclerView list;
  private View progress;
  private View emptyText;
  private View buttonOpenCollections;
  private View buttonContainer;
  private View backButton;
  private View buttonIndexExistingIcon;
  private android.widget.TextView titleCollection;
  private ScansAdapter adapter;
  private String collectionIdArg;
  private String collectionNameArg;

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_scans_library, container, false);
    list = root.findViewById(R.id.list);
    progress = root.findViewById(R.id.progress);
    emptyText = root.findViewById(R.id.emptyText);
    buttonOpenCollections = root.findViewById(R.id.buttonOpenCollections);
    buttonContainer = root.findViewById(R.id.button_container);
    backButton = root.findViewById(R.id.button_back);
    titleCollection = root.findViewById(R.id.titleCollection);
    buttonIndexExistingIcon = root.findViewById(R.id.buttonIndexExistingIcon);

    // Apply system insets: add status bar top inset to root padding and nav bar bottom inset to the
    // bottom button container
    final int origPadLeft = root.getPaddingLeft();
    final int origPadTop = root.getPaddingTop();
    final int origPadRight = root.getPaddingRight();
    final int origPadBottom = root.getPaddingBottom();
    final View bottomContainer = buttonContainer;
    final int origBottomMargin;
    if (bottomContainer != null
        && bottomContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
      origBottomMargin =
          ((ViewGroup.MarginLayoutParams) bottomContainer.getLayoutParams()).bottomMargin;
    } else {
      origBottomMargin = 0;
    }
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets sb =
              insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
          // Top inset for status bar
          v.setPadding(origPadLeft, origPadTop + sb.top, origPadRight, origPadBottom);
          // Bottom inset for nav bar on the bottom button container
          if (bottomContainer != null
              && bottomContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
            mlp.bottomMargin = origBottomMargin + sb.bottom;
            bottomContainer.setLayoutParams(mlp);
          }
          return insets;
        });

    adapter =
        new ScansAdapter(
            item -> {
              try {
                android.os.Bundle args = new android.os.Bundle();
                args.putString("scanId", item.id);
                androidx.navigation.Navigation.findNavController(requireView())
                    .navigate(R.id.navigation_scan_details, args);
              } catch (Throwable t) {
                de.schliweb.makeacopy.utils.UIUtils.showToast(
                    requireContext(),
                    R.string.navigation_failed,
                    android.widget.Toast.LENGTH_SHORT);
              }
            });

    // Back button action
    if (backButton != null) {
      backButton.setOnClickListener(
          v -> {
            try {
              Navigation.findNavController(requireView()).navigateUp();
            } catch (Throwable t) {
              requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
          });
    }
    list.setAdapter(adapter);
    list.addItemDecoration(
        new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

    if (!FeatureFlags.isScanLibraryEnable()) {
      de.schliweb.makeacopy.utils.UIUtils.showToast(
          requireContext(),
          R.string.feature_scan_library_disabled,
          android.widget.Toast.LENGTH_SHORT);
      // Optionally navigate up if embedded in backstack
      requireActivity().getOnBackPressedDispatcher().onBackPressed();
      return root;
    }

    if (getArguments() != null) {
      collectionIdArg = getArguments().getString("collectionId");
      collectionNameArg = getArguments().getString("collectionName");
    }
    // If no collection specified, show all completed documents (no collection title)
    if (collectionIdArg == null) {
      if (titleCollection != null) {
        titleCollection.setVisibility(View.GONE);
      }
      loadDataAsync();
    } else {
      // Show collection name as title when provided
      if (titleCollection != null) {
        if (collectionNameArg != null && !collectionNameArg.trim().isEmpty()) {
          titleCollection.setText(collectionNameArg);
          titleCollection.setVisibility(View.VISIBLE);
        } else {
          titleCollection.setVisibility(View.GONE);
        }
      }
      loadDataAsync();
    }
    // When viewing a specific collection, enable long-press to remove an item from that collection
    if (collectionIdArg != null) {
      adapter.setOnItemLongClickListener(
          item -> {
            String display =
                (item.title != null && !item.title.trim().isEmpty()) ? item.title : item.id;
            final androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(display)
                    .setMessage(R.string.remove_from_collection_question)
                    .setPositiveButton(
                        R.string.ok,
                        (d, w) -> {
                          showLoading(true);
                          new Thread(
                                  () -> {
                                    try {
                                      collectionsRepository.removeScanFromCollection(
                                          requireContext(), item.id, collectionIdArg);
                                    } catch (Throwable ignore) {
                                      // Best-effort; failure is non-critical
                                    }
                                    if (!isAdded()) return;
                                    requireActivity()
                                        .runOnUiThread(
                                            () -> {
                                              de.schliweb.makeacopy.utils.UIUtils.showToast(
                                                  requireContext(),
                                                  R.string.removed_from_collection,
                                                  android.widget.Toast.LENGTH_SHORT);
                                              loadDataAsync();
                                            });
                                  })
                              .start();
                        })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnShowListener(
                d -> {
                  try {
                    de.schliweb.makeacopy.utils.DialogUtils
                        .improveAlertDialogButtonContrastForNight(dialog, requireContext());
                  } catch (Throwable ignore) {
                    // Best-effort; failure is non-critical
                  }
                });
            dialog.show();
          });
    }

    buttonOpenCollections.setOnClickListener(
        v -> {
          try {
            Navigation.findNavController(requireView()).navigate(R.id.navigation_collections);
          } catch (Throwable t) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(
                requireContext(), R.string.navigation_failed, android.widget.Toast.LENGTH_SHORT);
          }
        });
    if (buttonIndexExistingIcon != null) {
      buttonIndexExistingIcon.setOnClickListener(
          v -> {
            showLoading(true);
            new Thread(
                    () -> {
                      int n = 0;
                      try {
                        n = ExistingScansIndexer.runIncremental(requireContext());
                      } catch (Throwable ignore) {
                        // Best-effort; failure is non-critical
                      }
                      final int finalN = n;
                      if (!isAdded()) return;
                      requireActivity()
                          .runOnUiThread(
                              () -> {
                                // Build a short user message and provide both Toast and A11y
                                // announcement
                                CharSequence msg;
                                if (finalN > 0) {
                                  msg = getString(R.string.indexed_new_items, finalN);
                                  de.schliweb.makeacopy.utils.UIUtils.showToast(
                                      requireContext(),
                                      msg.toString(),
                                      android.widget.Toast.LENGTH_SHORT);
                                } else {
                                  msg = getString(R.string.nothing_new_to_index);
                                  de.schliweb.makeacopy.utils.UIUtils.showToast(
                                      requireContext(),
                                      msg.toString(),
                                      android.widget.Toast.LENGTH_SHORT);
                                }
                                // Announce for accessibility so screen reader users receive a
                                // concise summary
                                announceText(msg);
                                loadDataAsync();
                              });
                    })
                .start();
          });
    }

    // loadDataAsync() already called above depending on collection presence
    return root;
  }

  /**
   * Announces a short message for accessibility users via the root view, centralized through
   * A11yUtils to avoid deprecated direct calls.
   */
  private void announceText(@NonNull CharSequence text) {
    View root = getView();
    if (root == null) return;
    root.setContentDescription(text);
    de.schliweb.makeacopy.utils.A11yUtils.announce(root, text);
  }

  private void loadDataAsync() {
    showLoading(true);
    final android.content.Context appCtx = requireContext().getApplicationContext();
    new Thread(
            () -> {
              List<ScanEntity> data;
              try {
                if (collectionIdArg == null) {
                  data = scansRepository.getAllScans(appCtx);
                  // On home screen: hide single-page CompletedScanEntry items from the registry.
                  // We detect these by a lightweight marker in sourceMetaJson:
                  // {"kind":"CompletedScanEntry"}
                  if (data != null && !data.isEmpty()) {
                    java.util.ArrayList<ScanEntity> filtered =
                        new java.util.ArrayList<>(data.size());
                    for (ScanEntity se : data) {
                      if (se == null) continue;
                      String sm = se.sourceMetaJson;
                      // Keep if not explicitly marked as CompletedScanEntry
                      if (sm == null || sm.isEmpty() || !sm.contains("\"CompletedScanEntry\"")) {
                        filtered.add(se);
                      }
                    }
                    data = filtered;
                  }
                } else {
                  data = scansRepository.getScansForCollection(appCtx, collectionIdArg);
                }
              } catch (Throwable t) {
                data = java.util.Collections.emptyList();
              }
              final List<ScanEntity> finalData = data;
              // Build memberships: scanId -> list of collection names
              final java.util.Map<String, java.util.List<String>> memberships =
                  new java.util.LinkedHashMap<>();
              try {
                de.schliweb.makeacopy.data.library.CollectionsRepository cr = collectionsRepository;
                if (finalData != null) {
                  for (ScanEntity se : finalData) {
                    if (se == null) continue;
                    java.util.List<de.schliweb.makeacopy.data.library.CollectionEntity> cols =
                        cr.getCollectionsForScan(appCtx, se.id);
                    if (cols != null && !cols.isEmpty()) {
                      java.util.ArrayList<String> names = new java.util.ArrayList<>(cols.size());
                      for (de.schliweb.makeacopy.data.library.CollectionEntity c : cols) {
                        if (c != null && c.name != null && !c.name.trim().isEmpty())
                          names.add(c.name);
                      }
                      if (!names.isEmpty()) memberships.put(se.id, names);
                    }
                  }
                }
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              final boolean finalShowIndexBtn =
                  de.schliweb.makeacopy.utils.FeatureFlags.isScanLibraryEnable();
              if (!isAdded()) return;
              requireActivity()
                  .runOnUiThread(
                      () -> {
                        adapter.setMemberships(memberships);
                        adapter.submitList(finalData);
                        showLoading(false);
                        boolean isEmpty = (finalData == null || finalData.isEmpty());
                        emptyText.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                        if (buttonIndexExistingIcon != null) {
                          buttonIndexExistingIcon.setVisibility(
                              finalShowIndexBtn ? View.VISIBLE : View.GONE);
                        }
                      });
            })
        .start();
  }

  private void showLoading(boolean show) {
    progress.setVisibility(show ? View.VISIBLE : View.GONE);
    list.setVisibility(show ? View.GONE : View.VISIBLE);
    emptyText.setVisibility(View.GONE);
  }
}
