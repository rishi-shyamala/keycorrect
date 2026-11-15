# KeyCorrect — Codebase Complexity & Entropy Review

> **Status:** analysis only. No production code was changed to produce this document.
> **Branch:** `phase-3-code-review`, based on `phase-3-learning` at commit `7e5dede`
> ("docs: handoff for resuming after Phase 3").
> **Date:** 2026-10-10.
> **Goal:** reduce complexity and entropy **without removing any functionality or feature** and
> without breaking the build, tests, lint or formatting.
>
> A future agent should be able to implement every item below from this document alone. Each
> finding gives *Evidence*, *Why it matters*, *Suggested fix*, and *Verification*. Do the P0 items
> first; they are mechanical and behaviour-preserving.

---

## 0. Status (2026-10-10, after validation)

Every finding was checked against the code before acting. Done on `phase-3-code-review` and merged
into `phase-3-learning` (PR #1):

| Item | Outcome |
|---|---|
| D1–D3, D5, D6, D8 | Deleted. `rejectWord` became dead in 6692faa when undo moved to `retypeGuard` + keep thresholds (PLAN §6.3 rows 6, 21); not a lost call. |
| D7 | Field removed. **Correction to this doc:** `thresholds.forgetSince(...)` has a side effect (it prunes outcomes), so the call stays; only the unused count went. |
| D9–D11 | Kept per user decision; KDoc now says Tiers 0/1, the other capabilities and `load`/`unload` are the Phase 7 plugin contract and unused for now. |
| F1 + D4 | Done differently from §5.2: no new module. `SentenceContext` moved to `core:learning` (JVM), which both the keyboard and the decoder already depend on; the decoder's `Text` delegates to it. Shim and parity test deleted. |
| F2 | Done: `RecentWords` uses `core:learning`'s `editDistance`/`fold`. |
| G2 | Done: `.github/workflows/ci.yml`. |
| G1 (Detekt) | Done on `pre-phase-4-cleanup` (below). |
| G3, F3, F4, F5, rest of §5.3 | Deferred (user decision, 2026-10-11): see below. |

Tests after the changes: 1066 pass (1067 minus the deleted one-test parity file).

**Second pass before Phase 4 (branch `pre-phase-4-cleanup`, 2026-10-11):**

| Item | Outcome |
|---|---|
| §5.3 `PredictiveInput` split | Partly done, by user choice: `TextContextCache`, `StripPrompts` and `PhraseLearning` extracted (1814 → 1604 lines). The undo / held-revert / `completeWord` core stays in place; it is the subtlest part. |
| G1 Detekt | `dev.detekt` 2.0.0-alpha.6 (the first line built for Kotlin 2.4) via a `keycorrect.detekt` convention plugin; `detektMain` with type resolution, per-module `detekt-baseline-*.xml`, run in CI. It found nine initializer-only `private val` constructor parameters, now plain parameters. |
| Strip strings | Accessibility labels and prompt text moved to `feature/keyboard-ui` strings.xml (`StripSlot.labelRes()`). |
| §3.4 lint | Plurals, ©, commons-compress 1.28.0; lint is now clean. |
| Still deferred | G3 (R8 strips the fakes from release, and AGP 9.4 testFixtures-with-Kotlin is still experimental), F3 (needs a device smoke test), F4/F5 (tools only), `AutocorrectPolicy` and parser splits (not touched by Phase 4). |

Tests: 1067 pass (1066 + one strip-label mapping test).

## 0.1 How to reproduce the analysis

Everything was measured on the phase-3 branch. Commands (from the repo root):

```sh
# Baseline build + tests + lint + format (must stay green after every change)
./gradlew --console=plain spotlessCheck testDebugUnitTest lintDebug assembleDebug

# Test inventory
find . -path '*/src/test/*Test.kt' -o -path '*/src/androidTest/*Test.kt' | grep -v /build/ | wc -l

# Detekt (not wired into the build — downloaded standalone to /tmp only)
curl -sL -o /tmp/detekt-cli.jar \
  https://repo1.maven.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/1.23.7/detekt-cli-1.23.7-all.jar
INPUT=$(find . -path '*/src/main/kotlin' -type d | grep -v build | sed "s|^|$PWD/|" | paste -sd, -)
java -Xmx4g -jar /tmp/detekt-cli.jar --build-upon-default-config \
  --input "$INPUT" --report xml:/tmp/detekt.xml
```

The clone detector and unused-declaration scanner used in this review were throwaway Python scripts
over `src/main/kotlin`; their method is described inline below so it can be repeated.

---

## 1. Executive summary

The phase-3 code base is in good health: it builds, formats, lints, and **1067 JVM tests pass with
0 failures**, all settings fields are consumed, there are no stubs and no
`TODO()`/`NotImplementedError`. There are no `@Ignore` tests; two tests use `assumeTrue` guarded on
the committed dictionary asset, which is present in this checkout, so they run. The module dependency graph is a clean DAG and the branch model is tidy.

The entropy is concentrated in a small number of places:

1. **A handful of genuinely dead members** (safe, mechanical deletions — P0).
2. **A few verbatim copy-pastes**, the largest being the sentence-context parser that exists twice
   with a parity test keeping the copies honest (P0/P1).
3. **One very large class**, `PredictiveInput` (1825 lines, 96 functions), plus a few large
   "parser/policy" classes whose high complexity is partly inherent (P2).
4. **Missing quality guardrails**: Detekt is described in the plan but **not configured in the
   build**, and there is **no CI**. These are what let the other entropy accumulate (P0/P1).

Recommended order: delete dead code, wire Detekt with a baseline, add CI, then remove duplication,
then (only with tests as a net) split `PredictiveInput`.

---

## 2. Health snapshot

| Signal | Result |
|---|---|
| Production Kotlin (`src/main/kotlin`) | 30,377 lines across 261 `.kt` files |
| Test Kotlin | 18,057 lines, 81 test source files |
| JVM unit tests | **1067 pass, 0 failures, 0 errors** |
| `spotlessCheck` | clean |
| `lintDebug` (all modules) | clean (3 informational app issues, §3.4) |
| `assembleDebug` | succeeds |
| Instrumented tests | 25 (`core:data` androidTest) — not run here |
| Branch model | `phase-1-core-keyboard` and `phase-2-prediction-core` have **no commits** beyond `main` (merged). `phase-3-learning` is 23 commits / +20,200 lines ahead of `main` and is the only divergent branch. |
| Module graph | Acyclic (§4) |

### Production lines by module

| Module | main LOC |
|---|---|
| `feature:keyboard-ui` | 5,652 |
| `tools:eval` | 4,935 |
| `app` | 4,593 |
| `core:learning` | 3,983 |
| `core:decoder` | 2,597 |
| `core:dictionary` | 1,765 |
| `core:data` | 1,663 |
| `core:layout` | 594 |
| `core:input` | 548 |
| `core:settings` | 225 |
| `core:engine-api` | 184 |
| `core:ime` | 86 |
| `core:designsystem` | 21 |

---

## 3. Static analysis results

### 3.1 Detekt (standalone, v1.23.7, default config + `--build-upon-default-config`)

**855 weighted issues.** Full rule counts in Appendix A. The categories that matter for entropy:

| Rule | Count | Notes |
|---|---|---|
| `MagicNumber` | 460 | mostly inside test/eval/tool code; see P2-4 |
| `ReturnCount` | 82 | guard-clause style; mostly benign |
| `FunctionNaming` | 63 | **all false positives**: Compose functions start with a capital letter |
| `CyclomaticComplexMethod` | 47 | see §5.3 |
| `LongParameterList` | 40 | constructors/DTOs |
| `LongMethod` | 34 | see §5.3 |
| `ComplexCondition` | 29 | |
| `TooManyFunctions` | 24 | `PredictiveInput` (96), `Dictionary` (43), `PersonalModel` (38), `OutcomeClassifier` (26), `ClassicEngine` (24), `Lexicon` (24) |
| `NestedBlockDepth` | 13 | |
| `LoopWithTooManyJumpStatements` | 12 | |
| **`UnusedPrivateClass`** | **1** | `PredictiveInput.LearnedWord` |
| **`UnusedPrivateMember`** | **1** | `PredictiveInput.rejectWord` |

Detekt is **not run by the build** (no `detekt` dependency, task or config anywhere). That is why
these 855 issues are invisible today.

### 3.2 Unused declaration scan (throwaway Python over `src/main/kotlin`)

Method: extract top-level/member `class|object|interface|fun|val|var|typealias` names, count
word-boundary references across all `src/main/kotlin` and `src/test` files. Names with **zero**
references outside their declaration (and zero in tests) are listed in §5.1. Framework overrides
(`onCreateInputView`, `onConfigure`, `onUpgrade`, `onCreateInlineSuggestionsRequest`, …) and Gradle
convention-plugin classes are deliberately excluded: the platform/Gradle calls them.

### 3.3 Clone detection (throwaway Python, 10-line normalised windows)

109 cross-file clone windows. Deduplicated by file pair:

| Clone windows | File A | File B |
|---:|---|---|
| 34 | `core/decoder/.../Text.kt` | `core/input/.../SentenceContext.kt` |
| 11 | `core/learning/.../classifier/EditDistance.kt` | `feature/keyboard-ui/.../prediction/RecentWords.kt` |
| 8 | `app/.../settings/LearningScreen.kt` | `app/.../settings/LearningSupport.kt` |
| 2 | `app/.../settings/LearningScreen.kt` | `app/.../settings/PersonalDataSupport.kt` |
| 2 | `app/.../settings/LearningSupport.kt` | `app/.../settings/PersonalDataSupport.kt` |
| 2 | `app/.../settings/PersonalDictionaryScreen.kt` | `app/.../settings/ShortcutsScreen.kt` |
| 2 | `tools/dictbuild/.../Compile.kt` | `tools/dictbuild/.../CountOptions.kt` |
| 1 | `tools/dictbuild/.../Json.kt` | `tools/eval/.../Json.kt` |

### 3.4 Android lint

3 findings, all informational:
- `NewerVersionAvailable`: `commons-compress 1.27.1` → 1.28.0 (tools only).
- `PluralsCandidate` in `app` (`%d words`).
- `TypographyOther`: `(c)` → `©`.

---

## 4. Architecture & branch model

### 4.1 Module graph (main-source imports; acyclic)

```
app ──────────────► core:data, core:decoder, core:dictionary, core:engine-api, core:ime,
                    core:input, core:layout, core:learning, core:settings, feature:keyboard-ui
feature:keyboard-ui ► core:engine-api, core:input, core:layout, core:learning, core:settings
core:data ─────────► core:learning
core:decoder ──────► core:dictionary, core:engine-api, core:learning
core:learning ─────► core:engine-api
tools:dictbuild ───► core:dictionary
tools:eval ────────► core:decoder, core:dictionary, core:engine-api, core:layout, core:learning
```

`app` additionally imports `KeyCorrectTheme` from `core:designsystem` (a top-level `@Composable`
`fun`, so it does not show as a class edge); it is also declared as a dependency in the module
build files. No cycles, no orphan module. `core:engine-api` is depended on by 5 modules.

### 4.2 Branch model

- `main` is the merge base (`2b1f732`).
- `phase-1-core-keyboard` and `phase-2-prediction-core` add **no** commits over `main` — safe to
  delete (the handoff already says so).
- `phase-3-learning` = `main` + 23 commits (+20,200/−185 across 119 files). It is the branch to
  review; there is no divergent feature work elsewhere.

### 4.3 Completeness

- Every `KeyboardSettings` field is read somewhere (min 7 refs). No dead settings.
- No unimplemented stubs except `ClassicEngine.load()/unload()` no-ops (see §5.1) and one UI TODO
  (`SuggestionStrip.kt:73`, accessibility strings).
- `core:engine-api` contains **Phase 7 (neural/plugin) surface that Phase 3 does not use** — see
  §5.1. This is "ahead of schedule", not a bug, but it is unused surface area today.
- Test coverage is strong and proportional: `feature:keyboard-ui` (17 files),
  `core:learning` (10), `core:decoder` (10), `tools:dictbuild` (10).

---

## 5. Findings

### 5.1 Dead & orphaned code (P0 — mechanical, no behaviour change)

| # | Location | Symbol | Evidence | Fix |
|---|---|---|---|---|
| D1 | `feature/keyboard-ui/.../prediction/PredictiveInput.kt:196` | `private class LearnedWord` | Detekt `UnusedPrivateClass`; 0 other references | Delete the class |
| D2 | `.../PredictiveInput.kt:1263` | `private fun rejectWord(word: String)` | Detekt `UnusedPrivateMember`; 0 callers | Delete the function |
| D3 | `.../PredictiveInput.kt:212` | `val isComposing: Boolean` | Zero references in main + tests | Delete the property |
| D4 | `core/decoder/.../Text.kt:146-148` | `object DecoderSentenceContext` | Only referenced by `feature/.../SentenceContextParityTest.kt` | Delete **after** de-duplicating §5.2 F1 (then the parity test is unnecessary) |
| D5 | `core/decoder/.../LanguageModel.kt:62-64` | `val userContextWords` | Never read; internal `userContext` already used | Delete the property |
| D6 | `core/learning/.../model/NgramModel.kt:21,24,27` | `unigramCount`, `bigramCount`, `trigramCount` | Only the declarations exist (the `Dictionary` class has same-named, *used*, properties — different type) | Delete all three properties |
| D7 | `core/learning/.../model/Lexicon.kt:17` | `ForgetResult.droppedOutcomes` | Field is written in `PersonalModel.forgetSince` (`PersonalModel.kt:652`, arg `dropped`) but read nowhere | Remove the field and the `dropped` computation |
| D8 | `core/dictionary/.../Format.kt:37` | `const val BITMAP_BLOCK_NODES` | 1 reference (definition); `BITMAP_BLOCK_BYTES` is the one used | Delete the constant |
| D9 | `core/engine-api/.../TextEngine.kt:40,44,51,54` | `GenerativeEngine`, `GenerateRequest`, `GenerateResult`, `ScoringEngine` | No production or test reference (grep `engineapi` shows only `PredictiveEngine`, `Candidate`, `Predict`, context, `Capability.Predict`) | Delete, or move to a clearly-labelled `future/` file. See note below |
| D10 | `core/engine-api/.../TextEngine.kt:23-27` | `Capability.Generate`, `Capability.Score`, `Capability.Learn`, `Capability.Hints` | Only `Capability.Predict` is ever used | Keep the enum but consider trimming entries; low priority |
| D11 | `core/decoder/.../ClassicEngine.kt:129,131` + `TextEngine.load/unload` | suspend `load()/unload()` | Only called from `SuspendPredictTest`; production uses the synchronous `Predictor` | Optional: collapse `PredictiveEngine` to the synchronous contract now and reintroduce async in Phase 7 |
| D12 | `core/data/.../InMemoryStores.kt:17,78` | `InMemoryUserDictionaryStore`, `InMemoryShortcutStore` | Referenced only from tests (`core:data` test, `app` test) | Move to a `testFixtures` source set or `src/test` (see §5.5) |
| D13 | `core/learning/.../model/Persistence.kt:187` | `InMemoryLearningStore` | Referenced only from tests/eval | Same as D12 |

> **D9 note (important):** `ClassicEngine : PredictiveEngine : TextEngine`, so `TextEngine` is used
> transitively. Removing the Tier-0/1 interfaces does **not** break `ClassicEngineTest` or
> `SuspendPredictTest` as long as `PredictiveEngine`, `EngineInfo`, `Capability.Predict` and the
> suspend `predict` remain. If the team prefers to keep the Phase 7 SDK surface, isolate it in a
> dedicated file (`TextEngineTiers.kt`) with a header comment saying it is unused until Phase 7, so
> it is clearly not load-bearing.

**Suggested first commit (D1–D3, D5, D6, D8):** purely additive-neutral deletions. Run
`./gradlew testDebugUnitTest spotlessCheck lintDebug` — expect the same 1067 tests. No test should
need editing.

### 5.2 Duplicated logic (P0/P1)

**F1. Sentence-context parser exists twice (34 clone windows). — P0**
- `core/input/.../SentenceContext.kt:18` `lastWords(...)`
- `core/decoder/.../Text.kt:57` `Text.lastSentenceWords(...)`
- The decoder copy is private; `DecoderSentenceContext` (`Text.kt:146`) is a **public shim that
  exists only so `SentenceContextParityTest` can assert the two agree**. The KDoc on
  `SentenceContext` literally says "Keep the two in step."
- Character helpers (`isWordChar`, `isApostrophe`, `isHyphen`, `isSentenceEnd`) are duplicated in
  the same two files, and `feature/.../PredictiveInput.kt:1798` has a third apostrophe helper.
- **Why it matters:** context buckets must be byte-identical or learning silently never matches.
  A parity test proves the risk is real; it is a correctness invariant maintained by hand.
- **Suggested fix:** create a tiny pure-JVM `core:text` module (or a `shared` source set) holding
  `Text`/`SentenceContext`/char classes; have `core:input` (Android) and `core:decoder` (JVM) both
  depend on it. Because `core:decoder` is `keycorrect.jvm.library` and `core:input` is
  `keycorrect.android.library`, the shared module must be a JVM library. Move the single
  implementation there, delete `DecoderSentenceContext` and `SentenceContextParityTest`, and have
  `SentenceContextTest` become the one authoritative test.
- **Alternative (smaller, lower risk):** invert one direction — make `core:input` depend on
  `core:decoder`? No: `core:input` is Android, `core:decoder` JVM, and the decoder must not depend
  on Android. Module extraction is the clean answer.
- **Verification:** `SentenceContextTest` + the learning-threshold tests
  (`core:learning/.../KeepThresholdTest`, `ReviewFixesTest`) must stay green; delete the parity test
  only once both callers use the shared function.

**F2. `editDistance` + `fold` copied verbatim (11 clone windows). — P0**
- `core/learning/.../classifier/EditDistance.kt:4` `editDistance`, `:25` `fold`
- `feature/keyboard-ui/.../prediction/RecentWords.kt:164` `editDistance`, `:149` `fold`,
  `:147` `sameWord`
- `feature:keyboard-ui` already depends on `core:learning` as `api`, so it can use the originals.
- **Suggested fix:** make `editDistance`/`fold` public (or move to an internal shared utility in
  `core:learning` and expose), delete the `RecentWords` copies and its `sameWord` wrapper, or keep
  `sameWord` as a one-liner delegating to `fold`. The two `fold` bodies are identical, so this is a
  pure move. `Internals.kt` has a third `Text.fold` that additionally calls `trim()` — leave that
  variant alone unless callers are audited for the trim semantics.
- **Verification:** `PredictionUnitsTest`, `LearningEventsTest`, `OutcomeClassifierPropertiesTest`.

**F3. Settings-screen scaffolding duplicated. — P1**
- Clone windows between `LearningScreen.kt`, `LearningSupport.kt`, `PersonalDataSupport.kt`
  (empty-state / row scaffolding), and between `PersonalDictionaryScreen.kt` and
  `ShortcutsScreen.kt` (list + query + dialog scaffolding).
- `LearningListScreens.kt` already provides a generic `LearningListScreen`/`ItemList`, but
  `PersonalDictionaryScreen`/`ShortcutsScreen` predate it and duplicate it. Detekt also flags their
  `LongParameterList` (`LearningListScreens.kt:152,227`, `PersonalDictionaryScreen.kt:132`).
- **Suggested fix:** route both remaining screens through the generic list component (or a small
  `SettingsList` composable in `SettingsRows.kt`), and extract the repeated empty-state block into
  one composable. This removes ~3 clone groups with no UI change.
- **Verification:** these screens have no direct Compose UI tests; validate with
  `./gradlew assembleDebug` + the device smoke test (setup → personal dictionary → shortcuts).

**F4. CLI argument parsing duplicated across tools. — P1**
- The `fun value(flag, inline)` + `when (flag) { "--x" -> o.copy(...) }` pattern appears in
  `tools/dictbuild/.../CountOptions.kt`, `CompileOptions` (`Compile.kt`), `EvalOptions`
  (`EvalLm.kt`), `ExtractWikiCommand`, and `tools/eval/.../Options.kt` (Detekt: `Options.parse`
  is 115 lines, CC 60).
- **Suggested fix:** add a small `ArgParser` helper in `tools/` (a plain `--flag value` /
  `--flag=value` loop with typed getters) and express each command's options as a `when` over a
  parser handle. This is tools-only and cannot affect the shipping APK.
- **Verification:** `tools:dictbuild` and `tools:eval` unit tests; run each command with `--help`
  and one representative invocation.

**F5. Two `Json` writers. — P2**
- `tools/dictbuild/.../Json.kt` and `tools/eval/.../Json.kt`. They are tools-only; consolidate only
  if their APIs converge. Low priority.

### 5.3 Complexity hotspots (P2 — refactor with tests, do not rewrite)

Detekt's top findings, with numbers:

| Class / function | Size / complexity | Comment |
|---|---|---|
| `PredictiveInput` (`feature:keyboard-ui`) | **1825 lines, 96 functions**, `LargeClass` | The single biggest entropy source. See plan below |
| `Dictionary.parse` (`core:dictionary`) | 171 lines, CC 52 | Binary-format parser; high CC is largely inherent |
| `DictionaryWriter.compile` | 215 lines, CC 41 | Offline writer; inherent |
| `DictionaryWriter.buildNgrams` | 123 lines, CC 39 | Offline |
| `ClassicEngine.predictWord` | 125 lines, CC 42; class has 24 functions | Core decode pipeline |
| `TrieSearch.expand` | 90 lines, CC 32 | Beam search inner loop |
| `AutocorrectPolicy.decide` | CC 32, 8 returns | Policy rule set (partly extracted already) |
| `LayoutParser.resolveKey` | CC 49; `LayoutParser.parse` CC 28 | Layout validation |
| `FieldProfile.from` | 94 lines, CC 32 | EditorInfo mapping |
| `AutoCaps.shouldCapitalize` | CC 28, 11 returns | Text rules |
| `SentenceContext.lastWords` | CC 22 | Duplicated (F1) |
| `SqlLearningStore.writeState` / `readState` | 153 / 121 lines | Persistence row mapping |
| `PersonalModel` | 814 lines, 38 functions; `handle` CC 19, 10 returns | Learning core |
| `Options.parse` (eval) | 115 lines, CC 60 | See F4 |
| `LearnSim.run` (eval) | 225 lines, CC 73 | Offline harness only |

**Recommended approach (do not attempt a big-bang rewrite):**

1. **`PredictiveInput` is the only class worth splitting now.** It is one cohesive state machine
   with clearly separable responsibilities already visible in its section comments. A safe split
   (done as a series of behaviour-preserving moves, each verified by the existing
   `PredictiveControllerTest`, `LearningEventsTest`, `DeleteAndRetypeTest`, `WordHapticsTest`):
   - `ComposingState` — `composing`, `touches`, `before/after` cache, `append`, `separator`,
     `finishWord`, `finishAsTyped`, `resetWordState`, context/caps methods.
   - `UndoController` — `Undo`, `Correction`, `closeUndo`, `keep`, `undoCorrection`,
     `recomposePreviousWord`, `beginEdit`, `endEdit`.
   - `HeldRevert` — `pendingRevertTracked`, `hold`, `flushPendingRevert`, `dropPendingRevert`,
     `settleHeldRevert`, `resolveEdit`.
   - `LearningBridge` — already partially extracted (`LearningRecorder`, `ShownTracker`,
     `WordMemory`, `PhraseWatch`); move `record`, `completeWord`, `touchSamples`, phrase methods.
   - `Prompts` — `addWord`/`neverWord` timers and overlays.
   Keep `PredictiveInput` as the facade the controller talks to, so no caller changes.
   After the split, re-run Detekt; the `LargeClass`/`TooManyFunctions` findings should fall below
   threshold.
2. **Binary parsers (`Dictionary`, `DictionaryWriter`) and offline tools (`LearnSim`, `Options`)**
   are high-CC but low-risk to leave: they are covered by round-trip/robustness tests and their
   complexity mirrors a file format or a CLI. Prefer **documenting the section structure** and
   adding targeted tests over refactoring. If touched, extract independent sections into private
   functions (e.g. `Dictionary.parseHeader`, `parseNodes`, `parseNgrams`) — mechanical, test-backed.
3. **`AutocorrectPolicy.decide`** already delegates to `blocksField`/`protects`/`eligible`. Extracting
   the remaining gates into named predicates (`passesLengthAndShape`, `beatsLiteral`,
   `runnerUpSafe`, `pickTie`) would cut CC and make each §6.3 rule separately testable. Tests exist
   (`AutocorrectSafetyTest`, `TieRuleTest`).
4. **Cut magic numbers where they are behavioural** (spatial sigma, thresholds, TTLs) into named
   constants/config. Do **not** chase all 460 Detekt `MagicNumber` hits — most are corpus math in
   `tools/*` and test fixtures.

### 5.4 Missing guardrails (P0/P1 — prevents new entropy)

**G1. Detekt is not in the build. — P0**
- Evidence: root `build.gradle.kts` only applies `spotless`; `gradle/libs.versions.toml` has no
  Detekt; `build-logic` has no Detekt convention. Yet `docs/PLAN.md` (Phase 0) lists
  "lint/detekt/ktfmt".
- **Suggested fix:** add Detekt as a root task via a `detekt` version catalog entry and a
  `detekt { }` block, wired into `check`. Start with `--build-upon-default-config` plus overrides:
  - exempt `@Composable` from `FunctionNaming` (removes all 63 false positives);
  - set a **baseline** (`detekt-baseline.xml`) capturing current findings so the build is green
    immediately, then burn the baseline down as part of this review;
  - turn on `UnusedPrivateClass`/`UnusedPrivateMember` (already default) so dead code cannot
    reappear.
- **Verification:** `./gradlew detekt` green on a clean checkout; deleting a new unused private
  member makes it fail.

**G2. No CI. — P0**
- Evidence: no `.github/` directory.
- **Suggested fix:** a GitHub Actions workflow running
  `./gradlew spotlessCheck testDebugUnitTest detekt lintDebug assembleDebug`. Keep `tools:eval`
  misspell mode out (needs downloaded data). This is explicitly offered as a next step in
  `docs/HANDOFF.md` §10.
- **Verification:** the workflow runs on a PR from this branch.

**G3. Test-only classes live in `src/main`. — P1**
- `InMemoryStores.kt` (`core:data`) and `InMemoryLearningStore` (`core:learning/model/Persistence.kt`)
  ship in production artifacts but are only used by tests.
- **Suggested fix:** move them to `testFixtures` source sets (enable `testFixtures` in the
  `keycorrect.android.library` / `keycorrect.jvm.library` convention plugins) or to `src/test`,
  consumed by the `app` tests via `testImplementation(testFixtures(project(":core:data")))`.
- **Verification:** `./gradlew testDebugUnitTest` + `assembleRelease` (confirm the fakes are absent
  from the release dex, shrinking the artifact).

### 5.5 Other observations (low priority)

- `core:engine-api` is a public SDK contract that currently only serves one synchronous engine; the
  Tier-0/1 hierarchy is unused (D9/D11). Decide explicitly whether to keep it now or defer it to
  Phase 7. Keeping unused public API is entropy; deferring it risks churn.
- `KeyboardController` (436 lines, 20 functions) and `PredictionHost` (13 functions, 8-parameter
  constructor) would benefit from the same "extract collaborators" treatment as `PredictiveInput`
  if they grow further.
- `core/designsystem` (21 lines) is a thin wrapper that is fine as-is.
- `app`'s settings package (3008 lines) duplicates list/empty-state/dialog scaffolding; see F3.

---

## 6. Prioritised implementation plan

Each step is independently shippable and must leave the build green.

**Phase A — dead code (safe, do first)**
1. D1–D3 (`PredictiveInput`), D5 (`LanguageModel`), D6 (`NgramModel`), D8 (`Format`).
2. D7 (`ForgetResult.droppedOutcomes`): remove the field and the `dropped` local in
   `PersonalModel.forgetSince`.
3. D9/D10/D11 decision on `core:engine-api` (delete Tier-0/1 or isolate with a comment).
4. D4 (delete `DecoderSentenceContext`) — only together with F1.

**Phase B — guardrails**
5. G1: add Detekt with baseline + `@Composable` exemption + `check` wiring.
6. G2: add the CI workflow.

**Phase C — de-duplication**
7. F1: extract the shared text/context module; delete the duplicate parser, the shim and the parity
   test.
8. F2: single `editDistance`/`fold` from `core:learning`, delete the `RecentWords` copies.
9. F3: consolidate the settings list/empty-state scaffolding.
10. F4: shared tools CLI parser; simplify `Options.parse`.
11. D12/D13 (G3): move the in-memory fakes out of `src/main`.

**Phase D — structure (only with tests as the net)**
12. Split `PredictiveInput` per §5.3 and re-check Detekt thresholds.
13. Extract `AutocorrectPolicy.decide` predicates.
14. Optional: split `Dictionary`/`DictionaryWriter` parse/compile sections into private functions.

Suggested commit boundaries: one commit per numbered step (or per group within a phase), each
running the full verification command. Do not combine de-duplication and structural splitting in one
commit.

---

## 7. Explicit non-goals / do not touch without a device test

- **Privacy invariants** documented in `docs/HANDOFF.md` §5 and `docs/PLAN.md` §15 (sensitive-field
  gates, no learning in email/URL/address, no INTERNET permission, logs without user text). Any
  refactor must preserve these; the existing tests cover many of them — keep them passing.
- **The tie rule, keep thresholds, sentence TTL and adaptive threshold** behaviour: changing default
  numbers changes user-visible behaviour and must go through the user (per the project workflow).
- **`core/dictionary` binary format** and the committed 8.9 MB asset: parser refactors must keep
  `ShippedAssetTest`, `DictionaryRoundTripTest`, `FormatRobustnessTest`, `CorruptPayloadTest` green.
- **`PredictiveInput` semantics around undo/held revert/retype guard** — this is subtle and
  behaviour-rich (see `docs/HANDOFF.md` §8 known issues). Split for readability only; do not change
  logic while splitting.
- Framework overrides flagged by the unused scan (`onCreateInputView`, `onConfigure`, `onUpgrade`,
  `onCreateInlineSuggestionsRequest`, `onInlineSuggestionsResponse`) and Gradle convention-plugin
  classes are **not** dead — do not delete them.

---

## 8. Verification checklist (run after every step)

```sh
./gradlew spotlessApply              # if needed, then spotlessCheck
./gradlew testDebugUnitTest          # expect 1067 tests, 0 failures (minus intentionally removed)
# The JVM-module and tools tests run as part of the task above, or individually:
./gradlew :core:learning:test :core:decoder:test :core:dictionary:test \
          :core:layout:test :core:input:test :tools:dictbuild:test :tools:eval:test
./gradlew lintDebug assembleDebug
```

- If a test count drops, every removed test must correspond to a deleted duplicate (F1's parity
  test, F2's duplicate coverage) — record which in the commit message.
- After G1: `./gradlew detekt` must not report **new** findings beyond the baseline.
- For UI changes (F3): run the Tab S9 smoke test (setup → personal dictionary → shortcuts →
  learning screens) per `docs/HANDOFF.md` §4, and restore the Samsung keyboard afterwards.

---

## Appendix A — Detekt rule counts (default config, main sources)

```
460  MagicNumber
 82  ReturnCount
 63  FunctionNaming        (all @Composable false positives)
 47  CyclomaticComplexMethod
 40  LongParameterList
 34  LongMethod
 29  ComplexCondition
 24  TooManyFunctions
 21  TooGenericExceptionCaught
 13  NestedBlockDepth
 12  LoopWithTooManyJumpStatements
 11  MaxLineLength
  6  ThrowsCount
  4  ImplicitDefaultLocale
  2  SwallowedException
  1  ForbiddenComment
  1  FunctionOnlyReturningConstant
  1  LargeClass
  1  UnusedPrivateClass
  1  UnusedPrivateMember
  1  MatchingDeclarationName
  1  SpreadOperator
```

## Appendix B — Unused-declaration scan output (main sources, zero refs outside declaration)

```
app/.../KeyCorrectImeService.kt:30     class KeyCorrectImeService                    (framework entry point — keep)
app/.../KeyCorrectImeService.kt:157    fun onCreateInlineSuggestionsRequest         (framework override — keep)
app/.../KeyCorrectImeService.kt:164    fun onInlineSuggestionsResponse              (framework override — keep)
build-logic/.../AndroidApplicationConventionPlugin.kt:7  ...                        (Gradle plugin — keep)
build-logic/.../AndroidLibraryConventionPlugin.kt:6      ...
build-logic/.../AndroidComposeConventionPlugin.kt:8      ...
build-logic/.../JvmLibraryConventionPlugin.kt:9          ...
build-logic/.../KotlinSerializationConventionPlugin.kt:6 ...
core/ime/.../ComposeInputMethodService.kt:38  val viewModelStore        (interface override — keep)
core/ime/.../ComposeInputMethodService.kt:55  fun onCreateInputView     (framework override — keep)
core/engine-api/.../TextEngine.kt:41          fun generate              (dead — D9)
core/engine-api/.../TextEngine.kt:46          val maxTokens             (dead — D9)
core/dictionary/.../Format.kt:37              BITMAP_BLOCK_NODES        (dead — D8)
core/decoder/.../LanguageModel.kt:62          userContextWords          (dead — D5)
core/data/.../KeyCorrectDatabase.kt:146       fun onConfigure           (framework override — keep)
core/data/.../KeyCorrectDatabase.kt:155       fun onUpgrade             (framework override — keep)
core/learning/.../model/NgramModel.kt:21      unigramCount              (dead — D6)
core/learning/.../model/Lexicon.kt:17         droppedOutcomes           (dead — D7)
feature/.../PredictiveInput.kt:196            class LearnedWord         (dead — D1)
feature/.../PredictiveInput.kt:212            val isComposing           (dead — D3)
feature/.../PredictiveInput.kt:1263           fun rejectWord            (dead — D2)
```

(Full machine output was `/tmp/zero_decls.txt` and `/tmp/orphan_types.txt` during the review; those
paths are ephemeral — re-run the described scripts if needed.)
