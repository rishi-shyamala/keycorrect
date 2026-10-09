# Offline evaluation harness (`:tools:eval`)

A JVM app that types sentences through a simulated, noisy touch typist, feeds
every keystroke to `ClassicEngine`, and scores what the keyboard would have
committed. It needs no device and runs 5000 sentences in about 8 s. It is
deterministic for a given `--seed`.

## Running

```
./gradlew :tools:eval:run --args="--sentences 5000 --sigma 0.35 --seed 1"
./gradlew :tools:eval:run --args="--sentences 5000 --config autocorrect.threshold=2.5,spatial.sigma=1.0"
./gradlew :tools:eval:run --args="--sweep"          # parameter sweep on the dev split
```

Output goes to `tools/eval/build/eval/` (override with `--out`): `report.txt`
(the table), `results.json`, `failures.csv` (one row per wrong word:
category, target, typed literal, committed, top-3 strip) and, for sweeps,
`sweep.csv`. `./gradlew :tools:eval:installDist` gives a faster launcher at
`tools/eval/build/install/eval/bin/eval` (run from the repo root).

| flag | meaning |
|---|---|
| `--sentences N`, `--offset N`, `--seed S` | corpus sample size, start offset, RNG seed |
| `--split dev\|test\|all` | which part of `heldout.txt` (default `test`) |
| `--sigma s`, `--sigma-x`, `--sigma-y` | typist touch noise, in key widths/heights |
| `--omission/--insertion/--transposition p`, `--no-typos` | cognitive typo rates (default 0.01 each) |
| `--oracle-context` | previous words are the true words instead of what was committed |
| `--names skip\|substitute\|keep` | handling of Tatoeba's placeholder names (below) |
| `--config k=v,k=v` | `ClassicConfig` overrides: `section.field=value`, `xN` multiplies the default, `a.x+a.y=x1.5` scales several; `spatial.sigma` is the model's own sigma |
| `--no-completion`, `--no-next-word`, `--phrase-words N` | skip parts of the episode |
| `--latency-sentences N` | sentences in the single-thread latency pass (0 disables) |
| `--sweep`, `--sweep-sentences N`, `--sweep-grid "k=v1,v2;k2=..."`, `--target-fc F` | sweep controls (target default 3 false corrections/1000) |
| `--what-if protect-capitalized[,...]` | decoder what-if overlays, to size a change before making it |
| `--heldout`, `--dict`, `--threads`, `--failures N`, `--label` | paths, parallelism, failure rows kept, report label |

## Method

* **Corpus.** `heldout.txt` sentences (Tatoeba). **Dev/test split:** a line is
  dev when a hash of its line index (independent of `--seed`) falls in the
  lowest 20%. Tune and sweep on dev; report on test (the default). The sweep
  always uses dev, and its recommended point is then re-run on test.
* **Names.** Tatoeba replaces people with Tom/Mary, which are out of the
  dictionary by design. `skip` (default) commits them but neither types nor
  scores them; `substitute` maps them to John/Anna; `keep` types them and
  counts the unavoidable failure. 1662 of 38893 tokens are skipped (digits and
  names).
* **Typist.** Each character is a touch at the key centre plus 2D Gaussian
  noise (sigma in key units), hit-tested against the real 1080x594 px
  keyboard; the typed character is the key under the noisy touch. Per
  character it may also be omitted, doubled/inserted, or transposed with its
  neighbour (cognitive slips, 1% each). Noise is seeded per (sentence, word) so
  runs with different configs see identical touches.
  **sigma 0.35 is a stress setting; real users are nearer 0.2 to 0.25.**
  Compare both.
* **Context.** Default: the previous words are what the engine actually
  committed (errors compound as in real use). `--oracle-context` uses the true
  words (upper bound for the language model).

## Metrics

All "accuracy" figures are over evaluated in-vocabulary and OOV words, case-insensitive
unless noted. Everything is also reported per target-word length bucket
(1, 2, 3, 4, 5-6, 7-8, 9+), per vocabulary (in/out of dictionary) and per typo kind.

* **word accuracy** committed word equals the target. **casing accuracy** exact match.
* **raw accuracy** the typed literal was already right (no help from the keyboard).
* **correction rate** wrong literals that end up fixed. **missed** the rest,
  split into target in the top 3 (offered but not applied) and not in the top 3.
* **false corrections per 1000 words** literal was right (in the dictionary
  or not) and the keyboard changed it. The headline safety number.
  Split into real-word and not-in-dictionary targets.
* **top-3 recall** target (or the literal, when right) is in the 3-slot strip.
* **completion KSR** keystroke savings if the user taps a strip suggestion as
  soon as the target is on it: one tap = one keystroke, baseline = typed
  length. "+ next-word taps" also counts tapping next-word predictions.
* **next-word top-1 / top-3** with empty composing text, truth = the next word.
* **phrase offer rate / precision** how often a multi-word phrase is offered at
  a word boundary, and how often the whole phrase / first word / words matched
  are right.
* **latency** p50/p95/p99/max of `predictNow`, single thread after warm-up, for
  composing and next-word calls, under the configured settings and a "wide
  search" variant. Desktop JVM only: multiply by roughly 3 to 6 for a mid-range
  phone and treat as relative, not absolute.

## Baseline (test split, 5000 sentences, 37 231 words, seed 1)

Engine defaults (threshold 2.5, spatial sigma 0.45, back-off -0.8/-1.3):

| | typist sigma 0.25 | typist sigma 0.35 |
|---|---|---|
| word accuracy | 93.71% | 75.52% |
| raw typed accuracy | n/a (see JSON) | 38.77% |
| correction rate | 78.14% | 60.25% |
| false corrections / 1000 | 3.01 | 1.45 (in-dictionary: 0.08) |
| top-3 recall | 97.31% | 83.64% |
| completion KSR | 32.56% | 25.98% (33.24% with next-word taps) |
| next-word top-1 / top-3 | | 17.2% / 28.4% |
| phrase offer / whole-phrase precision | | 3.3% / 22.4% |

Oracle context: 76.43% / FC 1.53 (so committed-context error compounding costs
about 0.9 points). No cognitive typos: 76.60%. `--names substitute` and
`keep` add the name tokens: 76.95% / 76.49% accuracy at the recommended config.

By target length at sigma 0.35 (default config): accuracy is 81.9% for 1-letter,
84.1% for 2, 79.2% for 3, 71.2% for 4, 77.2% for 5-6, 68.8% for 7-8 and 48.6%
for 9+ letters; 4-letter words carry most false corrections (5.1/1000).

Latency, default config (ms): composing p50 0.078, p95 0.50, p99 1.1, max 5.0;
next-word p95 0.07.

## Sweep and recommended config

Grid (108 points, 3000 dev sentences, sigma 0.35): `autocorrect.threshold`
{1.5, 2.0, 2.5, 3.0, 3.5, 4.5} x back-off penalties (trigram and bigram scaled
together) {x0.55, x1, x1.75} x `spatial.sigma` {0.35, 0.45, 0.6, 0.85, 1.0, 1.2}.
Objective: maximise accuracy subject to false corrections <= 3 per 1000.

* Default config on dev: 75.44% accuracy, FC 2.06.
* Dominant lever: **`spatial.sigma`**. 0.35 to 0.45 gives 70 to 75%; 0.85 to 1.2
  gives 77.0 to 77.6%, even when the typist sigma is only 0.25. A wider model
  sigma acts like a heavier language-model weight and absorbs errors the
  geometric model does not describe (slips, off-axis taps).
* Back-off scale: x1 and x0.55 are within 0.1 points once sigma is wide; x1.75
  hurts at narrow sigma. Leave the penalties alone.
* Threshold: lower is slightly better and costs FC (1.5: FC 1.21, 2.5: 1.08,
  3.5: 0.90, accuracy 77.57 / 77.49 / 77.27 on dev). The Pareto knee is flat.

**Recommended (config A): `autocorrect.threshold=2.5, spatial.sigma=1.0`**,
penalties unchanged. Sweep optimum is threshold 1.5 (77.57% dev, FC 1.21); 2.5
gives up 0.08 points for a more conservative gate.

| test split | default | config A |
|---|---|---|
| sigma 0.35: accuracy / correction / FC per 1000 | 75.52% / 60.25% / 1.45 | 77.63% / 63.63% / 0.97 |
| sigma 0.25: accuracy / correction / FC per 1000 | 93.71% / 78.14% / 3.01 | 93.66% / 77.62% / 2.04 |
| completion KSR (0.35 / 0.25) | 25.98% / 32.56% | 26.01% / 31.69% |

So A helps a lot at heavy noise, is neutral on accuracy at light noise, and
lowers FC in both.

**Aggressive variant (config B): A plus `edits.charsPerEdit=2, edits.maxEdits=3,
autocorrect.maxEdits=3`** (second 16-point sweep, dev 83.29% vs 77.49%, FC 1.79
vs 1.08). On test at sigma 0.35: 83.52% accuracy, 73.42% correction, FC 2.04,
top-3 recall 92.59%. At sigma 0.25 it adds no accuracy (93.75% vs 93.66%) and
doubles FC (3.95 vs 2.04), and composing p95 latency rises from 0.8 to 1.6 to
1.8 ms on the desktop JVM (about 2x). Only worth it if the real-user touch
noise is high; not applied by this report.

## Failure categories (default config, sigma 0.35, test)

| category | words | examples (target: typed -> committed) |
|---|---|---|
| missed, target not in top 3 | 3953 (10.6%) | parents: pqrrngw; terrible: grerjble (2+ edits away); to: fp |
| missed, in top 3 but not applied | 2547 (6.8%) | my: ny; can: fan; full: gull (real-word and gate margins) |
| wrong correction | 2108 (5.7%) | last: kaet -> Kate; how: hia -> his; kissed: kidsee -> kids |
| unfixable, target not in dictionary | 453 (1.2%) | Ziri, Rima, Sami (proper names), well-equipped |
| false correction, not in dictionary | 51 (0.14%) | Sami -> Sam, Layla -> Lay, Yanni -> Ann |
| false correction, real word | 3 (0.01%) | toe -> to |

False corrections are almost entirely out-of-vocabulary proper names; the
in-dictionary FC rate is 0.08/1000. Typed errors that are themselves words
(fan/can, be/he, ny) are the biggest cheaply-addressable class.

## Decoder suggestions, with measured evidence

1. Set `spatial.sigma` to about 1.0 (+2.1 points at heavy noise, neutral at light).
2. Edit budget: `charsPerEdit=2, maxEdits=3` is worth +8 points at sigma 0.35
   but needs a latency budget check on device and should be gated on a measured
   touch-noise estimate.
3. Real-word errors (2547 "in top 3 but not applied"): consider a context-aware
   real-word rule (bigram strongly prefers a neighbour) instead of a blanket margin.
4. Proper-name protection: mid-sentence capitalised OOV tokens are the main FC
   source; `protectMidSentenceCapitals` (now in the engine) targets this.
   Proper-noun entries in the dictionary would cut it further.
5. Wrong corrections of 4-letter words (FC 5.1/1000) hint that the top candidate
   is too often a frequent short word; test a length-aware prior.
6. 9+ letter words are at 48.6%: missing letters in long words need a
   cheaper omission edit cost or prefix-completion candidates.

## Applied defaults

`spatial.sigmaXFactor = sigmaYFactor = 1.0` and `autocorrect.contractions = true` (explicit
apostrophe-less contraction rule, independent of sigma) are now the defaults. Re-run, typist
sigma 0.25, seed 7, 2000 sentences, **test split**: 93.80% word accuracy, 2.48 false corrections
per 1000 words (was 93.68% / 3.88 at model sigma 0.45, same run settings). These 2000-sentence
numbers are not comparable to the 5000-sentence, seed 1 tables above (e.g. config A's 93.66% /
2.04 there, which predates the contraction rule); the dev split was only used for the sweeps.

## Caveats

The typist is a model, not data. Real users make correlated errors (thumb
bias, one-handed drift) the Gaussian model lacks, and Tatoeba sentences are
shorter and cleaner than chat text. Use the numbers to compare configs, not as
absolute user-facing accuracy. Re-run the baseline after any decoder change;
the harness reads `ClassicConfig` by field name so renamed knobs show up as
`--config` errors rather than silently being ignored.
