package de.schliweb.makeacopy.utils;

/**
 * A utility class for handling feature flags within the application.
 * <p>
 * This class provides methods to dynamically check whether specific
 * features are enabled or disabled based on the application's build
 * configuration. The feature flags are assumed to be defined as
 * static fields in the BuildConfig class.
 * <p>
 * This class is not intended to be instantiated or extended.
 */
public final class FeatureFlags {
    private FeatureFlags() {
    }

    /**
     * Determines whether the OCR review feature is enabled in the application's build configuration.
     * The method checks for the presence of the "FEATURE_REVIEW_OCR" field in the application's
     * BuildConfig class and retrieves its boolean value.
     *
     * @return true if the "FEATURE_REVIEW_OCR" field exists and is set to true; false otherwise,
     * including cases where the field is not found or an exception occurs.
     */
    public static boolean isOcrReviewEnabled() {
        try {
            Class<?> c = de.schliweb.makeacopy.BuildConfig.class;
            java.lang.reflect.Field f = c.getField("FEATURE_REVIEW_OCR");
            return f.getBoolean(null);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /**
     * Determines whether the scan library feature is enabled in the application's build configuration.
     * The method checks for the presence of the "FEATURE_SCAN_LIBRARY" field in the application's
     * BuildConfig class and retrieves its boolean value.
     *
     * @return true if the "FEATURE_SCAN_LIBRARY" field exists and is set to true; false otherwise,
     * including cases where the field is not found or an exception occurs.
     */
    public static boolean isScanLibraryEnable() {
        try {
            Class<?> c = de.schliweb.makeacopy.BuildConfig.class;
            java.lang.reflect.Field f = c.getField("FEATURE_SCAN_LIBRARY");
            return f.getBoolean(null);
        } catch (Throwable ignore) {
            return false;
        }
    }
}
