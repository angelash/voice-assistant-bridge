#!/usr/bin/env python3
"""
Voice Assistant Bridge Server (V1 + V2 Meeting Mode)
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import os
import re
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from aiohttp import web

# V2 Meeting Mode support
from meeting import MeetingStore
from v2_api import V2MeetingAPI

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

STATUS_NEW = "NEW"
STATUS_LOCAL_REPLIED = "LOCAL_REPLIED"
STATUS_FORWARDED = "FORWARDED"
STATUS_WAITING_OPENCLAW = "WAITING_OPENCLAW"
STATUS_RETRYING = "RETRYING"
STATUS_OPENCLAW_RECEIVED = "OPENCLAW_RECEIVED"
STATUS_DELIVERED = "DELIVERED"
STATUS_FAILED = "FAILED"
TERMINAL = {STATUS_DELIVERED, STATUS_FAILED}

SOURCE_LOCAL = "local-operator"
SOURCE_OPENCLAW = "openclaw"
SOURCE_SYSTEM = "system"

CONFIG_PATH = Path(__file__).with_name("config.json")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def clamp_int(value: Any, fallback: int, lo: int, hi: int) -> int:
    try:
        iv = int(value)
    except Exception:
        return fallback
    return max(lo, min(hi, iv))


def load_config() -> dict[str, Any]:
    if not CONFIG_PATH.exists():
        return {}
    try:
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("read config failed: %s", exc)
        return {}


def source_label(source: str) -> str:
    return {
        SOURCE_LOCAL: "本地接线员",
        SOURCE_OPENCLAW: "龙虾大脑",
        SOURCE_SYSTEM: "系统",
    }.get(source, source)


def extract_json_obj(text: str) -> Optional[dict[str, Any]]:
    s = text.find("{")
    e = text.rfind("}")
    if s < 0 or e <= s:
        return None
    try:
        obj = json.loads(text[s : e + 1])
        return obj if isinstance(obj, dict) else None
    except Exception:
        return None


def extract_reply_text(data: dict[str, Any]) -> str:
    for key in ("response_text", "reply_text", "text", "answer", "content", "response"):
        val = data.get(key)
        if isinstance(val, str) and val.strip():
            return val.strip()
    msg = data.get("message")
    if isinstance(msg, dict):
        val = msg.get("content")
        if isinstance(val, str) and val.strip():
            return val.strip()
    return ""


class Store:
    def __init__(self, db_path: Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.lock = threading.RLock()
        self._init()

    def _init(self) -> None:
        with self.lock:
            self.conn.execute(
                """
                CREATE TABLE IF NOT EXISTS message_states (
                    message_id TEXT PRIMARY KEY,
                    client_id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    turn_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    text TEXT NOT NULL,
                    status TEXT NOT NULL,
                    decision TEXT,
                    decision_reason TEXT,
                    decision_confidence REAL,
                    local_reply TEXT,
                    final_reply TEXT,
                    retry_count INTEGER NOT NULL DEFAULT 0,
                    max_retries INTEGER NOT NULL DEFAULT 5,
                    timeout_sec INTEGER NOT NULL DEFAULT 30,
                    last_error TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            self.conn.execute(
                "CREATE INDEX IF NOT EXISTS idx_message_session ON message_states(session_id, created_at)"
            )
            self.conn.execute("CREATE INDEX IF NOT EXISTS idx_message_status ON message_states(status)")
            self.conn.commit()

    @staticmethod
    def _dict(row: sqlite3.Row) -> dict[str, Any]:
        return {k: row[k] for k in row.keys()}

    def create(self, row: dict[str, Any]) -> None:
        keys = list(row.keys())
        cols = ", ".join(keys)
        vals = ", ".join("?" for _ in keys)
        with self.lock:
            self.conn.execute(f"INSERT INTO message_states ({cols}) VALUES ({vals})", [row[k] for k in keys])
            self.conn.commit()

    def update(self, message_id: str, **fields: Any) -> None:
        if not fields:
            return
        keys = list(fields.keys())
        sets = ", ".join(f"{k}=?" for k in keys)
        args = [fields[k] for k in keys] + [message_id]
        with self.lock:
            self.conn.execute(f"UPDATE message_states SET {sets} WHERE message_id=?", args)
            self.conn.commit()

    def get(self, message_id: str) -> Optional[dict[str, Any]]:
        with self.lock:
            row = self.conn.execute("SELECT * FROM message_states WHERE message_id=?", (message_id,)).fetchone()
        return self._dict(row) if row else None

    def pending(self) -> list[dict[str, Any]]:
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM message_states WHERE status IN (?, ?, ?)",
                (STATUS_FORWARDED, STATUS_WAITING_OPENCLAW, STATUS_RETRYING),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def recent_session(self, session_id: str, limit: int = 8) -> list[dict[str, Any]]:
        with self.lock:
            rows = self.conn.execute(
                "SELECT * FROM message_states WHERE session_id=? ORDER BY created_at DESC LIMIT ?",
                (session_id, limit),
            ).fetchall()
        return [self._dict(r) for r in rows]

    def close(self) -> None:
        with self.lock:
            self.conn.close()


class EventHub:
    def __init__(self):
        self.listeners: dict[int, tuple[web.WebSocketResponse, Optional[str], Optional[str]]] = {}
        self.lock = asyncio.Lock()

    async def register(self, ws: web.WebSocketResponse, session_id: Optional[str], client_id: Optional[str]) -> None:
        async with self.lock:
            self.listeners[id(ws)] = (ws, session_id, client_id)

    async def unregister(self, ws: web.WebSocketResponse) -> None:
        async with self.lock:
            self.listeners.pop(id(ws), None)

    async def publish(self, event: dict[str, Any]) -> None:
        async with self.lock:
            listeners = list(self.listeners.values())
        stale: list[web.WebSocketResponse] = []
        for ws, sid, cid in listeners:
            if sid and sid != event.get("session_id"):
                continue
            if cid and cid != event.get("client_id"):
                continue
            try:
                await ws.send_json(event)
            except Exception:
                stale.append(ws)
        if stale:
            async with self.lock:
                for ws in stale:
                    self.listeners.pop(id(ws), None)

    async def close(self) -> None:
        async with self.lock:
            listeners = [v[0] for v in self.listeners.values()]
            self.listeners.clear()
        for ws in listeners:
            try:
                await ws.close()
            except Exception:
                pass


class LocalOperator:
    DEFAULT_QUICK_REPLY = "收到，我正在转给龙虾大脑处理。"

    def __init__(self, endpoint: str, model: str, timeout_sec: int):
        self.endpoint = endpoint.rstrip("/")
        self.model = model
        self.timeout_sec = timeout_sec

    @classmethod
    def _normalize_quick_reply(cls, value: Any) -> str:
        if not isinstance(value, str):
            return cls.DEFAULT_QUICK_REPLY
        quick = re.sub(r"\s+", " ", value).strip()
        if len(quick) < 6:
            return cls.DEFAULT_QUICK_REPLY
        return quick

    def _fallback(self, text: str) -> dict[str, Any]:
        return {
            "quick_reply": self.DEFAULT_QUICK_REPLY,
            "reason": "fallback_quick_reply",
            "confidence": 0.45,
        }

    async def decide(self, text: str, history: list[dict[str, Any]]) -> dict[str, Any]:
        import aiohttp

        ctx = []
        for item in reversed(history[-6:]):
            if item.get("text"):
                ctx.append(f"用户: {item['text']}")
            if item.get("final_reply"):
                ctx.append(f"助手: {item['final_reply']}")
        prompt = (
            "你是语音助手接线员。只做两件事：快速回应 + 告知已转给龙虾大脑。"
            "仅输出 JSON，字段：quick_reply reason confidence。"
            "quick_reply 必须是完整短句，不要承诺具体结果。"
            "quick_reply 需包含“龙虾大脑”或“已转交处理中”语义，不能只输出单个词。"
            f"\n上下文:\n{chr(10).join(ctx[-8:]) or '(无)'}\n用户输入:\n{text}"
        )
        payload = {"model": self.model, "prompt": prompt, "stream": False}
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    self.endpoint,
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=self.timeout_sec),
                ) as resp:
                    if resp.status != 200:
                        raise RuntimeError(f"operator http {resp.status}: {await resp.text()}")
                    data = await resp.json(content_type=None)
            raw = extract_reply_text(data) or str(data)
            obj = extract_json_obj(raw)
            if not obj:
                raise RuntimeError("operator output is not json")
            conf = obj.get("confidence", 0.6)
            try:
                conf = max(0.0, min(1.0, float(conf)))
            except Exception:
                conf = 0.6
            reason = (obj.get("reason") or "local_operator").strip() or "local_operator"
            reason = reason[:80]
            return {
                "quick_reply": self._normalize_quick_reply(obj.get("quick_reply")),
                "reason": reason,
                "confidence": conf,
            }
        except Exception as exc:
            logger.warning("local operator failed, fallback: %s", exc)
            return self._fallback(text)

    @staticmethod
    def _fallback_summary(text: str, max_chars: int) -> str:
        cleaned = re.sub(r"\s+", " ", (text or "")).strip()
        if not cleaned:
            return ""
        if len(cleaned) <= max_chars:
            return cleaned
        return cleaned[:max_chars].rstrip("，。,.!? ") + "…"

    @staticmethod
    def _normalize_summary(value: Any, fallback: str, max_chars: int) -> str:
        if not isinstance(value, str):
            return fallback
        cleaned = re.sub(r"\s+", " ", value).strip()
        if not cleaned:
            return fallback
        if len(cleaned) > max_chars:
            cleaned = cleaned[:max_chars].rstrip("，。,.!? ") + "…"
        return cleaned

    async def summarize(self, text: str, history: list[dict[str, Any]], max_chars: int = 80) -> str:
        import aiohttp

        max_chars = max(20, min(200, int(max_chars)))
        fallback = self._fallback_summary(text, max_chars)
        ctx: list[str] = []
        for item in reversed(history[-6:]):
            if item.get("text"):
                ctx.append(f"用户: {item['text']}")
            if item.get("final_reply"):
                ctx.append(f"助手: {item['final_reply']}")
        prompt = (
            "你是本地接线员。请把下面内容压缩成中文简报，仅用于语音播报。\n"
            f"要求：只输出简报正文；尽量不超过{max_chars}个汉字；保留关键信息与结论。\n"
            f"上下文：\n{chr(10).join(ctx[-8:]) or '(无)'}\n"
            f"原文：\n{text}\n"
        )
        payload = {"model": self.model, "prompt": prompt, "stream": False}
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    self.endpoint,
                    json=payload,
                    timeout=aiohttp.ClientTimeout(total=self.timeout_sec),
                ) as resp:
                    if resp.status != 200:
                        raise RuntimeError(f"operator summary http {resp.status}: {await resp.text()}")
                    data = await resp.json(content_type=None)
            raw = extract_reply_text(data) or str(data)
            obj = extract_json_obj(raw)
            if obj:
                cand = obj.get("summary") or obj.get("brief") or obj.get("text") or ""
            else:
                cand = raw
            summary = self._normalize_summary(cand, fallback, max_chars)
            return summary or fallback
        except Exception as exc:
            logger.warning("local operator summarize failed, fallback: %s", exc)
            return fallback


class OpenClawClient:
    def __init__(self, base_url: str, chat_path: str, health_path: str, token: str):
        self.base_url = (base_url or "").rstrip("/")
        path = (chat_path or "").strip()
        self.chat_path = path if path.startswith("/") else f"/{path}"
        hp = (health_path or "").strip()
        self.health_path = hp if hp.startswith("/") else f"/{hp}"
        self.token = (token or "").strip()

    @property
    def enabled(self) -> bool:
        return bool(self.base_url and self.chat_path)

    async def health(self, timeout_sec: int = 3) -> tuple[bool, str]:
        import aiohttp

        if not self.enabled:
            return False, "openclaw disabled"
        url = f"{self.base_url}{self.health_path}"
        headers: dict[str, str] = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    url,
                    headers=headers,
                    timeout=aiohttp.ClientTimeout(total=max(1, timeout_sec)),
                ) as resp:
                    if resp.status == 200:
                        return True, "ok"
                    body = (await resp.text()).strip()
                    return False, f"http {resp.status}: {body[:160]}"
        except Exception as exc:
            return False, str(exc).strip() or exc.__class__.__name__

    async def chat(self, text: str, session_id: str, message_id: str, timeout_sec: int) -> str:
        import aiohttp

        if not self.enabled:
            raise RuntimeError("openclaw disabled")
        headers = {"Content-Type": "application/json", "X-Message-Id": message_id}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        payload = {"text": text, "session_id": session_id, "message_id": message_id}
        url = f"{self.base_url}{self.chat_path}"
        async with aiohttp.ClientSession() as session:
            async with session.post(
                url,
                json=payload,
                headers=headers,
                timeout=aiohttp.ClientTimeout(total=timeout_sec),
            ) as resp:
                if resp.status != 200:
                    raise RuntimeError(f"openclaw http {resp.status}: {await resp.text()}")
                data = await resp.json(content_type=None)
        if not isinstance(data, dict):
            raise RuntimeError("openclaw invalid payload")
        text_out = extract_reply_text(data)
        if not text_out:
            raise RuntimeError(f"openclaw empty reply: {json.dumps(data, ensure_ascii=False)[:300]}")
        return text_out


class VoiceAssistantServer:
    def __init__(self, port: int = 8765):
        self.port = port
        cfg = load_config()

        self.default_session_id = (cfg.get("openclaw_session_id", "voice-bridge-session") or "voice-bridge-session").strip()
        self.forward_timeout = clamp_int(cfg.get("openclaw_forward_timeout", 30), 30, 5, 300)
        self.forward_max_retries = clamp_int(cfg.get("openclaw_max_retries", 5), 5, 1, 20)
        self.forward_backoff = float(cfg.get("openclaw_retry_backoff", 1.5))
        self.openclaw_probe_timeout = clamp_int(cfg.get("openclaw_probe_timeout", 3), 3, 1, 20)

        self.local_operator = LocalOperator(
            endpoint=os.getenv("VOICE_OPERATOR_ENDPOINT", cfg.get("ollama_endpoint", "http://127.0.0.1:11434/api/generate")),
            model=os.getenv("VOICE_OPERATOR_MODEL", cfg.get("ollama_model", "qwen2.5:7b")),
            timeout_sec=clamp_int(cfg.get("operator_timeout", 20), 20, 3, 120),
        )
        self.openclaw = OpenClawClient(
            base_url=os.getenv("VOICE_OPENCLAW_GATEWAY_URL", cfg.get("openclaw_gateway_url", cfg.get("gateway_url", "http://127.0.0.1:18789"))),
            chat_path=os.getenv("VOICE_OPENCLAW_CHAT_PATH", cfg.get("openclaw_chat_path", "/api/voice-brain/chat")),
            health_path=os.getenv("VOICE_OPENCLAW_HEALTH_PATH", cfg.get("openclaw_health_path", "/api/voice-brain/health")),
            token=os.getenv("VOICE_OPENCLAW_GATEWAY_TOKEN", cfg.get("openclaw_gateway_token", cfg.get("gateway_token", ""))),
        )
        db_path = Path(cfg.get("bridge_db_path", str(Path(__file__).with_name("bridge_state.db"))))
        self.store = Store(db_path)
        self.events = EventHub()

        # V2 Meeting Mode
        self.meeting_store = MeetingStore(db_path)
        self.v2_api = V2MeetingAPI(self.meeting_store, self.events)

        self.tts_voice = os.getenv("VOICE_TTS_VOICE", cfg.get("tts_edge_voice", "zh-CN-XiaoxiaoNeural"))
        self.stt = None
        self.session_locks: dict[str, asyncio.Lock] = {}
        self.forward_tasks: dict[str, asyncio.Task[None]] = {}

    def _session_lock(self, session_id: str) -> asyncio.Lock:
        lock = self.session_locks.get(session_id)
        if not lock:
            lock = asyncio.Lock()
            self.session_locks[session_id] = lock
        return lock

    def _base_event(self, row: dict[str, Any], event_type: str) -> dict[str, Any]:
        data = {
            "event_type": event_type,
            "message_id": row["message_id"],
            "turn_id": row["turn_id"],
            "session_id": row["session_id"],
            "client_id": row["client_id"],
            "status": row["status"],
            "retry_count": row["retry_count"],
            "max_retries": row["max_retries"],
            "timeout_sec": row["timeout_sec"],
            "updated_at": row["updated_at"],
        }
        if row.get("last_error"):
            data["last_error"] = row["last_error"]
        return data

    async def _emit_reply(self, row: dict[str, Any], source: str, text: str, event_type: str) -> None:
        evt = self._base_event(row, event_type)
        evt.update({"source": source, "source_label": source_label(source), "text": text})
        await self.events.publish(evt)

    async def _emit_status(self, row: dict[str, Any], event_type: str, **extra: Any) -> None:
        evt = self._base_event(row, event_type)
        evt.update(extra)
        await self.events.publish(evt)

    async def _local_stage(self, message_id: str) -> dict[str, Any]:
        row = self.store.get(message_id)
        if not row:
            raise RuntimeError("message not found")
        async with self._session_lock(row["session_id"]):
            row = self.store.get(message_id)
            if not row or row["status"] != STATUS_NEW:
                return row or {}

            decision = await self.local_operator.decide(row["text"], self.store.recent_session(row["session_id"]))
            reason = (decision.get("reason") or "local_operator").strip() or "local_operator"
            quick_reply = (decision.get("quick_reply") or "").strip() or "收到，我正在转给龙虾大脑处理。"

            if not self.openclaw.enabled:
                reason = f"{reason}|openclaw_disabled"
                quick_reply = f"{quick_reply}（链路不可用：OpenClaw 未配置）"
                self.store.update(
                    message_id,
                    status=STATUS_FAILED,
                    decision="forward_openclaw",
                    decision_reason=reason,
                    decision_confidence=decision.get("confidence", 0.6),
                    local_reply=quick_reply,
                    last_error="openclaw disabled",
                    updated_at=now_iso(),
                )
                row = self.store.get(message_id) or row
                await self._emit_reply(row, SOURCE_LOCAL, row.get("local_reply") or "", "local_reply")
                await self._emit_status(row, "failed")
                return row

            ok, probe_msg = await self.openclaw.health(timeout_sec=self.openclaw_probe_timeout)
            if not ok:
                reason = f"{reason}|openclaw_probe_failed"
                quick_reply = f"{quick_reply}（链路检测异常，已进入重试队列）"

            self.store.update(
                message_id,
                status=STATUS_LOCAL_REPLIED,
                decision="forward_openclaw",
                decision_reason=reason,
                decision_confidence=decision.get("confidence", 0.6),
                local_reply=quick_reply,
                updated_at=now_iso(),
            )
            row = self.store.get(message_id) or row
            await self._emit_reply(row, SOURCE_LOCAL, row.get("local_reply") or "", "local_reply")

            if not ok:
                self.store.update(message_id, last_error=f"probe_failed: {probe_msg}", updated_at=now_iso())
                row = self.store.get(message_id) or row
                await self._emit_status(row, "openclaw_probe_failed")

            self.store.update(message_id, status=STATUS_FORWARDED, updated_at=now_iso())
            row = self.store.get(message_id) or row
            await self._emit_status(row, "forwarded")
            self.store.update(message_id, status=STATUS_WAITING_OPENCLAW, updated_at=now_iso())
            row = self.store.get(message_id) or row
            await self._emit_status(row, "waiting_openclaw")
            self._ensure_forward_task(message_id)
            return row

    def _ensure_forward_task(self, message_id: str) -> None:
        task = self.forward_tasks.get(message_id)
        if task and not task.done():
            return
        task = asyncio.create_task(self._forward_task(message_id), name=f"forward-{message_id}")
        self.forward_tasks[message_id] = task

        def _done(t: asyncio.Task[None]) -> None:
            self.forward_tasks.pop(message_id, None)
            if not t.cancelled() and t.exception():
                logger.exception("forward task failed %s: %s", message_id, t.exception())

        task.add_done_callback(_done)

    def _backoff(self, attempt: int) -> float:
        return min(max(self.forward_backoff * (2 ** max(attempt - 1, 0)), 0.5), 10.0)

    async def _forward_task(self, message_id: str) -> None:
        row = self.store.get(message_id)
        if not row or row["status"] in TERMINAL:
            return
        async with self._session_lock(row["session_id"]):
            row = self.store.get(message_id)
            if not row or row["status"] in TERMINAL:
                return
            done_failures = int(row.get("retry_count") or 0)
            max_retry = int(row.get("max_retries") or self.forward_max_retries)
            timeout = int(row.get("timeout_sec") or self.forward_timeout)
            for attempt in range(done_failures + 1, max_retry + 1):
                try:
                    text_out = await self.openclaw.chat(row["text"], row["session_id"], row["message_id"], timeout)
                    self.store.update(
                        message_id,
                        status=STATUS_OPENCLAW_RECEIVED,
                        final_reply=text_out,
                        retry_count=attempt - 1,
                        last_error=None,
                        updated_at=now_iso(),
                    )
                    row = self.store.get(message_id) or row
                    await self._emit_reply(row, SOURCE_OPENCLAW, text_out, "openclaw_reply")
                    self.store.update(message_id, status=STATUS_DELIVERED, updated_at=now_iso())
                    row = self.store.get(message_id) or row
                    await self._emit_status(row, "delivered", source=SOURCE_OPENCLAW)
                    return
                except Exception as exc:
                    err = str(exc).strip() or exc.__class__.__name__
                    if attempt < max_retry:
                        backoff = self._backoff(attempt)
                        self.store.update(
                            message_id,
                            status=STATUS_RETRYING,
                            retry_count=attempt,
                            last_error=err,
                            updated_at=now_iso(),
                        )
                        row = self.store.get(message_id) or row
                        await self._emit_status(row, "retrying", next_attempt=attempt + 1, backoff_sec=backoff)
                        await asyncio.sleep(backoff)
                        self.store.update(message_id, status=STATUS_WAITING_OPENCLAW, updated_at=now_iso())
                        row = self.store.get(message_id) or row
                        await self._emit_status(row, "waiting_openclaw")
                        continue
                    self.store.update(
                        message_id,
                        status=STATUS_FAILED,
                        retry_count=attempt,
                        last_error=err,
                        updated_at=now_iso(),
                    )
                    row = self.store.get(message_id) or row
                    await self._emit_status(row, "failed")
                    return

    async def _wait_terminal(self, message_id: str, timeout_sec: Optional[int] = None) -> dict[str, Any]:
        timeout = timeout_sec or (self.forward_timeout * self.forward_max_retries + 10)
        start = asyncio.get_running_loop().time()
        while True:
            row = self.store.get(message_id)
            if not row:
                raise RuntimeError("message not found")
            if row["status"] in TERMINAL:
                return row
            if asyncio.get_running_loop().time() - start >= timeout:
                return row
            await asyncio.sleep(0.5)

    async def submit(
        self,
        *,
        text: str,
        client_id: str,
        session_id: str,
        source: str,
        message_id: Optional[str] = None,
        turn_id: Optional[str] = None,
        wait_terminal: bool = False,
    ) -> tuple[dict[str, Any], bool]:
        text = (text or "").strip()
        if not text:
            raise ValueError("text is required")
        message_id = (message_id or "").strip() or f"msg-{uuid.uuid4().hex}"
        turn_id = (turn_id or "").strip() or f"turn-{uuid.uuid4().hex}"
        session_id = (session_id or "").strip() or self.default_session_id or f"session-{uuid.uuid4().hex}"
        client_id = (client_id or "").strip() or f"client-{uuid.uuid4().hex}"
        source = (source or "windows").strip() or "windows"

        existing = self.store.get(message_id)
        if existing:
            return existing, True

        ts = now_iso()
        self.store.create(
            {
                "message_id": message_id,
                "client_id": client_id,
                "session_id": session_id,
                "turn_id": turn_id,
                "source": source,
                "text": text,
                "status": STATUS_NEW,
                "decision": None,
                "decision_reason": None,
                "decision_confidence": None,
                "local_reply": None,
                "final_reply": None,
                "retry_count": 0,
                "max_retries": self.forward_max_retries,
                "timeout_sec": self.forward_timeout,
                "last_error": None,
                "created_at": ts,
                "updated_at": ts,
            }
        )
        row = self.store.get(message_id)
        if not row:
            raise RuntimeError("create message failed")
        await self._emit_status(row, "accepted")
        row = await self._local_stage(message_id)
        if wait_terminal and row.get("status") not in TERMINAL:
            row = await self._wait_terminal(message_id)
        return row, False

    @staticmethod
    def _messages_list(row: dict[str, Any]) -> list[dict[str, str]]:
        out: list[dict[str, str]] = []
        local_text = (row.get("local_reply") or "").strip()
        final_text = (row.get("final_reply") or "").strip()
        if local_text:
            out.append({"source": SOURCE_LOCAL, "source_label": source_label(SOURCE_LOCAL), "kind": "quick_reply", "text": local_text})
        if final_text:
            out.append({"source": SOURCE_OPENCLAW, "source_label": source_label(SOURCE_OPENCLAW), "kind": "final_reply", "text": final_text})
        if row.get("status") == STATUS_FAILED:
            out.append(
                {
                    "source": SOURCE_SYSTEM,
                    "source_label": source_label(SOURCE_SYSTEM),
                    "kind": "error",
                    "text": f"龙虾大脑回复失败：{(row.get('last_error') or 'unknown').strip()}",
                }
            )
        return out

    def _submit_resp(self, row: dict[str, Any], deduped: bool) -> dict[str, Any]:
        return {
            "ok": True,
            "accepted": True,
            "deduped": deduped,
            "message_id": row["message_id"],
            "turn_id": row["turn_id"],
            "session_id": row["session_id"],
            "client_id": row["client_id"],
            "status": row["status"],
            "decision": row.get("decision"),
            "reason": row.get("decision_reason"),
            "confidence": row.get("decision_confidence"),
            "local_reply": row.get("local_reply"),
            "local_source": SOURCE_LOCAL if row.get("local_reply") else None,
            "local_source_label": source_label(SOURCE_LOCAL) if row.get("local_reply") else None,
            "retry": {"count": row.get("retry_count", 0), "max": row.get("max_retries", self.forward_max_retries), "timeout_sec": row.get("timeout_sec", self.forward_timeout)},
            "updated_at": row.get("updated_at"),
        }

    def _status_resp(self, row: dict[str, Any]) -> dict[str, Any]:
        return {
            "ok": True,
            "message_id": row["message_id"],
            "turn_id": row["turn_id"],
            "session_id": row["session_id"],
            "client_id": row["client_id"],
            "source": row["source"],
            "text": row["text"],
            "status": row["status"],
            "decision": row.get("decision"),
            "reason": row.get("decision_reason"),
            "confidence": row.get("decision_confidence"),
            "messages": self._messages_list(row),
            "retry": {"count": row.get("retry_count", 0), "max": row.get("max_retries", self.forward_max_retries), "timeout_sec": row.get("timeout_sec", self.forward_timeout)},
            "last_error": row.get("last_error"),
            "created_at": row.get("created_at"),
            "updated_at": row.get("updated_at"),
        }

    async def handle_v1_submit(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "invalid_json"}, status=400)
        text = (data.get("text") or "").strip()
        if not text:
            return web.json_response({"ok": False, "error": "text is required"}, status=400)
        try:
            row, deduped = await self.submit(
                text=text,
                client_id=(data.get("client_id") or "windows-client"),
                session_id=(data.get("session_id") or self.default_session_id),
                source=(data.get("source") or "windows"),
                message_id=data.get("message_id"),
                turn_id=data.get("turn_id"),
                wait_terminal=False,
            )
            return web.json_response(self._submit_resp(row, deduped))
        except ValueError as exc:
            return web.json_response({"ok": False, "error": str(exc)}, status=400)
        except Exception as exc:
            logger.exception("handle_v1_submit failed")
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def handle_v1_status(self, request: web.Request) -> web.Response:
        message_id = (request.match_info.get("message_id") or "").strip()
        if not message_id:
            return web.json_response({"ok": False, "error": "message_id required"}, status=400)
        row = self.store.get(message_id)
        if not row:
            return web.json_response({"ok": False, "error": "message_not_found"}, status=404)
        return web.json_response(self._status_resp(row))

    async def handle_v1_events(self, request: web.Request) -> web.StreamResponse:
        ws = web.WebSocketResponse(heartbeat=30)
        await ws.prepare(request)
        sid = request.query.get("session_id")
        cid = request.query.get("client_id")
        await self.events.register(ws, sid, cid)
        await ws.send_json({"event_type": "connected", "status": "ok", "session_id": sid, "client_id": cid, "timestamp": now_iso()})
        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT and (msg.data or "").strip().lower() in {"ping", "heartbeat"}:
                    await ws.send_json({"event_type": "pong", "timestamp": now_iso()})
                elif msg.type in {web.WSMsgType.ERROR, web.WSMsgType.CLOSE, web.WSMsgType.CLOSING}:
                    break
        finally:
            await self.events.unregister(ws)
        return ws

    async def handle_v1_operator_summarize(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
        except Exception:
            return web.json_response({"ok": False, "error": "invalid_json"}, status=400)

        text = (data.get("text") or "").strip()
        if not text:
            return web.json_response({"ok": False, "error": "text is required"}, status=400)

        session_id = (data.get("session_id") or self.default_session_id or "voice-bridge-session").strip()
        client_id = (data.get("client_id") or "android-client").strip() or "android-client"
        max_chars = clamp_int(data.get("max_chars", 80), 80, 20, 200)
        try:
            summary = await self.local_operator.summarize(
                text=text,
                history=self.store.recent_session(session_id),
                max_chars=max_chars,
            )
            return web.json_response(
                {
                    "ok": True,
                    "source": SOURCE_LOCAL,
                    "source_label": source_label(SOURCE_LOCAL),
                    "session_id": session_id,
                    "client_id": client_id,
                    "summary": summary,
                    "max_chars": max_chars,
                }
            )
        except Exception as exc:
            logger.exception("handle_v1_operator_summarize failed")
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def handle_chat(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            text = (data.get("text") or "").strip()
            if not text:
                return web.json_response({"ok": False, "error": "text is required"}, status=400)
            row, _ = await self.submit(
                text=text,
                client_id="legacy-chat-client",
                session_id=(data.get("session_id") or self.default_session_id),
                source="legacy-chat",
                message_id=data.get("message_id"),
                turn_id=data.get("turn_id"),
                wait_terminal=True,
            )
            reply = (row.get("final_reply") or row.get("local_reply") or "").strip()
            return web.json_response(
                {
                    "ok": row["status"] != STATUS_FAILED,
                    "message_id": row["message_id"],
                    "input_text": text,
                    "response_text": reply,
                    "status": row["status"],
                    "reply_backend": SOURCE_OPENCLAW if row.get("final_reply") else SOURCE_LOCAL,
                    "session_id": row["session_id"],
                    "last_error": row.get("last_error"),
                }
            )
        except Exception as exc:
            logger.exception("handle_chat failed")
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def init_models(self) -> None:
        if self.stt is None:
            from faster_whisper import WhisperModel

            logger.info("loading Whisper model for /audio")
            self.stt = WhisperModel("small", device="cpu", compute_type="int8")

    async def transcribe_audio(self, audio_data: bytes) -> str:
        import numpy as np

        await self.init_models()
        arr = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
        segments, _ = self.stt.transcribe(arr, language="zh", beam_size=5)
        return "".join(seg.text for seg in segments).strip()

    async def synthesize_tts(self, text: str) -> bytes:
        import edge_tts

        communicate = edge_tts.Communicate(text, self.tts_voice)
        chunks: list[bytes] = []
        async for chunk in communicate.stream():
            if chunk.get("type") == "audio":
                chunks.append(chunk["data"])
        return b"".join(chunks)

    async def handle_audio(self, request: web.Request) -> web.Response:
        try:
            audio_data = await request.read()
            if not audio_data:
                return web.json_response({"ok": False, "error": "empty_audio"}, status=400)
            text = await self.transcribe_audio(audio_data)
            if not text:
                return web.json_response({"ok": False, "error": "stt_failed"}, status=400)
            row, _ = await self.submit(
                text=text,
                client_id="legacy-audio-client",
                session_id=self.default_session_id,
                source="legacy-audio",
                wait_terminal=True,
            )
            reply = (row.get("final_reply") or row.get("local_reply") or "").strip()
            tts_audio = await self.synthesize_tts(reply) if reply else b""
            return web.json_response(
                {
                    "ok": row["status"] != STATUS_FAILED,
                    "message_id": row["message_id"],
                    "input_text": text,
                    "response_text": reply,
                    "status": row["status"],
                    "reply_backend": SOURCE_OPENCLAW if row.get("final_reply") else SOURCE_LOCAL,
                    "last_error": row.get("last_error"),
                    "tts_audio_base64": base64.b64encode(tts_audio).decode("ascii"),
                    "tts_size": len(tts_audio),
                    "tts_content_type": "audio/mpeg",
                }
            )
        except Exception as exc:
            logger.exception("handle_audio failed")
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def handle_tts(self, request: web.Request) -> web.Response:
        try:
            data = await request.json()
            text = (data.get("text") or "").strip()
            if not text:
                return web.json_response({"ok": False, "error": "text is required"}, status=400)
            tts_audio = await self.synthesize_tts(text)
            return web.Response(body=tts_audio, content_type="audio/mpeg")
        except Exception as exc:
            logger.exception("handle_tts failed")
            return web.json_response({"ok": False, "error": str(exc)}, status=500)

    async def handle_health(self, _request: web.Request) -> web.Response:
        return web.json_response(
            {
                "status": "ok",
                "role": "voice-bridge-v1+v2",
                "routes": {
                    "submit": "/v1/messages",
                    "status": "/v1/messages/{message_id}",
                    "events": "/v1/events",
                    "operator_summarize": "/v1/operator/summarize",
                    "legacy_chat": "/chat",
                    "v2_meetings": "/v2/meetings",
                    "v2_meeting_mode": "/v2/meetings/{meeting_id}/mode",
                    "v2_meeting_timeline": "/v2/meetings/{meeting_id}/timeline",
                    "v2_events_batch": "/v2/meetings/{meeting_id}/events:batch",
                },
                "local_operator_endpoint": self.local_operator.endpoint,
                "local_operator_model": self.local_operator.model,
                "openclaw_gateway": f"{self.openclaw.base_url}{self.openclaw.chat_path}",
                "openclaw_enabled": self.openclaw.enabled,
                "forward_timeout_sec": self.forward_timeout,
                "forward_max_retries": self.forward_max_retries,
                "default_session_id": self.default_session_id,
            }
        )

    async def on_startup(self, _app: web.Application) -> None:
        pend = self.store.pending()
        if not pend:
            return
        logger.info("recovering %d pending messages", len(pend))
        for row in pend:
            self._ensure_forward_task(row["message_id"])

    async def on_shutdown(self, _app: web.Application) -> None:
        for task in list(self.forward_tasks.values()):
            task.cancel()
        if self.forward_tasks:
            await asyncio.gather(*self.forward_tasks.values(), return_exceptions=True)
        await self.events.close()
        self.store.close()
        self.meeting_store.close()

    def create_app(self) -> web.Application:
        app = web.Application()
        # V1 routes
        app.router.add_post("/v1/messages", self.handle_v1_submit)
        app.router.add_get("/v1/messages/{message_id}", self.handle_v1_status)
        app.router.add_get("/v1/events", self.handle_v1_events)
        app.router.add_post("/v1/operator/summarize", self.handle_v1_operator_summarize)
        app.router.add_post("/chat", self.handle_chat)
        app.router.add_get("/health", self.handle_health)
        app.router.add_post("/audio", self.handle_audio)
        app.router.add_post("/tts", self.handle_tts)
        # V2 routes (meeting mode)
        self.v2_api.register_routes(app)
        app.on_startup.append(self.on_startup)
        app.on_shutdown.append(self.on_shutdown)
        return app

    def run(self) -> None:
        web.run_app(self.create_app(), host="0.0.0.0", port=self.port)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Voice Assistant Bridge V1+V2 server")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    srv = VoiceAssistantServer(args.port)
    print(f"Bridge server listening on :{args.port}")
    print("V1 API:")
    print("  POST /v1/messages")
    print("  GET  /v1/messages/{message_id}")
    print("  GET  /v1/events (websocket)")
    print("  POST /chat (legacy)")
    print("V2 Meeting API:")
    print("  POST /v2/meetings")
    print("  POST /v2/meetings/{meeting_id}/mode")
    print("  GET  /v2/meetings/{meeting_id}/timeline")
    srv.run()
