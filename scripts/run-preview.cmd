@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-preview.ps1" %*
if errorlevel 1 pause
