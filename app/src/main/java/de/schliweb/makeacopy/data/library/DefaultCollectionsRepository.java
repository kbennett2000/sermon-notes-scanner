package de.schliweb.makeacopy.data.library;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.UUID;

/**
 * Default implementation of the CollectionsRepository interface.
 * Provides concrete methods to manage collections within the application, supporting
 * operations related to collection creation, retrieval, modification, and deletion, as well
 * as managing associations between scans and collections.
 * <p>
 * This class interacts with the Room persistence library through DAO (Data Access Object)
 * interfaces to perform database operations such as insertions, deletions, and queries.
 * It uses a singleton instance of the AppDatabase to avoid redundant instances of the database.
 * <p>
 * Key Features:
 * - Create a new collection and store it in the database.
 * - Retrieve all collections stored in the database.
 * - Associate a scan with a collection or remove a specific association.
 * - Delete a collection only if it contains no scans.
 * - Count the number of scans within a given collection.
 * <p>
 * Error Handling:
 * For each database operation, exceptions are caught and logged to facilitate debugging, and an appropriate
 * fallback behavior (e.g., returning null or an empty list) is implemented to handle errors gracefully.
 * <p>
 * Thread Safety:
 * Database operations are thread-safe due to the Room library's thread-safety guarantees.
 */
public class DefaultCollectionsRepository implements CollectionsRepository {
    private static final String TAG = "CollectionsRepo";

    private boolean isDefaultCollection(Context context, CollectionEntity e) {
        if (e == null) return false;
        try {
            // Determine the real default collection (by ID) and compare IDs.
            CollectionEntity def = getOrCreateDefaultCompletedCollection(context.getApplicationContext());
            return def != null && def.id != null && def.id.equals(e.id);
        } catch (Throwable ignore) {
            return false;
        }
    }

    private boolean isDefaultCollectionId(Context context, String collectionId) {
        try {
            if (collectionId == null) return false;
            CollectionEntity def = getOrCreateDefaultCompletedCollection(context.getApplicationContext());
            return def != null && def.id != null && def.id.equals(collectionId);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public CollectionEntity createCollection(Context context, String name) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            CollectionsDao dao = db.collectionsDao();
            // Normalize input
            String trimmed = (name == null) ? null : name.trim();
            if (trimmed == null || trimmed.isEmpty()) return null;
            // If the requested name is the reserved default name, return the (existing or newly created)
            // default collection instead of creating a duplicate.
            try {
                String defName = context.getApplicationContext().getString(de.schliweb.makeacopy.R.string.collection_completed_scans);
                if (defName != null && defName.equalsIgnoreCase(trimmed)) {
                    return getOrCreateDefaultCompletedCollection(context);
                }
            } catch (Throwable ignore) {
            }
            int nextOrder = dao.getAll().size();
            CollectionEntity entity = new CollectionEntity(UUID.randomUUID().toString(), trimmed, nextOrder, System.currentTimeMillis());
            dao.insert(entity);
            return entity;
        } catch (Throwable t) {
            Log.e(TAG, "createCollection failed", t);
            return null;
        }
    }

    /**
     * Retrieves all collections from the database ordered by their sort order and creation timestamp.
     * If an error occurs during the database operation, an empty list is returned.
     *
     * @param context the Context of the application or activity, required to initialize the database.
     * @return a list of {@code CollectionEntity} objects representing all collections in the database.
     */
    @Override
    public List<CollectionEntity> getAllCollections(Context context) {
        try {
            return AppDatabase.getInstance(context).collectionsDao().getAll();
        } catch (Throwable t) {
            Log.e(TAG, "getAllCollections failed", t);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Assigns a scan to a collection by creating a relationship between the specified scan and collection
     * in the database. This method inserts a record into the {@code scan_collection_join} table,
     * associating the given scan and collection IDs with the current timestamp.
     *
     * @param context      the application or activity context, required to initialize the database instance.
     * @param scanId       the unique identifier of the scan to be added to the collection.
     * @param collectionId the unique identifier of the collection to which the scan is to be assigned.
     */
    @Override
    public void assignScanToCollection(Context context, String scanId, String collectionId) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            // Guard: Default "Completed Scans" collection is ONLY for CompletedScanEntry items.
            // If target is the default collection, verify the scan has the marker in sourceMetaJson.
            try {
                if (isDefaultCollectionId(context, collectionId)) {
                    try {
                        ScanEntity se = db.scansDao().getById(scanId);
                        String sm = (se != null) ? se.sourceMetaJson : null;
                        boolean isCompletedScanEntry = (sm != null && sm.contains("\"CompletedScanEntry\""));
                        if (!isCompletedScanEntry) {
                            android.util.Log.i(TAG, "assignScanToCollection: blocked assigning finished document to default Completed Scans collection");
                            return; // silently ignore
                        }
                    } catch (Throwable ignore) {
                        // If we cannot verify, be conservative and block assignment.
                        android.util.Log.i(TAG, "assignScanToCollection: could not verify scan type, blocking assignment to default collection");
                        return;
                    }
                }
            } catch (Throwable ignore) {
            }
            db.scanCollectionJoinDao().insert(new ScanCollectionCrossRef(scanId, collectionId, System.currentTimeMillis()));
        } catch (Throwable t) {
            Log.e(TAG, "assignScanToCollection failed", t);
        }
    }

    /**
     * Removes the association between a specific scan and a collection from the database.
     * <p>
     * This method deletes the relationship in the {@code scan_collection_join} table
     * for the provided scan and collection identifiers.
     *
     * @param context      the application or activity context, required to initialize the database instance.
     * @param scanId       the unique identifier of the scan to be dissociated from the collection.
     * @param collectionId the unique identifier of the collection from which the scan is to be removed.
     */
    @Override
    public void removeScanFromCollection(Context context, String scanId, String collectionId) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            db.scanCollectionJoinDao().remove(scanId, collectionId);
        } catch (Throwable t) {
            Log.e(TAG, "removeScanFromCollection failed", t);
        }
    }

    /**
     * Deletes a collection from the database if it contains no items. This method checks the item
     * count for the collection and removes the collection only if the count is zero.
     *
     * @param context      the application or activity context, used to initialize the database.
     * @param collectionId the unique identifier of the collection to be deleted.
     * @return {@code true} if the collection was empty and successfully deleted, {@code false} otherwise.
     */
    @Override
    public boolean deleteCollectionIfEmpty(Context context, String collectionId) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            CollectionsDao cdao = db.collectionsDao();
            // Guard: default collection cannot be deleted at all
            if (isDefaultCollectionId(context, collectionId)) return false;
            int count = cdao.countItems(collectionId);
            if (count > 0) return false;
            cdao.deleteById(collectionId);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "deleteCollectionIfEmpty failed", t);
            return false;
        }
    }

    @Override
    public int countItems(Context context, String collectionId) {
        try {
            return AppDatabase.getInstance(context).collectionsDao().countItems(collectionId);
        } catch (Throwable t) {
            Log.e(TAG, "countItems failed", t);
            return 0;
        }
    }

    @Override
    public boolean renameCollection(Context context, String collectionId, String newName) {
        try {
            if (newName == null) return false;
            String trimmed = newName.trim();
            if (trimmed.isEmpty()) return false;
            AppDatabase db = AppDatabase.getInstance(context);
            CollectionsDao dao = db.collectionsDao();
            CollectionEntity e = dao.getById(collectionId);
            if (e == null) return false;
            // Guard: default collection cannot be renamed (by ID)
            if (isDefaultCollection(context, e)) return false;
            // Guard: do not allow renaming another collection to the reserved default name
            try {
                String defName = context.getApplicationContext().getString(de.schliweb.makeacopy.R.string.collection_completed_scans);
                if (defName != null && defName.equalsIgnoreCase(trimmed)) {
                    return false;
                }
            } catch (Throwable ignore) {
            }
            e.name = trimmed;
            dao.update(e);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "renameCollection failed", t);
            return false;
        }
    }

    @Override
    public java.util.List<CollectionEntity> getCollectionsForScan(Context context, String scanId) {
        try {
            if (scanId == null) return java.util.Collections.emptyList();
            AppDatabase db = AppDatabase.getInstance(context);
            java.util.List<String> ids = db.scanCollectionJoinDao().getCollectionIdsForScan(scanId);
            if (ids == null || ids.isEmpty()) return java.util.Collections.emptyList();
            CollectionsDao cdao = db.collectionsDao();
            java.util.ArrayList<CollectionEntity> list = new java.util.ArrayList<>();
            for (String id : ids) {
                try {
                    CollectionEntity ce = cdao.getById(id);
                    if (ce != null) list.add(ce);
                } catch (Throwable ignore) {
                }
            }
            // Optional: sort by sortOrder then name
            try {
                list.sort((a, b) -> {
                    int so = Integer.compare(a.sortOrder, b.sortOrder);
                    if (so != 0) return so;
                    String an = (a.name == null ? "" : a.name);
                    String bn = (b.name == null ? "" : b.name);
                    return an.compareToIgnoreCase(bn);
                });
            } catch (Throwable ignore) {
            }
            return list;
        } catch (Throwable t) {
            Log.e(TAG, "getCollectionsForScan failed", t);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public CollectionEntity getOrCreateDefaultCompletedCollection(Context context) {
        try {
            Context app = context.getApplicationContext();
            AppDatabase db = AppDatabase.getInstance(app);
            CollectionsDao dao = db.collectionsDao();
            String name = app.getString(de.schliweb.makeacopy.R.string.collection_completed_scans);
            CollectionEntity existing = null;
            try {
                existing = dao.getByName(name);
            } catch (Throwable ignore) {
            }
            if (existing != null) return existing;
            // create new
            int nextOrder = 0;
            try {
                java.util.List<CollectionEntity> all = dao.getAll();
                nextOrder = (all != null) ? all.size() : 0;
            } catch (Throwable ignore) {
            }
            CollectionEntity entity = new CollectionEntity(java.util.UUID.randomUUID().toString(), name, nextOrder, System.currentTimeMillis());
            try {
                dao.insert(entity);
            } catch (Throwable ignore) {
            }
            return entity;
        } catch (Throwable t) {
            android.util.Log.e(TAG, "getOrCreateDefaultCompletedCollection failed", t);
            return null;
        }
    }
}

