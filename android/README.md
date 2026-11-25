# Ollama4School Android client

Java library for Android apps that talk to PCs set up with `..\OllamaServer.ps1`.
It finds every such PC on the Wi-Fi automatically (mDNS service `_ollama._tcp`) and spreads
students' questions across them. No IP addresses need to be typed in.

| File | What it does |
|---|---|
| `Ollama4School.java` | Entry point: `connect()` finds the PCs, `ask()` / `askStream()` keep a conversation |
| `OllamaPool.java` | Load balancing and failover across PCs (plain Java, also works off Android) |
| `OllamaServerFinder.java` | mDNS discovery with Android's `NsdManager` |
| `OllamaOkHttpClient.java` | Raw Ollama HTTP API for one PC (chat, streaming, generate, version) |
| `ThinkSplitter.java` | Separates the model's `<think>` reasoning from the answer |

## Build (Windows and Linux)

Needs JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties` with `sdk.dir=...`).
One-time, if `gradlew` is missing: install Gradle and run `gradle wrapper --gradle-version 8.10.2`.

```
gradlew.bat assembleRelease      (Windows)
./gradlew assembleRelease        (Linux)
```

## Publish to Maven Central

1. Verify your namespace in the Central Portal and set `group` in `build.gradle.kts`; fix the POM URLs.
2. Put `mavenCentralUsername`, `mavenCentralPassword`, `signingInMemoryKey`,
   `signingInMemoryKeyPassword` in `~/.gradle/gradle.properties`.
3. `./gradlew publishAndReleaseToMavenCentral` (or `publishToMavenCentral` to release manually).

## Use in an app

```kotlin
implementation("io.github.yojob:ollama4school:1.0.0")
```

`AndroidManifest.xml` of the app (Ollama is plain `http://`):
   ```xml
   <application android:usesCleartextTraffic="true" ...>
   ```

## Usage

Every call blocks, so run it off the main thread:

```java
ExecutorService io = Executors.newSingleThreadExecutor();

io.execute(() -> {
    try {
        Ollama4School ai = Ollama4School.connect(this);   // finds all PCs (~3 s)
        ai.setSystemPrompt("You are a patient tutor for middle-school students.");

        // Whole answer at once
        OllamaOkHttpClient.Reply r = ai.ask("What is photosynthesis?");
        runOnUiThread(() -> answerView.setText(r.content));   // r.thinking = the reasoning

        // Or token by token (callbacks arrive on a background thread)
        ai.askStream("And what is respiration?", token ->
                runOnUiThread(() -> answerView.append(token)));
    } catch (IOException e) {
        runOnUiThread(() -> answerView.setText("Can't reach the AI server: " + e.getMessage()));
    }
});
```

Other options:

```java
Ollama4School.connect(ctx, "http://192.168.1.20:11434", "http://192.168.1.21:11434"); // fixed PCs
ai.refreshServers(ctx);          // search again, e.g. after a PC was switched on
ai.getServers();                 // PCs in use;  ai.getLastServer() = who answered last
ai.pool().setFanout(1);          // plain random PC choice instead of racing two PCs
ai.reset();                      // new conversation
ai.cancel();                     // stop the current answer (e.g. from a Stop button)
```

## How the load balancing works

Ollama answers one request at a time per PC; others wait in that PC's queue. Phones can't see
each other, so instead of a central coordinator each question is sent to **two** PCs at once.
The PC that starts answering first wins and the other copy is cancelled. Ollama drops a
cancelled request from its queue without doing any work on it. The result is that no PC sits
idle while a student is waiting.

- **A PC is off or unreachable:** its connection fails within 5 s and another PC takes the question;
  the PC is then skipped for 30 s.
- **A PC goes to sleep or hangs mid-request:** every 15 s a waiting request checks that its PC still
  answers; if not, the question moves to another PC (about 18 s worst case). If the PC dies while
  an answer is already streaming, `ask` throws an IOException and the student can ask again.

## Notes

- **Speed:** each PC runs the model on its CPU and produces about 20–25 tokens/s. `lfm2.5:8b` reasons
  before it answers, so one reply takes about 3–13 s. With N PCs, roughly N students are served at
  a time; the rest wait in line. `askStream` lets students watch the answer appear.
  `setThink(false)` doesn't speed this model up: it reasons anyway.
- **Each PC needs a unique name.** The server script now uses `OLLAMA_SERVER-<COMPUTERNAME>` by
  default. PCs installed with an older version all advertise `OLLAMA_SERVER`, and Android only sees
  one of them, so re-run `OllamaServer.ps1 -Install` on those.
- **Discovery fails but the IPs work:** the Wi-Fi blocks multicast or isolates clients (common on
  guest networks). Use the fixed-URL `connect` and DHCP reservations for the PCs.
- **Emulator:** the Android emulator can't see LAN mDNS. Test on a real phone, or use
  `connect(ctx, "http://10.0.2.2:11434")` when Ollama runs on the same PC.
