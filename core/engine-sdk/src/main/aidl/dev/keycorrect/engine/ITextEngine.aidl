package dev.keycorrect.engine;

import android.os.Bundle;

/**
 * The plugin contract of KeyCorrect's text engines (action "dev.keycorrect.TEXT_ENGINE", API
 * version 1). The built-in neural engine implements it too. Every call may be made from any
 * binder thread; an engine that has no answer returns null. No call may take longer than the
 * timeout it is given (the keyboard allows at most 2000 ms).
 */
interface ITextEngine {
    /** The version of this contract the engine implements (1). */
    int apiVersion();

    /**
     * Who the engine is: "id", "name", "capabilities" (String[] of "Score" / "Generate"),
     * "languages" (String[] of BCP-47 tags) and "status" ("ready", "no_model", "loading" or
     * "error:<reason>").
     */
    Bundle info();

    /** Engine-specific options (the built-in engine takes "modelPath" or "useDefault"). */
    void configure(in Bundle options);

    /**
     * For each candidate, the sum of log probabilities of its tokens after the context, in the same
     * order. Null when the engine can't answer in time.
     */
    float[] score(String context, in String[] candidates, long timeoutMs);

    /** A short continuation of the prompt, or null when the engine can't answer in time. */
    String generate(String prompt, int maxTokens, long timeoutMs);
}
