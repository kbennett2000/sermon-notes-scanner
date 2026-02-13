# MakeACopy Release Notes

## Upcoming Release (v3.1.0)

This release includes several important improvements and dependency updates across multiple development branches.

### Library Updates

#### OpenCV Update (4.13.0)
- **Branch**: `copilot/update-opencv-submodule`
- **Changes**:
  - Updated OpenCV from 4.11.0 to 4.13.0
  - Regenerated Java classes and JNI bindings for compatibility
  - Added photo.inl.hpp to pinned JNI files
  - Improved image processing capabilities
  - Enhanced build reproducibility

#### ONNX Runtime Update (v1.24.1)
- **Branch**: `copilot/update-onnxruntime-submodule`
- **Changes**:
  - Updated ONNX Runtime to v1.24.1
  - Improved ML model inference performance
  - Enhanced edge detection capabilities
  - Better compatibility with latest Android versions

### Code Quality Improvements

#### Refactoring Round 1
- **Branch**: `copilot/refactor-duplicated-code`
- **Changes**:
  - Moved duplicated `isUriReadable` method to FileUtils utility class
  - Moved duplicated `firstUriFromJson` method to FileUtils
  - Removed duplicated `decodeSampledBitmapFromFile` and `calculateInSampleSize` methods
  - Consolidated bitmap decoding logic into a single, maintainable implementation
  - Fixed inconsistent naming conventions
  - Cleaned up imports and formatting

#### Refactoring Round 2
- **Branch**: `copilot/refactor-duplicated-code-again`
- **Changes**:
  - Created `DocQuadGoldenTestBase` base class for test files
  - Refactored test files to extend the base class and remove duplicated methods
  - Fixed behavior preservation in `ColumnDetector`
  - Added private constructor to `ImageValidator` utility class
  - Improved code maintainability and test consistency

### Summary of Changes

**User-Facing Improvements:**
- Faster and more accurate document edge detection (ONNX Runtime update)
- Improved image processing quality (OpenCV update)
- Better overall app stability and performance

**Developer-Facing Improvements:**
- Significantly reduced code duplication
- Improved code maintainability
- Better test infrastructure
- Enhanced build reproducibility
- Updated to latest stable versions of core dependencies

**Technical Details:**
- OpenCV: 4.11.0 → 4.13.0
- ONNX Runtime: Previous version → v1.24.1
- Refactored ~10+ duplicated methods across multiple files
- Consolidated test infrastructure

### Backward Compatibility

All changes maintain backward compatibility with existing app data and settings. No user action required after updating.

### Privacy & Security

As always, MakeACopy:
- Operates 100% offline
- No cloud services or tracking
- All processing happens on-device
- No internet connection required

---

## Previous Releases

### v3.0.1 (Current Stable)
- Fixed repeated ML model loading during live camera analysis
- Document detection now starts significantly faster
- Fully offline, no cloud or tracking

### v3.0.0
- Major release with custom-trained ONNX model for edge detection
- Enhanced document corner detection using machine learning
- Improved OCR review functionality
- Added accessibility mode features

### v2.6.0
- OCR language improvements
- Performance optimizations
- Bug fixes and stability improvements
