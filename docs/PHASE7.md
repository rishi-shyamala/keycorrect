# KeyCorrect — Phase 7 plan: neural and plugin engines

Goal (PLAN §4, §17): an optional on-device language model that improves suggestions (the SCORE tier
reranks the classic engine's candidates; GENERATE gives phrase completion), an LM-strength slider,
and the plugin AIDL SDK with a sample plugin. Exit criteria: the reranker improves eval metrics
within the latency budget, and the sample plugin works end to end.

## Decisions (user, 2026-10-18)

| # | Question | Decision |
|---|---|---|
| P7-1 | Getting the model onto the phone (no INTERNET, D3) | **A neural-pack APK plus file import.** An optional `dev.keycorrect.neural` APK holds the default model and the llama.cpp runtime. It is signature-checked like the voice pack. Settings → Engines → "Import model" also accepts any GGUF the user picks (SAF). |
| P7-2 | Downloads | **Approved:** llama.cpp (MIT) source at a pinned release, built with the NDK; candidate models from their official repos (SmolLM2-135M and 360M, Qwen3-0.6B, and the small Gemma models for testing). There is also a research step for newer small models released since the project began. Downloads are git-ignored with checksums recorded, and only the chosen default ships (in the pack). |
| P7-3 | Default | **On when the pack is installed:** the reranker is on automatically and phrase completion is off. Both switches and the LM-strength slider are in Settings → Engines. |
| P7-4 | Delivery | **One PR.** |

## Design
- **Process:** the model runs in a separate `:engine` process (bound service), so a crash or
  out-of-memory there never takes the keyboard down. The keyboard talks to it over the same AIDL
  contract that plugins use.
- **SCORE:** up to 16 candidates are scored against the shared context, reusing the KV cache. The
  score enters fusion as a log-prob feature weighted by the LM-strength slider. It is asynchronous
  with a deadline: the classic result is shown at once and the reranked one replaces it if it
  arrives in time.
- **GENERATE:** phrase completion runs asynchronously, with prompt templates.
- **Privacy gates (PLAN §4.3):** no engine text in password, incognito or no-learning fields.
  Plugins need per-plugin approval with a pinned certificate.
- **Plugin SDK:** the AIDL `dev.keycorrect.TEXT_ENGINE` and a sample plugin APK.
- **Eval:** a reranker arm in `tools/eval`, giving accuracy, false corrections and latency for each
  candidate model. The default is chosen from the data.
