# Security Policy

## Reporting a Vulnerability

We take security seriously and appreciate your efforts to responsibly disclose any security vulnerabilities you discover.

### How to Report

**Please do NOT open a public GitHub issue for security vulnerabilities.** Instead, please report security vulnerabilities to the project maintainers privately.

1. **Use GitHub's Security Advisory Form**
   - Navigate to: https://github.com/kbennett2000/sermon-notes-scanner/security/advisories/new
   - This creates a private report visible only to repository maintainers

2. **Or contact the maintainers directly**
   - Send an email describing the vulnerability with details about:
     - The type of vulnerability
     - Location in the code (if applicable)
     - Potential impact
     - Any suggested fixes or workarounds

### What to Include

Please provide as much information as possible to help us understand and assess the vulnerability:

- **Description** - A clear description of the vulnerability
- **Affected Version(s)** - Which version(s) of Sermon Scanner are affected
- **Steps to Reproduce** - How to reproduce the issue (if applicable)
- **Potential Impact** - What could an attacker do with this vulnerability?
- **Suggested Fix** - If you have a suggestion for fixing the issue (optional)
- **Your Contact Information** - How we can reach you if we need more details

## Security Response Timeline

We will make our best effort to:

1. **Acknowledge receipt** - Respond within 3-5 business days to confirm we received your report
2. **Investigate** - Work to understand and reproduce the issue
3. **Develop a fix** - Create and test a patch
4. **Coordinate disclosure** - Work with you on a responsible disclosure timeline
5. **Release patch** - Issue a security update and publicly disclose the vulnerability

Depending on the complexity and severity of the vulnerability, this process may take anywhere from a few days to a few weeks.

## Disclosure Policy

We follow a responsible disclosure process:

1. After receiving your report, we will work to verify and understand the vulnerability
2. We will attempt to fix the issue and release a patch
3. We will coordinate with you on the timing of public disclosure
4. We will credit you in the security advisory if you wish (with your permission)
5. We aim to have a patch available before public disclosure, when possible

## Supported Versions

This is a private, sideloaded fork with no versioned release channel — `main` is the supported version.
Build and sideload from the latest `main`; security fixes land there.

## Security Considerations

### On-device OCR, LAN-only networking

OCR runs **entirely on-device** (PaddleOCR via ONNX Runtime + OpenCV); images never leave the phone for
recognition. The app's **only** network egress is the operator-initiated **import POST to a
user-configured [songbird](https://github.com/kbennett2000/songbird) instance** over the LAN/Tailscale —
there are no third-party servers, no cloud storage, no tracking or analytics, and no telemetry.

- ✅ OCR and all document processing stay on-device
- ✅ The single network call is operator-initiated, to a self-hosted server the operator configures
- ✅ No cloud storage of user data; no tracking/analytics
- Network specifics:
  - songbird credentials (username + password) are stored in **`EncryptedSharedPreferences`** and are
    never logged or echoed.
  - Auth is songbird's cookie-session; the session cookie is held only for the duration of a send.
  - **Cleartext HTTP is permitted** (via `res/xml/network_security_config.xml`) because songbird is a
    LAN/tailnet service without TLS; revisit if it is ever fronted by HTTPS.

Other ongoing considerations: input validation for document processing, safe handling of OCR models and
image processing, secure storage of cached data, and safe file I/O.

### Dependencies

Key third-party components:

- **ONNX Runtime** - ML inference engine (DocQuad corner detection + PaddleOCR)
- **OpenCV** - Image processing library
- **PaddleOCR** - on-device OCR models
- **androidx.security-crypto** - EncryptedSharedPreferences for the songbird credentials

All dependencies are regularly updated to patch known vulnerabilities. We use tools like:

- Gradle dependency scanning
- GitHub's Dependabot for monitoring CVEs
- Regular security audits

### Code Review

All code changes, including security fixes, go through:

- Code review by maintainers
- Automated testing (unit and instrumented tests)
- Lint checks and code quality analysis
- Compatibility testing on multiple Android versions

### Android Security Features

Sermon Scanner leverages Android's built-in security features:

- **Sandboxing** - Each app instance is isolated from others
- **Permission System** - Users must grant permissions for camera, storage, etc.
- **SELinux** - Android's mandatory access control framework
- **Code Signing** - All releases are cryptographically signed

### Builds

There are no official store releases. The app is built from source and **sideloaded as an unsigned debug
APK** (the build uses fixed tool versions — JDK 21, NDK 28, CMake 3.31.6 — per CLAUDE.md "Build & test").
Verify provenance by building from `main` yourself; there is no Play Store / F-Droid distribution and no
published signing key for this fork.

## Staying Informed

- ⭐ Watch the GitHub repository for activity
- 🔔 Enable notifications for security advisories

## Scope

This security policy covers:

- The Sermon Scanner application code
- Sideloaded debug builds from `main`
- The project documentation and build infrastructure

This security policy does **not** cover:

- Third-party forks or modified versions
- Older versions beyond the supported versions listed above
- Dependencies maintained by third parties (though we will help coordinate fixes)

## Questions or Concerns?

If you have questions about this security policy or security in general, feel free to open a private security advisory or discussion in the repository.

---

**Thank you for helping keep Sermon Scanner secure!**

We appreciate the security research community and responsible disclosure practices that help make our project safer for all users.

