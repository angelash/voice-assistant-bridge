"""
Voice Assistant Bridge - Image Analysis Worker (M5)

Background worker for analyzing meeting images via OpenClaw.

Features:
- Async job queue processing
- OpenClaw image API integration
- Progress reporting via events
- Error handling with retries
"""

from __future__ import annotations

import asyncio
import json
import logging
import base64
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any, Optional, Callable

import aiohttp

from meeting import (
    MeetingStore,
    EVT_IMAGE_ANALYSIS_STARTED,
    EVT_IMAGE_ANALYSIS_COMPLETED,
    EVT_IMAGE_ANALYSIS_FAILED,
    IMAGE_STATUS_ANALYZING,
    IMAGE_STATUS_ANALYZED,
    IMAGE_STATUS_ANALYSIS_FAILED,
    build_event_envelope,
    now_iso,
)

logger = logging.getLogger(__name__)

# OpenClaw configuration
# The worker can be configured to call OpenClaw's image analysis endpoint
# Set OPENCLAW_API_URL environment variable or pass in constructor
DEFAULT_OPENCLAW_API_URL = "http://127.0.0.1:8766"


class ImageAnalysisWorker:
    """
    Background worker for image analysis jobs.
    
    Uses ThreadPoolExecutor to run analysis in background threads
    without blocking the main async event loop.
    """
    
    def __init__(
        self,
        store: MeetingStore,
        event_hub: Any,
        artifacts_dir: Path = Path("artifacts/meetings"),
        openclaw_api_url: Optional[str] = None,
        max_workers: int = 2,
    ):
        self.store = store
        self.event_hub = event_hub
        self.artifacts_dir = artifacts_dir
        self.openclaw_api_url = openclaw_api_url or DEFAULT_OPENCLAW_API_URL
        self.max_workers = max_workers
        
        self._executor = ThreadPoolExecutor(max_workers=max_workers)
        self._running = False
        self._poll_interval = 10.0  # seconds
        self._poll_task: Optional[asyncio.Task] = None
        
        # Callbacks
        self.on_analysis_started: Optional[Callable[[str], None]] = None
        self.on_analysis_completed: Optional[Callable[[str, dict], None]] = None
        self.on_analysis_failed: Optional[Callable[[str, str], None]] = None

    async def start(self) -> None:
        """Start the worker poll loop."""
        if self._running:
            return
        
        self._running = True
        self._poll_task = asyncio.create_task(self._poll_loop())
        logger.info(f"ImageAnalysisWorker started with {self.max_workers} workers")

    async def stop(self) -> None:
        """Stop the worker."""
        self._running = False
        if self._poll_task:
            self._poll_task.cancel()
            try:
                await self._poll_task
            except asyncio.CancelledError:
                pass
        self._executor.shutdown(wait=False)
        logger.info("ImageAnalysisWorker stopped")

    async def _poll_loop(self) -> None:
        """Poll for pending analysis images and process them."""
        while self._running:
            try:
                images = self.store.get_pending_analysis_images(limit=self.max_workers)
                if images:
                    tasks = [asyncio.create_task(self._process_image(image)) for image in images]
                    await asyncio.gather(*tasks, return_exceptions=True)
            except Exception as e:
                logger.error(f"Error polling image analysis jobs: {e}")
            
            await asyncio.sleep(self._poll_interval)

    async def _process_image(self, image: dict) -> None:
        """Process a single image analysis job."""
        image_id = image["image_id"]
        meeting_id = image["meeting_id"]
        original_path = image.get("original_path")
        
        if not original_path:
            logger.error(f"No original_path for image {image_id}")
            self.store.update_meeting_image(
                image_id,
                analysis_status=IMAGE_STATUS_ANALYSIS_FAILED,
                analysis_error="no_original_path",
                analysis_at=now_iso(),
            )
            return
        
        # Update status to analyzing
        self.store.update_meeting_image(image_id, analysis_status=IMAGE_STATUS_ANALYZING)
        
        # Emit started event
        event = self.store.append_event(
            meeting_id=meeting_id,
            source="image-analysis-worker",
            event_type=EVT_IMAGE_ANALYSIS_STARTED,
            payload={
                "image_id": image_id,
                "seq": image.get("seq"),
                "original_path": original_path,
            },
        )
        await self.event_hub.publish(build_event_envelope(event))
        
        self.on_analysis_started and self.on_analysis_started(image_id)
        logger.info(f"Starting image analysis for {image_id}")
        
        try:
            # Run analysis in thread pool
            loop = asyncio.get_event_loop()
            result = await loop.run_in_executor(
                self._executor,
                self._run_analysis,
                image,
            )
            
            # Update with result
            self.store.update_meeting_image(
                image_id,
                analysis_status=IMAGE_STATUS_ANALYZED,
                analysis_result=result,
                analysis_at=now_iso(),
            )
            
            # Emit completed event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="image-analysis-worker",
                event_type=EVT_IMAGE_ANALYSIS_COMPLETED,
                payload={
                    "image_id": image_id,
                    "result": result,
                },
            )
            await self.event_hub.publish(build_event_envelope(event))
            
            self.on_analysis_completed and self.on_analysis_completed(image_id, result)
            logger.info(f"Image analysis completed for {image_id}")
            
        except Exception as e:
            logger.error(f"Image analysis failed for {image_id}: {e}")
            
            # Update status to failed
            self.store.update_meeting_image(
                image_id,
                analysis_status=IMAGE_STATUS_ANALYSIS_FAILED,
                analysis_error=str(e),
                analysis_at=now_iso(),
            )
            
            # Emit failed event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="image-analysis-worker",
                event_type=EVT_IMAGE_ANALYSIS_FAILED,
                payload={
                    "image_id": image_id,
                    "error": str(e),
                },
            )
            await self.event_hub.publish(build_event_envelope(event))
            
            self.on_analysis_failed and self.on_analysis_failed(image_id, str(e))

    def _run_analysis(self, image: dict) -> dict:
        """
        Run the actual image analysis (called in thread pool).
        
        Attempts to call OpenClaw's image analysis API.
        Falls back to basic analysis if OpenClaw is unavailable.
        
        Returns dict with analysis results.
        """
        image_id = image["image_id"]
        original_path = image.get("original_path")
        
        if not original_path or not Path(original_path).exists():
            raise ValueError(f"Image file not found: {original_path}")
        
        # Read image file
        with open(original_path, "rb") as f:
            image_data = f.read()
        
        # Try to call OpenClaw API first
        try:
            result = self._call_openclaw_api(image_data, image)
            if result:
                return result
        except Exception as e:
            logger.warning(f"OpenClaw API call failed: {e}, falling back to basic analysis")
        
        # Fallback: basic file-based analysis
        return self._basic_analysis(image_data, image)

    def _call_openclaw_api(self, image_data: bytes, image: dict) -> Optional[dict]:
        """
        Call OpenClaw's image analysis API.
        
        This method attempts to connect to a running OpenClaw instance
        and request image analysis.
        
        OpenClaw API endpoint: POST /api/image/analyze
        Request body: {"image": "base64_encoded_image", "prompt": "optional prompt"}
        Response: {"description": "...", "labels": [...], ...}
        """
        import requests  # Lazy import to avoid dependency issues
        
        # Encode image to base64
        image_base64 = base64.b64encode(image_data).decode("utf-8")
        
        # Build request
        url = f"{self.openclaw_api_url}/api/image/analyze"
        
        payload = {
            "image": image_base64,
            "prompt": "Analyze this meeting image. Describe what you see, identify any text, diagrams, or key content. Provide a summary suitable for meeting notes.",
        }
        
        # Make synchronous request (we're in a thread pool)
        try:
            response = requests.post(
                url,
                json=payload,
                timeout=60,  # 60 second timeout for image analysis
            )
            
            if response.status_code == 200:
                return response.json()
            else:
                logger.warning(f"OpenClaw API returned status {response.status_code}")
                return None
                
        except requests.exceptions.ConnectionError:
            logger.debug("OpenClaw API not available, using fallback")
            return None
        except Exception as e:
            logger.warning(f"OpenClaw API error: {e}")
            return None

    def _basic_analysis(self, image_data: bytes, image: dict) -> dict:
        """
        Basic fallback analysis when OpenClaw is unavailable.
        
        Provides basic metadata and placeholder analysis.
        """
        import hashlib
        
        # Basic metadata
        filename = image.get("filename", "unknown")
        size_bytes = len(image_data)
        width = image.get("width")
        height = image.get("height")
        fmt = image.get("format", "unknown")
        
        # Compute image hash for identification
        image_hash = hashlib.sha256(image_data).hexdigest()[:16]
        
        # Basic format detection
        format_info = self._detect_image_format(image_data)
        
        return {
            "description": f"Meeting image: {filename}",
            "labels": ["meeting-image", "uploaded"],
            "metadata": {
                "filename": filename,
                "size_bytes": size_bytes,
                "width": width,
                "height": height,
                "format": fmt,
                "hash": image_hash,
            },
            "format_detected": format_info,
            "analysis_source": "fallback",
            "analysis_note": "Basic analysis - OpenClaw not available. For detailed analysis, ensure OpenClaw is running.",
            "analyzed_at": now_iso(),
        }

    def _detect_image_format(self, data: bytes) -> dict:
        """Detect image format from magic bytes."""
        if len(data) < 12:
            return {"format": "unknown", "confidence": 0}
        
        # PNG
        if data[:8] == b'\x89PNG\r\n\x1a\n':
            return {"format": "png", "confidence": 1.0}
        
        # JPEG
        if data[:2] == b'\xff\xd8':
            return {"format": "jpeg", "confidence": 1.0}
        
        # WebP
        if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
            return {"format": "webp", "confidence": 1.0}
        
        # GIF
        if data[:6] in (b'GIF87a', b'GIF89a'):
            return {"format": "gif", "confidence": 1.0}
        
        # BMP
        if data[:2] == b'BM':
            return {"format": "bmp", "confidence": 1.0}
        
        return {"format": "unknown", "confidence": 0}


async def analyze_image_via_openclaw(
    image_path: str,
    openclaw_api_url: str = DEFAULT_OPENCLAW_API_URL,
    prompt: Optional[str] = None,
) -> dict:
    """
    Analyze an image via OpenClaw API (async helper).
    
    This is a standalone helper that can be called from the API handler
    for synchronous analysis requests.
    """
    path = Path(image_path)
    if not path.exists():
        raise FileNotFoundError(f"Image not found: {image_path}")
    
    # Read image
    with open(path, "rb") as f:
        image_data = f.read()
    
    # Encode to base64
    image_base64 = base64.b64encode(image_data).decode("utf-8")
    
    # Build request
    url = f"{openclaw_api_url}/api/image/analyze"
    
    payload = {
        "image": image_base64,
        "prompt": prompt or "Analyze this image. Describe the content, identify any text, and provide relevant labels.",
    }
    
    # Make async request
    async with aiohttp.ClientSession() as session:
        async with session.post(
            url,
            json=payload,
            timeout=aiohttp.ClientTimeout(total=60),
        ) as response:
            if response.status == 200:
                return await response.json()
            else:
                text = await response.text()
                raise Exception(f"OpenClaw API error: {response.status} - {text}")


def create_image_analysis_on_upload(
    store: MeetingStore,
    image_id: str,
) -> Optional[dict]:
    """
    Create an image analysis job when an image is uploaded.
    
    This is called from the image upload handler if auto-analysis is enabled.
    The actual analysis is done by the ImageAnalysisWorker.
    """
    image = store.get_meeting_image(image_id)
    if not image:
        return None
    
    # Update status to pending (worker will pick it up)
    store.update_meeting_image(image_id, analysis_status="pending")
    
    logger.info(f"Image {image_id} queued for analysis")
    return image
