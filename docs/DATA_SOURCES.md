# Data sources for the English dictionary

The classic engine's English dictionary (vocabulary, unigram frequencies, bigram/trigram table;
see `PLAN.md` §4.4) is built offline from three third-party sources. Raw downloads live in the
git-ignored `data/raw/`; everything derived lives in the git-ignored `data/processed/`. Only the
compiled dictionary (`app/src/main/assets/dictionaries/en_US.kcd`, produced by `dictbuild compile`) is
committed.

Reproduce from scratch:

```sh
tools/data/fetch-sources.sh                      # download + verify against tools/data/sources.sha256
./gradlew :tools:dictbuild:run --args="count"    # ~1 min, ~2 GB heap; writes data/processed/
```

`fetch-sources.sh` pins every source (exact AOSP commit, dated Wikipedia dump), verifies SHA-256,
skips files that are already present and valid, and never executes anything it downloads.
`fetch-sources.sh --record` rewrites `sources.sha256` and is only for deliberately bumping a source.

The licence of the compiled dictionary and its required attributions are stated in
[`LICENSE-DATA.md`](../LICENSE-DATA.md) (repository root); the app shows them under
About > Open-source licences & data.

## Summary

| Source | Used for | Version | Licence |
|---|---|---|---|
| AOSP LatinIME `en_US_wordlist.combined.gz` | vocabulary, word frequencies, offensive/abbreviation flags | commit `127336e9…`, header `version=54`, `date=1414726260` (2014-10-30) | Apache-2.0 |
| Simple English Wikipedia `pages-articles` dump | formal-text n-gram counts | dump `20261001` | CC BY-SA 4.0 (also GFDL; we use CC BY-SA 4.0) |
| Tatoeba `eng_sentences.tsv` | conversational n-gram counts, held-out eval set | rolling export of 2026-10-03, downloaded 2026-10-09 | CC BY 2.0 FR |
| English Wikipedia "Lists of common misspellings/For machines" | **evaluation only** (`--mode misspell`); never shipped, not in the dictionary or language model | revision `1199637275` (2024-01-27) | CC BY-SA 4.0 |

## 1. AOSP LatinIME English wordlist

- **File:** `dictionaries/en_US_wordlist.combined.gz` in
  <https://android.googlesource.com/platform/packages/inputmethods/LatinIME>, pinned to commit
  `127336e9f29d69607eab55982324b210279ae8c5` (tip of `main` on 2026-10-09; the file is unchanged
  since its 2014 header date). Raw file URL (gitiles serves base64 with `?format=TEXT`):
  `https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/dictionaries/en_US_wordlist.combined.gz`.
  `en_US` exists, so no fallback to `en_wordlist` / `en_GB` was needed.
- **Size / SHA-256:** 883,015 bytes,
  `0f78dd455b532be169a23f233227b811fabced4b5bd7fc9c40cc05839793bcbd`.
- **Header:** `dictionary=main:en_us,locale=en_US,description=English (US),date=1414726260,version=54`.
- **Licence:** Apache License 2.0. The repository's
  [`NOTICE`](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/127336e9f29d69607eab55982324b210279ae8c5/NOTICE)
  ("Copyright (c) 2008, The Android Open Source Project, Licensed under the Apache License,
  Version 2.0") covers the tree, including `dictionaries/`. Text:
  <https://www.apache.org/licenses/LICENSE-2.0>. Note that the list carries no per-word provenance;
  we rely on the repository-level licence.
- **Format:** header line, then ` word=…,f=0..255,flags=…,originalFreq=…[,possibly_offensive=true][,not_a_word=true]`
  (one leading space). Deeper-indented `shortcut=` / `bigram=` lines (99 of them in this file, all
  `shortcut=…,f=whitelist`) are skipped. 160,715 words of which 47 are `not_a_word` (e.g. lowercase
  `i`, a shortcut source) and 1,300 `possibly_offensive`. `f=0` means "never suggest"
  (profanity/blocked), not "rare"; `originalFreq` keeps the pre-adjustment frequency.
- **Derived:** `data/processed/vocab.tsv` (word, freq, originalFreq, flags) and the vocabulary id
  table used to restrict n-gram counting.

## 2. Simple English Wikipedia

- **File:** `simplewiki-20261001-pages-articles.xml.bz2`
  <https://dumps.wikimedia.org/simplewiki/20261001/simplewiki-20261001-pages-articles.xml.bz2>
  (dump finished 2026-10-01; 358,258,425 bytes).
- **Checksums:** SHA-1 `f55881650e447c39e8bdeba4b1bb8d5576342d4b` (matches Wikimedia's published
  `simplewiki-20261001-sha1sums.txt`); SHA-256
  `2db94ff149adbbaf88efecb8f510a549eaa2a82d59df6278a4af88e13c1b99bd`.
- **Licence:** per <https://dumps.wikimedia.org/legal.html>, text is licensed under the GNU Free
  Documentation License and the Creative Commons Attribution-ShareAlike 4.0 licence; "some text may
  be available only under the Creative Commons license". We use it under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Controlling terms:
  [Wikimedia Terms of Use §7](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use).
  The dump page itself warns it may contain copyright infringements not yet removed.
- **Used:** main-namespace (`ns=0`) non-redirect articles only (285,478 articles; 115,214 redirects
  and 161,522 other-namespace pages skipped). Wikitext is stripped (templates, refs, tables,
  files/categories/interwiki, link syntax, emphasis, headings, HTML) and split into sentences.
  Headings and list fragments are dropped, so what is counted is prose.
- **Derived:** aggregated unigram/bigram/trigram counts only. No article text is stored in
  `data/processed/` or shipped.

## 3. Tatoeba English sentences

- **File:** `eng_sentences.tsv.bz2`
  <https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2>. Tatoeba only
  publishes a rolling weekly export (no dated URL); the one used was the export listed as
  2026-10-03 06:31 UTC, downloaded 2026-10-09. 24,894,467 bytes.
- **SHA-256:** `5f7ebf552bd3925dfd33236ccb6a210c5ba02b365e4a39964fb658e1209e87d9`. A later
  download will not match; bumping means re-running `fetch-sources.sh --record` and re-building.
- **Licence:** CC BY 2.0 FR (Creative Commons Attribution 2.0 France,
  <https://creativecommons.org/licenses/by/2.0/fr/>). Stated on <https://tatoeba.org/en/downloads>
  ("These files are released under CC BY 2.0 FR"; a subset is also CC0) and in the
  [terms of use §6.2](https://tatoeba.org/en/terms_of_use). The sole condition is attribution of
  the author. The plain `eng_sentences` file has no author column; `eng_sentences_detailed`
  does.
- **Used:** 2,038,137 English sentences (`id<TAB>eng<TAB>text`).
  - About 5% (101,392 sentences) are held out by hash of the sentence id (SplitMix64, `< 5 mod 100`)
    into `data/processed/heldout.txt` for the later evaluation harness and excluded from all counts;
    24 further training sentences whose text duplicates a held-out sentence are dropped too.
  - `heldout.txt` reproduces Tatoeba sentences verbatim. It is git-ignored; if it is ever
    committed or distributed, per-sentence author attribution would be required (use the
    `detailed` export then).
- **Derived:** aggregated counts only.

### Why Tatoeba is weighted 3x

Mobile keyboard input is short, conversational and first/second-person ("I don't know", "can you
…"), which Simple English Wikipedia (third-person, encyclopaedic: "the united states", "one of
the") badly under-represents. In the build Tatoeba contributes 14.9 M tokens against 39.5 M for
Wikipedia; a weight of 3.0 (`--tatoeba-weight`, default) makes conversational text weigh about
1.1x the Wikipedia mass in the combined counts (14.9 M x 3 = 44.8 M vs 39.5 M), so that it shapes
sentence starts and contractions without drowning out the broader topical vocabulary. This is a judgement call to
be tuned against `heldout.txt` by `dictbuild eval-lm` (3.0 beat 1 and 2 on that Tatoeba held-out set,
which naturally favours Tatoeba-heavy models). Tatoeba is dominated by the placeholder names "Tom"
and "Mary", which would otherwise rank among the most frequent words and top the next-word
predictions. `count` therefore treats the exact-case tokens in `--tatoeba-ignore-names` (default
`Tom,Mary`) as out-of-vocabulary in Tatoeba sentences only: they break the n-gram chain and add no
counts. Wikipedia counts for these names are unchanged; pass `--tatoeba-ignore-names ""` to disable.

## 4. Common-misspellings list (evaluation only)

- **File:** `wikipedia-misspellings-1199637275.txt`, the raw wikitext of
  <https://en.wikipedia.org/wiki/Wikipedia:Lists_of_common_misspellings/For_machines> at revision
  `1199637275` (`index.php?title=...&oldid=1199637275&action=raw`; revision pinned so the content
  cannot drift). 99,150 bytes, SHA-256
  `3b6a9290e5aaad968da7ec769a5174adb45247688ef7ae097fd2dd80237a3050`, fetched by
  `tools/data/fetch-sources.sh` into the git-ignored `data/raw/`.
- **Licence:** CC BY-SA 4.0 (Wikipedia text). Because of the share-alike condition this list is
  used **only as test input** to the offline evaluation harness (`:tools:eval`, `--mode
  misspell`): it is not added to the dictionary, the language model, the app assets or any
  training/tuning artefact that is distributed, and is not redistributed in the repository.
  Decoder defaults tuned against it are numbers, not derived data.
- **Format:** one ` misspelling->correction[, alternative ...]` entry per line (leading space).
  The harness keeps entries whose misspelling is alphabetic (apostrophes allowed), is not itself a
  word of the shipped dictionary, and has at least one correction that is a single word the
  dictionary knows.

## Pipeline output (`data/processed/`)

All TSVs start with a `#`-prefixed column header line.

| File | Columns |
|---|---|
| `vocab.tsv` | word, freq (0–255), originalFreq, flags (comma list) |
| `unigrams.tsv` | word, count (weighted), wiki, tatoeba, cont (distinct left contexts, for Kneser-Ney); `<s>` row = sentence count |
| `bigrams.tsv` | w1, w2, count (weighted), wiki, tatoeba; `w1` may be `<s>` |
| `trigrams.tsv` | w1, w2, w3, count (weighted), wiki, tatoeba |
| `bigram_contexts.tsv`, `trigram_contexts.tsv` | per context (w1 / w1,w2): total wiki and tatoeba counts, distinct successors, Tatoeba share, over all successors before pruning (needed to normalise the model) |
| `count_of_counts.tsv` | number of n-grams seen exactly 1, 2, 3, ... times (Chen-Goodman discounts) |
| `heldout.txt` | one held-out Tatoeba sentence per line |
| `stats.json` | token counts per source, OOV rate, n-gram counts before/after pruning, runtime, memory |

Counting rules: tokens keep internal apostrophes/hyphens; a token maps to a vocabulary surface form
exactly, else case-insensitively to the most frequent variant (sentence-initial capitalised words
fold into their lowercase form unless clearly a proper noun); `'s` on a known word and all-known
hyphen compounds fall back to their parts; an out-of-vocabulary token (including bare numbers)
breaks the n-gram chain. Pruning keeps n-grams with at least 2 raw occurrences and at most 1000
(bigram) / 200 (trigram) successors per context (`--max-bigram-successors`,
`--max-trigram-successors`); weighted count = wiki + weight x tatoeba.

## Licence compatibility with the app (GPL-3.0-or-later)

*Engineering notes, not legal advice.*

- **Apache-2.0 (AOSP):** compatible with GPLv3 (one-way: Apache-2.0 code/data may be included in a
  GPLv3 work). Keep the Apache licence text and copyright notice with the data.
- **CC BY-SA 4.0 (Wikipedia):** Creative Commons lists CC BY-SA 4.0 as one-way compatible with
  GPLv3 (BY-SA 4.0 material may be adapted into a GPLv3 work; the reverse is not allowed). The
  shipped dictionary contains only aggregated counts of words, not Wikipedia text, so it is at
  most a thin adaptation; attribution and licence notice are still given. Do not ship
  Wikipedia-derived text under a licence other than CC BY-SA 4.0 / GPLv3.
- **CC BY 2.0 FR (Tatoeba):** an attribution-only licence, with no copyleft condition, so it can be
  combined into a GPLv3 app provided attribution is kept. (Whether the FSF formally lists this older
  jurisdiction-ported version is unverified; the shipped artefact is aggregated counts, which carry
  little or no sentence-level expression.)

## Attribution text for the app's About screen

> **English dictionary data**
> Word list and word frequencies: Android Open Source Project, LatinIME English dictionary,
> licensed under the Apache License 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
> Next-word statistics derived from Simple English Wikipedia (text by Wikipedia contributors,
> https://simple.wikipedia.org, licensed under CC BY-SA 4.0,
> https://creativecommons.org/licenses/by-sa/4.0/) and from sentences on Tatoeba
> (https://tatoeba.org, by Tatoeba contributors, licensed under CC BY 2.0 FR,
> https://creativecommons.org/licenses/by/2.0/fr/). The statistics are aggregated word counts;
> no article or sentence text is included. Changes were made.

## Tuning and evaluation hygiene

Thresholds and weights that are tuned against data (autocorrect `threshold` / margins, back-off
penalties, `lambda`, the Tatoeba weight, ...) must be tuned on a **development split that is
distinct from the final held-out set**. `heldout.txt` (the Tatoeba 5% hash split above) is split
further into a *dev* part used for tuning and a *test* part that is scored once, after the
parameters are frozen; reporting numbers from data that influenced the parameters overstates
quality. The eval harness implements the split; any new tuning code must use it.

## Compiling the dictionary

```
./gradlew :tools:dictbuild:installDist
tools/dictbuild/build/install/dictbuild/bin/dictbuild compile --build-date 2026-10-09
tools/dictbuild/build/install/dictbuild/bin/dictbuild eval-lm
```

`compile` estimates an interpolated Kneser-Ney style model from the `count` output (AOSP vocabulary
is kept in full; unseen words get a monotone fit of the AOSP 0-255 level), prunes by count-weighted
gain (`--bigram-gain`/`--trigram-gain`, default 12 nats) and refuses to write more than 10 MiB.
`eval-lm` reports held-out perplexity, OOV rate and next-word accuracy.
