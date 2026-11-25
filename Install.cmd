@echo off
:: Double-click to install. Re-launches itself elevated, then registers the boot task.
net session >nul 2>&1 || (powershell -NoProfile -Command "Start-Process '%~f0' -Verb RunAs" & exit /b)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0OllamaServer.ps1" -Install %*
pause
