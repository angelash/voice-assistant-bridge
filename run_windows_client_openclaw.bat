@echo off
setlocal
cd /d %~dp0
if exist .venv\Scripts\activate.bat call .venv\Scripts\activate.bat

set "_EXTRA_TOKEN="
if not "%GATEWAY_TOKEN%"=="" set "_EXTRA_TOKEN=--token %GATEWAY_TOKEN%"

python windows_client.py --gateway http://127.0.0.1:18789 --chat-path /api/voice-brain/chat --health-path /api/voice-brain/health %_EXTRA_TOKEN% %*
endlocal
