---
title: Android app guide
---

# Android app guide

## 1. Add the library

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.yojob:ollama4school:1.0.0")
}
```

Or build it yourself from the `android` folder (JDK 17+, Android SDK):

```
gradlew.bat assembleRelease      (Windows)
./gradlew assembleRelease        (Linux)
```

Requires `minSdk 21`.

## 2. Manifest

Ollama uses plain `http://`, so cleartext traffic must be allowed:

```xml
<uses-permission android:name="android.permission.INTERNET" />

<application android:usesCleartextTraffic="true" ...>
```

## 3. Connect and ask

All calls **block**. Never call them on the main thread.

```java
ExecutorService io = Executors.newSingleThreadExecutor();

io.execute(() -> {
    try {
        Ollama4School ai = Ollama4School.connect(this);   // finds all PCs, ~3 s
        ai.setSystemPrompt("You are a patient tutor for middle-school students.");

        OllamaOkHttpClient.Reply r = ai.ask("What is photosynthesis?");
        runOnUiThread(() -> answerView.setText(r.content));
    } catch (IOException e) {
        runOnUiThread(() -> answerView.setText("Can't reach the AI server: " + e.getMessage()));
    }
});
```

Connect once (for example when the screen opens) and reuse the `Ollama4School` object.

## 4. Stream the answer

```java
ai.askStream("And what is respiration?", token ->
        runOnUiThread(() -> answerView.append(token)));
```

Callbacks arrive on a background thread. To also receive the model's reasoning, implement `StreamListener.onThinking`.

## 5. Conversations

`ask` and `askStream` remember the conversation. Call `ai.reset()` to start a new one (the system prompt is kept). If a request fails, the question is removed from the history so you can simply retry.

## 6. Stop button

```java
stopButton.setOnClickListener(v -> ai.cancel());
```

The blocked `ask` call then throws an `IOException`.

## 7. Fixed PCs instead of discovery

```java
Ollama4School.connect(ctx, "http://192.168.1.20:11434", "http://192.168.1.21:11434");
```

## Testing

- **Real phone** on the school Wi-Fi: use `connect(ctx)`.
- **Emulator** cannot see LAN mDNS. If Ollama runs on your development PC use `connect(ctx, "http://10.0.2.2:11434")`.

A full sample app is in the [examples](examples.html).
