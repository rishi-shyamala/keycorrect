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

Also recorded in [DECISIONS.md](DECISIONS.md).

## External pieces (checked 2026-11-19)

- **FUTO Swipe encoder** ([futo-org/futo-swipe](https://huggingface.co/futo-org/futo-swipe)). A 1D TCN with 635k parameters. Inputs: the gesture resampled to 64 (x, y) points, the key centres (up to 64), and a key mask. Outputs: 32 steps of log-probabilities over the keys plus a blank (CTC-style), plus an intention gate. Because it takes the layout as input, it works with our layouts without retraining. Tensors: inputs `features [1,2,64]`, `layout_keys [1,64,2]`, `layout_mask [1,64]`; outputs `log_emissions [1,32,65]` (64 keys plus a blank), `coefficients [1,32,64]` and the gate `lambda [1,32,1]`. Files: `model_int8.pte` 1.0 MB, `model_fp32.pte` 2.65 MB (ExecuTorch 1.5, XNNPACK). FUTO's decoder and language model are not used: they are QWERTY/English-only, and our own dictionary and LM do that job.
- **Licence: FUTO Model Weights License 1.0.** Use, redistribution and derivative models are allowed for any purpose, commercial included. Conditions:
  - a visible notice to users that the product is "powered by FUTO Swipe";
  - a copy of the terms with every redistribution;
  - a notice when the weights are modified;
  - automatic termination on breach. A cure within 30 days reinstates it once; a later breach is permanent. There is also a patent-claim clause.

  The weights ship only in the separately installed pack, not in the GPL app.
- **Dataset** ([futo-org/swipe.futo.org](https://huggingface.co/datasets/futo-org/swipe.futo.org), MIT, commit `d71bf5f`). About 1.22M swipes as JSON Lines. `swipe-1` has train 939,550, dev 54,269 and test 49,970 rows. Each row has the target `word`, its `sentence`, `canvas_width`/`canvas_height`, `orientation`, and `data` points `{t, x, y}`, with x and y normalised to the canvas. Rows record no key positions; `swipe-5` adds a `layout` name, and `swipe-5/layouts/*.json` probably holds the geometry.
- **Inference library:** FUTO's C++ `swipe-library` (said to be GPL; check before reading it for the preprocessing). No training code is published.

## Parts (one PR each, against `main`)

### 10a-1. Real-swipe eval (no model yet) (done 2026-11-19)
- `tools/data/fetch-swipe.sh` downloads the `swipe-1` dev and test splits (and the layouts) into `data/raw/swipe/`, pinned to commit `d71bf5f`, with SHA-256 in `tools/data/swipe-sources.sha256`. The data is git-ignored, as with the other fetch scripts, and documented in DATA_SOURCES.md.
- Recover the collection keyboard's geometry: from `swipe-5/layouts/qwerty.json` if it covers the swipe-1 site, otherwise from the canvas aspect ratio and the paper.
- Map each swipe's points onto our QWERTY layout geometry, in key-width units.
- `eval --mode swipe --real <split>` reports top-1, top-3, wrong commits and latency for SHARK² on real swipes, using the same metrics as the synthetic eval.
- **Exit:** the SHARK² baseline on real swipes, recorded in EVAL.md. If it is already close to the encoder's published accuracy, stop here and report back before building the rest.
- **Result:** SHARK² gets 81.2% top-1 on 48,355 real test swipes, and 84.0% top-1 / 91.9% top-3 on dictionary words. Long words are weakest (72-78%). The mapping residual is 0.001 key widths, so the two QWERTYs line up. There is clear headroom, so the plan continues with 10a-2.

### 10a-2. Runtime and pack
- A new `:swipepack` application module, `dev.keycorrect.swipe`, like `:voicepack`: no code, no permissions, signed with the app's key. It holds the ExecuTorch runtime (built with the NDK, or the prebuilt AAR's `.so` if its licence and size fit), `model_int8.pte`, and the FUTO licence text. It stays separate from the 141 MB neural pack, so swipe users don't need the LLM.
- A new `:core:swipenet` module: JNI glue that loads the pack's library through `SignedPackLocator` (signature-checked, as for voice). It exposes one call, `emissions(points, keys, mask)`. It runs in the keyboard process on the decoder thread, because the encoder is small enough that an IPC round trip would cost more than it saves. To be confirmed with on-device latency.
- A desktop build of the same glue, so `tools/eval` can run the encoder on the host (as `:core:llama` does).
- **Exit:** emissions identical (within float tolerance) to FUTO's reference on a few fixed inputs; the pack's signature check is tested; the cost per call is measured on the S23 Ultra.

### 10a-3. Fusion decoder
- A CTC-style prefix beam search over our dictionary trie with the encoder's emissions. The intention gate down-weights steps that aren't aimed at a key. Each beam word gets LM context from the existing `LanguageModel`.
- Merge with SHARK²'s top candidates: a weighted sum of the neural log-likelihood, the SHARK² score and the LM, with the weights tuned on the dev split only.
- Gestures with no encoder (pack missing, a layout with more than 64 keys, or a load failure) fall back to SHARK² alone.
- **Exit:** a better top-1 than SHARK² on the real test split; no regression beyond noise on the synthetic swipe eval; tap eval byte-identical; decode on lift ≤30 ms p95 on the S23 Ultra (SHARK² alone is ~1.1 ms).

### 10a-4. Settings, attribution, release
- A "Neural swipe" row (on when the pack is installed) in the swipe settings, from `SettingsRows.kt`. A visible "Powered by FUTO Swipe" line, with the licence text reachable from Settings → About and in THIRD_PARTY_NOTICES.md.
- `release.sh publish` attaches the swipe pack like the others (`publish_pack`).
- The test-build release notes explain the pack.

### Later (not in 10a)
- **10b, our own encoder:** trained on the `swipe-1` train split (MIT), exported to ExecuTorch, and compared on the same real test split. It replaces FUTO's when it matches, which removes the attribution. Needs a training setup (PyTorch; likely a GPU).
- **LoRA finetuning:** a long-term stretch goal (N-1).

## Risks
- ~~The dataset's points may not map cleanly onto our layout.~~ Measured in 10a-1: the affine fit's residual is 0.001 key widths.
- ExecuTorch's runtime size, and building it with the NDK for arm64-v8a and x86_64.
- The encoder's input normalisation must match FUTO's exactly. The model card and FUTO's library are the reference, and 10a-2's exit test checks it.
- Licence compliance is a release requirement: the notice and the licence copy, in every build that ships the pack.
