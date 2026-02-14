package de.schliweb.makeacopy.utils.layout;

import android.util.Log;
import org.opencv.core.Mat;

/**
 * Utility class for common image validation operations used by layout detection classes.
 */
public class ImageValidator {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private ImageValidator() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Validates that an image is not null, not empty, and meets minimum size requirements.
     *
     * @param image      the Mat image to validate
     * @param tag        the logging tag to use for error messages
     * @param methodName the method name to include in log messages
     * @param minWidth   minimum required width (typically 100)
     * @param minHeight  minimum required height (typically 100)
     * @return true if the image is valid, false otherwise
     */
    public static boolean validateImage(Mat image, String tag, String methodName, int minWidth, int minHeight) {
        if (image == null || image.empty()) {
            Log.w(tag, methodName + ": empty or null image");
            return false;
        }

        int width = image.cols();
        int height = image.rows();

        if (width < minWidth || height < minHeight) {
            Log.d(tag, methodName + ": image too small (" + width + "x" + height + 
                  ", minimum required: " + minWidth + "x" + minHeight + ")");
            return false;
        }

        return true;
    }

    /**
     * Validates that an image is not null, not empty, and meets a minimum size of 100x100.
     *
     * @param image      the Mat image to validate
     * @param tag        the logging tag to use for error messages
     * @param methodName the method name to include in log messages
     * @return true if the image is valid, false otherwise
     */
    public static boolean validateImage(Mat image, String tag, String methodName) {
        return validateImage(image, tag, methodName, 100, 100);
    }
}
