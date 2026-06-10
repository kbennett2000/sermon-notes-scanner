# Sermon Scanner

A **private, sideloaded Android app** that photographs a two-sided printed sermon-note handout,
OCRs it on-device, lets the operator fix the text and confirm a Scripture anchor, and emits a
[songbird](https://github.com/kbennett2000/songbird)-compatible annotation **import JSON**
(sending it straight into songbird over the LAN). Not a store app.

It was built to digitize the weekly printed sermon-note handouts from **Majestic View Church**
(Kiowa, Colorado) and file them into [songbird](https://github.com/kbennett2000/songbird) as
Scripture-anchored notes — turning a paper handout into a searchable annotation in a couple of minutes.

The scan/OCR pipeline — CameraX capture, crop/perspective correction, ONNX DocQuad corner
detection, OpenCV enhancement, and **PaddleOCR** inference — is inherited unchanged from the
upstream project and validated on real handouts.

## How it works

1. **Scan** the handout — photograph the front, crop, OCR; then the back, crop, OCR.
2. The app **concatenates** the two pages and **auto-detects the Scripture passage** (the first
   structurally-resolvable reference, top-to-bottom).
3. **Review & fix** on one edit screen: correct the OCR text, confirm the anchor (book / chapter /
   verses, with a live passage label), and set the title, date, and tags.
4. **Finalize**: preview the exact import JSON, then **Send to songbird** (shows *created / skipped* —
   re-sending the same note is a harmless no-op) or **Share JSON** as a file.

## Status

Feature-complete. The full workflow is live end-to-end:

> snap front → crop → OCR → snap back → crop → OCR → **concatenate** →
> **auto-detect the Scripture anchor** → **edit screen** → **finalize**

- **Anchor auto-detection** — scans the combined OCR text top-to-bottom and resolves the first
  structurally valid Scripture reference using a 66-book USFM map (handles Roman numerals,
  abbreviations with periods, parenthesised cross-references). Best-effort; the operator fixes it.
- **Edit screen** — fix the OCR text, confirm the anchor (book picker + chapter/verse fields with a
  live passage label), and set the title, date, and tags. Out-of-range chapters block; the rest is
  best-effort with warnings.
- **Finalize** — preview the exact import JSON, then **Send to songbird** (`POST /api/v1/import`,
  showing the *created / skipped* result) or **Share JSON** as a file. Verse spans for whole-chapter
  references are filled from a bundled, offline verse-count table; the app never calls a Bible API at
  runtime.

### First run

songbird uses cookie-session login. Before **Send** is enabled, open **Settings** (from the finalize
screen) and enter the songbird base URL (e.g. `http://<host>:<port>`, reachable over the LAN/Tailscale),
your songbird **username**, and **password**. Credentials are stored encrypted on-device
(`EncryptedSharedPreferences`) and never logged. Until they're set you can still **Share JSON**.

## Fork & licensing

This is a fork of **MakeACopy** ([egdels/makeacopy](https://github.com/egdels/makeacopy)),
**custom-tailored to Majestic View's notes format** (the anchor-detection heuristic and book-name
aliases) and retargeted from a document-scanner to a songbird note importer. Licensed under the
**Apache License 2.0**; the `LICENSE` and `NOTICE` files are preserved verbatim and attribution is
retained permanently. See `NOTICE` for upstream and third-party attributions (OpenCV, ONNX Runtime,
PaddleOCR, and others).

## Developer notes — adapting to other note formats

If you keep your own sermon (or study) notes in songbird and want to OCR a *different* handout layout,
most of this app is reusable as-is; only a couple of spots encode Majestic View's conventions. The
authoritative spec is [`docs/BUILD-BRIEF.md`](docs/BUILD-BRIEF.md) (Appendix B = the book map,
Appendix C = a real OCR fixture); architecture and slice history are in [`CLAUDE.md`](CLAUDE.md).

- **Scan / OCR** (CameraX → OpenCV → PaddleOCR) is **format-agnostic** — it reads any printed page; no
  changes needed.
- **Anchor auto-detection** — `de.schliweb.makeacopy.anchor.AnchorFinder`. It assumes the passage is the
  *first structurally-resolvable Scripture reference scanning top-to-bottom* (true for Majestic View,
  whose handouts print the passage in the header). If your layout puts the reference elsewhere, or has
  several, adjust the heuristic here. The operator can always override on the edit screen.
- **Book recognition** — `anchor/BookMap.java` is a table-driven 66-book USFM map with normalization
  (Roman numerals → arabic, period-abbreviations, the `|john` OCR quirk). Adding an accepted spelling for
  a different abbreviation style is a one-line entry. (Spec: BUILD-BRIEF Appendix B.)
- **Note body** — `emit/NoteMarkdown.java` renders a deliberately minimal Markdown body (H1 title, a
  `passage — date` line, then each non-empty OCR line as a `- ` bullet). Reshape this if you want a
  different note structure (e.g. preserving an outline).
- **Verse-count table** — `tools/generate_verse_counts/` produces
  `app/src/main/assets/anchor/verse_counts.json` (used to fill whole-chapter end-verses). It's
  canon-structural and translation-agnostic; regenerate it from any [Concord](https://github.com/kbennett2000/concord)
  deployment.
- **songbird contract** — `emit/ImportJsonEmitter.java` (the import-JSON shape) and
  `songbird/HttpImportPoster.java` (cookie-session login → import) are reusable for any songbird
  instance; just point Settings at your URL + login.

## Building

The build is non-trivial (native OpenCV + ONNX Runtime compiled from source, paddle flavor,
arm64-v8a). The full, verified recipe — toolchain versions, submodule init, native-lib build,
and the Gradle assemble step — lives in **[CLAUDE.md](CLAUDE.md)** under "Build & test".
