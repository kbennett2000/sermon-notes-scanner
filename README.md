# MakeACopy

MakeACopy is an open-source document scanner app for Android that allows you to digitize paper documents with OCR functionality. The app is designed to be privacy-friendly, working completely offline without any cloud connection or tracking.

## Design Philosophy

MakeACopy treats privacy and offline operation as technical design decisions, not as moral or political statements.
The app works fully offline, is open source, and avoids tracking or cloud services because this results in a simpler, more robust, and more auditable system.
Users are encouraged to evaluate MakeACopy based on its concrete features and implementation rather than on positioning or narratives.

### App Size Note

To ensure fully offline operation, MakeACopy bundles OCR language models, fonts, and image processing components locally.
This significantly increases the app size, but avoids any cloud dependencies or on-demand downloads.

**100 MB are not a sign of excess, but of consistency.**

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/de.schliweb.makeacopy/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
     alt="Get it on Google Play"
     height="80">](https://play.google.com/store/apps/details?id=de.schliweb.makeacopy)

Or download the latest APK from the [Releases Section](https://github.com/egdels/makeacopy/releases/latest).

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## APK Verification

All official releases of MakeACopy are signed with one of the following certificates:

- **Upload key** (used for GitHub releases, F-Droid, and sideload APKs)  
  SHA-256: AE:32:2D:3F:B7:1A:FE:21:DF:47:27:E3:7A:5C:68:03:51:1D:5A:2F:E1:FC:31:35:43:0C:EE:06:99:FA:1B:34  

- **Google Play App Signing key** (used for Play Store releases)  
  SHA-256: C0:71:44:39:CB:51:62:32:A4:47:91:7A:6F:C2:28:1E:45:FA:AA:DD:37:F8:30:B1:01:1F:B4:85:68:8E:0D:64  
  
### Verify with apksigner
```bash
apksigner verify --print-certs MakeACopy-vX.Y.Z.apk
```

## Features

- **Camera Scanning**: Capture documents using the device camera
- **Edge Detection**: Automatic document edge detection using OpenCV, enhanced with a machine learning model ([ONNX, from DocAligner](https://github.com/DocsaidLab/DocAligner) – Apache 2.0)
- **Perspective Correction**: Adjust and crop documents with manual or automatic perspective correction
- **Image Enhancement**: Apply filters (grayscale, contrast, sharpening)
- **OCR**: Offline text recognition with Tesseract (fast models included, optional best models via Language-Pack APKs)
- **OCR Review**: Interactive review and correction of OCR results with word-level editing, dictionary-based suggestions, confidence highlighting, and re-OCR for individual words
- **PDF Export**: Save as searchable PDF with recognized text
- **JPEG Export**: Export scans as high-quality JPEG images (configurable quality, color/BW)
- **Multi-page Scanning**: Combine multiple pages into one document; reorder and manage pages before export
- **Last Scans**: Quickly access and reuse your most recent scans
- **Share & Save**: Export locally or share with other apps
- **Dark Mode**: Material 3 theme with day/night support
- **Privacy-Focused**: 100% offline functionality, no internet connection required

### Accessibility Mode 
- MakeACopy includes an Accessibility Mode that provides spoken and haptic feedback and lets you use the hardware volume keys as the shutter.
- Guide:
  - English: docs/accessibility_mode_guide_en.md
  - Deutsch: docs/accessibility_mode_guide_de.md
  - Français: docs/accessibility_mode_guide_fr.md

### OCR Languages

MakeACopy supports OCR via Tesseract. You can choose the OCR language during the OCR step.

Supported out of the box:
- English (eng), German (deu), French (fra), Italian (ita), Spanish (spa), Portuguese (por), Dutch (nld), Polish (pol), Czech (ces), Slovak (slk), Hungarian (hun), Romanian (ron), Danish (dan), Norwegian (nor), Swedish (swe), Russian (rus), Thai (tha)
- Chinese (Simplified) — chi_sim, Chinese (Traditional) — chi_tra
- Arabic (ara), Persian/Farsi (fas)

Notes:
- All OCR runs fully offline on-device.
- For Chinese, MakeACopy includes appropriate CJK fonts for better PDF text embedding.
- For Arabic and Persian, MakeACopy includes the Noto Naskh Arabic font for proper RTL (right-to-left) text rendering in PDF exports.

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1_en-US.png" alt="MakeACopy screenshot 1" width="110" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_en-US.png" alt="MakeACopy screenshot 2" width="110" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3_en-US.png" alt="MakeACopy screenshot 3" width="110" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4_en-US.png" alt="MakeACopy screenshot 4" width="110" />
</p>

## Installation

### F-Droid

MakeACopy is F-Droid compliant. The app builds all required native components from source during CI/local builds:

1. **OpenCV Java Classes**: The required OpenCV Java wrapper classes are directly included in the app's source tree (copied from OpenCV but now part of this project). They are no longer used from the submodule.
2. **OpenCV Native Libraries**: All OpenCV native libraries are built from source using the official OpenCV code provided via the Git submodule at `external/opencv`.
3. **ONNX Runtime Native Libraries**: For ML-assisted edge detection, ONNX Runtime is built from source (XNNPACK and NNAPI, Java bindings) using the submodule at `external/onnxruntime` via `scripts/build_onnxruntime_android.sh`. The resulting artifacts are integrated into `app/src/main/jniLibs/<ABI>/` (e.g., `libonnxruntime.so`, `libonnxruntime4j_jni.so`) and `app/libs/` (`onnxruntime-*.jar`).

This approach ensures F-Droid compatibility by not including any pre-compiled binaries in the repository and building OpenCV and ONNX Runtime native components from source.

### GitHub Releases

You can download the latest APK from the [Releases](https://github.com/egdels/makeacopy/releases) page.

#### Automated Builds

All automated builds are handled by a single GitHub Actions workflow:

- Workflow: .github/workflows/build-release.yml
- Triggers: on push to main, pull_request to main, and tags starting with v*

What the workflow does on every run (aligned with CI):
- Sets up JDK 17 (Temurin) and installs Android NDK 28.0.13004108
- Pins CMake 3.31.6 and Python 3.11.2 for deterministic native builds
- Checks out submodules (external/opencv and external/onnxruntime)
- Builds OpenCV native libraries from source via scripts/build_opencv_android.sh
- Collects reproducibility evidence for native builds (scripts/collect_repro_evidence.sh)
- Integrates OpenCV artifacts into the app via scripts/prepare_opencv.sh
- Builds ONNX Runtime for Android (XNNPACK and NNAPI, Java bindings) via scripts/build_onnxruntime_android.sh
- Builds the Android app with Gradle (AAB and ABI-split APKs)

Behavior by event type:
- Push/PR to main (non-tag):
  - Builds unsigned artifacts (Release AAB and per-ABI Release APKs)
  - Uploads all artifacts for download from the workflow run
- Tag (refs/tags/vX.Y.Z):
  - Optionally decodes a keystore from repository secrets and signs the builds
  - Verifies APK signatures using apksigner
  - Renames artifacts to MakeACopy-vX.Y.Z-<abi>-release.apk and MakeACopy-vX.Y.Z-release.aab
  - Generates SHA-256 checksum files for each artifact
  - Loads release notes from fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
  - Creates a GitHub Release and attaches all APKs, the AAB, and their .sha256 files
  - Uploads artifacts to the workflow as well

How to trigger a release build:
1. Create a tag starting with v (e.g., v1.0.0)
2. Push the tag to GitHub
3. The workflow will build native libraries (OpenCV, ONNX Runtime) from source, build the app, sign (if secrets provided), verify, checksum, and publish a GitHub Release with artifacts

Notes:
- All native components are built from source to stay F-Droid compatible; no prebuilt binaries are stored in the repo.
- A look at .github/workflows/build-release.yml shows you how the build process works and how you can reproduce it in your own development environment.

## Architecture

MakeACopy follows the Single-Activity + Multi-Fragment pattern with MVVM architecture.

- **Camera Fragment**: Capture via CameraX/Camera2
- **Crop Fragment**: Perspective correction
- **OCR Fragment**: Tesseract-based recognition
- **Export Fragment**: PDF/text export

## Libraries Used

| Purpose | Library / Model | License |
|--------|-----------------|---------|
| Image Processing | OpenCV for Android | Apache 2.0 |
| Document Corner Detection | [DocAligner ONNX model](https://github.com/DocsaidLab/DocAligner) | Apache 2.0 |
| OCR | tess-two (Tesseract JNI) | Apache 2.0 |
| PDF | Android PdfDocument, pdfbox-android | Apache 2.0 |
| UI | Material Components | Apache 2.0 |
| OCR Dictionaries | [FrequencyWords](https://github.com/hermitdave/FrequencyWords) | CC BY-SA 4.0 |

### OCR Dictionaries

MakeACopy uses word frequency dictionaries for OCR post-processing to improve text recognition accuracy.

**Source:** [FrequencyWords by Hermit Dave](https://github.com/hermitdave/FrequencyWords)  
**License:** [Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)](https://creativecommons.org/licenses/by-sa/4.0/)  
**Data Origin:** Wikipedia word frequency lists

The dictionaries are used to validate and correct OCR results by checking recognized words against known word lists. This helps reduce common OCR errors while maintaining fully offline operation.

Included dictionaries cover 21 languages: Arabic (ara), Czech (ces), Danish (dan), German (deu), English (eng), Persian (fas), French (fra), Hungarian (hun), Italian (ita), Dutch (nld), Norwegian (nor), Polish (pol), Portuguese (por), Romanian (ron), Russian (rus), Slovak (slk), Spanish (spa), Swedish (swe), Thai (tha), Chinese Simplified (chi_sim), and Chinese Traditional (chi_tra).

## Submodules

- external/opencv — OpenCV source used to build native libraries during the build; Apache 2.0.
- external/onnxruntime — ONNX Runtime source required for ML-assisted corner detection; MIT License.
  - Built from source via scripts/build_onnxruntime_android.sh (XNNPACK and NNAPI, Java bindings).
  - Artifacts integrated into app/src/main/jniLibs/<ABI>/ (libonnxruntime.so, libonnxruntime4j_jni.so) and app/libs/ (onnxruntime-*.jar).
  - See [NOTICE](NOTICE) for attributions.

### Optional OCR Language-Packs

MakeACopy includes fast, compact Tesseract models by default.  
If you want **higher accuracy OCR**, you can install optional *Language-Pack APKs*:

- **MakeACopy OCR Latin (Best)** → Includes best-quality models for  
  English (eng), German (deu), French (fra), Italian (ita), Spanish (spa), Portuguese (por), Dutch (nld), Polish (pol), Czech (ces), Slovak (slk), Hungarian (hun), Romanian (ron), Danish (dan), Norwegian (nor), Swedish (swe).

How it works:
- Language-Packs are separate, permissionless APKs.
- They only contain `.traineddata` files, no code, no network.
- MakeACopy discovers installed packs locally and lets you import the models.
- Once imported, the best models appear in the OCR language selection.

📦 [GitHub Release Page](https://github.com/egdels/makeacopy/releases/tag/langpack-latin-best-v1.1.0)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
alt="Get it on F-Droid"
height="40">](https://f-droid.org/packages/de.schliweb.makeacopy.lang.latin.best/)
[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
alt="Get it on Google Play"
height="40">](https://play.google.com/store/apps/details?id=de.schliweb.makeacopy.lang.latin.best)

Notes:
- Packs are signed with the same key as the main app, so you can verify them with `apksigner`.
- They rarely change (only when new Tesseract models are published).

Alternatively: You can also use the original Tesseract "best" models from the official tessdata repository.  
- Source: https://github.com/tesseract-ocr/tessdata_best  
- Tesseract project: https://github.com/tesseract-ocr/tesseract  
Install or download the desired `.traineddata` files and add them via the same import dialog in MakeACopy.

## Privacy

MakeACopy respects your privacy:

- Works 100% offline
- No tracking, telemetry, or analytics
- No cloud upload
- Requires only camera and storage permissions

See our [Privacy Policy](https://egdels.github.io/makeacopy/privacy) for details.

## Contributing

Contributions welcome!

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

## Future Enhancements

- Editable OCR text export
- Add more languages to OCR detection
- Further ML-based scan enhancements (quality and speed)

## 🙌 Community Hall of Fame

A big thank you to the people who make *MakeACopy* better:

- Our very first supporter — created an issue, helped with a fix, and even made a donation.  
  This is the spirit of open source: collaboration, improvement, and appreciation.
- **KalleMerkt** (from the [android-hilfe.de](https://www.android-hilfe.de/) community) – provided valuable suggestions to make the user experience smoother and more intuitive.
- **tofg** – stayed persistent and helped track down a tricky SD card issue affecting some devices. Thanks to thorough testing, the fix now benefits all users.
- **Felix H.** – supported the project with a kind donation at a moment of self-doubt,  
  a reminder that *MakeACopy* truly means something to people out there.
- **teastrainer** – tested several release candidates with remarkable precision and patience,  
  helping to refine the crop and rotation workflow step by step.  
  A great example of constructive collaboration.
- **hekele1022** – tested multiple release candidates on a Sony Xperia device, providing logs and feedback that confirmed the new camera system works reliably without any device-specific hacks.  
  A key contribution that helped make *MakeACopy* fully cross-device compatible.
- **djayko** – helped test Sony builds with great patience in real-world use, confirming the stability of the new camera flow.  
  A calm, steady tester spirit that every open-source project dreams of.
- **pvagner (Peter)** – initiated and co-shaped the **Accessibility Mode** through issue-driven discussion and extensive real-world testing.  
  His detailed feedback led to substantial improvements in camera guidance, OCR rotation and export behavior, and the accessibility documentation (see issue #44).

  Working together with Peter on accessibility was genuinely enjoyable.  
  The collaboration was constructive, thoughtful, and ultimately helped improve *MakeACopy* as a whole.

### 📝 Notes from the Field

- A public review claimed that *MakeACopy* installs other apps and drains the battery.  
  This is technically impossible, as the app only requests the `CAMERA` permission.  
  This highlights how misunderstandings can sometimes appear in public reviews.

- An anonymous email criticized the application size without providing actionable feedback.  
  While not directly addressable, it serves as a reminder that open-source projects may also receive non-constructive input.

Want to join the Hall of Fame?  
Contribute code, file helpful issues, or support the project.

## ❤️ Support this project
MakeACopy is free and open source.
If you find it useful, please consider supporting development:

[![Ko-fi](https://img.shields.io/badge/Buy%20me%20a%20coffee-Ko--fi-orange)](https://ko-fi.com/egdels)
[![PayPal](https://img.shields.io/badge/Donate-PayPal-blue)](https://www.paypal.com/paypalme/egdels)

## License

```
Copyright 2025 Christian Kierdorf

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Technical Documentation

For a complete technical overview of the project, see [TECHNICAL_DOCUMENTATION.md](docs/TECHNICAL_DOCUMENTATION.md).
