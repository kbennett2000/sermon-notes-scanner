#!/usr/bin/env python3
# Copyright 2025 Christian Kierdorf
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
"""Generate the canon-structural verse-count table for the sermon-notes-scanner anchor span
resolver (slice F3b, decision D2).

This is a one-off tool Kris runs on his LAN against Concord (his self-hosted Bible API,
github.com/kbennett2000/concord). It is NEVER part of the app at runtime — the app ships the
generated JSON as a bundled asset and reads it offline.

The verse counts are treated as CANONICAL STRUCTURE. The source translation and Concord version
are recorded only as provenance metadata; the resolver ignores them.

Output (exactly):
  {
    "meta": {"source_translation": "...", "concord_version": "...",
             "generated_at": "...", "book_count": 66},
    "books": {"GEN": [31, 25, ...], ...}   # array index = chapter - 1
  }

Stdlib only (urllib/json/argparse/datetime) — no pip install required.

Concord endpoints used (see github.com/kbennett2000/concord README + docs/API.md):
  GET /v1/translations                          -> validate the translation id
  GET /v1/books                                 -> 66 books, each {id (USFM), chapter_count}
  GET /v1/chapters/{usfm}/{chapter}?translation=ID -> {verses:[{verse:N}, ...]}; count = max(verse)
There is no dedicated verse-count endpoint, so per-chapter counts come from the chapters route.
"""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

# The canonical 66 USFM codes — MUST stay identical to BookMap.java's codes. The generator fails
# loudly if Concord's /v1/books id set differs from this, so the bundled table's keys always match
# the app's BookMap (the Java VerseCountsSchemaTest enforces the same invariant from the other side).
EXPECTED_USFM = [
    # Old Testament (39)
    "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA",
    "1KI", "2KI", "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO",
    "ECC", "SNG", "ISA", "JER", "LAM", "EZK", "DAN", "HOS", "JOL", "AMO",
    "OBA", "JON", "MIC", "NAM", "HAB", "ZEP", "HAG", "ZEC", "MAL",
    # New Testament (27)
    "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL", "EPH",
    "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS",
    "1PE", "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
]

DEFAULT_OUT = "app/src/main/assets/anchor/verse_counts.json"


def get_json(base_url, path, query=None):
    """GET base_url + path (+query) and return parsed JSON, or exit with a clear error."""
    url = base_url.rstrip("/") + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        sys.exit(f"ERROR: GET {url} -> HTTP {e.code} {e.reason}")
    except urllib.error.URLError as e:
        sys.exit(f"ERROR: GET {url} -> {e.reason} (is Concord reachable on this network?)")


def main():
    ap = argparse.ArgumentParser(description="Generate the bundled verse-count table from Concord.")
    ap.add_argument("--base-url", required=True,
                    help="Concord base URL, e.g. http://concord-host:8000")
    ap.add_argument("--translation", required=True,
                    help="Concord translation id, e.g. KJV (see GET /v1/translations)")
    ap.add_argument("--concord-version", required=True,
                    help="Concord version string for provenance metadata (no version endpoint exists)")
    ap.add_argument("--out", default=DEFAULT_OUT,
                    help=f"output path (default: {DEFAULT_OUT})")
    args = ap.parse_args()

    base = args.base_url

    # 1) Validate the requested translation exists.
    translations = get_json(base, "/v1/translations").get("translations", [])
    ids = [t.get("id") for t in translations]
    match = next((i for i in ids if i and i.upper() == args.translation.upper()), None)
    if match is None:
        sys.exit(f"ERROR: translation '{args.translation}' not found. Available: {sorted(ids)}")
    translation_id = match
    print(f"Using translation: {translation_id}")

    # 2) Enumerate books and cross-check the USFM set against the canonical 66.
    books = get_json(base, "/v1/books").get("books", [])
    by_usfm = {}
    for b in books:
        usfm = b.get("id")
        if usfm:
            by_usfm[usfm] = b
    got = set(by_usfm)
    expected = set(EXPECTED_USFM)
    if got != expected:
        missing = sorted(expected - got)
        extra = sorted(got - expected)
        sys.exit(
            "ERROR: Concord book set does not match BookMap's 66 USFM codes.\n"
            f"  missing from Concord: {missing}\n"
            f"  unexpected from Concord: {extra}"
        )
    print(f"Books: {len(by_usfm)} (matches BookMap)")

    # 3) For each book x chapter, fetch the chapter and take max(verse) as the verse count.
    out_books = {}
    for usfm in sorted(EXPECTED_USFM):
        chapter_count = by_usfm[usfm].get("chapter_count")
        if not isinstance(chapter_count, int) or chapter_count < 1:
            sys.exit(f"ERROR: {usfm} has invalid chapter_count: {chapter_count!r}")
        counts = []
        for ch in range(1, chapter_count + 1):
            data = get_json(base, f"/v1/chapters/{usfm}/{ch}", {"translation": translation_id})
            verses = data.get("verses", [])
            nums = [v.get("verse") for v in verses if isinstance(v.get("verse"), int)]
            if not nums:
                sys.exit(f"ERROR: {usfm} {ch} returned no verses")
            count = max(nums)
            if len(verses) != count:
                print(f"  WARN: {usfm} {ch}: {len(verses)} verse rows but max verse {count} "
                      "(non-contiguous numbering); using max")
            counts.append(count)
        out_books[usfm] = counts
        print(f"  {usfm}: {chapter_count} chapters")

    payload = {
        "meta": {
            "source_translation": translation_id,
            "concord_version": args.concord_version,
            "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "book_count": 66,
        },
        "books": out_books,
    }

    import os
    out_dir = os.path.dirname(args.out)
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(payload, f, sort_keys=True, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"Wrote {args.out}")


if __name__ == "__main__":
    main()
