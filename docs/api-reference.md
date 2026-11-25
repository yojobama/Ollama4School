---
title: API reference
---

# API reference

Package `com.ollama4school`. All network methods block and throw `IOException`.

## Ollama4School

| Member | Description |
|---|---|
| `static connect(Context)` | Discovers all PCs (mDNS, ~3 s) plus remembered PCs that still answer |
| `static connect(Context, String... urls)` | Uses only the given PCs, e.g. `http://192.168.1.20:11434` |
| `refreshServers(Context)` | Searches again, keeps the conversation |
| `static forgetServers(Context)` | Clears remembered PCs |
| `ask(String)` | Sends a question, returns an `OllamaOkHttpClient.Reply` |
| `askStream(String, StreamListener)` | Same, delivering tokens as they are generated |
| `setSystemPrompt(String)` | Instructions for the model |
| `reset()` | New conversation, keeps system prompt |
| `cancel()` | Stops the current answer |
| `setThink(Boolean)` | Enable/disable/auto model reasoning |
| `setModel(String)` / `getModel()` | Model name, default `lfm2.5:8b` |
| `getServers()` | URLs of PCs in use |
| `getLastServer()` | PC that answered last |
| `pool()` | The underlying `OllamaPool` |

## OllamaOkHttpClient

Talks to a single PC.

- `Reply` – `content` (answer) and `thinking` (reasoning)
- `Message` – `Message.system/user/assistant(text)`
- `StreamListener` – `onContent(String)`, optional `onThinking(String)`
- `version()`, `chat(model, messages)`, `chatStream(model, messages, listener)`, `generate(model, prompt)`, `cancel()`

## OllamaPool

Plain Java load balancer across several PCs.

- `new OllamaPool(Collection<String> urls)`, `setServers(...)`, `getServers()`
- `chat(...)`, `chatStream(...)`, `cancel()`
- `setFanout(int)` – PCs a question is sent to at once (default 2; `1` = random PC)
- `lastServer()`, `stats()` – answers given per PC
- `setThink(Boolean)`

## OllamaServerFinder

- `SERVICE_TYPE` = `_ollama._tcp`
- `findAll(Context, long windowMs)` – list of `Server(name, url, model)`
- `find(Context, String name, long timeoutMs)` – one PC by name

## ThinkSplitter

Separates `<think>…</think>` reasoning from the final answer in a token stream. Used internally.
