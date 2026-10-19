# Writing a KeyCorrect engine plugin

A plugin is a separate APK with a bound service. The keyboard asks it to **score** candidate words
(a language-model reranker) and, optionally, to **generate** a short continuation. This page is the
contract; `:sampleplugin` is a working example. The design is in [PHASE7.md](PHASE7.md).

## The contract

Add `:core:engine-sdk` (or copy `ITextEngine.aidl` and `EngineContract.kt`) and extend
`TextEngineService`. The base class wraps your methods in guards: an exception becomes a null
answer, a call that runs past its timeout is abandoned, and over-long input is refused.

```xml
<service android:name=".MyEngineService" android:exported="true">
    <intent-filter><action android:name="dev.keycorrect.TEXT_ENGINE" /></intent-filter>
</service>
```

The AIDL interface is `dev.keycorrect.engine.ITextEngine` (API version 1):

| Method | Meaning |
|---|---|
| `int apiVersion()` | `1`. |
| `Bundle info()` | Who you are (keys below). Called often; keep it cheap. |
| `void configure(in Bundle options)` | Engine-specific options. The keyboard sends none to a plugin today; ignore what you don't know. Return quickly and load heavy things in the background. |
| `float[] score(String context, in String[] candidates, long timeoutMs)` | For each candidate, the log probability (natural log, summed over its tokens) of the candidate after `context`, in the same order. Higher is better; only differences between candidates matter. |
| `String generate(String prompt, int maxTokens, long timeoutMs)` | A short continuation of `prompt`. Only if you declare `Generate`. |

`context` is the text before the word being typed (up to the limits below). An empty context means
the start of a text.

### `info()` Bundle keys (`EngineContract.INFO_*`)

| Key | Type | Value |
|---|---|---|
| `id` | String | A stable id, e.g. `"sample"`. |
| `name` | String | Shown in Settings → Engines. |
| `capabilities` | String[] | `"Score"` and/or `"Generate"`. |
| `languages` | String[] | BCP-47 tags, e.g. `"en"`. |
| `status` | String | `"ready"`, `"no_model"`, `"loading"` or `"error:<reason>"` (a short code, not user text). |
| `model` | String | Optional short description for the settings screen. |

While the status is not `ready`, calls may simply return null.

### Answers, timeouts and limits

- **Return null for "no answer"** (not ready, can't tell, out of time). The keyboard then uses the
  classic ranking. Never return a made-up score. `score` must return exactly one value per
  candidate and no NaN, or the guard turns it into null.
- **Timeouts:** the keyboard passes `timeoutMs` and applies at most **2000 ms**
  (`EngineContract.MAX_TIMEOUT_MS`). In practice the reranker waits tens of milliseconds, so
  answer fast and honour the timeout you are given. A call that overruns is abandoned.
- **Limits:** at most 32 candidates, 8192 characters per text, 64 generated tokens.
- **Threading:** calls arrive on binder threads, up to four at once. Make the service stateless
  or synchronize your own state. Don't block on the main thread; don't assume one call at a time.
  The keyboard allows one call in flight, but other apps can bind an exported service too.

## Privacy expectations

- The keyboard sends text only from fields that are **not sensitive** (not passwords, payment,
  etc.), where **learning is allowed**, and **not incognito**, and only when the user turned the
  engine on.
- Even so, it is what the user typed. A plugin should **not store it, log it, or transmit it**.
  The base class never logs call arguments; don't add logging of them either.
- Declare **no permissions** if you can. The approval screen **warns the user about any plugin
  that holds `INTERNET`**, because it could send the text away.

## Approval and certificate pinning

The keyboard never uses a plugin on its own. The user must approve it in Settings → Engines. The
approval pins the **package name plus the SHA-256 of the signing certificate**. If an update is
signed with a different certificate, the approval is **revoked** and the user must approve again,
so keep one signing key for your plugin's life. Approvals are never part of backup or transfer.

The service is exported, because the keyboard and the plugin are usually signed with different
keys, so a signature permission would stop the keyboard from binding it. The trade-off: any app
can bind your service. That is fine for a stateless scorer like the sample. If your plugin has
state or secrets, check the caller (`Binder.getCallingUid()`) yourself.

## The sample plugin

`:sampleplugin` (`dev.keycorrect.sampleplugin`) is a deliberately small engine: a hand-written
table of about 600 English bigrams over a 260-word vocabulary, scoring
`ln P(candidate | last word of context)` with add-k smoothing (unigram-weighted). It declares
`Score` only, has no permissions and no launcher icon, and needs no downloaded data.

```sh
./gradlew :sampleplugin:testDebugUnitTest      # scorer tests (pure Kotlin)
./gradlew :sampleplugin:assembleDebug
adb install -r sampleplugin/build/outputs/apk/debug/sampleplugin-debug.apk
```

Then open Settings → Engines in KeyCorrect, find "Sample bigram engine" and approve it. The
release APK (`:sampleplugin:assembleRelease`) is signed with the `keycorrect.release.*` Gradle
properties like the other APKs ([INSTALL.md](INSTALL.md)) and left unsigned without them. The
release script doesn't attach it.
