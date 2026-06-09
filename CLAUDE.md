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

Do not implement against a pending decision — ask first. Update this table when Kris locks one.

## Environment

- Devices: Samsung Tab A11, Galaxy S23. Sideloaded debug APK. OCR stays on-device — never
  move it to a server.
- songbird import: `POST {base_url}/api/v1/import`, header `Authorization: Bearer <token>`,
  body = ImportDocument. Response: `{"annotations": {"created": N, "skipped": M}}`. Idempotent.
  Reachable over Tailscale. Concord is NOT a runtime dependency.
- Secrets: the bearer token never enters the repo — runtime settings only.

## Build & test

To be filled in during F0b (green build). Until then, do not guess Gradle/NDK invocations —
discover them, verify, then document here.

## Methodology (non-negotiable)

- Plan Mode first: investigate, present the plan, wait for approval before writing code.
- One PR per slice; Kris merges. Never self-merge, never push `main`, no force-pushes.
- Default slice = smallest reviewable load-bearing unit; flag explicitly when combining.
- Verify, don't guess: pin behavior to the brief's contracts. When the codebase surprises you,
  report it rather than improvising.
- Pure logic (anchor finder, JSON emitter) gets offline, deterministic unit tests. Fixtures come
  from the brief or are synthetic — never bundle licensed Scripture text.

## Slice map

- [ ] F0a — fork + repo wiring (bootstrap)
- [ ] F0b — green build: sideloaded debug APK; capture → crop → OCR verified unchanged
- [ ] F1 — strip to barebones (brief §3 keep/strip lists)
- [ ] F2 — two-shot capture + concatenated OCR text
- [ ] F3 — book map + anchor finder (pure logic, fixture-tested)
- [ ] F4 — edit screen (text, anchor, title, tags)
- [ ] F5 — songbird JSON emitter (deterministic, Appendix A)
- [ ] F6 — finalize: POST / save-share per D4