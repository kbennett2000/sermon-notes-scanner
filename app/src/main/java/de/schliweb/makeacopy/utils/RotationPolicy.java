package de.schliweb.makeacopy.utils;

/**
 * Centralized rotation decision helpers used by thumbnails/previews and export paths.
 * Keeping the policy in one place allows simple JVM tests without Android runtime.
 */
public final class RotationPolicy {
    private RotationPolicy() {
    }

    /**
     * Returns true if a bitmap should be rotated for UI preview/thumbnail rendering.
     * - In-memory sources: rotate when rotationDeg != 0.
     * - Disk sources: rotate only when orientationMode == "metadata" and rotationDeg != 0.
     */
    public static boolean shouldRotateForThumbnail(boolean fromDisk, String orientationMode, int rotationDeg) {
        int deg = normalize(rotationDeg);
        if (deg == 0) return false;
        boolean isMetadata = orientationMode != null && "metadata".equalsIgnoreCase(orientationMode);
        if (!fromDisk) return true; // in-memory
        return isMetadata;          // disk
    }

    /**
     * Returns true if a bitmap should be rotated for export (PDF/ZIP/JPEG) before encoding.
     * Mirrors ExportFragment logic:
     * - If loadedFromFile=false (in-memory) → rotate when deg != 0.
     * - If loadedFromFile=true (disk) → rotate only for metadata entries (deg != 0).
     */
    public static boolean shouldRotateForExport(boolean loadedFromFile, String orientationMode, int rotationDeg) {
        int deg = normalize(rotationDeg);
        if (deg == 0) return false;
        boolean isMetadata = orientationMode != null && "metadata".equalsIgnoreCase(orientationMode);
        if (!loadedFromFile) return true;
        return isMetadata;
    }

    private static int normalize(int d) {
        int x = d % 360;
        return (x < 0) ? (x + 360) : x;
    }
}
