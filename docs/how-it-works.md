---
title: How it works
---

# How it works

## Discovery

Each server PC announces itself over mDNS/DNS-SD as `OLLAMA_SERVER-<COMPUTERNAME>._ollama._tcp` on port 11434, with the model name in its TXT record. `Ollama4School.connect` listens for about 3 seconds with Android's `NsdManager`, and also tries PCs that worked last time.

## Load balancing

Ollama answers one request at a time per PC; other requests wait in that PC's queue. Phones can't see each other, so there is no central coordinator. Instead each question is sent to **two** PCs at once. The first PC to start answering wins and the other request is cancelled; Ollama drops a cancelled request from its queue without doing any work. No PC sits idle while a student waits.

Set `pool().setFanout(1)` for a plain random choice.

## Failures

| Situation | Behaviour |
|---|---|
| PC off or unreachable | Connection fails within 5 s, another PC takes the question; the PC is skipped for 30 s |
| PC sleeps or hangs while a request waits | Checked every 15 s; the question moves to another PC (≈18 s worst case) |
| PC dies while streaming an answer | `ask` throws `IOException`; the student can ask again |

## Capacity

Each PC produces about 20–25 tokens/s on its CPU. With N PCs, roughly N students are served at once; the rest queue. `askStream` lets students watch the answer appear. `lfm2.5:8b` reasons before answering, so a reply takes 3–13 s.
