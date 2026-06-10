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
 * The five-field Scripture span required by BUILD-BRIEF Appendix A (slice F3b). Produced by {@link
 * SpanResolver} from a {@link StructuralAnchor} + the bundled verse-count table.
 *
 * <p>Because a {@link StructuralAnchor} carries a single chapter, {@code endChapter} always equals
 * {@code startChapter} here. All four numeric coordinates are concrete (no nulls) — chapter-only
 * references have had their end-verse filled from the table per decision D2.
 *
 * <p>Pure value type; record {@code equals}/{@code hashCode} drive the resolver's unit tests.
 */
public record ResolvedSpan(
    String bookUsfm, int startChapter, int startVerse, int endChapter, int endVerse) {}
