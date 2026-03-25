/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.infra;

import lombok.experimental.UtilityClass;

/**
 * A utility class for handling feature flags within the application.
 *
 * <p>This class provides methods to dynamically check whether specific features are enabled or
 * disabled based on the application's build configuration. The feature flags are assumed to be
 * defined as static fields in the BuildConfig class.
 *
 * <p>This class is not intended to be instantiated or extended.
 */
@UtilityClass
public class FeatureFlags {

  /**
   * Determines whether the OCR review feature is enabled in the application's build configuration.
   * The method checks for the presence of the "FEATURE_REVIEW_OCR" field in the application's
   * BuildConfig class and retrieves its boolean value.
   *
   * @return true if the "FEATURE_REVIEW_OCR" field exists and is set to true; false otherwise,
   *     including cases where the field is not found or an exception occurs.
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
   * Determines whether the scan library feature is enabled in the application's build
   * configuration. The method checks for the presence of the "FEATURE_SCAN_LIBRARY" field in the
   * application's BuildConfig class and retrieves its boolean value.
   *
   * @return true if the "FEATURE_SCAN_LIBRARY" field exists and is set to true; false otherwise,
   *     including cases where the field is not found or an exception occurs.
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

  /**
   * Feature flag: enables logging of FramingEngine results during camera analysis. This does not
   * change any UI behavior and is safe for experimental builds.
   */
  public static boolean isFramingLoggingEnabled() {
    try {
      Class<?> c = de.schliweb.makeacopy.BuildConfig.class;
      java.lang.reflect.Field f = c.getField("FEATURE_FRAMING_LOGGING");
      return f.getBoolean(null);
    } catch (Throwable ignore) {
      return false;
    }
  }

  /**
   * Feature flag: enables experimental Accessibility Guidance based on FramingEngine results. When
   * disabled, no additional announcements are produced during preview.
   */
  public static boolean isA11yGuidanceEnabled() {
    try {
      Class<?> c = de.schliweb.makeacopy.BuildConfig.class;
      java.lang.reflect.Field f = c.getField("FEATURE_A11Y_GUIDANCE");
      return f.getBoolean(null);
    } catch (Throwable ignore) {
      return false;
    }
  }

  /**
   * Feature flag: enables Inbox Mode for automatic export to a default directory. When enabled,
   * users can configure a default export folder and skip the file picker during export.
   */
  public static boolean isInboxModeEnabled() {
    try {
      Class<?> c = de.schliweb.makeacopy.BuildConfig.class;
      java.lang.reflect.Field f = c.getField("FEATURE_INBOX_MODE");
      return f.getBoolean(null);
    } catch (Throwable ignore) {
      return false;
    }
  }

  /**
   * Feature flag: enables layout analysis for complex documents. When enabled, OCR can detect and
   * process multi-column documents and tables with optimized settings for each region.
   */
  public static boolean isLayoutAnalysisEnabled() {
    try {
      Class<?> c = de.schliweb.makeacopy.BuildConfig.class;
      java.lang.reflect.Field f = c.getField("FEATURE_LAYOUT_ANALYSIS");
      return f.getBoolean(null);
    } catch (Throwable ignore) {
      return false;
    }
  }
}
