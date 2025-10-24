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

    @Override
    public CollectionEntity createCollection(Context context, String name) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            CollectionsDao dao = db.collectionsDao();
            int nextOrder = dao.getAll().size();
            CollectionEntity entity = new CollectionEntity(UUID.randomUUID().toString(), name, nextOrder, System.currentTimeMillis());
            dao.insert(entity);
            return entity;
        } catch (Throwable t) {
            Log.e(TAG, "createCollection failed", t);
            return null;
        }
    }

    @Override
    public List<CollectionEntity> getAllCollections(Context context) {
        try {
            return AppDatabase.getInstance(context).collectionsDao().getAll();
        } catch (Throwable t) {
            Log.e(TAG, "getAllCollections failed", t);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public void assignScanToCollection(Context context, String scanId, String collectionId) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            db.scanCollectionJoinDao().insert(new ScanCollectionCrossRef(scanId, collectionId, System.currentTimeMillis()));
        } catch (Throwable t) {
            Log.e(TAG, "assignScanToCollection failed", t);
        }
    }

    @Override
    public void removeScanFromCollection(Context context, String scanId, String collectionId) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            db.scanCollectionJoinDao().remove(scanId, collectionId);
        } catch (Throwable t) {
            Log.e(TAG, "removeScanFromCollection failed", t);
        }
    }

    @Override
    public boolean deleteCollectionIfEmpty(Context context, String collectionId) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            CollectionsDao cdao = db.collectionsDao();
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
                } catch (Throwable ignore) {}
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
            } catch (Throwable ignore) {}
            return list;
        } catch (Throwable t) {
            Log.e(TAG, "getCollectionsForScan failed", t);
            return java.util.Collections.emptyList();
        }
    }
}

