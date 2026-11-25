---
title: Troubleshooting
---

# Troubleshooting

| Problem | Cause and fix |
|---|---|
| `connect` throws "can't reach" / no PCs found | Check the PC is on and finished its first model download (`C:\Ollama4School\ollama4school.log`). Test `curl http://<pc-ip>:11434/api/version` |
| Discovery finds nothing, but IPs work | The Wi-Fi blocks multicast or isolates clients (common on guest networks). Use fixed URLs with `connect(ctx, urls...)` and DHCP reservations |
| Only one PC is found though several run | Old installs all advertise `OLLAMA_SERVER`. Re-run `Install.cmd` on each so names are unique |
| Discovery fails on a PC in a "Public" network | mDNS is blocked on Public networks. Keep `-SetPrivateNetwork $true` or set the network to Private |
| Works on a phone, not in the emulator | The emulator can't see LAN mDNS. Use a real phone or `http://10.0.2.2:11434` |
| `Cleartext HTTP traffic not permitted` | Add `android:usesCleartextTraffic="true"` to `<application>` |
| `NetworkOnMainThreadException` | Every call blocks; run it on a background thread |
| Answers are slow | Normal on CPU: 3–13 s per reply. Add more PCs, or use a smaller model |
| First start takes very long | The model is being downloaded; wait for the log to say it is loaded |
| App can't connect to a PC from another PC | Firewall rule for TCP 11434 is missing; re-run `Install.cmd` |

Still stuck? Open an issue at <https://github.com/yojobama/Ollama4School/issues> with the log file contents.
