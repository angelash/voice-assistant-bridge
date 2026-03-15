"""
Voice Assistant Bridge - Transcription Worker (M3/M4)

Background worker for post-meeting audio transcription using faster-whisper.

Features:
- Async job queue processing
- Progress reporting via events
- Error handling with retries
- Output to refined.jsonl with versioning
- M4: Speaker diarization integration
"""

from __future__ import annotations

import asyncio
import json
import logging
import threading
import hashlib
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional, Callable

from meeting import (
    MeetingStore,
    JOB_STATUS_QUEUED,
    JOB_STATUS_RUNNING,
    JOB_STATUS_SUCCESS,
    JOB_STATUS_FAILED,
    EVT_TRANSCRIPTION_STARTED,
    EVT_TRANSCRIPTION_COMPLETED,
    EVT_TRANSCRIPTION_FAILED,
    EVT_SPEAKER_IDENTIFIED,
    EVT_SPEAKER_RENAMED,
    SPEAKER_SOURCE_DIARIZATION,
    SPEAKER_SOURCE_MANUAL,
    build_event_envelope,
    now_iso,
)

logger = logging.getLogger(__name__)


class TranscriptionWorker:
    """
    Background worker for transcription jobs.
    
    Uses ThreadPoolExecutor to run transcription in background threads
    without blocking the main async event loop.
    """
    
    def __init__(
        self,
        store: MeetingStore,
        event_hub: Any,
        artifacts_dir: Path = Path("artifacts/meetings"),
        max_workers: int = 2,
    ):
        self.store = store
        self.event_hub = event_hub
        self.artifacts_dir = artifacts_dir
        self.max_workers = max_workers
        
        self._executor = ThreadPoolExecutor(max_workers=max_workers)
        self._running = False
        self._poll_interval = 5.0  # seconds
        self._poll_task: Optional[asyncio.Task] = None
        
        # Callbacks
        self.on_job_started: Optional[Callable[[str], None]] = None
        self.on_job_progress: Optional[Callable[[str, int], None]] = None
        self.on_job_completed: Optional[Callable[[str, str], None]] = None
        self.on_job_failed: Optional[Callable[[str, str], None]] = None

    async def start(self) -> None:
        """Start the worker poll loop."""
        if self._running:
            return
        
        self._running = True
        self._poll_task = asyncio.create_task(self._poll_loop())
        logger.info(f"TranscriptionWorker started with {self.max_workers} workers")

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
        logger.info("TranscriptionWorker stopped")

    async def _poll_loop(self) -> None:
        """Poll for queued jobs and process them."""
        while self._running:
            try:
                jobs = self.store.get_queued_transcription_jobs(limit=self.max_workers)
                if jobs:
                    tasks = [asyncio.create_task(self._process_job(job)) for job in jobs]
                    await asyncio.gather(*tasks, return_exceptions=True)
            except Exception as e:
                logger.error(f"Error polling transcription jobs: {e}")
            
            await asyncio.sleep(self._poll_interval)

    async def _process_job(self, job: dict) -> None:
        """Process a single transcription job."""
        job_id = job["job_id"]
        meeting_id = job["meeting_id"]
        
        # Update status to running
        self.store.update_transcription_job(
            job_id,
            status=JOB_STATUS_RUNNING,
            started_at=now_iso(),
        )
        
        # Emit started event
        event = self.store.append_event(
            meeting_id=meeting_id,
            source="transcription-worker",
            event_type=EVT_TRANSCRIPTION_STARTED,
            payload={"job_id": job_id},
        )
        await self.event_hub.publish(build_event_envelope(event))
        
        self.on_job_started and self.on_job_started(job_id)
        logger.info(f"Starting transcription job {job_id} for meeting {meeting_id}")
        
        try:
            # Run transcription in thread pool
            loop = asyncio.get_event_loop()
            result = await loop.run_in_executor(
                self._executor,
                self._run_transcription,
                job,
            )
            
            # Update status to success
            self.store.update_transcription_job(
                job_id,
                status=JOB_STATUS_SUCCESS,
                progress_percent=100,
                output_path=result["output_path"],
                completed_at=now_iso(),
            )
            
            # Emit completed event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="transcription-worker",
                event_type=EVT_TRANSCRIPTION_COMPLETED,
                payload={
                    "job_id": job_id,
                    "output_path": result["output_path"],
                    "segments_count": result.get("segments_count", 0),
                },
            )
            await self.event_hub.publish(build_event_envelope(event))

            speaker_event_payload = result.get("speaker_identified_payload")
            if speaker_event_payload:
                speaker_event = self.store.append_event(
                    meeting_id=meeting_id,
                    source="transcription-worker",
                    event_type=EVT_SPEAKER_IDENTIFIED,
                    payload=speaker_event_payload,
                )
                await self.event_hub.publish(build_event_envelope(speaker_event))
            
            self.on_job_completed and self.on_job_completed(job_id, result["output_path"])
            logger.info(f"Transcription job {job_id} completed: {result['output_path']}")
            
        except Exception as e:
            logger.error(f"Transcription job {job_id} failed: {e}")
            
            # Update status to failed
            self.store.update_transcription_job(
                job_id,
                status=JOB_STATUS_FAILED,
                error_message=str(e),
                completed_at=now_iso(),
            )
            
            # Emit failed event
            event = self.store.append_event(
                meeting_id=meeting_id,
                source="transcription-worker",
                event_type=EVT_TRANSCRIPTION_FAILED,
                payload={
                    "job_id": job_id,
                    "error": str(e),
                },
            )
            await self.event_hub.publish(build_event_envelope(event))
            
            self.on_job_failed and self.on_job_failed(job_id, str(e))

    def _run_transcription(self, job: dict) -> dict:
        """
        Run the actual transcription (called in thread pool).
        
        Returns dict with output_path and segments_count.
        """
        job_id = job["job_id"]
        meeting_id = job["meeting_id"]
        model = job.get("model", "small")
        
        # Get audio segments
        segments = self.store.get_audio_segments(meeting_id)
        if not segments:
            raise ValueError(f"No audio segments found for meeting {meeting_id}")
        
        # Filter uploaded segments
        uploaded_segments = [s for s in segments if s.get("upload_status") == "uploaded"]
        if not uploaded_segments:
            raise ValueError(f"No uploaded audio segments for meeting {meeting_id}")
        
        # Load faster-whisper
        try:
            from faster_whisper import WhisperModel
        except ImportError:
            raise ImportError("faster-whisper not installed. Run: pip install faster-whisper")
        
        # Initialize model
        whisper = WhisperModel(model, device="cpu", compute_type="int8")
        
        # Prepare output
        meeting_dir = self.artifacts_dir / meeting_id
        transcript_dir = meeting_dir / "transcript"
        transcript_dir.mkdir(parents=True, exist_ok=True)
        
        output_path = transcript_dir / "refined.jsonl"
        
        # Clear existing refined segments for re-processing
        self.store.clear_refined_segments(meeting_id)
        
        # Process each audio segment
        all_results = []
        total_segments = len(uploaded_segments)
        
        # M4: Speaker diarization - collect all segments first
        all_whisper_segments = []
        
        for idx, seg in enumerate(uploaded_segments):
            audio_path = seg.get("local_path")
            if not audio_path or not Path(audio_path).exists():
                logger.warning(f"Audio file not found: {audio_path}")
                continue
            
            # Transcribe
            audio_array, sample_rate = self._load_audio(audio_path)
            whisper_segments, info = whisper.transcribe(audio_array, language="zh", beam_size=5)
            
            # Collect segments with timing info
            for ws in whisper_segments:
                all_whisper_segments.append({
                    "audio_segment_id": seg["segment_id"],
                    "audio_seq": seg["seq"],
                    "start": ws.start,
                    "end": ws.end,
                    "text": ws.text.strip(),
                })
            
            # Report progress (50% for transcription)
            progress = int((idx + 1) * 50 / total_segments)
            self.store.update_transcription_job(job_id, progress_percent=progress)
            self.on_job_progress and self.on_job_progress(job_id, progress)
        
        # M4: Run diarization on collected segments
        logger.info(f"Running speaker diarization for {len(all_whisper_segments)} segments")
        diarized_segments = self._run_diarization(all_whisper_segments, meeting_id)
        
        # Store refined segments with speaker info
        for idx, seg in enumerate(diarized_segments):
            refined = self.store.create_refined_segment(
                meeting_id=meeting_id,
                seq=idx,
                start_ts=seg["start"],
                end_ts=seg["end"],
                text=seg["text"],
                audio_segment_id=seg.get("audio_segment_id"),
                speaker_cluster_id=seg.get("speaker_cluster_id"),
                speaker_confidence=seg.get("speaker_confidence"),
                speaker_name=seg.get("speaker_name"),
                speaker_name_source=seg.get("speaker_name_source"),
            )
            all_results.append({
                "segment_ref_id": refined["segment_ref_id"],
                "seq": idx,
                "start": seg["start"],
                "end": seg["end"],
                "text": seg["text"],
                "speaker_cluster_id": seg.get("speaker_cluster_id"),
                "speaker_name": seg.get("speaker_name"),
            })
            
            # Report progress (50-100% for storage)
            if idx % 10 == 0:
                progress = 50 + int((idx + 1) * 50 / len(diarized_segments))
                self.store.update_transcription_job(job_id, progress_percent=progress)
        
        # Write output JSONL
        with open(output_path, "w", encoding="utf-8") as f:
            for item in all_results:
                f.write(json.dumps(item, ensure_ascii=False) + "\n")
        
        # M4: Build speaker identified payload for async publish in event loop
        speakers = self.store.get_speakers_for_meeting(meeting_id)
        speaker_identified_payload = None
        if speakers:
            speaker_identified_payload = {
                "job_id": job_id,
                "speakers_count": len(speakers),
                "speakers": [
                    {
                        "speaker_cluster_id": s["speaker_cluster_id"],
                        "segment_count": s["segment_count"],
                        "avg_confidence": s.get("avg_confidence"),
                    }
                    for s in speakers
                ],
            }
        
        return {
            "output_path": str(output_path),
            "segments_count": len(all_results),
            "speakers_count": len(speakers),
            "speaker_identified_payload": speaker_identified_payload,
        }

    def _run_diarization(
        self,
        segments: list[dict],
        meeting_id: str,
    ) -> list[dict]:
        """
        Run speaker diarization on transcribed segments.
        
        V1 Implementation: Simple pause-based clustering.
        When there's a significant pause (>2s) between segments, 
        assume a speaker change.
        
        Future: Integrate pyannote.audio for more accurate diarization.
        """
        if not segments:
            return segments
        
        PAUSE_THRESHOLD_SEC = 2.0  # Seconds of silence to assume speaker change
        
        # Sort by start time
        sorted_segments = sorted(segments, key=lambda s: (s.get("audio_seq", 0), s.get("start", 0)))
        
        # Assign speaker clusters based on pauses
        current_speaker = "speaker_0"
        speaker_counter = 0
        prev_end = 0.0
        prev_audio_seq = -1
        
        for seg in sorted_segments:
            # Check for pause (gap between segments)
            gap = seg["start"] - prev_end
            
            # Also consider audio segment boundaries
            if seg.get("audio_seq", 0) != prev_audio_seq:
                # Reset timing across audio segment boundaries
                gap = PAUSE_THRESHOLD_SEC + 1  # Force new speaker check
            
            if gap > PAUSE_THRESHOLD_SEC and prev_end > 0:
                # Significant pause - likely speaker change
                speaker_counter += 1
                current_speaker = f"speaker_{speaker_counter}"
            
            seg["speaker_cluster_id"] = current_speaker
            seg["speaker_confidence"] = 0.7  # Default confidence for pause-based
            seg["speaker_name"] = None  # Will be set by user or history
            seg["speaker_name_source"] = SPEAKER_SOURCE_DIARIZATION
            
            prev_end = seg["end"]
            prev_audio_seq = seg.get("audio_seq", 0)
        
        # Apply historical speaker names if available
        self._apply_historical_speaker_names(sorted_segments, meeting_id)
        
        return sorted_segments

    def _apply_historical_speaker_names(
        self,
        segments: list[dict],
        meeting_id: str,
    ) -> None:
        """Apply speaker names from historical mappings."""
        # Get unique speakers
        speakers = set(seg["speaker_cluster_id"] for seg in segments if seg.get("speaker_cluster_id"))
        
        for speaker_id in speakers:
            # Check for historical name
            latest_name = self.store.get_latest_speaker_name(meeting_id, speaker_id)
            if latest_name:
                # Apply to all segments with this speaker
                for seg in segments:
                    if seg.get("speaker_cluster_id") == speaker_id:
                        seg["speaker_name"] = latest_name
                        seg["speaker_name_source"] = "history"

    def _load_audio(self, audio_path: str) -> tuple:
        """Load audio file for transcription."""
        import numpy as np
        
        # Check if it's a WAV file
        path = Path(audio_path)
        if path.suffix.lower() == ".wav":
            import wave
            with wave.open(str(path), "rb") as wf:
                sample_rate = wf.getframerate()
                n_frames = wf.getnframes()
                audio_data = wf.readframes(n_frames)
                
                # Convert to numpy array
                audio_array = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
                return audio_array, sample_rate
        else:
            # Assume raw PCM
            audio_data = path.read_bytes()
            audio_array = np.frombuffer(audio_data, dtype=np.int16).astype(np.float32) / 32768.0
            return audio_array, 48000  # Assume 48kHz for raw PCM


def create_transcription_job_on_meeting_end(
    store: MeetingStore,
    meeting_id: str,
) -> Optional[dict]:
    """
    Create a transcription job when a meeting ends.
    Called from the meeting mode handler.
    """
    # Check if there are audio segments
    segments = store.get_audio_segments(meeting_id)
    if not segments:
        logger.info(f"No audio segments for meeting {meeting_id}, skipping transcription job")
        return None
    
    # Check if there's already a job
    existing = store.get_latest_transcription_job(meeting_id)
    if existing and existing.get("status") in (JOB_STATUS_QUEUED, JOB_STATUS_RUNNING):
        logger.info(f"Transcription job already exists for meeting {meeting_id}")
        return existing
    
    # Create new job
    job = store.create_transcription_job(meeting_id=meeting_id)
    logger.info(f"Created transcription job {job['job_id']} for meeting {meeting_id}")
    return job
