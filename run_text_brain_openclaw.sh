#!/usr/bin/env bash
set -e
cd /home/shash/clawd
export VOICE_REPLY_BACKEND=openclaw
export VOICE_OPENCLAW_SESSION_ID=voice-bridge-session
export VOICE_OPENCLAW_TIMEOUT=120
python3 skills/voice-text-brain/scripts/server.py --port 8765
