# generate_verse_counts

One-off generator for the bundled **canon-structural verse-count table** used by the anchor span
resolver (slice F3b, decision D2). It reads chapter/verse structure from **Concord** (Kris's
self-hosted Bible API, reachable only from Kris's machines over Tailscale) and writes a JSON asset the
app loads **offline**. Concord is never a runtime dependency of the app — this script is the only thing
that talks to it, and only when Kris regenerates the table.

The verse counts are treated as **canonical structure**. `source_translation` and `concord_version` are
recorded as provenance metadata only; the resolver ignores them.

## Requirements

`python3` (3.8+). **No pip install** — standard library only.

## Run it

From the repo root, on a machine that can reach Concord:

```bash
python3 tools/generate_verse_counts/generate_verse_counts.py \
  --base-url http://<concord-host>:8000 \
  --translation KJV \
  --concord-version <your-concord-version> \
  --out app/src/main/assets/anchor/verse_counts.json
```

- `--base-url` — Concord base URL (default port 8000, base path `/v1`).
- `--translation` — a translation id from `GET /v1/translations` (e.g. `KJV`).
- `--concord-version` — free-text provenance string; Concord exposes no version endpoint, so you supply
  it (e.g. the deployed image tag / git sha).
- `--out` — defaults to `app/src/main/assets/anchor/verse_counts.json` (shown above for clarity).

This makes ~1189 requests (one per chapter across all 66 books) — typically a couple of minutes over
the LAN. Output keys are sorted for clean diffs; only `meta.generated_at` changes between runs.

The script **fails loudly** if Concord's `/v1/books` USFM set differs from the app's canonical 66 codes,
so the table's keys always match `BookMap`.

## Then commit it

```bash
git add app/src/main/assets/anchor/verse_counts.json
git commit -m "data(F3b): bundle Concord-generated verse-count table"
```

Pushing the table to the F3b branch makes the Java `VerseCountsSchemaTest` flip from **skipped** to
**passing** (it asserts 66 keys matching `BookMap`, positive counts, and the four `meta` fields). The
F3b PR is complete only once that test passes against the real asset.

## Endpoints used

| call | purpose |
|---|---|
| `GET /v1/translations` | validate the `--translation` id |
| `GET /v1/books` | enumerate the 66 books `{id (USFM), chapter_count}` |
| `GET /v1/chapters/{usfm}/{chapter}?translation=<ID>` | per-chapter verse count = `max(verse)` over `verses[]` |

There is no dedicated verse-count endpoint in Concord, so per-chapter counts are derived from the
chapters route.
