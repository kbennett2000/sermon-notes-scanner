# Contributing to Sermon Scanner

Sermon Scanner is a **private, sideloaded fork** of
[MakeACopy](https://github.com/egdels/makeacopy), retargeted to import sermon-note handouts into
[songbird](https://github.com/kbennett2000/songbird). It is not a store app and is developed for one
operator's workflow, so this is a short guide rather than an open community process.

## Build & toolchain

The **authoritative, verified build recipe** — JDK 21, Android SDK/NDK 28, CMake 3.31.6, the Python venv
for the ONNX build, submodule init, the three native-lib scripts, and the Gradle assemble step — lives in
**[CLAUDE.md](CLAUDE.md)** under "Build & test". It is not duplicated here.

The app ships a single product flavor (**paddle**, PaddleOCR) targeting **arm64-v8a**; native OpenCV +
ONNX Runtime are built from source (the `./gradlew` assemble does **not** trigger that — run the scripts
first).

## The verification gate

A change is green only when this passes (it mirrors `.github/workflows/build-release.yml`):

```
./gradlew :app:compilePaddleDebugJavaWithJavac :app:testPaddleDebugUnitTest :app:lintPaddleDebug
```

`lintPaddleDebug` is part of the gate (`abortOnError` is on, no baseline) — assemble + unit tests alone do
**not** catch lint errors. Code is formatted with **Spotless / google-java-format**:

```
./gradlew :app:spotlessApply   # auto-format
./gradlew :app:spotlessCheck   # verify
```

## Methodology (how this fork is developed)

- **Plan first.** Investigate, write the plan, get it approved, then implement.
- **One PR per slice**, smallest reviewable unit; the maintainer merges. Never force-push `main`.
- **The scan/OCR core is frozen** — CameraX/Camera2 capture, the crop/perspective fragment, ONNX DocQuad
  corner detection, OpenCV enhancement, PaddleOCR inference, `OCRPostProcessor`, multi-page capture, the
  paddle model assets, and the native build. Its output quality is already validated on real handouts;
  don't touch it without a forced, re-validated reason (see the "Prime directive" in CLAUDE.md).
- **Pure logic gets offline, deterministic unit tests** (the anchor finder, the JSON emitter, the span
  resolver). Fixtures come from `docs/BUILD-BRIEF.md` or are synthetic — never bundle licensed Scripture.
- Pin behavior to the contracts in `docs/BUILD-BRIEF.md` (and the Environment errata in CLAUDE.md where
  reality supersedes the brief).

## Licensing & upstream

Apache-2.0; `LICENSE` and `NOTICE` are preserved verbatim and attribution is permanent. Improvements to
the underlying scanner belong **upstream** at [egdels/makeacopy](https://github.com/egdels/makeacopy);
this fork only carries the songbird-import workflow.
