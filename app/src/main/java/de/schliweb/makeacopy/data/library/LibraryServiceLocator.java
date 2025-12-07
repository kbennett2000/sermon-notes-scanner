package de.schliweb.makeacopy.data.library;

import android.content.Context;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.utils.FeatureFlags;

/**
 * Singleton service locator for providing repository instances to manage
 * scans and collections within the application. This class provides access
 * to the `ScansRepository` and `CollectionsRepository` implementations while
 * enforcing feature toggle checks and ensuring thread safety.
 * <p>
 * Features are enabled or disabled based on the application's build
 * configuration (`BuildConfig.FEATURE_SCAN_LIBRARY`). When the feature is
 * disabled, no-op implementations of the repositories are provided to avoid
 * impacting the application's execution.
 * <p>
 * The class uses double-checked locking to initialize and manage the
 * repository instances in a thread-safe manner. It also ensures that the
 * database is initialized before creating repository instances.
 */
public final class LibraryServiceLocator {
    private static volatile ScansRepository scansRepository;
    private static volatile CollectionsRepository collectionsRepository;

    private LibraryServiceLocator() {
    }

    /**
     * Checks whether the "Scan Library" feature is enabled in the application.
     * The feature flag is configured in the BuildConfig class during the build process.
     *
     * @return true if the "Scan Library" feature is enabled, false otherwise.
     */
    private static boolean isFeatureEnabled() {
        return FeatureFlags.isScanLibraryEnable();
    }

    /**
     * Provides the instance of the {@link ScansRepository} implementation. If the "Scan Library"
     * feature is disabled, it returns a no-op implementation of {@link ScansRepository}. Otherwise,
     * it initializes and returns the default implementation of {@link ScansRepository}, ensuring
     * that the database is initialized when required.
     *
     * @param context the application context used to initialize or retrieve the repository and database
     * @return an instance of {@link ScansRepository}, either a no-op implementation or the default implementation
     */
    public static ScansRepository getScansRepository(Context context) {
        if (!isFeatureEnabled()) {
            // Return a no-op implementation when feature disabled
            return new ScansRepository() {
                @Override
                public void indexExportedScan(Context ctx, ScanIndexMeta meta) { /* no-op */ }

                @Override
                public java.util.List<ScanEntity> getAllScans(Context ctx) {
                    return java.util.Collections.emptyList();
                }

                @Override
                public java.util.List<ScanEntity> getScansForCollection(Context ctx, String collectionId) {
                    return java.util.Collections.emptyList();
                }

                @Override
                public ScanEntity getScanById(Context context, String id) {
                    return null;
                }

                @Override
                public void deleteScan(Context context, String id) {
                }

                @Override
                public void updateTitle(Context context, String id, String newTitle) {
                }

                @Override
                public void updateExportPathsJson(Context context, String id, String exportPathsJson) {
                }
            };
        }
        if (scansRepository == null) {
            synchronized (LibraryServiceLocator.class) {
                if (scansRepository == null) {
                    // Ensure DB is initialized
                    AppDatabase.getInstance(context.getApplicationContext());
                    scansRepository = new DefaultScansRepository();
                }
            }
        }
        return scansRepository;
    }

    /**
     * Provides the instance of the {@link CollectionsRepository} implementation. If the "Scan Library"
     * feature is disabled, it returns a no-op implementation of {@link CollectionsRepository}.
     * Otherwise, it initializes and returns the default implementation of {@link CollectionsRepository},
     * ensuring that the database is initialized when required.
     *
     * @param context the application context used to initialize or retrieve the repository and database
     * @return an instance of {@link CollectionsRepository}, either a no-op implementation or the default implementation
     */
    public static CollectionsRepository getCollectionsRepository(Context context) {
        if (!isFeatureEnabled()) {
            return new CollectionsRepository() {
                @Override
                public CollectionEntity createCollection(Context ctx, String name) {
                    return null;
                }

                @Override
                public java.util.List<CollectionEntity> getAllCollections(Context ctx) {
                    return java.util.Collections.emptyList();
                }

                @Override
                public void assignScanToCollection(Context ctx, String scanId, String collectionId) {
                }

                @Override
                public void removeScanFromCollection(Context ctx, String scanId, String collectionId) {
                }

                @Override
                public boolean deleteCollectionIfEmpty(Context ctx, String collectionId) {
                    return false;
                }

                @Override
                public int countItems(Context ctx, String collectionId) {
                    return 0;
                }

                @Override
                public boolean renameCollection(Context ctx, String collectionId, String newName) {
                    return false;
                }

                @Override
                public java.util.List<CollectionEntity> getCollectionsForScan(Context ctx, String scanId) {
                    return java.util.Collections.emptyList();
                }

                @Override
                public CollectionEntity getOrCreateDefaultCompletedCollection(Context ctx) {
                    return null;
                }
            };
        }
        if (collectionsRepository == null) {
            synchronized (LibraryServiceLocator.class) {
                if (collectionsRepository == null) {
                    // Ensure DB is initialized
                    AppDatabase.getInstance(context.getApplicationContext());
                    collectionsRepository = new DefaultCollectionsRepository();
                }
            }
        }
        return collectionsRepository;
    }
}