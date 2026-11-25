---
title: Server setup
---

# Server setup

Do this once on every PC that should answer questions. More PCs means more students served at the same time.

## Requirements

- Windows 10/11 (64-bit) with administrator rights
- Internet access for the first run (downloads Ollama and the model, several GB)
- PC and student devices on the same Wi-Fi/LAN

## Install

1. Copy the repository folder to the PC.
2. Double-click **`Install.cmd`** and accept the administrator prompt.

This copies the script to `C:\Ollama4School` and registers a scheduled task named `Ollama4School` that runs at every boot as SYSTEM – no one needs to log in.

On each boot the script:

1. Downloads Ollama to `C:\Ollama` if missing
2. Opens TCP 11434 in the firewall and allows mDNS (UDP 5353)
3. Starts `ollama serve` on `0.0.0.0:11434`
4. Pulls the model (default `lfm2.5:8b`) and loads it into memory
5. Announces the PC on the network as `_ollama._tcp`
6. Re-announces when the IP changes and restarts Ollama if it stops

The first start takes a while because of the model download. Progress is written to `C:\Ollama4School\ollama4school.log`.

## Options

Run from an elevated PowerShell:

```powershell
# Different model and name
powershell -ExecutionPolicy Bypass -File .\OllamaServer.ps1 -Install -Model lfm2.5:24b -ServiceName CLASSROOM_AI
```

| Parameter | Default | Meaning |
|---|---|---|
| `-Install` | | Install and register the boot task |
| `-Uninstall` | | Remove the task and firewall rules (keeps `C:\Ollama` and the models) |
| `-Model` | `lfm2.5:8b` | Model to download and serve |
| `-ServiceName` | `OLLAMA_SERVER-<COMPUTERNAME>` | Name shown on the network; must be unique per PC |
| `-Port` | `11434` | TCP port |
| `-OllamaDir` | `C:\Ollama` | Where Ollama and models are stored |
| `-InstallDir` | `C:\Ollama4School` | Where the script and log live |
| `-SetPrivateNetwork` | `$true` | Switch "Public" networks to "Private" (mDNS is blocked on Public) |

Use the same `-Model` on every PC.

## Check that it works

From any computer on the network:

```
curl http://<pc-ip>:11434/api/version
```

You should get a JSON version number.

## Uninstall

```powershell
powershell -ExecutionPolicy Bypass -File .\OllamaServer.ps1 -Uninstall
```

## Tips

- Give each PC a DHCP reservation so its address stays the same.
- PCs installed with an old script version all advertise `OLLAMA_SERVER`; run `-Install` again on them so each name is unique.
- Faster CPUs and more RAM give faster answers. A reply typically takes 3–13 s at 20–25 tokens/s.
