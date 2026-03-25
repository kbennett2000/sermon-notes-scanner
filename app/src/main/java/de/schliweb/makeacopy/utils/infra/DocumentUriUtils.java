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

import android.net.Uri;
import android.util.Log;

/**
 * Utility methods for deriving parent/folder URIs from document URIs returned by the system file
 * picker. These folder URIs are more suitable as {@code EXTRA_INITIAL_URI} hints for future picker
 * invocations than raw file URIs, which many OEM pickers (Xiaomi, Samsung, etc.) ignore.
 */
public final class DocumentUriUtils {

  private static final String TAG = "DocumentUriUtils";

  private DocumentUriUtils() {}

  /**
   * Attempts to derive a parent/folder document URI from a file document URI returned by the
   * picker. This folder URI is more suitable as {@code EXTRA_INITIAL_URI} for future picker
   * invocations. Returns {@code null} if derivation is not possible (e.g. non-document URI).
   */
  public static Uri deriveParentDocumentUri(Uri documentUri) {
    if (documentUri == null) return null;
    try {
      if (!android.provider.DocumentsContract.isDocumentUri(null, documentUri)) {
        // Not a document URI from a DocumentsProvider — cannot derive parent.
        // However, isDocumentUri with null context only checks URI structure (authority + path
        // pattern), which is sufficient here.
        // Fallback: try heuristic path-based parent for content URIs.
        return deriveParentFromPathSegments(documentUri);
      }
      String docId = android.provider.DocumentsContract.getDocumentId(documentUri);
      if (docId == null) return null;
      // Common document ID formats use ':' or '/' as separators (e.g. "primary:DCIM/photo.jpg").
      // Derive the parent by stripping the last path segment.
      int lastSep = Math.max(docId.lastIndexOf('/'), docId.lastIndexOf(':'));
      if (lastSep <= 0) return null; // root or no separator — cannot go higher
      // For ':' separator (e.g. "primary:DCIM/photo.jpg"), keep the prefix including ':'
      // For '/' separator, strip the filename
      String parentDocId;
      if (docId.lastIndexOf('/') > docId.lastIndexOf(':')) {
        parentDocId = docId.substring(0, docId.lastIndexOf('/'));
      } else {
        // Only root:filename — parent is just "root:"
        parentDocId = docId.substring(0, lastSep + 1);
      }
      return android.provider.DocumentsContract.buildDocumentUri(
          documentUri.getAuthority(), parentDocId);
    } catch (Exception e) {
      Log.w(TAG, "Could not derive parent document URI", e);
      return null;
    }
  }

  /**
   * Fallback: derive a parent URI from path segments for content URIs that are not standard
   * document URIs. Returns {@code null} if not feasible.
   */
  static Uri deriveParentFromPathSegments(Uri uri) {
    if (uri == null || !"content".equals(uri.getScheme())) return null;
    try {
      java.util.List<String> segments = uri.getPathSegments();
      if (segments == null || segments.size() < 2) return null;
      // Rebuild without the last segment
      Uri.Builder builder = uri.buildUpon().path(null);
      for (int i = 0; i < segments.size() - 1; i++) {
        builder.appendPath(segments.get(i));
      }
      return builder.build();
    } catch (Exception e) {
      Log.w(TAG, "Could not derive parent from path segments", e);
      return null;
    }
  }
}
