# Build brief — "Sermon Scanner": a stripped MakeACopy fork that emits songbird import JSON

You are a senior technical advisor and prompt-engineer. The person you're working with (Kris,
GitHub `kbennett2000`) directs **Claude Code (cc)** for implementation; you design the work, write the
cc prompts, and review. He is an experienced software-engineering instructor who self-hosts everything
on a home LAN. This document is your complete context — you do not need anything from any prior
conversation.

## 1. What you're building

Fork the open-source Android app **MakeACopy** (`github.com/egdels/makeacopy`, Apache-2.0) and strip it
down to one purpose: photograph a two-sided printed **sermon-note handout**, OCR it on-device, let the
operator make a couple of edits, and emit/import a **songbird-compatible annotation JSON**.

**songbird** is Kris's self-hosted Scripture-annotation app (`github.com/kbennett2000/songbird`,
FastAPI/React/SQLite). The output of this fork is one *annotation* imported into songbird.

MakeACopy already does the hard part — its on-device OCR (Tesseract + an ONNX document-corner-detection
model + OpenCV crop/dewarp) produces results Kris has validated as excellent on his actual handouts.
**Do not touch the OCR/scan core.** The whole job is to remove everything else and add a thin
text → anchor → JSON path plus one edit screen.

## 2. Current pain → target workflow

Today Kris scans in stock MakeACopy, copies the recognized text to a notepad, cancels the PDF/JPEG
export he doesn't want, repeats for the back page, then hand-assembles and pastes into songbird. The
seam between "great OCR" and "songbird note" is manual.

**Target flow (the entire UX):**
1. Snap a photo of the **front** page → crop → OCR.
2. Snap the **back** page → crop → OCR. (MakeACopy's multi-page already supports a single session.)
3. App concatenates the two OCR texts and auto-detects the sermon's **anchor** (see §6).
4. **Edit screen**: operator can fix the OCR **text**, correct the **anchor**, set the **title**, and
   confirm **tags**. (OCR has garbles and the title is usually mangled — this step is expected, not a
   failure.)
5. **Finalize** → build the songbird import JSON → either POST it into songbird or save/share the file
   (see decision D4).

No notepad. No PDF. Snap, snap, tweak, done.

## 3. The starting codebase — keep vs. strip

MakeACopy is Single-Activity + multi-Fragment, MVVM, Java (~88%), with native OpenCV + ONNX Runtime
built from source and Tesseract4Android for OCR.

**Keep (untouched):** CameraX/Camera2 capture, crop/perspective fragment, the ONNX corner-detection +
OpenCV enhancement, the Tesseract OCR engine, and multi-page capture (needed for front+back).

**Strip:** searchable-PDF export, JPEG export, the OCR-review/word-edit UI (replaced by our simpler
edit screen), multi-language pickers and dictionary-management UI (keep just the OCR language(s) Kris
needs — confirm in D3), Inbox-mode / SambaLite / paperless workflow extras, "Last Scans," and any
F-Droid/Play store scaffolding not needed for a private sideloaded build. When in doubt, removing UI is
the default — this is meant to be barebones.

**License note:** Apache-2.0, so the fork and any reuse of MakeACopy's own code is fine. Preserve the
NOTICE/attribution.

## 4. Environment & integration points

- **Devices:** Android (Samsung Tab A11, Galaxy S23). Kris always uses one of these to take the photo —
  on-device OCR is the right place for it; do not try to move OCR to a server.
- **songbird + Concord** run on the same always-on LAN box, reachable from the phone over **Tailscale**.
- **songbird import endpoint (the consumer of this fork's output):**
  `POST /api/v1/import`, header `Authorization: Bearer <token>`, body = the ImportDocument JSON in
  Appendix A. Returns a summary like `{"annotations": {"created": N, "skipped": M}}`. Idempotent: the
  same document re-imported creates nothing.
- **Concord** is Kris's self-hosted Bible API (`github.com/kbennett2000/concord`), also reachable over
  Tailscale. The fork does **not** require Concord at runtime (anchor "resolvability" is purely
  structural — a known book token + sane numbers; see §6). It is only relevant to decision D2 (how to
  fill the anchor's end-verse for a whole-chapter passage).

## 5. How Kris works (apply this methodology to every cc prompt)

- **Spec-first.** cc runs in **Plan Mode** and shows the plan before writing code; he reviews, then cc
  implements.
- **One PR per slice; he merges.** cc never self-merges, never pushes to `main`, no force-pushes.
- **Smallest reviewable load-bearing unit** is the default slice size. Combine only with a clear reason,
  and flag it when you do.
- **Verify, don't guess.** Pin behavior to the real contracts in this brief (the JSON shape, the book
  map, the real OCR sample). Unit-test pure logic; keep tests offline/deterministic.
- Deliver every cc prompt in a **code fence** so he can copy-paste it.
- Start by confirming the open decisions in §7 with Kris, then produce the first cc prompt.

## 6. The anchor rule (and a real-data finding that shapes it)

**Rule:** the sermon's anchor is the **first resolvable Scripture reference** scanning the concatenated
OCR text top-to-bottom. The passage is printed in the header, so the first reference is normally the
sermon passage. If auto-detection is wrong, the edit screen (step 4) lets the operator fix it — so
best-effort is fine; do not over-engineer.

"**Resolvable**" means *structurally* resolvable, with **no Concord call**: a recognized book token
(from the map in Appendix B, after normalization) followed by a chapter, with an optional `:verse` or
`:verse-verse`. No verse/chapter-existence checking — that's deliberately out of scope.

Emit the anchor as five flat fields: `book_usfm`, `start_chapter`, `start_verse`, `end_chapter`,
`end_verse` (see Appendix A). For `1 Samuel 25:3-6` → `1SA 25:3 → 25:6`. For a chapter-only passage
like `1 Samuel 25`, the end-verse needs deciding — see decision D2.

**Critical finding from real OCR (Appendix C):** a comprehensive book map is mandatory. The reference
forms that actually appear in Kris's handouts include **Roman numerals** (`I Samuel`, `II Kings`) and
**abbreviations with periods** (`1Sam.`, `Mt.`, `Ps.`). A naive/narrow map that only knows full
spellings will skip all of those and land the anchor on an unrelated cross-reference. Concretely, on the
Appendix C sample (a sermon on **1 Samuel 25**), a narrow map skips `I Samuel 24:1-2`, `(1Sam. 22:1)`,
and `I Samuel 25`, and wrongly resolves the first thing it recognizes — `John 1:14-17`, the final line.
With the Appendix B map it lands on `1 Samuel 22:1` (right book; operator nudges the chapter to 25).

So: **port the full map in Appendix B**, apply the normalization + Roman-numeral rule, and write the
anchor-finder's unit tests against the Appendix C sample.

## 7. Decisions to confirm with Kris before building

- **D1 — note body content.** Kris leans toward *minimal*: the annotation's `note_markdown` =
  title heading + a passage/date line + the (operator-edited) OCR text, with **no verse hydration** and
  **no outline-structure reconstruction**. Recommend D1 = minimal. (To preserve the outline's line
  breaks in Markdown, render each non-empty OCR line as a list item, or convert single newlines to hard
  breaks — flat, no parsing.) Confirm he doesn't want structured nesting or inlined verses.
- **D2 — anchor end-verse for a whole-chapter passage.** Verse-ranges parse directly; only a
  chapter-only anchor (e.g. `1 Samuel 25`) needs a last-verse. Options: (a) bundle a small offline
  verse-count table (book→chapter→verse-count) and look it up — fully offline, deterministic, gets the
  span right; (b) call Concord over Tailscale at finalize to read the canonical span; (c) MVP shortcut:
  `start_verse = end_verse = 1`. Recommend (a). **Do not hand-author the verse counts** — generate the
  table once from Concord (authoritative) or a known public dataset and bundle it.
- **D3 — OCR languages to keep.** English only, or English + German? (Affects how much language UI/data
  to strip.)
- **D4 — finalize action.** POST the JSON straight into songbird's `/api/v1/import` over Tailscale
  (closes the loop, no manual import), or just save/share the JSON file? Recommend the POST, with
  save/share as a fallback. If POST: where do the songbird base URL + bearer token live (settings
  screen)?

## 8. Suggested slice plan (turn each into a cc Plan-Mode prompt)

- **F0 — fork & green build.** Fork MakeACopy; get it building and running a sideloaded debug APK with
  the native OpenCV/ONNX libs; confirm capture → crop → OCR works unchanged. No feature work.
- **F1 — strip to barebones.** Remove the components listed in §3. App still scans and shows OCR text;
  everything off-workflow is gone.
- **F2 — two-shot capture + concatenate.** Front then back in one session; concatenate the two OCR
  texts (with a page separator) into one working string.
- **F3 — book map + anchor-finder (pure logic, unit-tested).** Port the Appendix B map + normalization
  (incl. Roman-numeral → arabic) + the disambiguation rules; scan for the first resolvable
  `book chapter[:verse[-verse]]`; emit the anchor. Tests assert against the Appendix C sample (lands on
  `1SA 22:1`) plus targeted cases (`I Samuel 25:3-6`, `1Sam.`, `Mt. 4:11`, bare `(1-9)` → not
  resolvable alone).
- **F4 — edit screen.** Editable OCR text + editable anchor (book/chapter/verse or a single reference
  field) + editable title + tags. Pre-filled from F2/F3.
- **F5 — songbird JSON emitter (to Appendix A, deterministic).** Build the ImportDocument; body per D1;
  anchor span per D2; tags default `["sermon"]`. Byte-stable output for the same inputs.
- **F6 — finalize.** Per D4: POST to songbird `/api/v1/import` (show the created/skipped result) and/or
  save/share the JSON. Settings for base URL + token if POSTing.

F3 and F5 are the load-bearing logic and deserve the most test care. F0/F1 are setup and deletion.

---

# Appendix A — songbird import JSON contract (authoritative)

This is exactly what the fork must emit. It mirrors songbird's `ExportDocument` / `AnnotationExport`
(songbird `backend/songbird/api/schemas.py`). Reference implementation already exists in Kris's
`sermon-notes-ocr` repo (`app/songbird/schema.py`) and is the source of this spec.

## Complete one-annotation example (the whole document)

```json
{
  "version": 1,
  "exported_at": null,
  "annotations": [
    {
      "book_usfm": "1SA",
      "start_chapter": 25,
      "start_verse": 1,
      "end_chapter": 25,
      "end_verse": 44,
      "note_markdown": "# A Story about David & Abigail — 'Grace & Truth'\n\n1 Samuel 25 — 2026-05-10\n\n- I. The key people in the story.\n- A. Abigail ...\n",
      "color": null,
      "scope_type": "all",
      "scope_translations": [],
      "tags": ["sermon"]
    }
  ],
  "sermon_notes": []
}
```

## Invariants (always true for this fork's output)

- `version` = `1`; `exported_at` = `null` (typed null, deliberately — keeps re-emits byte-identical);
  `sermon_notes` = `[]`; exactly **one** annotation.
- Per annotation: `color` = `null`; `scope_type` = `"all"`; `scope_translations` = `[]`;
  `tags` defaults to `["sermon"]`.

## Annotation fields

| field | type | required | constraint / value |
|---|---|---|---|
| `book_usfm` | string | yes | USFM 3-char code (e.g. `JHN`, `1SA`, `PSA`) |
| `start_chapter` | int | yes | ≥ 1 |
| `start_verse` | int | yes | ≥ 1 |
| `end_chapter` | int | yes | ≥ `start_chapter` |
| `end_verse` | int | yes | ≥ `start_verse` when same chapter |
| `note_markdown` | string | yes | the entire note body (see below) |
| `color` | string\|null | no | emit `null` |
| `scope_type` | string | no | emit `"all"` |
| `scope_translations` | array<string> | no | emit `[]` |
| `tags` | array<string> | no | emit `["sermon"]` (or per D3/settings) |

There is **no** nested anchor object and **no** anchor string — just the five flat fields. No DB ids,
no `created_at`/`updated_at`. (In the reference implementation the end-coordinates come from a Concord
call that echoes the canonical verse span; this fork instead fills them per decision D2.)

## `note_markdown` structure (per D1 = minimal)

Plain Markdown only — `#` headings, `-` bullets, `>` blockquotes. **No emphasis** (no italic/bold/
underline) — this is a locked rule in the reference implementation; keep it.

Recommended minimal body, in order:
1. `# {title}` — the confirmed title (annotations have no title field, so it lives here as H1).
2. blank line.
3. `{passage} — {YYYY-MM-DD}` — the passage label and the confirmed date (annotations have no date
   field, so the date rides here).
4. blank line, then the operator-edited OCR text. To preserve line structure in Markdown, render each
   non-empty line as a `- ` list item (or use hard line breaks). No structure reconstruction, no verse
   text, unless Kris changes D1.

## Idempotency

songbird dedupes on `(book_usfm, start/end chapter/verse, note_markdown, scope_type,
scope_translations)`. Because the output is fully deterministic (fixed `version`, null timestamp, single
annotation, stable `note_markdown` for the same inputs), re-importing the same file is a no-op. Keep the
emitter deterministic.

---

# Appendix B — book name/abbreviation → USFM map (port verbatim to the fork)

The reference implementation's map is **corpus-scoped and insufficient** for general sermons (it lacks
abbreviations and Roman numerals — the exact forms real handouts use). Use this fuller map instead.

**Normalization (apply to the scanned token before lookup):**
1. Convert a leading standalone Roman numeral to arabic: `I `→`1 `, `II `→`2 `, `III `→`3 ` (only when
   directly preceding a book name).
2. Lowercase.
3. Remove all whitespace and all `.` characters.
So `I Samuel` → `1 Samuel` → `1samuel`; `Ps.` → `ps`; `1Sam.` → `1sam`.

Build a reverse lookup: normalized form → USFM. Below, each USFM code lists accepted forms (normalize
both sides when matching). Numbered books accept the arabic and (via rule 1) Roman prefixes.

**Old Testament**
- `GEN` ← genesis, gen, ge, gn
- `EXO` ← exodus, exod, exo, ex
- `LEV` ← leviticus, lev, lv
- `NUM` ← numbers, num, nu, nm
- `DEU` ← deuteronomy, deut, deu, dt
- `JOS` ← joshua, josh, jos, jsh
- `JDG` ← judges, judg, jdg, jg
- `RUT` ← ruth, rut, ru
- `1SA` ← 1 samuel, 1 sam, 1sa, 1 sm
- `2SA` ← 2 samuel, 2 sam, 2sa, 2 sm
- `1KI` ← 1 kings, 1 kgs, 1 ki, 1kg
- `2KI` ← 2 kings, 2 kgs, 2 ki
- `1CH` ← 1 chronicles, 1 chron, 1 chr, 1 ch
- `2CH` ← 2 chronicles, 2 chron, 2 chr, 2 ch
- `EZR` ← ezra, ezr
- `NEH` ← nehemiah, neh, ne
- `EST` ← esther, esth, est
- `JOB` ← job, jb
- `PSA` ← psalm, psalms, ps, psa, pslm
- `PRO` ← proverbs, prov, pro, prv, pr
- `ECC` ← ecclesiastes, eccl, ecc, qoh
- `SNG` ← song of solomon, song of songs, song, sos, canticles, cant
- `ISA` ← isaiah, isa, is
- `JER` ← jeremiah, jer, je
- `LAM` ← lamentations, lam, la
- `EZK` ← ezekiel, ezek, eze, ezk
- `DAN` ← daniel, dan, da, dn
- `HOS` ← hosea, hos, ho
- `JOL` ← joel, joe, jl
- `AMO` ← amos, amo, am
- `OBA` ← obadiah, obad, oba, ob
- `JON` ← jonah, jon, jnh
- `MIC` ← micah, mic, mc
- `NAM` ← nahum, nah, na
- `HAB` ← habakkuk, hab, hb
- `ZEP` ← zephaniah, zeph, zep, zp
- `HAG` ← haggai, hag, hg
- `ZEC` ← zechariah, zech, zec, zc
- `MAL` ← malachi, mal, ml

**New Testament**
- `MAT` ← matthew, matt, mat, mt
- `MRK` ← mark, mrk, mar, mk
- `LUK` ← luke, luk, lk
- `JHN` ← john, jhn, jn  *(bare "John" = the Gospel; see disambiguation)*
- `ACT` ← acts, act, ac
- `ROM` ← romans, rom, ro, rm
- `1CO` ← 1 corinthians, 1 cor, 1co
- `2CO` ← 2 corinthians, 2 cor, 2co
- `GAL` ← galatians, gal, ga
- `EPH` ← ephesians, eph, ephes
- `PHP` ← philippians, phil, php, pp
- `COL` ← colossians, col
- `1TH` ← 1 thessalonians, 1 thess, 1 th, 1thes
- `2TH` ← 2 thessalonians, 2 thess, 2 th, 2thes
- `1TI` ← 1 timothy, 1 tim, 1 ti
- `2TI` ← 2 timothy, 2 tim, 2 ti
- `TIT` ← titus, tit
- `PHM` ← philemon, philem, phlm, phm
- `HEB` ← hebrews, heb
- `JAS` ← james, jas, jam, jm
- `1PE` ← 1 peter, 1 pet, 1pe
- `2PE` ← 2 peter, 2 pet, 2pe
- `1JN` ← 1 john, 1 jn, 1jo, 1jn
- `2JN` ← 2 john, 2 jn, 2jo
- `3JN` ← 3 john, 3 jn, 3jo
- `JUD` ← jude, jud, jd
- `REV` ← revelation, rev, re, apoc

**OCR variant worth keeping (from the reference impl):** `|john` → `1JN` (Tesseract reads the leading
"1" of "1 John" as a pipe `|`).

**Disambiguation rules (encode these — short abbreviations collide):**
- **John vs 1/2/3 John:** a leading digit or Roman numeral routes to the epistle (`1JN`/`2JN`/`3JN`);
  bare `john/jhn/jn` is the Gospel `JHN`.
- **Judges vs Jude:** map `judges/judg/jdg/jg` → `JDG` and `jude/jud/jd` → `JUD`. Do **not** add a bare
  `jud→JDG` alias; it's ambiguous.
- **Philippians vs Philemon:** `phil/php` → `PHP`; `philem/phlm/phm` → `PHM`. Treat bare `phil` as
  Philippians (common usage), not Philemon.
- Avoid one- or two-letter aliases that collide (e.g. don't alias bare `jo`, `ja`, `am` loosely beyond
  what's listed); when a token is genuinely ambiguous, skip it — the operator override is the safety
  net.

This list is comprehensive but not exhaustive of every abbreviation in the wild; because the lookup is
table-driven, new forms are one-line additions. Build it from the map keys so a scanning regex (if used)
picks up additions automatically.

---

# Appendix C — real MakeACopy OCR sample + anchor-finder expectations

This is the **actual concatenated OCR output** (front + back) from MakeACopy on one of Kris's handouts,
on his device. Use it verbatim as the primary fixture for the F3 anchor-finder. It shows the real
warts: a scrambled title, Roman-numeral book names, an abbreviation with a period, OCR garbles, and a
trailing cross-reference that a narrow map would wrongly grab.

```
A Story David about & Abigail 'Grace - I Samuel & Truth'. 25
I. The key people in the story.
A. Abigail
1. Her name means: "My Father is joy".
2. Scripture describes her as:
a. "of good" or pleasant, agreeable.
b."understanding": prudence, insight, having good sense.
c. Having a beautiful countenance.
B. Nabal
1. his name means 'fool'.
2. He was described as:
a. very rich,
b. kä·sheh' - hard, cruel, severe, obstinate,
c. 'rah' - bad, evil, disagreeable, malignant.
C. David - means 'Beloved'
He was a boy shepherd who had killed a lion and a bear while
protecting his sheep. He learned to worship God and listen to the
voice and heart of God.
David was theafterthought f his father's day when Samuel
    the Holy Spirit came upon him.
3. He killed Goliath.
He develop a y ron convictinh and wrong He was a
cuseeu n ve nt a l.
4. He played his harp for King Saul.
i Saul was jealous and sougt o kill him.He was 'n ten
for about 14 years.
6. The LORD began to gather an army around him. (1Sam. 22:1)
7. He spared the life of Saul after Saul came after him with an
army of 3000 chosen men. I Samuel 24:1-2
II. Abigail had to deal with a past of poor choices.
A. She was the wife of a man whose name means 'fool'.
B. Yet, She was "a woman of understanding and beautiful
appearance".
C. She had to respond to the knowledge of a foolish decision by her
husband. I Samuel 25
1. David's request (1-9)
2. Nabal's response. (10-11)
3. David's reaction. (12-13)
4. Abigail's response. (14-20)
Ill. When Abigail's 'Grace' met 'David's 'truth'
A. Abigail became the messenger of the grace of God. (21-31)
B. David was brought to a place of balance. (32-36)
Grace and Truth'.
C. God intervened.
1. He dealt with Nabal His way. (37-38)
2. He taught David about listening to the voice of wisdom and
grace. (39)
3. He rescued Abigail from the evil of Nabal. (40-42)
Truths to be learned & celebrated on
'Mother's Day' 2026:
1. We don't have to live our lives as victims of poor choices.
2. Women and men need others to speak 'grace' into their lives and
give them balance.
3. Godly mothers and wives are a gift from God to show us His
Grace.
4. Marriage was designed by God to demonstrate,
Grace & Truth'.
John 1:14-17; Ephesians 2:22-29
"A God honoring Marriage gives us one of the
LORD's greatest examples of what we have
because of Jesus."
```

**Intended anchor:** `1 Samuel 25` (the sermon passage; the title is "A Story about David & Abigail —
'Grace & Truth'").

**What the anchor-finder should do, in order, with the Appendix B map:**
- Title line is scrambled — `I Samuel` and `25` are separated by `& Truth'.`, so a strict
  `book chapter` regex will **not** capture it there. That's fine.
- `(1Sam. 22:1)` → normalizes `1sam` → `1SA`, chapter 22, verse 1 → **first clean hit → anchor =
  `1SA 22:1`**. Right book; operator changes the chapter to 25 on the edit screen. Acceptable.
- (Without the Appendix B map: `1sam`, `i samuel` all miss, and the scan falls through to
  `John 1:14-17` at the very end — **wrong book**. This is the failure the map prevents.)

**Anchor-finder unit tests to include (F3):**
- This sample → `1SA 22:1` (documents the title-split behavior + that the map is what makes it land in
  the right book).
- `I Samuel 25:3-6` → `1SA 25:3 → 25:6` (Roman numeral + verse range).
- `I Samuel 24:1-2` → `1SA 24:1 → 24:2`.
- `(1Sam. 22:1)` → `1SA 22:1` (abbreviation with period + parens).
- `Mt. 4:11` → `MAT 4:11`; `Ps. 23` → `PSA 23` (whole chapter → end-verse per D2).
- bare `(1-9)`, `(21-31)` → **not resolvable** in isolation (no book) → skipped.
- `John 1:14-17` → `JHN 1:14 → 1:17` (resolvable, but appears last — proves ordering matters and why a
  narrow map is dangerous).

---

*End of brief. Start by confirming decisions D1–D4 with Kris, then write the F0 (fork & green build) cc
prompt in a copy-paste code fence.*