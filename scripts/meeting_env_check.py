#!/usr/bin/env python3
"""Environment readiness and meeting API smoke test.

Checks:
1. Health endpoint availability.
2. Optional local auto-start for `server.py` when base URL is loopback.
3. V2 meeting smoke flow: create -> mode on -> mode off.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def _is_loopback_host(host: str) -> bool:
    return host.lower() in {"127.0.0.1", "localhost", "::1", "0.0.0.0"}


def _port_from_base_url(base_url: str, fallback: int = 8765) -> int:
    parsed = urlparse(base_url)
    if parsed.port:
        return parsed.port
    return 443 if parsed.scheme == "https" else fallback


def _http_json(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    timeout_sec: float = 5.0,
) -> tuple[int, dict[str, Any] | None, str]:
    body = None
    headers: dict[str, str] = {}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = Request(url=url, method=method.upper(), data=body, headers=headers)
    try:
        with urlopen(req, timeout=timeout_sec) as resp:
            status = int(resp.status)
            text = resp.read().decode("utf-8", errors="replace")
    except HTTPError as e:
        status = int(e.code)
        text = e.read().decode("utf-8", errors="replace")
    except URLError as e:
        return 0, None, f"{type(e.reason).__name__}: {e.reason}"
    except TimeoutError:
        return 0, None, "TimeoutError"
    except Exception as e:  # pragma: no cover - diagnostic path
        return 0, None, f"{type(e).__name__}: {e}"

    try:
        data = json.loads(text) if text.strip() else {}
        if isinstance(data, dict):
            return status, data, text
        return status, None, text
    except json.JSONDecodeError:
        return status, None, text


def _wait_for_health(base_url: str, health_path: str, retries: int, interval_sec: float) -> bool:
    health_url = f"{base_url.rstrip('/')}{health_path}"
    for _ in range(retries):
        status, data, raw = _http_json("GET", health_url, timeout_sec=2.0)
        if status == 200 and isinstance(data, dict):
            return True
        time.sleep(interval_sec)
    return False


def _try_auto_start_local_server(base_url: str) -> subprocess.Popen[bytes] | None:
    parsed = urlparse(base_url)
    host = parsed.hostname or ""
    if not _is_loopback_host(host):
        return None

    repo_root = Path(__file__).resolve().parents[1]
    server_path = repo_root / "server.py"
    if not server_path.exists():
        print(f"[ERR] Missing server entry: {server_path}")
        return None

    env = os.environ.copy()
    env.setdefault("VOICE_OPERATOR_ENDPOINT", "http://localhost:11434/api/generate")
    env.setdefault("VOICE_OPERATOR_MODEL", "qwen2.5:7b")
    port = _port_from_base_url(base_url, fallback=8765)

    kwargs: dict[str, Any] = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW

    try:
        proc = subprocess.Popen(
            [sys.executable, str(server_path), "--port", str(port)],
            cwd=str(repo_root),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            **kwargs,
        )
        print(f"[INFO] Auto-started local server.py (pid={proc.pid}, port={port})")
        return proc
    except Exception as e:
        print(f"[ERR] Failed to auto-start local server: {type(e).__name__}: {e}")
        return None


def run_smoke(base_url: str, client_id: str) -> int:
    base = base_url.rstrip("/")
    create_url = f"{base}/v2/meetings"

    status, data, raw = _http_json("POST", create_url, {"client_id": client_id}, timeout_sec=12.0)
    if status == 0:
        print(f"[ERR] create meeting request failed: {raw}")
        return 3
    if not isinstance(data, dict) or not data.get("ok"):
        print(f"[ERR] create meeting failed: status={status}, body={raw[:300]}")
        return 3

    meeting_id = str(data.get("meeting_id") or "").strip()
    if not meeting_id:
        print(f"[ERR] create meeting returned empty meeting_id: {raw[:300]}")
        return 3
    print(f"[OK] create meeting: {meeting_id}")

    mode_url = f"{base}/v2/meetings/{meeting_id}/mode"
    for mode in ("on", "off"):
        status, mode_data, mode_raw = _http_json("POST", mode_url, {"mode": mode}, timeout_sec=12.0)
        if status == 0:
            print(f"[ERR] mode={mode} request failed: {mode_raw}")
            return 4
        if not isinstance(mode_data, dict) or not mode_data.get("ok"):
            print(f"[ERR] mode={mode} failed: status={status}, body={mode_raw[:300]}")
            return 4
        print(f"[OK] mode={mode}")

    print("[PASS] meeting API smoke test passed")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Voice Bridge environment check + V2 meeting smoke test")
    parser.add_argument("--base-url", default="http://127.0.0.1:8765", help="Gateway base URL")
    parser.add_argument("--health-path", default="/health", help="Health endpoint path")
    parser.add_argument("--client-id", default="env-check", help="Smoke test client_id")
    parser.add_argument("--no-auto-start", action="store_true", help="Disable local auto-start when health fails")
    parser.add_argument("--wait-retries", type=int, default=24, help="Health wait retries after auto-start")
    parser.add_argument("--wait-interval", type=float, default=0.5, help="Health wait interval seconds")
    parser.add_argument("--stop-started-server", action="store_true", help="Stop server if this script started it")
    args = parser.parse_args()

    base_url = args.base_url.rstrip("/")
    health_url = f"{base_url}{args.health_path}"
    started_proc: subprocess.Popen[bytes] | None = None

    print(f"[INFO] checking health: {health_url}")
    status, data, raw = _http_json("GET", health_url, timeout_sec=2.0)
    healthy = status == 200 and isinstance(data, dict)
    if healthy:
        print("[OK] health is ready")
    else:
        print(f"[WARN] initial health failed: status={status}, detail={raw[:200]}")
        if not args.no_auto_start:
            started_proc = _try_auto_start_local_server(base_url)
        if not _wait_for_health(base_url, args.health_path, args.wait_retries, args.wait_interval):
            print("[ERR] service is not ready after wait")
            if started_proc and args.stop_started_server:
                started_proc.terminate()
            return 2
        print("[OK] health became ready")

    code = run_smoke(base_url, args.client_id)

    if started_proc and args.stop_started_server:
        started_proc.terminate()
        print(f"[INFO] stopped auto-started server pid={started_proc.pid}")

    return code


if __name__ == "__main__":
    raise SystemExit(main())
