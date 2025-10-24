package de.schliweb.makeacopy.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.library.CollectionEntity;
import de.schliweb.makeacopy.data.library.LibraryServiceLocator;

import java.util.ArrayList;
import java.util.List;

/**
 * A fragment that handles displaying and managing user collections.
 * It utilizes a RecyclerView for listing collections and includes
 * options for creating, navigating, and deleting collections.
 */
public class CollectionsFragment extends Fragment {

    private RecyclerView list;
    private View progress;
    private View emptyText;
    private View addButton;
    private View backButton;
    private View buttonContainer;
    private final CollectionsAdapter adapter = new CollectionsAdapter();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_collections, container, false);
        list = root.findViewById(R.id.list);
        progress = root.findViewById(R.id.progress);
        emptyText = root.findViewById(R.id.emptyText);
        addButton = root.findViewById(R.id.buttonAdd);
        backButton = root.findViewById(R.id.button_back);
        buttonContainer = root.findViewById(R.id.button_container);

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
            if (bottomContainer != null && bottomContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) bottomContainer.getLayoutParams();
                mlp.bottomMargin = origBottomMargin + sb.bottom;
                bottomContainer.setLayoutParams(mlp);
            }
            return insets;
        });

        list.setAdapter(adapter);
        list.addItemDecoration(new DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL));

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

        if (!BuildConfig.FEATURE_SCAN_LIBRARY) {
            Toast.makeText(requireContext(), "Scan library feature is disabled", Toast.LENGTH_SHORT).show();
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return root;
        }

        adapter.listener = new CollectionsAdapter.Listener() {
            @Override
            public void onClick(@NonNull CollectionRow row) {
                try {
                    Bundle args = new Bundle();
                    args.putString("collectionId", row.id);
                    // Also pass the collection name so ScansLibraryFragment can show it as a title
                    args.putString("collectionName", row.name);
                    Navigation.findNavController(requireView()).navigate(R.id.navigation_scans_library, args);
                } catch (Throwable t) {
                    Toast.makeText(requireContext(), "Navigation failed", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onLongClick(@NonNull CollectionRow row) {
                // Show actions: Rename or Delete
                final CharSequence[] items = new CharSequence[]{getString(R.string.rename), getString(R.string.delete)};
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(row.name)
                        .setItems(items, (dialog, which) -> {
                            if (which == 0) {
                                // Rename
                                final android.widget.EditText input = new android.widget.EditText(requireContext());
                                input.setHint(R.string.collection_name_hint);
                                input.setText(row.name);
                                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                        .setTitle(R.string.rename_collection_title)
                                        .setView(input)
                                        .setPositiveButton(R.string.ok, (d, w) -> {
                                            final String name = String.valueOf(input.getText()).trim();
                                            if (name.isEmpty()) return;
                                            new Thread(() -> {
                                                boolean ok;
                                                try {
                                                    ok = LibraryServiceLocator.getCollectionsRepository(requireContext()).renameCollection(requireContext(), row.id, name);
                                                } catch (Throwable t) {
                                                    ok = false;
                                                }
                                                final boolean finalOk = ok;
                                                if (!isAdded()) return;
                                                requireActivity().runOnUiThread(() -> {
                                                    if (!finalOk) {
                                                        Toast.makeText(requireContext(), R.string.error_empty_input, Toast.LENGTH_SHORT).show();
                                                    }
                                                    loadDataAsync();
                                                });
                                            }).start();
                                        })
                                        .setNegativeButton(android.R.string.cancel, null)
                                        .show();
                            } else if (which == 1) {
                                // Delete if empty
                                new Thread(() -> {
                                    boolean deleted;
                                    try {
                                        deleted = LibraryServiceLocator.getCollectionsRepository(requireContext()).deleteCollectionIfEmpty(requireContext(), row.id);
                                    } catch (Throwable t) {
                                        deleted = false;
                                    }
                                    final boolean finalDeleted = deleted;
                                    if (!isAdded()) return;
                                    requireActivity().runOnUiThread(() -> {
                                        if (finalDeleted) {
                                            Toast.makeText(requireContext(), getString(R.string.deleted), Toast.LENGTH_SHORT).show();
                                            loadDataAsync();
                                        } else {
                                            Toast.makeText(requireContext(), getString(R.string.collection_not_empty), Toast.LENGTH_SHORT).show();
                                        }
                                    });
                                }).start();
                            }
                        })
                        .show();
            }
        };

        addButton.setOnClickListener(v -> showCreateDialog());
        loadDataAsync();
        return root;
    }

    private void showCreateDialog() {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(R.string.collection_name_hint);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.new_collection_title)
                .setView(input)
                .setPositiveButton(R.string.create, (d, w) -> {
                    final String name = String.valueOf(input.getText()).trim();
                    if (name.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            LibraryServiceLocator.getCollectionsRepository(requireContext()).createCollection(requireContext(), name);
                        } catch (Throwable ignore) {
                        }
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(this::loadDataAsync);
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadDataAsync() {
        showLoading(true);
        new Thread(() -> {
            List<CollectionEntity> cols;
            try {
                cols = LibraryServiceLocator.getCollectionsRepository(requireContext()).getAllCollections(requireContext());
            } catch (Throwable t) {
                cols = java.util.Collections.emptyList();
            }
            List<CollectionRow> rows = new ArrayList<>();
            for (CollectionEntity c : cols) {
                int count;
                try {
                    count = LibraryServiceLocator.getCollectionsRepository(requireContext()).countItems(requireContext(), c.id);
                } catch (Throwable t) {
                    count = 0;
                }
                rows.add(new CollectionRow(c.id, c.name, count));
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                adapter.submit(rows);
                showLoading(false);
                emptyText.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    private void showLoading(boolean show) {
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyText.setVisibility(View.GONE);
    }

    private record CollectionRow(String id, String name, int count) {
    }

    private static class CollectionsAdapter extends RecyclerView.Adapter<CollectionsAdapter.VH> {
        interface Listener {
            void onClick(@NonNull CollectionRow row);

            void onLongClick(@NonNull CollectionRow row);
        }

        private final List<CollectionRow> items = new ArrayList<>();
        Listener listener;

        void submit(List<CollectionRow> data) {
            items.clear();
            if (data != null) items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_2, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            CollectionRow r = items.get(position);
            h.title.setText(r.name);
            h.subtitle.setText(h.subtitle.getContext().getString(R.string.collection_items_count, r.count));
            h.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onClick(r);
            });
            h.itemView.setOnLongClickListener(v -> {
                if (listener != null) listener.onLongClick(r);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView title;
            TextView subtitle;

            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(android.R.id.text1);
                subtitle = itemView.findViewById(android.R.id.text2);
            }
        }
    }
}
