/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.anchor;

/**
 * Renders the human passage label for a {@link StructuralAnchor} (slice F4) — what the operator sees on
 * the edit screen and what F5 carries into the note body's "{passage} — {date}" line. It reflects WHAT
 * WAS ENTERED (chapter-only stays chapter-only); it is not the resolved five-field span.
 *
 * <ul>
 *   <li>chapter-only → {@code "1 Samuel 25"}
 *   <li>single verse → {@code "1 Samuel 25:3"}
 *   <li>range → {@code "1 Samuel 25:3-6"}
 * </ul>
 *
 * <p>Pure Java — no Android dependencies.
 */
public final class PassageLabel {

  private PassageLabel() {}

  /**
   * Formats the label, using {@link BookNames} for the display name (falling back to the raw USFM code
   * if unknown). Returns {@code ""} for a null anchor.
   */
  public static String format(StructuralAnchor anchor) {
    if (anchor == null) return "";
    String name = BookNames.displayName(anchor.bookUsfm());
    if (name == null) name = anchor.bookUsfm();
    StringBuilder sb = new StringBuilder(name).append(' ').append(anchor.chapter());
    if (anchor.startVerse() != null) {
      sb.append(':').append(anchor.startVerse());
      if (anchor.endVerse() != null) {
        sb.append('-').append(anchor.endVerse());
      }
    }
    return sb.toString();
  }
}
