package de.schliweb.makeacopy.data.library;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import org.json.JSONArray;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DefaultScansRepository is a concrete implementation of the ScansRepository interface.
 * It provides access to the application's scan-related data and operations by interacting
 * with the local Room database via DAOs. This repository layer abstracts database interactions
 * for clients needing to manage or retrieve scans.
 * <p>
 * Features:
 * - Idempotent creation or updating of scan records in the database.
 * - Retrieval of all scans or scans filtered by collection IDs.
 * - Deletion of scan records, including their associations with collections.
 * - Updating scan titles in the database.
 * <p>
 * Error Handling:
 * - Catches throwable exceptions and logs errors to avoid application crashes during
 * database operations. Defaults are returned where appropriate (e.g., empty lists or nulls).
 */
public class DefaultScansRepository implements ScansRepository {
    private static final String TAG = "ScansRepo";

    /**
     * Indexes or updates the metadata of an exported scan in the database. This method ensures
     * that the scan represented by the given metadata is persisted in the database. If the scan
     * already exists, its basic fields are updated with any new information provided in the
     * metadata. If the scan does not exist, it is inserted as a new entry.
     *
     * @param context the application context used to access the database
     * @param meta    the metadata of the scan to be indexed or updated, represented as a {@code ScanIndexMeta}
     */
    @Override
    public void indexExportedScan(Context context, ScanIndexMeta meta) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            ScansDao scansDao = db.scansDao();
            // Idempotent-ish: check if exists
            ScanEntity existing = scansDao.getById(meta.id());
            if (existing == null) {
                ScanEntity entity = new ScanEntity(
                        meta.id(),
                        meta.title(),
                        meta.createdAt(),
                        meta.pageCount(),
                        meta.coverPath(),
                        meta.exportPathsJson(),
                        meta.sourceMetaJson()
                );
                scansDao.insert(entity);
            } else {
                // Update basic fields
                existing.title = meta.title() != null ? meta.title() : existing.title;
                existing.createdAt = existing.createdAt == 0 ? meta.createdAt() : existing.createdAt;
                existing.pageCount = Math.max(existing.pageCount, meta.pageCount());
                if (meta.coverPath() != null) existing.coverPath = meta.coverPath();
                if (meta.exportPathsJson() != null) existing.exportPathsJson = meta.exportPathsJson();
                if (meta.sourceMetaJson() != null) existing.sourceMetaJson = meta.sourceMetaJson();
                scansDao.update(existing);
            }
        } catch (Throwable t) {
            Log.e(TAG, "indexExportedScan failed", t);
        }
    }

    /**
     * Retrieves all saved scans from the database in descending order of their creation timestamp.
     * This method returns an empty list if the query fails or if no scans exist in the database.
     *
     * @param context the application context used to access the database.
     * @return a list of {@code ScanEntity} objects representing all saved scans from the database.
     * Returns an empty list if the query fails or no scans are found.
     */
    @Override
    public List<ScanEntity> getAllScans(Context context) {
        try {
            return AppDatabase.getInstance(context).scansDao().getAll();
        } catch (Throwable t) {
            Log.e(TAG, "getAllScans failed", t);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Retrieves all scans associated with a specific collection from the database.
     * If the query fails or no scans are found for the given collection, an empty list is returned.
     *
     * @param context      the application context used to access the database
     * @param collectionId the unique identifier of the collection for which scans are being retrieved
     * @return a list of {@code ScanEntity} objects associated with the specified collection.
     * Returns an empty list if the query fails or no scans are found for the given collection.
     */
    @Override
    public List<ScanEntity> getScansForCollection(Context context, String collectionId) {
        try {
            return AppDatabase.getInstance(context).scansDao().getAllByCollection(collectionId);
        } catch (Throwable t) {
            Log.e(TAG, "getScansForCollection failed", t);
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Retrieves a specific scan from the database using its unique identifier.
     * If the query fails or the scan is not found, this method returns null.
     *
     * @param context the application context used to access the database
     * @param id      the unique identifier of the scan to be retrieved
     * @return the {@code ScanEntity} associated with the given identifier,
     * or null if the query fails or the scan cannot be found
     */
    @Override
    public ScanEntity getScanById(Context context, String id) {
        try {
            return AppDatabase.getInstance(context).scansDao().getById(id);
        } catch (Throwable t) {
            Log.e(TAG, "getScanById failed", t);
            return null;
        }
    }

    /**
     * Deletes a scan with the specified unique identifier from the database, including associated
     * data and physical artifacts. This method performs cleanup on related entries, files, and joins
     * associated with the scan to ensure data integrity and avoid orphaned references or artifacts.
     *
     * @param context the application context used to access the underlying database and perform cleanup operations
     * @param id      the unique identifier of the scan to be deleted from the database
     */
    @Override
    public void deleteScan(Context context, String id) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            ScanEntity entity = null;
            try {
                entity = db.scansDao().getById(id);
            } catch (Throwable ignore) {
            }

            // Best-effort: If this scan originated from the app's registry (incrementally indexed),
            // remove its physical artifacts under files/scans/<id> and drop the registry entry.
            try {
                de.schliweb.makeacopy.data.RegistryCleaner.removeEntryAndFiles(context.getApplicationContext(), id);
            } catch (Throwable ignoreCleanup) {
                // keep delete robust; physical cleanup is best-effort
            }

            // Best-effort: Delete exported files referenced by this scan (file://, content://, or absolute paths).
            if (entity != null) {
                try {
                    bestEffortDeleteBackedFiles(context.getApplicationContext(), entity);
                } catch (Throwable ignore) {
                }
            }

            // Remove any joins first to respect FK constraints if added later
            db.scanCollectionJoinDao().removeAllForScan(id);
            db.scansDao().deleteById(id);
        } catch (Throwable t) {
            Log.e(TAG, "deleteScan failed", t);
        }
    }

    private void bestEffortDeleteBackedFiles(Context ctx, ScanEntity e) {
        if (ctx == null || e == null) return;
        Set<String> seen = new HashSet<>();

        // delete all export paths
        try {
            if (e.exportPathsJson != null && !e.exportPathsJson.isEmpty()) {
                JSONArray arr = new JSONArray(e.exportPathsJson);
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, null);
                    deleteOnePathOrUri(ctx, s, seen);
                }
            }
        } catch (Throwable ignore) {
        }

        // delete cover path if present (may be a file path or uri) and not already deleted
        deleteOnePathOrUri(ctx, e.coverPath, seen);
    }

    private void deleteOnePathOrUri(Context ctx, String pathOrUri, Set<String> seen) {
        if (pathOrUri == null || pathOrUri.isEmpty()) return;
        if (seen != null) {
            if (seen.contains(pathOrUri)) return;
            seen.add(pathOrUri);
        }
        try {
            if (pathOrUri.startsWith("content://")) {
                Uri u = Uri.parse(pathOrUri);
                ContentResolver cr = ctx.getContentResolver();
                try {
                    cr.delete(u, null, null);
                } catch (SecurityException se) {
                    // No permission to delete; ignore to keep operation robust
                }
            } else if (pathOrUri.startsWith("file://")) {
                Uri u = Uri.parse(pathOrUri);
                File f = new File(u.getPath());
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } else {
                // treat as plain filesystem path
                File f = new File(pathOrUri);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void updateTitle(Context context, String id, String newTitle) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            ScansDao dao = db.scansDao();
            ScanEntity e = dao.getById(id);
            if (e != null) {
                e.title = newTitle;
                dao.update(e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "updateTitle failed", t);
        }
    }

    @Override
    public void updateExportPathsJson(Context context, String id, String exportPathsJson) {
        try {
            AppDatabase db = AppDatabase.getInstance(context);
            ScansDao dao = db.scansDao();
            ScanEntity e = dao.getById(id);
            if (e != null) {
                e.exportPathsJson = exportPathsJson;
                dao.update(e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "updateExportPathsJson failed", t);
        }
    }
}