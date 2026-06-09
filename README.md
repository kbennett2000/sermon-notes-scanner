# Sermon Scanner

A **private, sideloaded Android app** that photographs a two-sided printed sermon-note handout,
OCRs it on-device, lets the operator make a few edits, and emits a
[songbird](https://github.com/kbennett2000/songbird)-compatible annotation **import JSON**
(optionally POSTing it straight into songbird over the LAN). Not a store app.

The scan/OCR pipeline — CameraX capture, crop/perspective correction, ONNX DocQuad corner
detection, OpenCV enhancement, and **PaddleOCR** inference — is inherited unchanged from the
upstream project and validated on real handouts.

## Status

Work in progress. The app currently scans and shows recognized OCR text; the dedicated edit
screen, the Scripture-anchor detector, the songbird JSON emitter, and the finalize/POST step
are not built yet. After the current strip-down, the app terminates at the OCR result screen
(a temporary terminus until the edit screen lands).

## Fork & licensing

This is a fork of **MakeACopy** ([egdels/makeacopy](https://github.com/egdels/makeacopy)),
licensed under the **Apache License 2.0**. The `LICENSE` and `NOTICE` files are preserved
verbatim and attribution is retained permanently. See `NOTICE` for upstream and third-party
attributions (OpenCV, ONNX Runtime, PaddleOCR, and others).

## Building

The build is non-trivial (native OpenCV + ONNX Runtime compiled from source, paddle flavor,
arm64-v8a). The full, verified recipe — toolchain versions, submodule init, native-lib build,
and the Gradle assemble step — lives in **[CLAUDE.md](CLAUDE.md)** under "Build & test".
