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

Never modify: CameraX/Camera2 capture, the crop/perspective fragment, ONNX corner detection,
OpenCV enhancement/dewarp, the Tesseract OCR integration, or the native OpenCV/ONNX build setup.
Output quality is already validated on real handouts. Everything else in the app exists to be
deleted or replaced — when in doubt, removing UI is the default.

## Fork & licensing

- `origin` = github.com/kbennett2000/sermon-notes-scanner (this repo — public fork)
- `upstream` = github.com/egdels/makeacopy (Apache-2.0)
- Preserve LICENSE and NOTICE/attribution permanently. Never push to upstream. Pulling upstream
  changes is a deliberate, reviewed slice of its own — never casual.

## Target workflow (the entire UX)

snap front → crop → OCR → snap back → crop → OCR → concatenate → auto-detect anchor →
edit screen (text, anchor, title, tags) → finalize (build JSON → POST to songbird and/or share file)

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

## Decisions

| # | Question | Status |
|---|---|---|
| D1 | Note body = minimal: `# title`, `passage — date` line, edited OCR lines as flat list | pending — recommended yes |
| D2 | Chapter-only anchor end-verse via bundled offline verse-count table generated from Concord | pending — recommended (a) |
| D3 | OCR languages to keep | pending |
| D4 | Finalize = POST to songbird `/api/v1/import`, save/share as fallback; base URL + token in settings | pending — recommended yes |
| D5 | Standard (Tesseract) flavor only; paddle removed in F1a. Changing OCR engine would invalidate the validated-quality premise — spec change, ask Kris first. | locked |

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

### Assemble debug APK — standard (Tesseract) flavor, arm64-v8a only (~1 min after deps cached)
```
echo "sdk.dir=$ANDROID_HOME" > local.properties      # gitignored
./gradlew :app:assembleStandardDebug -PenableAbiSplits=true -PABIS=arm64-v8a
```
- Output APK: `app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk` (~262 MB).
- applicationId `io.github.kbennett2000.sermonscanner` (coexists with stock MakeACopy).
  Sideload (device not assumed connected): `adb install -r <apk>`; uninstall:
  `adb uninstall io.github.kbennett2000.sermonscanner`.

Notes: paddle flavor removed in F1a (D5) — there is no `assemblePaddleDebug` and no paddle APK. A fresh
ONNX rebuild is now **DocQuad-only** (the paddle ops config is gone; `build_onnxruntime_android.sh` skips
it via its file-exists guard), which is exactly what the Tesseract flavor needs. `jniLibs/`, `app/libs/*.jar`,
and `local.properties` are gitignored — the native build never dirties the tree and submodule gitlink SHAs
stay at upstream's pins. For all four ABIs (CI default) set `ABIS="arm64-v8a armeabi-v7a x86 x86_64"` for both
the scripts and `-PABIS=...`. Dropping `-PenableAbiSplits=true` yields one fat universal APK. The big debug
APK size is unstripped OCR language data/fonts (trimmed in F1b).

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
- [x] F1a — fork identity: applicationId io.github.kbennett2000.sermonscanner, label "Sermon Scanner", paddle flavor removed, v0-upstream-baseline tag
- [ ] F1b — strip to barebones (brief §3 keep/strip lists + D3 English-only OCR)
- [ ] F2 — two-shot capture + concatenated OCR text
- [ ] F3 — book map + anchor finder (pure logic, fixture-tested)
- [ ] F4 — edit screen (text, anchor, title, tags)
- [ ] F5 — songbird JSON emitter (deterministic, Appendix A)
- [ ] F6 — finalize: POST / save-share per D4