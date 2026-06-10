# CLAUDE.md — sermon-notes-scanner

Stripped fork of MakeACopy (`egdels/makeacopy`, Apache-2.0) with one job: photograph a two-sided
printed sermon-note handout, OCR it on-device, let the operator fix text/anchor/title/tags on one
edit screen, and emit a songbird-compatible import JSON (optionally POSTing it straight into
songbird). Private, sideloaded, LAN-first. Not a store app.

**Authoritative spec: `docs/BUILD-BRIEF.md`.** The songbird JSON contract (Appendix A), the
book-name→USFM map + normalization rules (Appendix B), and the real OCR fixture with expected
anchor behavior (Appendix C) live there. If this file, the code, and the brief ever disagree:
stop and ask Kris.

## Prime directive — the scan/OCR core is frozen

Never modify: CameraX/Camera2 capture, the crop/perspective fragment, ONNX DocQuad corner
detection, OpenCV enhancement/dewarp, PaddleOCR inference, `OCRPostProcessor` text assembly
(`wordsToText()` on the live paddle path), multi-page capture, the 22 paddle model assets
(`app/src/paddle/assets/paddleocr/v5/`), or the native OpenCV/ONNX build setup.
Output quality is already validated on real handouts. Everything else in the app exists to be
deleted or replaced — when in doubt, removing UI is the default.

"Frozen" means the **validated scan behavior**, not byte-frozen files: nav/UI stitching edits
*inside* a frozen-core file are allowed when **forced** by deletion elsewhere (e.g. removing a
library button whose nav target no longer exists), must be minimal, and trigger on-device
re-validation of the affected flow. Electively editing a frozen file for hygiene is out of bounds.

`utils/ocr/DictionaryManager.java` is **RETAINED-DEAD**: it survives solely because its only inbound
reference is the paddle-dead `OCRPostProcessor.processWithDictionary()` inside byte-frozen
`OCRPostProcessor`. Do not open OCRPostProcessor to delete that method electively. Disposal rule: the
next slice that opens OCRPostProcessor for a forced/behavioral reason deletes `processWithDictionary()`
and DictionaryManager together.

## Fork & licensing

- `origin` = github.com/kbennett2000/sermon-notes-scanner (this repo — public fork)
- `upstream` = github.com/egdels/makeacopy (Apache-2.0)
- Preserve LICENSE and NOTICE/attribution permanently. Never push to upstream. Pulling upstream
  changes is a deliberate, reviewed slice of its own — never casual.

## Target workflow (the entire UX)

snap front → crop → OCR → snap back → crop → OCR → concatenate → auto-detect anchor →
edit screen (text, anchor, title, tags) → finalize (build JSON → POST to songbird and/or share file)

Until F4 lands the edit screen, the kept app terminates at the upstream **OCR result/review screen**
(`OcrReviewFragment`), which displays the recognized text. It is a temporary terminus, replaced in F4.
`ExportFragment` survives as the multi-page **page hub** (filmstrip, add/clear page, preview, per-page
OCR badge, registry persistence) but, since F1c-2, offers **no export of any kind** — all PDF/JPEG/ZIP/
TXT/share/inbox output was removed. Document files are not this fork's output; F5/F6 emit songbird JSON.

## Combined OCR text contract (F2 — the downstream artifact)

The **combined OCR string** is THE artifact F3 (anchor scan), F4 (edit screen), and F5 (emitter)
consume — page provenance is irrelevant after the join. It is produced **on-demand/derived** from the
page hub (no persistence, no cache, no database): `CombinedOcrTextProvider.fromPages(pages)` reads each
page's plain text and hands the ordered list to the pure `OcrTextJoiner.join(List<String>)`. Contract:

- **Order** = hub order (`ExportSessionViewModel.getPages()` — filmstrip order incl. operator reorder;
  front first in normal use).
- Each page's text is **trimmed** (leading/trailing); internal whitespace untouched.
- `null`/empty/whitespace-only pages **contribute nothing** (no stray separators).
- Survivors are joined with **exactly `"\n\n"`** — plain whitespace, no marker tokens or headers, so the
  brief's Appendix C concatenation stays valid verbatim for F3's scanner.
- **Deterministic**: same pages + order + disk ⇒ byte-identical output.

Per-page text comes from `scans/{id}/text.txt` (the validated `wordsToText()` plain text, co-written by
both write sites); the provider never parses `words.json` and never touches frozen-core OCR. `OcrTextJoiner`
is pure (no Android deps) and fixture-tested.

## Output contract (summary — full spec is brief Appendix A)

- ImportDocument: `version: 1`, `exported_at: null`, exactly one annotation, `sermon_notes: []`.
- Annotation: five flat anchor fields (`book_usfm`, `start_chapter`, `start_verse`,
  `end_chapter`, `end_verse`) + `note_markdown`, `color: null`, `scope_type: "all"`,
  `scope_translations: []`, `tags: ["sermon"]` default. No nested anchor object, no ids,
  no timestamps.
- `note_markdown`: plain Markdown only — `#` headings, `-` bullets, `>` blockquotes.
  **No emphasis (italic/bold/underline), ever — locked rule.**
- Emitter is deterministic: identical inputs → byte-identical JSON. songbird dedupes on content;
  re-import must be a no-op.

## Anchor rule (summary — details + fixture in brief Appendix C)

The anchor is the **first structurally resolvable** Scripture reference scanning the concatenated
OCR text top-to-bottom: a known book token (Appendix B map, after normalization: leading Roman
numeral → arabic, lowercase, strip whitespace and `.`) followed by a chapter, with optional
`:verse[-verse]`. No Concord call, no verse/chapter-existence checking. Best-effort only — the
edit screen is the safety net. Canonical fixture: brief Appendix C must yield `1SA 22:1`.
The Appendix C fixture is **PaddleOCR** output captured from the stock app — the same engine this fork ships (D5).

Implemented in F3 as pure logic in `de.schliweb.makeacopy.anchor`: `BookMap` (Appendix B, ported
verbatim) + `AnchorFinder.find(String) → Optional<StructuralAnchor>` over the F2 combined string.
`StructuralAnchor` keeps a chapter-only reference chapter-only; five-field span / end-verse fill (D2)
is F3b, not done here. The fixture lives at `app/src/test/resources/anchor/appendix_c_fixture.txt`.

## Decisions

| # | Question | Status |
|---|---|---|
| D1 | Note body = minimal: `# title`, `passage — date` line, edited OCR lines as flat list | pending — recommended yes |
| D2 | Chapter-only anchor end-verse via bundled offline verse-count table generated from Concord | **locked (a) — implemented in F3b** |
| D3 | English only. Strip non-English OCR UI/data; ship only the model/data files English recognition requires. | locked |
| D4 | Finalize = POST to songbird `/api/v1/import`, save/share as fallback; base URL + token in settings | pending — recommended yes |
| D5 | PaddleOCR only (re-locked, inverted from F1a). The stock build Kris validated runs PaddleOCR — confirmed on-device by side-by-side comparison and the in-app engine notice. Tesseract/standard flavor removed in F1b. Changing OCR engine invalidates the validated-quality premise — spec change, ask Kris first. | locked |

Do not implement against a pending decision — ask first. Update this table when Kris locks one.

## Environment

- Devices: Samsung Tab A11, Galaxy S23. Sideloaded debug APK. OCR stays on-device — never
  move it to a server.
- songbird import: `POST {base_url}/api/v1/import`, header `Authorization: Bearer <token>`,
  body = ImportDocument. Response: `{"annotations": {"created": N, "skipped": M}}`. Idempotent.
  Reachable over Tailscale. Concord is NOT a runtime dependency.
- Secrets: the bearer token never enters the repo — runtime settings only.

## Build & test

Verified in F0b on Linux x86_64 (8 cores) for the **arm64-v8a** target (Tab A11 + S23). Produces
unsigned debug APKs for both OCR flavors. Native OpenCV/ONNX are built from source (frozen — see Prime
directive); `./gradlew` does **not** trigger that — the three scripts must run first.

### One-time toolchain (CI-pinned versions, per `.github/workflows/build-release.yml`)
- **JDK 21** (Temurin): `export JAVA_HOME=/path/to/jdk-21 PATH=$JAVA_HOME/bin:$PATH`.
- **Android SDK**: platform API 36 + build-tools 36.x, and **NDK 28.0.13004108**
  (`sdkmanager "ndk;28.0.13004108"`; `sdkmanager --licenses` once).
- **CMake 3.31.6** from cmake.org (the SDK's cmake package has no 3.31.x).
- **Python for the ONNX build** — the ONNX script calls bare `python3`, and that interpreter must have
  ORT's build deps or the minimal-build step dies with `cannot import name 'parse_config' from 'util'`
  (flatbuffers missing). Make a venv and put it first on PATH:
  ```
  python3.12 -m venv ~/ort-venv
  ~/ort-venv/bin/pip install -r external/onnxruntime/requirements.txt   # flatbuffers numpy packaging protobuf sympy
  export PATH=~/ort-venv/bin:$PATH        # so `python3` resolves to this venv
  ```

### Submodules (~2–4 GB download, a few minutes)
```
git submodule update --init --recursive   # external/opencv @4.13.0, external/onnxruntime (+5 nested)
```

### Build native libs — arm64-v8a (~15 min on 8 cores)
```
export ANDROID_HOME=/path/to/Android/Sdk ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.0.13004108
export ABIS="arm64-v8a" BUILD_GENERATOR="Unix Makefiles"
export OPENCV_CMAKE=/path/to/cmake-3.31.6/bin/cmake ORT_CMAKE=$OPENCV_CMAKE
bash scripts/build_opencv_android.sh        # -> /tmp/opencv-build/lib/arm64-v8a/*.so   (~8 min)
bash scripts/prepare_opencv.sh              # -> app/src/main/jniLibs/arm64-v8a/
bash scripts/build_onnxruntime_android.sh   # -> jniLibs/ + app/libs/onnxruntime-1.24.1.jar (~6 min)
```

### Assemble debug APK — paddle (PaddleOCR) flavor, arm64-v8a only (~1 min after deps cached)
```
echo "sdk.dir=$ANDROID_HOME" > local.properties      # gitignored
./gradlew :app:assemblePaddleDebug -PenableAbiSplits=true -PABIS=arm64-v8a
```
- Output APK: `app/build/outputs/apk/paddle/debug/app-paddle-arm64-v8a-debug.apk` (~154 MB).
- applicationId `io.github.kbennett2000.sermonscanner` (coexists with stock MakeACopy).
  Sideload (device not assumed connected): `adb install -r <apk>`; uninstall:
  `adb uninstall io.github.kbennett2000.sermonscanner`.

Notes: paddle is the sole flavor (F1b, D5) — Tesseract removed; there is no `assembleStandardDebug`. The
packaged ONNX runtime carries **DocQuad + PaddleOCR** ops; the on-disk `libonnxruntime.so` is the F0b
merged-ops build, and a fresh clone rebuilds the same because `build_onnxruntime_android.sh` re-merges the
restored paddle ops config (`app/src/paddle/assets/paddleocr/v5/paddleocr_v5.required_operators.config`).
`jniLibs/`, `app/libs/*.jar`, and `local.properties` are gitignored — the native build never dirties the tree
and submodule gitlink SHAs stay at upstream's pins. For all four ABIs (CI default) set
`ABIS="arm64-v8a armeabi-v7a x86 x86_64"` for both the scripts and `-PABIS=...`. Dropping
`-PenableAbiSplits=true` yields one fat universal APK. The remaining APK size is unstripped non-English OCR
UI/data (trimmed in F1c per D3).

## Methodology (non-negotiable)

- Plan Mode first: investigate, present the plan, wait for approval before writing code.
- One PR per slice; Kris merges. Never self-merge, never push `main`, no force-pushes.
- Default slice = smallest reviewable load-bearing unit; flag explicitly when combining.
- Verify, don't guess: pin behavior to the brief's contracts. When the codebase surprises you,
  report it rather than improvising.
- Pure logic (anchor finder, JSON emitter) gets offline, deterministic unit tests. Fixtures come
  from the brief or are synthetic — never bundle licensed Scripture text.

## Slice map

- [x] F0a — fork + repo wiring (bootstrap)
- [x] F0b — green build: sideloaded debug APK; capture → crop → OCR verified unchanged
- [x] F1a — fork identity: applicationId io.github.kbennett2000.sermonscanner, label "Sermon Scanner", v0-upstream-baseline tag (engine choice inverted by F1b)
- [x] F1b — engine correction: paddle restored as sole flavor, Tesseract removed
- [x] F1c — strip (re-scoped to safe non-code islands): deleted Tesseract data assets (tessdata 66 MB + dictionaries 4 MB; fonts kept — PDF-coupled), store scaffolding (fastlane/ + F-Droid metadata/), langpack-latin-best module + its CI workflow + copyBestModelsForTest, the unused libs.tesseract alias, and the release-CI publish/signing; README rewrite. The strip's entangled CODE is re-sliced along DI seams (below) because this app is heavily Hilt-wired — features interconnect through DI + the Application + the export screen, not clean islands.
- [x] F1c-2 — export-output excision: removed ExportFragment export-output + PDF/JPEG/ZIP/TXT/share/inbox classes (PdfCreator, PdfTextUtils, PdfQualityPreset, PageFormat, JpegExporter, JpegExportOptions, InboxExporter, ExportTxtHelper, ExportOptionsDialogFragment, ExportUiBindings) + pdfbox & documentfile deps + FEATURE_INBOX_MODE + orphaned export strings; trimmed ExportPrefsHelper to skip_ocr/pending_add_page/last_import_uri. Page hub kept (filmstrip, add/clear page, preview, OCR badge, ScanPersister persistence). Kept-and-deferred to F1c-3: ShareIntentHelper (used by ui/library/ScanDetailsFragment) + ScanLibraryIndexer.
- [x] F1c-3 — library/Room subgraph removed: deleted ui/library, data/library (Room), di/DatabaseModule, ui/export/ScanLibraryIndexer, ui/ocr/review/suggest/DictionarySuggestProvider, utils/export/ShareIntentHelper, library buttons/nav (incl. forced minimal edits to CameraFragment + fragment_camera.xml), Room deps, FEATURE_SCAN_LIBRARY + FeatureFlags.isScanLibraryEnable; MakeACopyApplication + CacheCleanupService de-Roomed (startup is now OpenCV → CacheCleanupService, no DB at launch). The app is **database-free**: scan → view text → dead-end (until F2–F6). DictionaryManager was **de-wired but KEPT (RETAINED-DEAD)** to preserve OCRPostProcessor byte-zero-diff (see prime directive). CompletedScansRegistry/RegistryCleaner/ScanPersister (JSON registry, Room-free) kept.
- [x] F2 — combined OCR text artifact: pure `OcrTextJoiner.join(List<String>)` (hub order, per-page trim, `"\n\n"` seam, null/blank skipped, deterministic, 10 unit tests) + `CombinedOcrTextProvider.fromPages()` glue reading each page's `text.txt` on-demand (no persistence, no frozen-core edits) + TEMP long-press proof hook on the hub preview (removed at F4). Two-shot capture is the pre-existing multi-page hub; this slice delivers the concatenation. Contract recorded under "Combined OCR text contract".
- [x] F3 — book map + anchor finder (pure logic, fixture-tested): `de.schliweb.makeacopy.anchor` — `BookMap` (Appendix B verbatim, 66 books + `|john`→1JN, collision-guarded), `StructuralAnchor` (record; chapter-only stays chapter-only — D2/F3b not bundled), `AnchorFinder.find()` (first resolvable ref, token-window scan, book recognition is pure BookMap lookup). `BookMapTest` (10) + `AnchorFinderTest` (17, incl. Appendix C fixture → `1SA 22:1` by design). No UI/wiring (F4); no frozen-core edits.
- [x] F3b — verse-count table + span resolver (D2): `de.schliweb.makeacopy.anchor` — `SpanResolver.resolve(StructuralAnchor, VerseTable)` → `ResolvedSpan` (five Appendix A fields) or typed `SpanResolution` failure (`UNKNOWN_BOOK`/`CHAPTER_OUT_OF_RANGE`). Chapter-only fills `1..table[ch]`; single verse `start=end`; range passes through; verses never validated (§6). `VerseTable` (pure Gson parser) reads the **canon-structural** table at `app/src/main/assets/anchor/verse_counts.json` (counts = canonical structure; `source_translation`/`concord_version` are provenance only). Regenerate offline with `python3 tools/generate_verse_counts/generate_verse_counts.py --base-url <concord> --translation <id> --concord-version <v>` — never a runtime dep. Tests: `SpanResolverTest` (8) + `VerseTableTest` (6) on a sample table; `VerseCountsSchemaTest` enforces the real asset (66 keys == BookMap, positive counts). Asset loading is F4 wiring; no UI / no frozen-core edits.
- [ ] F4 — edit screen (text, anchor, title, tags)
- [ ] F5 — songbird JSON emitter (deterministic, Appendix A)
- [ ] F6 — finalize: POST / save-share per D4