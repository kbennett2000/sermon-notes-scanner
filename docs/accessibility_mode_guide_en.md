# MakeACopy – Accessibility Mode

This guide explains step by step how to use Accessibility Mode in MakeACopy. The mode is designed to operate the camera without visual cues — using clear spoken announcements and gentle haptic feedback instead.

Note: When this guide mentions “tap”, “double tap”, or “button”, it refers to typical screen reader usage such as TalkBack.

---

Contents
- What does Accessibility Mode do?
- Turn on Accessibility Mode
- Operating the camera (flow)
- Feedback in detail
- Using the volume keys to capture
- Behavior in low light (flashlight)
- Success and error messages after capture
- Tips for good results
- Privacy and offline operation
- Frequently Asked Questions (FAQ)

---

What does Accessibility Mode do?
- Focuses on audio and haptic feedback. The visual corner overlay follows the "Preview corner detection" option.
- Speaks important states, e.g., “Camera ready”, “Low light”, “Document detected – ready to capture.”
- Provides alignment guidance when needed, e.g., “Move left”, “Move up”, “Move closer”, “Tilt forward”.
- Allows capturing using the hardware volume keys so you don’t have to aim for a small on‑screen button.
- Works fully offline — no data connection, no upload.

Turn on Accessibility Mode
1) In the camera screen, open the “Options” button at the bottom.
2) Enable the checkbox “Accessibility Mode”.
3) Confirm with “Confirm”.

From now on:
- You will receive spoken hints and short haptic feedback. If you enable "Preview corner detection" in Options, the corner overlay is shown; otherwise it stays hidden.

Operating the camera (flow)
1) Open the camera: After initialization you’ll hear “Camera ready. Double tap Scan to capture.”
2) Align: Move the device over the document. The system continuously analyzes the camera image.
3) Good framing detected: As soon as the document is detected well and stably, you’ll hear a short confirmation tone, a light vibration, and the announcement “Document detected – ready to capture.”
4) Capture:
   - Double tap the “Scan” button OR
   - Press one of the two volume keys (see section below).

Feedback in detail
- Camera ready: Spoken as soon as the camera starts.
- Document detected (stable):
  - Short tone
  - Light haptic feedback
  - Spoken announcement “Document detected – ready to capture.”
  - Note: These signals are rate‑limited so they don’t repeat constantly.
- Low light: Instead of a pop‑up dialog, you’ll hear “Low light detected. Double tap Flash to enable.”
- Toggle flashlight: When switching, you’ll hear “Flashlight on/off”.

 Alignment guidance (examples)
 - Movement:
   - “Move left” / “Move right”
   - “Move up” / “Move down”
 - Distance:
   - “Move closer” / “Move back”
 - Tilt:
   - “Tilt left” / “Tilt right”
   - “Tilt forward” / “Tilt back”
 - Alignment OK: “Document aligned”

 Frequency notes:
 - Announcements are intentionally calm: speech occurs only after brief stability (hysteresis) and at most about every 1–1.5 seconds (rate limit).
 - “OK” (aligned) is typically only spoken when entering a good state or after a longer quiet period to avoid repetition.

Using the volume keys to capture
- When Accessibility Mode is active, you can capture with Volume Up or Volume Down.
- The app suppresses the actual volume change and triggers capture instead.
- A short vibration is provided on key press.
- Inputs are debounced: Pressing repeatedly within about 0.8 seconds won’t trigger multiple captures.
- While an image is being processed, key presses are ignored to avoid conflicts.

Behavior in low light (flashlight)
- If the app detects low light, it does not show a blocking prompt in Accessibility Mode.
- Instead, you’ll hear a spoken recommendation to enable the flashlight.
- Double tap the “Flash” button to enable/disable it. You will hear “Flashlight on” or “Flashlight off”.


Success and error messages after capture
- Saved successfully: “Image captured.” plus a short vibration.
- Capture failed: “Capture failed.” — you can trigger capture again afterwards.


Tips for good results
- Distance: Hold the device so the document fully fits in the frame (typically 20–40 cm / 8–16 in above the page).
- Stability: A brief moment of steady holding helps detection.
- Alignment: If possible, keep the device parallel to the document surface.
- Light: Turn on the flashlight in shadows or very low light.

Privacy and offline operation
- MakeACopy processes images locally on your device.
- No upload or sharing takes place in Accessibility Mode. No internet connection required.

Frequently Asked Questions (FAQ)
Q: I hear “Document detected” very often.
A: The app already limits repetitions. Move the device slightly away and realign. When the detection is stable you’ll get a tone + vibration + announcement.

Q: The volume keys still change the volume.
A: This should not happen in Accessibility Mode while the camera preview is visible. Ensure Accessibility Mode is enabled and the camera is on screen. While processing an image, key presses are ignored.

Q: It’s very dark and detection doesn’t work.
A: Turn on the flashlight. Try to illuminate the document as evenly and shadow‑free as possible.


Q: Do I need to enable the visual “Preview corner detection” (Analysis) for Accessibility Mode to work?
A: No. Accessibility Mode runs the required analysis internally even if the visual analysis option is turned off. The app still analyzes frames to provide audio/haptic feedback.

Q: If both “Preview corner detection” (Live analysis) and Accessibility Mode are enabled, is the camera preview visible?
A: Yes. The normal camera preview remains visible. If "Preview corner detection" is enabled, the visual corner overlay is also shown even in Accessibility Mode. Analysis runs in the background regardless, to power scoring and feedback.

Q: Does Accessibility Mode work without TalkBack?
A: Yes. Spoken output is done as a system announcement. With TalkBack, you also get the usual screen reader navigation.

—

Contact
If anything is unclear or you have suggestions to improve accessibility, we welcome feedback in app store reviews or in the project repository.
