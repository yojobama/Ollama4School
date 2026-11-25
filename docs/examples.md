---
title: Examples
---

# Examples

All files are in the [`examples`](https://github.com/yojobama/Ollama4School/tree/main/examples) folder.

## Complete chat screen

[`ChatActivity.java`](https://github.com/yojobama/Ollama4School/blob/main/examples/ChatActivity.java), [`activity_chat.xml`](https://github.com/yojobama/Ollama4School/blob/main/examples/activity_chat.xml) and [`AndroidManifest.xml`](https://github.com/yojobama/Ollama4School/blob/main/examples/AndroidManifest.xml) form a working chat app: it discovers the PCs, streams answers, and has Stop and New chat buttons.

Core of it:

```java
Ollama4School client = Ollama4School.connect(this);
client.setSystemPrompt("You are a patient tutor for middle-school students.");

client.askStream(question, new OllamaOkHttpClient.StreamListener() {
    @Override public void onContent(String token)  { runOnUiThread(() -> answer.append(token)); }
    @Override public void onThinking(String token) { runOnUiThread(() -> status.setText("Thinking...")); }
});
```

## Snippets

[`Snippets.java`](https://github.com/yojobama/Ollama4School/blob/main/examples/Snippets.java) contains:

**Hint-only math tutor**
```java
ai.setSystemPrompt("You are a math teacher. Never give the final answer, only hints.");
ai.ask("How do I solve 2x + 6 = 14?");
ai.ask("I got x = 3, is that right?");   // remembers the first question
```

**Fixed PCs (Wi-Fi blocks mDNS)**
```java
Ollama4School.connect(ctx, "http://192.168.1.20:11434", "http://192.168.1.21:11434");
```

**Emulator**
```java
Ollama4School.connect(ctx, "http://10.0.2.2:11434");
```

**Pool statistics**
```java
ai.pool().setFanout(1);
ai.ask("Name three planets.");
ai.getLastServer();      // who answered
ai.pool().stats();       // answers per PC
```

**Different model**
```java
ai.setModel("lfm2.5:24b");   // must be installed on the PCs (-Model)
```

## Desktop test without Android

[`ConsoleExample.java`](https://github.com/yojobama/Ollama4School/blob/main/examples/ConsoleExample.java) uses `OllamaPool` directly from a normal JVM:

```
java ConsoleExample http://192.168.1.20:11434 http://192.168.1.21:11434
```
