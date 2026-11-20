# Third-party components of the voice feature

KeyCorrect itself is GPL-3.0-or-later ([`LICENSE`](LICENSE)). The offline speech feature (Phase 6,
[`docs/archive/PHASE6.md`](docs/archive/PHASE6.md)) uses the components below. The first row is compiled into the
keyboard; the rest ship only in the optional **voice pack** APK (`dev.keycorrect.voice`) or **neural pack**
APK (`dev.keycorrect.neural`), which the keyboard loads at run time. All of them allow redistribution with this notice; their licence texts
are available at the links, and the models' own LICENSE files are in the downloaded archives
(`tools/data/fetch-voice.sh`).

| Component | Used for | Licence | Copyright |
|---|---|---|---|
| sherpa-onnx v1.13.8, Kotlin API (`feature/voice-sherpa/src/main/kotlin/com/k2fsa/sherpa/onnx/`, modified: see the headers) | Speech-recognition API | Apache-2.0, https://www.apache.org/licenses/LICENSE-2.0 | Xiaomi Corporation (k2-fsa/sherpa-onnx) |
| sherpa-onnx v1.13.8, native library `libsherpa-onnx-jni.so` (voice pack) | Runs the speech and voice-activity models | Apache-2.0 | Xiaomi Corporation and the sherpa-onnx contributors |
| ONNX Runtime, `libonnxruntime.so` (voice pack; build bundled with sherpa-onnx) | Neural-network inference | MIT, https://github.com/microsoft/onnxruntime/blob/main/LICENSE | Microsoft Corporation |
| Moonshine v2 tiny (English), `encoder_model.ort`, `decoder_model_merged.ort`, `tokens.txt` (voice pack) | Speech to text | MIT (the English models) | 2025 Useful Sensors, Inc. (dba Moonshine AI) |
| Silero VAD, `silero_vad.onnx` (voice pack) | Finds where speech starts and stops | MIT, https://github.com/snakers4/silero-vad/blob/master/LICENSE | Silero Team |
| llama.cpp v0.7.0 and ggml, compiled into `libkcllama.so` (neural pack) | Runs the language model that reranks suggestions | MIT, https://github.com/ggml-org/llama.cpp/blob/master/LICENSE | 2023-2026 The ggml authors |
| SmolLM2-135M, 8-bit GGUF quantisation `models/default.gguf` (neural pack) | Language model for reranking suggestions | Apache-2.0, https://www.apache.org/licenses/LICENSE-2.0 | Hugging Face (HuggingFaceTB/SmolLM2-135M) |

Apache-2.0 notice for sherpa-onnx and SmolLM2: licensed under the Apache License, Version 2.0; you may not use
these files except in compliance with the License; software distributed under it is provided "AS
IS", without warranties or conditions of any kind. The Kotlin API files carry their original
copyright headers and state their changes.

MIT notice (ONNX Runtime, Moonshine, Silero VAD, llama.cpp and ggml): permission is hereby granted, free of charge, to
any person obtaining a copy of the software and associated files, to deal in it without
restriction, subject to including the copyright notice and this permission notice in all copies or
substantial portions; the software is provided "AS IS", without warranty of any kind.

## Neural swipe (Phase 10)

The first row is compiled into the keyboard's code; the rest ship only in the optional **swipe
pack** APK (`dev.keycorrect.swipe`), which the keyboard loads at run time. Sources are pinned by
`tools/data/fetch-swipe-model.sh`.

| Component | Used for | Licence | Copyright |
|---|---|---|---|
| FUTO swipe-library, input resampler (`src/resampler.cpp` at commit `e8ade0e0`), ported to Kotlin in `core/swipenet/.../SwipeResampler.kt` (see its header) | Preparing a gesture for the encoder exactly as it was trained | GPL-3.0, https://gitlab.futo.org/keyboard/swipe-library/-/blob/main/LICENSE | FUTO |
| FUTO Swipe encoder `honorable_sturgeon/model_int8.pte`, unmodified (`swipenet/model_int8.pte`, swipe pack; the licence text ships beside it as `swipenet/LICENSE.md`) | Turning a swipe into per-key probabilities | FUTO Model Weights License 1.0, https://huggingface.co/futo-org/futo-swipe/blob/main/LICENSE.md | FUTO Holdings, Inc. |
| ExecuTorch v1.5.1, compiled into `libkcswipenet.so` (swipe pack) | Runs the encoder | BSD-3-Clause, https://github.com/pytorch/executorch/blob/main/LICENSE | Meta Platforms, Inc. and affiliates |
| XNNPACK (ExecuTorch's pinned commit), in `libkcswipenet.so` | Neural-network kernels | BSD-3-Clause, https://github.com/google/XNNPACK/blob/master/LICENSE | Facebook, Inc. and affiliates; Google LLC |
| cpuinfo and pthreadpool, in `libkcswipenet.so` | CPU feature detection, thread pool | BSD-2-Clause | Facebook Inc.; Google LLC; Georgia Institute of Technology; Marat Dukhan |
| FP16 and FXdiv (headers), in `libkcswipenet.so` | Half-precision conversion, fast division | MIT | Facebook Inc.; Georgia Institute of Technology; Marat Dukhan |
| FlatBuffers (headers), in `libkcswipenet.so` | Reading the `.pte` program format | Apache-2.0 | Google Inc. |

FUTO Swipe terms: the weights may be used and redistributed for any purpose provided users see a
notice that the product is powered by "FUTO Swipe" (shown under Settings → Editing → Neural swipe
whenever the pack is installed, and in Settings → About → Open-source licences) and every
redistribution carries a copy of the terms. The pack carries it as `swipenet/LICENSE.md`; the
keyboard bundles no copy and shows the installed pack's text in Settings → About.

BSD notice (ExecuTorch, XNNPACK, cpuinfo, pthreadpool): redistribution and use in source and binary
forms, with or without modification, are permitted provided that the copyright notice, this list
of conditions and the disclaimer are retained; the software is provided "AS IS", without warranty
of any kind, and the names of the copyright holders may not be used to endorse derived products.
FP16 and FXdiv are under the MIT notice above; FlatBuffers under the Apache-2.0 notice above.
