---
title: Home
---

# Ollama4School Manual

Ollama4School lets students on Android phones and tablets chat with an AI that runs on **your own school PCs**. Nothing leaves the building, and no IP addresses are typed in.

It has two parts:

| Part | What it is |
|---|---|
| **Server** (`OllamaServer.ps1`, `Install.cmd`) | Installs [Ollama](https://ollama.com) on a Windows PC, starts it at every boot and announces it on the Wi-Fi |
| **Android library** (`com.ollama4school`) | Finds every such PC automatically and spreads students' questions across them |

## Contents

1. [Server setup](server-setup.html) – prepare the PCs
2. [Android app guide](android-guide.html) – add the library to an app
3. [API reference](api-reference.html) – every class and method
4. [Examples](examples.html) – ready-to-copy code
5. [How it works](how-it-works.html) – discovery and load balancing
6. [Troubleshooting](troubleshooting.html)

## Quick start

1. On each school PC, double-click `Install.cmd` and accept the admin prompt.
2. Add `implementation("io.github.yojob:ollama4school:1.0.0")` to your app.
3. In the app:

```java
Ollama4School ai = Ollama4School.connect(context);
String answer = ai.ask("What is photosynthesis?").content;
```

Run this on a background thread – see the [Android guide](android-guide.html).
