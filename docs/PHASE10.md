# KeyCorrect: Phase 10a plan (neural swipe)

Goal (PLAN §7 "Phase 10 (optional) neural encoder", D7): better swipe accuracy on real gestures,
by fusing a neural encoder with the SHARK² decoder. SHARK² scores 89.5% top-1 on clean synthetic
gestures and 78.5% on messy ones (docs/EVAL.md), but the generator follows SHARK²'s own model of a
swipe, so its accuracy on real fingers is unknown. Measuring that comes first.

## Decisions (user, 2026-11-19)

| # | Question | Decision |
|---|---|---|
| N-3 | Model source | **FUTO's encoder first, our own model later.** Ship FUTO Swipe's encoder (`model_int8.pte`, 1.0 MB, ExecuTorch) now. A model of our own, trained on the MIT-licensed swipe.futo.org data, follows as a separate part. It replaces FUTO's once it matches on the eval, which drops the attribution requirement. |
| N-4 | Integration | **Fuse both.** The encoder's key probabilities are decoded against our dictionary and language model and merged with SHARK²'s candidates by a tuned score. SHARK² stays the decoder when the pack is missing. The fusion is kept only if it beats SHARK² on real swipes. |
| N-5 | A new stage after this one | **Phase 11, simplification:** remove as many lines as possible without losing any feature or performance. Rework sections into their simplest form and justify every line of app code before keeping it. Recorded in PLAN §17; it gets its own plan when it starts. |

## Decisions (user, 2026-11-20)

| # | Question | Decision |
|---|---|---|
| N-6 | Runtime | **ExecuTorch v1.5.1 built from source** (the version the model was exported with), with the NDK for arm64-v8a and x86_64 and on the host for tools/eval: the model's portable ops plus XNNPACK, statically linked into one small JNI library (`libkcswipenet.so`). Not the prebuilt AAR (9-12 MB per ABI, fbjni/SoLoader, no bool tensors in its Java API, Android only). |
| N-7 | FUTO's C++ library | **Port only the input resampler** from swipe-library (GPL-3.0); the beam search stays ours (N-4). Attributed in THIRD_PARTY_NOTICES.md. |
| N-8 | Reference for the exit test | **The official `executorch==1.5.1` pip wheel**, run once in a scratch venv outside the repo; only the small golden file is committed. |
| N-9 | Working ahead | Phase 10 parts may be developed on local branches while the previous PR awaits merge, then rebased onto main before their own PR (still never stacked). |
| N-10 | Learning from swipes | **Add 10a-5, swipe learning, after 10a-3.** The encoder's weights stay fixed (on-device training is the N-1 stretch goal). Instead, learn swipe misdecodes from clear signals only, and learn a per-user path correction from accepted swipes. Keep each piece only if the eval shows a gain without new wrong commits. |
| N-11 | Intention gate | **The encoder's gate (lambda) stays as a tunable setting, default 0 (off),** because every gate > 0 lost accuracy on dev. |
| N-12 | Loading the swipe pack | **It loads lazily on the first swipe.** A missing pack is looked for again at most every 60 s on a swipe; any other failure disables neural swipe until the process restarts. |

All of these are also recorded in [DECISIONS.md](DECISIONS.md).

## External pieces (checked 2026-11-19)

- **FUTO Swipe encoder** ([futo-org/futo-swipe](https://huggingface.co/futo-org/futo-swipe)). A 1D TCN with 635k parameters. Inputs: the gesture resampled to 64 (x, y) points, the key centres (up to 64), and a key mask. Outputs: 32 steps of log-probabilities over the keys plus a blank (CTC-style), plus an intention gate. Because it takes the layout as input, it works with our layouts without retraining. Tensors: inputs `features [1,2,64]`, `layout_keys [1,64,2]`, `layout_mask [1,64]`; outputs `log_emissions [1,32,65]` (64 keys plus a blank), `coefficients [1,32,64]` and the gate `lambda [1,32,1]`. Files: `model_int8.pte` 1.0 MB, `model_fp32.pte` 2.65 MB (ExecuTorch 1.5, XNNPACK). FUTO's decoder and language model are not used: they are QWERTY/English-only, and our own dictionary and LM do that job.
- **Licence: FUTO Model Weights License 1.0.** Use, redistribution and derivative models are allowed for any purpose, commercial included. Conditions:
  - a visible notice to users that the product is "powered by FUTO Swipe";
  - a copy of the terms with every redistribution;
  - a notice when the weights are modified;
  - automatic termination on breach. A cure within 30 days reinstates it once; a later breach is permanent. There is also a patent-claim clause.

  The weights ship only in the separately installed pack, not in the GPL app.
- **Dataset** ([futo-org/swipe.futo.org](https://huggingface.co/datasets/futo-org/swipe.futo.org), MIT, commit `d71bf5f`). About 1.22M swipes as JSON Lines. `swipe-1` has train 939,550, dev 54,269 and test 49,970 rows. Each row has the target `word`, its `sentence`, `canvas_width`/`canvas_height`, `orientation`, and `data` points `{t, x, y}`, with x and y normalised to the canvas. Rows record no key positions; `swipe-5` adds a `layout` name, and `swipe-5/layouts/*.json` probably holds the geometry.
- **Inference library:** FUTO's C++ `swipe-library` (GPL-3.0). Only its input resampler is ported (N-7). No training code is published.

## Parts (one PR each, against `main`)

### 10a-1. Real-swipe eval (no model yet) (done 2026-11-19)
- `tools/data/fetch-swipe.sh` downloads the `swipe-1` dev and test splits (and the layouts) into `data/raw/swipe/`, pinned to commit `d71bf5f`, with SHA-256 in `tools/data/swipe-sources.sha256`. The data is git-ignored, as with the other fetch scripts, and documented in DATA_SOURCES.md.
- Recover the collection keyboard's geometry: from `swipe-5/layouts/qwerty.json` if it covers the swipe-1 site, otherwise from the canvas aspect ratio and the paper.
- Map each swipe's points onto our QWERTY layout geometry, in key-width units.
- `eval --mode swipe --real <split>` reports top-1, top-3, wrong commits and latency for SHARK² on real swipes, using the same metrics as the synthetic eval.
- **Exit:** the SHARK² baseline on real swipes, recorded in EVAL.md. If it is already close to the encoder's published accuracy, stop here and report back before building the rest.
- **Result:** SHARK² gets 81.2% top-1 on 48,355 real test swipes, and 84.0% top-1 / 91.9% top-3 on dictionary words. Long words are weakest (72-78%). The mapping residual is 0.001 key widths, so the two QWERTYs line up. There is clear headroom, so the plan continues with 10a-2.

### 10a-2. Runtime and pack
- A new `:swipepack` application module, `dev.keycorrect.swipe`, like `:voicepack`: no code, no permissions, signed with the app's key. It holds the ExecuTorch runtime (built from source with the NDK, N-6), `model_int8.pte`, and the FUTO licence text. It stays separate from the 141 MB neural pack, so swipe users don't need the LLM.
- A new `:core:swipenet` module: JNI glue that loads the pack's library through `SignedPackLocator` (signature-checked, as for voice). It exposes one call, `emissions(points, keys, mask)`. It runs in the keyboard process on the decoder thread, because the encoder is small enough that an IPC round trip would cost more than it saves. Confirmed on the S23 Ultra: 0.95 ms per call.
- A desktop build of the same glue, so `tools/eval` can run the encoder on the host (as `:core:llama` does).
- **Exit:** emissions identical (within float tolerance) to FUTO's reference on a few fixed inputs; the pack's signature check is tested; the cost per call is measured on the S23 Ultra.
- **Progress (2026-11-20):** built as planned, with the runtime of N-6.
  - `tools/data/fetch-swipe-model.sh` pins the model, its licence and metadata (Hugging Face commit `0a9a44b`, SHA-256) and the ExecuTorch source (tag v1.5.1, commit `3b60683`).
  - `tools/swipenet/build.sh android|host` builds `libkcswipenet.so`: the JNI glue, the ExecuTorch runtime, the 16 portable ops the model calls outside its delegate (`tools/swipenet/ops.txt`, derived from the model) and XNNPACK, all static. Stripped sizes: arm64-v8a 1.78 MB, x86_64 2.92 MB. The swipe pack APK is 3.2 MB with both ABIs.
  - The model takes the pack's `.pte` bytes (read from the uncompressed asset) rather than a path, so nothing is copied to disk.
  - `:core:swipenet` (JVM) has the resampler port, `SwipeLayout` (the letter-area frame of any layout, up to 64 letter keys) and `SwipeEncoder` (also opened on the desktop). `:core:packs` has `SwipePackLoader`, which opens the encoder through `SignedPackLocator`.
  - **Exit results.** Against the wheel's outputs on 5 fixed inputs (the model card's "computer" swipe on FUTO's and on our QWERTY, a 120 Hz stroke that leaves the frame, 40 random keys, and a two-point gesture), the host build's max abs difference is 1.9e-6 (tolerance 1e-4). The Kotlin resampler matches FUTO's reference within 1e-5, and "computer" greedy-decodes to "computer". The pack signature check is unit-tested (`SignedPackLocatorTest`).
  - Cost per call: 0.33 ms on the desktop and 1.2 ms on the x86_64 emulator (one thread; relative only). On the S23 Ultra (`SwipeNetBenchmark`, debug pack, one thread, 50 runs): median 0.95 ms, min 0.84 ms, max 1.12 ms. That confirms running in the keyboard process on the decoder thread, with no IPC.

### 10a-3. Fusion decoder
- A CTC-style prefix beam search over our dictionary trie with the encoder's emissions. The intention gate down-weights steps that aren't aimed at a key. Each beam word gets LM context from the existing `LanguageModel`.
- Merge with SHARK²'s top candidates: a weighted sum of the neural log-likelihood, the SHARK² score and the LM, with the weights tuned on the dev split only.
- Gestures with no encoder (pack missing, a layout with more than 64 keys, or a load failure) fall back to SHARK² alone.
- **Exit:** a better top-1 than SHARK² on the real test split; no regression beyond noise on the synthetic swipe eval; tap eval byte-identical; decode on lift ≤30 ms p95 on the S23 Ultra (SHARK² alone is ~1.1 ms).
- **Progress (2026-11-20):** built as planned (details and tuning in EVAL.md, "Neural fusion").
  - `ClassicEngine` takes the encoder as an optional provider. `NeuralSwipe` runs the encoder on the decoder thread, outside the engine lock. `NeuralSearch` runs a best-path prefix beam search over the dictionary trie (32 prefixes per step; blank = class 64; a repeated key and apostrophes are free edges). Its 16 best words join SHARK²'s shortlist, and every word is ranked by `channel + 0.8 * neural + 1.0 * LM + 2.0 * letters`. The weights were tuned on dev.jsonl only.
  - The intention gate is implemented (`NeuralSwipeConfig.gate`), but on dev every non-zero setting lost accuracy (0.25: -0.07 points, 1: -14.5). The encoder's lambda is about 1 minus its blank probability, so the default is 0.
  - In the keyboard, one `SwipeNetHost` per process (in `AppContainer`) opens the swipe pack through `SwipePackLoader` on the IO dispatcher, triggered by the first swipe. A pack that isn't installed is looked for again after a minute. Any other failure is final for the process. The manifest's `<queries>` lists `dev.keycorrect.swipe`. The "Neural swipe" setting gates it (10a-4).
  - Fallback: with no encoder, a failed load, a layout `SwipeLayout.of` refuses, or an encoder error, the decode is SHARK² alone. Its output is byte-identical (`NeuralFusionTest`; the real dev and synthetic evals diff clean against the previous decoder).
  - **Exit results.** Real test split: top-1 81.20% → **91.11%**, top-3 88.92% → 94.78%, wrong commits 18.64% → 8.81%. Synthetic: 89.53% → 94.28% (noise 0.25) and 78.53% → 83.85% (0.4). The tap eval is byte-identical. Decode on lift on the desktop: p50/p95 0.97/2.01 ms (SHARK² 0.21/1.35). The emulator numbers are in PERF.md. On the S23 Ultra (`SwipeNetBenchmark`, 50 runs) a fused decode on lift takes 2.86 ms median, 2.98 ms in the slowest run (SHARK² alone 1.19 ms), against the ≤30 ms target. Previews go out at most every 50 ms and cost about the same as a decode on lift (the encoder always sees 64 resampled points), so a swipe keeps the decoder thread about 6% busy.

- **Swipe rerank (2026-11-22):** the final decode of a swipe is re-ranked by the SmolLM2 scorer of the tap path (`NeuralReranker.refineGesture`, gesture weight 0.8, 0.4 effective at LM strength 0.5; candidates of the final result only). Test split top-1 91.11% to 92.22% (698 gained, 161 lost), scorer p50/p95 5.8/13.9 ms on the desktop (EVAL.md, "Swipe rerank"). Previews are never reranked; `GestureRequest.engineAllowed` is set on the final request under the typing gates (neural rerank on, not sensitive, learning gate open). It runs on the decoder thread after the fused decode and waits at most the reranker's 40 ms deadline for the scorer; `DecoderLoop`'s 50 ms wait is unchanged, so a slow scorer answer makes the word arrive through the existing late-swipe path (typed when it lands, if nothing else happened) rather than delaying the main thread. The on-device scorer time is not measured yet.

### 10a-4. Settings, attribution, release (done)
- **Built.** `KeyboardSettings.neuralSwipe` (default on, key `neural_swipe`, in the backup like every setting). `SwipeNetHost` takes an `enabled` check: off, `encoder()` returns null without loading the pack, and an already-loaded encoder is kept but not used, so decoding is SHARK² alone (`SwipeNetHostTest`). `AppContainer` feeds the check from the settings and treats the setting as off until it is known. The row sits under Gesture typing in Settings → Editing (`EditingSettingsScreen`): with the signed pack installed it is live with "Powered by FUTO Swipe" below it; without, or with a pack signed by another key or whose licence asset can't be read, it stays visible, off and disabled, with the reason (`SwipePackState.kt` probes the pack through `SignedPackLocator`). The licence: Settings → About → Open-source licences has a Neural swipe section with the credit and a row that shows the full FUTO Model Weights License, read from the installed pack's own `swipenet/LICENSE.md` asset (nothing is bundled in the app; without the pack the section says the text appears once it is installed). `release.sh publish` attaches the swipe pack through `publish_pack` with release notes. THIRD_PARTY_NOTICES.md matches.

### 10a-5. Swipe learning (N-10)
The encoder's weights never change on the device. Swipes already teach the dictionary and the LM like typed words (word use, context, new-word promotion), and the fusion decoder uses both. This part adds the two things swipe lacks:
- **Misdecode memory (PLAN §6 row 12, a stub today in `OutcomeClassifier`, `CommitMethod.Gesture`).** Only clear signals count, so a change of mind is not mistaken for a decoding error:
  - **Strong:** the user taps another strip candidate right after the swipe.
  - **Medium:** the user deletes the whole word within a few seconds and re-swipes a word that was in the first swipe's candidate list.
  - **Ignored:** a delete followed by typing something unrelated, an edit made after the cursor moved, or a later edit.
  - **Weak positive:** keeping the word.
  - Evidence must repeat before it moves a ranking. It is bounded per word pair, keyed by a coarse gesture shape (the key sequence), and fades like the other learned data. Through the same privacy gates as all learning: none in password fields, incognito, no-learning fields or with learning off.
- **Path calibration.** A small per-user correction (offset, scale and skew per axis, in key widths) fitted to the paths of accepted swipes against their words' key centres, applied before both decoders. It works like the touch model's tap offsets: bounded, slow to adapt, and reset with the learned data.
- **Eval:** replay the real dev split as a simulated user who picks the right candidate or re-swipes after a misdecode (`--mode swipe --real --learn`), then score the held-out remainder. Report top-1, top-3 and wrong commits before and after. Tap and learn evals stay byte-identical.
- **Exit:** each piece is kept only if it improves top-1 on the replay without increasing wrong commits; the extra decode cost stays within 10a-3's ≤30 ms p95 budget on the S23 Ultra.
- **Progress (2026-11-23):** part 1 (replay) done: `--mode swipe --real FILE --learn` (docs/EVAL.md, "Swipe learning replay"; N-23, N-24). On dev (141 sessions, 16,609 scored swipes) today's word-use learning moves top-1 by -0.01 (SHARK²) and -0.04 (fusion) points; the oracle full affine calibration loses 0.67 points on SHARK² and is flat with the encoder, and an offset-only oracle gains +0.26 / +0.16, below the 0.3 bar. The misdecode memory's ceiling on the same replay is +0.16 points with the encoder (27 of 1,380 late-half misdecodes repeat an earlier decoded -> picked pair) against 202 correct swipes it could flip. **Stopped here (N-23):** neither piece is built.

### Later (not in 10a)
- **10b, our own encoder:** trained on the `swipe-1` train split (MIT), exported to ExecuTorch, and compared on the same real test split. It replaces FUTO's when it matches, which removes the attribution. Needs a training setup (PyTorch; likely a GPU).
- **LoRA finetuning:** a long-term stretch goal (N-1).

## Risks
- ~~The dataset's points may not map cleanly onto our layout.~~ Measured in 10a-1: the affine fit's residual is 0.001 key widths.
- ~~ExecuTorch's runtime size, and building it with the NDK for arm64-v8a and x86_64.~~ Built in 10a-2: 1.78 MB (arm64-v8a) and 2.92 MB (x86_64), stripped.
- The encoder's input normalisation must match FUTO's exactly. The model card and FUTO's library are the reference, and 10a-2's exit test checks it. Checked in 10a-2 (1.9e-6 max abs difference).
- Licence compliance is a release requirement: the notice and the licence copy, in every build that ships the pack.
