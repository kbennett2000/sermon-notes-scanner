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
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.library.ExistingScansIndexer;
import de.schliweb.makeacopy.data.library.LibraryServiceLocator;
import de.schliweb.makeacopy.data.library.ScanEntity;

import java.util.List;

/**
 * A Fragment that displays a library of scans. Provides functionality to manage and view
 * scanned entities. It includes features like displaying the list of scans, indexing existing scans,
 * and navigating to scan details or other collections.
 * <p>
 * This fragment relies on the {@link ScansAdapter} for displaying scan data and interacts
 * with the scan repository to fetch or index scan data.
 * <p>
 * Features:
 * - Displays a list of scans with details.
 * - Indicates empty state when no scans are available.
 * - Provides navigation to other collections.
 * - Supports re-indexing of existing scans to populate the library dynamically.
 * <p>
 * Lifecycle:
 * - Initializes views and adapters in `onCreateView`.
 * - Retrieves collection data during runtime using `loadDataAsync`.
 * <p>
 * Navigation:
 * - Navigates to scan details when an item in the list is clicked.
 * - Provides a button to navigate to other collections.
 * <p>
 * Data Handling:
 * - Loads scan data asynchronously.
 * - Uses `LibraryServiceLocator` to fetch scan data from the repository.
 * - Dynamically updates the RecyclerView through `ScansAdapter`.
 * <p>
 * Configuration Dependencies:
 * - Requires `BuildConfig.FEATURE_SCAN_LIBRARY` to enable scan library functionality.
 * - Provides feedback to users via Toast messages when features are unavailable or actions complete.
 */
public class ScansLibraryFragment extends Fragment {

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
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_scans_library, container, false);
        list = root.findViewById(R.id.list);
        progress = root.findViewById(R.id.progress);
        emptyText = root.findViewById(R.id.emptyText);
        buttonOpenCollections = root.findViewById(R.id.buttonOpenCollections);
        buttonContainer = root.findViewById(R.id.button_container);
        backButton = root.findViewById(R.id.button_back);
        titleCollection = root.findViewById(R.id.titleCollection);
        buttonIndexExistingIcon = root.findViewById(R.id.buttonIndexExistingIcon);

        // Apply system insets: add status bar top inset to root padding and nav bar bottom inset to the bottom button container
        final int origPadLeft = root.getPaddingLeft();
        final int origPadTop = root.getPaddingTop();
        final int origPadRight = root.getPaddingRight();
        final int origPadBottom = root.getPaddingBottom();
        final View bottomContainer = buttonContainer;
        final int origBottomMargin;
        if (bottomContainer != null && bottomContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            origBottomMargin = ((ViewGroup.MarginLayoutParams) bottomContainer.getLayoutParams()).bottomMargin;
        } else {
            origBottomMargin = 0;
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            // Top inset for status bar
            v.setPadding(origPadLeft, origPadTop + sb.top, origPadRight, origPadBottom);
            // Bottom inset for nav bar on the bottom button container
            if (bottomContainer != null && bottomContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                mlp.bottomMargin = origBottomMargin + sb.bottom;
                bottomContainer.setLayoutParams(mlp);
            }
            return insets;
        });

        adapter = new ScansAdapter(item -> {
            try {
                android.os.Bundle args = new android.os.Bundle();
                args.putString("scanId", item.id);
                androidx.navigation.Navigation.findNavController(requireView()).navigate(R.id.navigation_scan_details, args);
            } catch (Throwable t) {
                de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.navigation_failed, android.widget.Toast.LENGTH_SHORT);
            }
        });

        // Back button action
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                try {
                    Navigation.findNavController(requireView()).navigateUp();
                } catch (Throwable t) {
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            });
        }
        list.setAdapter(adapter);
        list.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

        if (!BuildConfig.FEATURE_SCAN_LIBRARY) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.feature_scan_library_disabled, android.widget.Toast.LENGTH_SHORT);
            // Optionally navigate up if embedded in backstack
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return root;
        }

        if (getArguments() != null) {
            collectionIdArg = getArguments().getString("collectionId");
            collectionNameArg = getArguments().getString("collectionName");
        }
        // Show collection name as title when provided
        if (titleCollection != null) {
            if (collectionNameArg != null && !collectionNameArg.trim().isEmpty()) {
                titleCollection.setText(collectionNameArg);
                titleCollection.setVisibility(View.VISIBLE);
            } else {
                titleCollection.setVisibility(View.GONE);
            }
        }
        // When viewing a specific collection, enable long-press to remove an item from that collection
        if (collectionIdArg != null) {
            adapter.setOnItemLongClickListener(item -> {
                String display = (item.title != null && !item.title.trim().isEmpty()) ? item.title : item.id;
                final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(display)
                        .setMessage(R.string.remove_from_collection_question)
                        .setPositiveButton(R.string.ok, (d, w) -> {
                            showLoading(true);
                            new Thread(() -> {
                                try {
                                    LibraryServiceLocator.getCollectionsRepository(requireContext())
                                            .removeScanFromCollection(requireContext(), item.id, collectionIdArg);
                                } catch (Throwable ignore) {
                                }
                                if (!isAdded()) return;
                                requireActivity().runOnUiThread(() -> {
                                    de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.removed_from_collection, android.widget.Toast.LENGTH_SHORT);
                                    loadDataAsync();
                                });
                            }).start();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
                dialog.setOnShowListener(d -> {
                    try {
                        de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext());
                    } catch (Throwable ignore) {
                    }
                });
                dialog.show();
            });
        }

        buttonOpenCollections.setOnClickListener(v -> {
            try {
                Navigation.findNavController(requireView()).navigate(R.id.navigation_collections);
            } catch (Throwable t) {
                de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.navigation_failed, android.widget.Toast.LENGTH_SHORT);
            }
        });
        if (buttonIndexExistingIcon != null) {
            buttonIndexExistingIcon.setOnClickListener(v -> {
                showLoading(true);
                new Thread(() -> {
                    int n = 0;
                    try {
                        n = ExistingScansIndexer.runIncremental(requireContext());
                    } catch (Throwable ignore) {
                    }
                    final int finalN = n;
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (finalN > 0) {
                            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), getString(R.string.indexed_new_items, finalN), android.widget.Toast.LENGTH_SHORT);
                        } else {
                            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.nothing_new_to_index, android.widget.Toast.LENGTH_SHORT);
                        }
                        loadDataAsync();
                    });
                }).start();
            });
        }

        loadDataAsync();
        return root;
    }

    private void loadDataAsync() {
        showLoading(true);
        new Thread(() -> {
            List<ScanEntity> data;
            try {
                if (collectionIdArg == null) {
                    data = LibraryServiceLocator.getScansRepository(requireContext()).getAllScans(requireContext());
                } else {
                    data = LibraryServiceLocator.getScansRepository(requireContext()).getScansForCollection(requireContext(), collectionIdArg);
                }
            } catch (Throwable t) {
                data = java.util.Collections.emptyList();
            }
            final List<ScanEntity> finalData = data;
            // Build memberships: scanId -> list of collection names
            final java.util.Map<String, java.util.List<String>> memberships = new java.util.LinkedHashMap<>();
            try {
                de.schliweb.makeacopy.data.library.CollectionsRepository cr = LibraryServiceLocator.getCollectionsRepository(requireContext());
                if (finalData != null) {
                    for (ScanEntity se : finalData) {
                        if (se == null) continue;
                        java.util.List<de.schliweb.makeacopy.data.library.CollectionEntity> cols = cr.getCollectionsForScan(requireContext(), se.id);
                        if (cols != null && !cols.isEmpty()) {
                            java.util.ArrayList<String> names = new java.util.ArrayList<>(cols.size());
                            for (de.schliweb.makeacopy.data.library.CollectionEntity c : cols) {
                                if (c != null && c.name != null && !c.name.trim().isEmpty()) names.add(c.name);
                            }
                            if (!names.isEmpty()) memberships.put(se.id, names);
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                adapter.setMemberships(memberships);
                adapter.submitList(finalData);
                showLoading(false);
                boolean isEmpty = (finalData == null || finalData.isEmpty());
                emptyText.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                // Always offer incremental indexing when feature is enabled; safe to run repeatedly
                boolean showIndexBtn = BuildConfig.FEATURE_SCAN_LIBRARY;
                if (buttonIndexExistingIcon != null) {
                    buttonIndexExistingIcon.setVisibility(showIndexBtn ? View.VISIBLE : View.GONE);
                }
            });
        }).start();
    }

    private void showLoading(boolean show) {
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyText.setVisibility(View.GONE);
    }
}
