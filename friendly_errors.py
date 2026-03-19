from __future__ import annotations

import asyncio
import json
from typing import Any, Mapping

import aiohttp


_BACKEND_ERROR_MESSAGES = {
    "invalid_json": "请求格式不正确，请稍后重试。",
    "invalid_payload": "提交内容不完整，请检查后重试。",
    "text is required": "请输入内容后再提交。",
    "text_required": "请输入内容后再提交。",
    "message_id required": "缺少消息编号，请刷新后重试。",
    "message_not_found": "消息不存在，可能已过期。",
    "active_meeting_exists": "已有进行中的会议，请先结束当前会议。",
    "meeting_id required": "缺少会议编号，请刷新后重试。",
    "meeting_not_found": "会议不存在或已结束，请刷新后重试。",
    "segment_id required": "缺少音频分段编号，请重新上传。",
    "segment_not_found": "音频分段不存在，可能尚未上传完成。",
    "segment_meeting_mismatch": "音频分段与当前会议不匹配，请重新上传。",
    "audio data required": "没有收到音频数据，请重新录音后再试。",
    "empty_audio": "没有录到有效音频，请重新录音。",
    "stt_failed": "语音识别失败，请重新录音后再试。",
    "seq required": "缺少分段序号，请重新上传。",
    "checksum_mismatch": "上传文件校验失败，请重新上传。",
    "job_in_progress": "已有任务正在处理中，请稍后刷新。",
    "job_id required": "缺少任务编号，请刷新后重试。",
    "job_not_found": "任务不存在，可能已完成或已被清理。",
    "speaker_cluster_id required": "缺少说话人编号，请刷新后重试。",
    "speaker_name_required": "请输入说话人名称后再保存。",
    "no_fields_to_update": "没有检测到可保存的内容。",
    "file_not_found": "文件不存在，可能尚未生成完成。",
    "image data required": "没有收到图片数据，请重新上传。",
    "image_id required": "缺少图片编号，请刷新后重试。",
    "image_not_found": "图片不存在，可能尚未上传成功。",
    "meeting_id and image_id required": "缺少图片或会议编号，请刷新后重试。",
    "meeting_id and image_id required": "缺少图片或会议编号，请刷新后重试。",
    "openclaw_failed": "龙虾大脑暂时不可用，请稍后重试。",
}

_HTTP_STATUS_MESSAGES = {
    400: "请求参数不正确，请检查后重试。",
    401: "服务拒绝访问，请检查令牌或登录状态。",
    403: "当前操作没有权限。",
    404: "请求的资源不存在，请刷新后重试。",
    408: "请求超时，请稍后重试。",
    409: "当前状态冲突，请刷新后再试。",
    413: "提交内容过大，请缩小后重试。",
    429: "请求过于频繁，请稍后再试。",
    500: "服务端处理失败，请稍后重试。",
    502: "网关暂时不可用，请稍后重试。",
    503: "服务暂时不可用，请稍后重试。",
    504: "服务响应超时，请稍后重试。",
}


def _error_text(value: Any) -> str:
    return str(value or "").strip()


def _parse_json_text(raw_text: str) -> dict[str, Any] | None:
    text = _error_text(raw_text)
    if not text:
        return None
    try:
        data = json.loads(text)
    except Exception:
        return None
    return data if isinstance(data, dict) else None


def backend_error_message(error: Any, default: str = "请求失败，请稍后重试。") -> str:
    code = _error_text(error).lower()
    if not code:
        return default
    return _BACKEND_ERROR_MESSAGES.get(code, default)


def http_status_message(status: int, default: str = "服务返回异常，请稍后重试。") -> str:
    return _HTTP_STATUS_MESSAGES.get(int(status or 0), default)


def friendly_exception_message(exc: BaseException, action: str = "请求服务") -> str:
    if isinstance(exc, asyncio.TimeoutError):
        return f"{action}超时，请检查网络或稍后重试。"
    if isinstance(exc, aiohttp.InvalidURL):
        return "服务地址配置不正确，请检查设置。"
    if isinstance(exc, aiohttp.ClientConnectorCertificateError):
        return "服务证书校验失败，请检查 HTTPS 配置。"
    if isinstance(exc, aiohttp.ClientConnectorError):
        return f"{action}失败，当前无法连接到服务，请确认服务已启动且地址可达。"
    if isinstance(exc, aiohttp.ClientResponseError):
        return http_status_message(exc.status, default=f"{action}失败，请稍后重试。")
    if isinstance(exc, aiohttp.ClientError):
        return f"{action}失败，请检查网络连接后重试。"

    text = _error_text(getattr(exc, "message", None) or exc)
    lowered = text.lower()
    if "timed out" in lowered or "timeout" in lowered:
        return f"{action}超时，请检查网络或稍后重试。"
    if "name or service not known" in lowered or "nodename nor servname provided" in lowered:
        return "无法解析服务地址，请检查配置。"
    if "cannot connect to host" in lowered or "connect call failed" in lowered:
        return f"{action}失败，当前无法连接到服务，请确认服务已启动且地址可达。"
    if "connection refused" in lowered:
        return "服务未启动或端口未监听，请先启动服务。"
    if "service prepare failed" in lowered:
        return "本地服务未就绪，请先启动 bridge 服务后重试。"
    return f"{action}失败，请稍后重试。"


def friendly_result_message(result: Mapping[str, Any] | None, default: str = "请求失败，请稍后重试。") -> str:
    if not isinstance(result, Mapping):
        return default

    message = _error_text(result.get("message"))
    if message:
        return message

    error = _error_text(result.get("error"))
    if error.startswith("service prepare failed"):
        return "本地服务未就绪，请先启动 bridge 服务后重试。"

    payload = _parse_json_text(_error_text(result.get("detail")) or error)
    if payload:
        payload_message = _error_text(payload.get("message"))
        if payload_message:
            return payload_message
        payload_error = _error_text(payload.get("error"))
        if payload_error:
            return backend_error_message(payload_error, default)

    if error:
        mapped = backend_error_message(error, default="")
        if mapped:
            return mapped
        if error.startswith("http_"):
            try:
                return http_status_message(int(error.split("_", 1)[1]), default)
            except Exception:
                return default
        if error.startswith("HTTP "):
            parts = error.split(":", 1)
            status_token = parts[0].replace("HTTP", "").strip()
            try:
                return http_status_message(int(status_token), default)
            except Exception:
                return default

    status = result.get("status")
    if isinstance(status, int):
        return http_status_message(status, default)

    return default


def attach_friendly_message(result: Mapping[str, Any] | None, default: str = "请求失败，请稍后重试。") -> dict[str, Any]:
    payload = dict(result or {})
    payload.setdefault("ok", False)
    payload["message"] = friendly_result_message(payload, default)
    return payload


def build_exception_result(exc: BaseException, action: str = "请求服务", error: str = "request_failed") -> dict[str, Any]:
    return {
        "ok": False,
        "error": error,
        "message": friendly_exception_message(exc, action=action),
        "detail": _error_text(exc),
    }
