# Third-party components of the voice feature

KeyCorrect itself is GPL-3.0-or-later ([`LICENSE`](LICENSE)). The offline speech feature (Phase 6,
[`docs/PHASE6.md`](docs/PHASE6.md)) uses the components below. The first row is compiled into the
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
