# Sermon Scanner

A **private, sideloaded Android app** that photographs a two-sided printed sermon-note handout,
OCRs it on-device, lets the operator fix the text and confirm a Scripture anchor, and emits a
[songbird](https://github.com/kbennett2000/songbird)-compatible annotation **import JSON**
(optionally POSTing it straight into songbird over the LAN). Not a store app.

The scan/OCR pipeline — CameraX capture, crop/perspective correction, ONNX DocQuad corner
detection, OpenCV enhancement, and **PaddleOCR** inference — is inherited unchanged from the
upstream project and validated on real handouts.

## Status

Feature-complete per its brief. The full workflow is live end-to-end:

> snap front → crop → OCR → snap back → crop → OCR → **concatenate** →
> **auto-detect the Scripture anchor** → **edit screen** → **finalize**

- **Anchor auto-detection** — scans the combined OCR text top-to-bottom and resolves the first
  structurally valid Scripture reference using a 66-book USFM map (handles Roman numerals,
  abbreviations with periods, parenthesised cross-references). Best-effort; the operator fixes it.
- **Edit screen** — fix the OCR text, confirm the anchor (book picker + chapter/verse fields with a
  live passage label), and set the title, date, and tags. Out-of-range chapters block; the rest is
  best-effort with warnings.
- **Finalize** — preview the exact import JSON, then **Send to songbird** (`POST /api/v1/import`,
  showing the *created / skipped* result — re-sending the same note is a harmless no-op) or
  **Share JSON** as a file. Verse spans for whole-chapter references are filled from a bundled,
  offline verse-count table; the app never calls a Bible API at runtime.

### First run

Before **Send** is enabled, open **Settings** (from the finalize screen) and enter the songbird
base URL (e.g. `http://<host>:8000`, reachable over the LAN/Tailscale) and bearer token. The token
is stored encrypted on-device. Until then you can still **Share JSON**.

## Fork & licensing

This is a fork of **MakeACopy** ([egdels/makeacopy](https://github.com/egdels/makeacopy)),
licensed under the **Apache License 2.0**. The `LICENSE` and `NOTICE` files are preserved
verbatim and attribution is retained permanently. See `NOTICE` for upstream and third-party
attributions (OpenCV, ONNX Runtime, PaddleOCR, and others).

## Building

The build is non-trivial (native OpenCV + ONNX Runtime compiled from source, paddle flavor,
arm64-v8a). The full, verified recipe — toolchain versions, submodule init, native-lib build,
and the Gradle assemble step — lives in **[CLAUDE.md](CLAUDE.md)** under "Build & test".
