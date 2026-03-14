# M2 Milestone Status Report

**Date**: 2026-03-14
**Commit**: 5f1cbfa

## M2 Tasks Completion

| Task | Status | Notes |
|------|--------|-------|
| Backend: POST /v2/meetings/{id}/audio:upload | ✅ DONE | v2_api.py with checksum verification |
| Backend: Upload state machine | ✅ DONE | pending/uploaded/failed/uploading |
| Backend: Upload manifest endpoint | ✅ DONE | GET /v2/meetings/{id}/audio/manifest |
| Backend: Pending uploads endpoint | ✅ DONE | GET /v2/meetings/{id}/audio/pending |
| Backend: Reset failed uploads | ✅ DONE | POST /v2/meetings/{id}/audio:reset-failed |
| Persistence: get_audio_segment | ✅ DONE | Single segment lookup |
| Persistence: get_failed_audio_segments | ✅ DONE | For retry queue |
| Android: UploadQueueManager | ✅ DONE | Exponential backoff + idempotent IDs |
| Android: WakeWordStateMachine | ✅ DONE | LISTENING/COMMAND_WINDOW/COOLDOWN/SUPPRESSED |
| Windows UI: BackupStatusWidget | ✅ DONE | Upload progress and segment status |

## API Endpoints (M2)

### POST /v2/meetings/{meeting_id}/audio:upload
Upload audio segment with checksum verification.

**Request (multipart/form-data):**
- `segment_id}/audio/manifest | ✅ DONE | Returns segment status summary |
| Backend: GET /v2/meetings/{id}/audio/pending | ✅ DONE | Returns pending+failed for retry |
| Backend: POST /v2/meetings/{id}/audio:reset-failed | ✅ DONE | Reset failed to pending |
| Backend: get_audio_segment method | ✅ DONE | meeting.py |
| Backend: get_failed_audio_segments method | ✅ DONE | meeting.py |
| Android: UploadQueueManager | ✅ DONE | Exponential backoff, idempotent IDs |
| Android: WakeWordStateMachine | ✅ DONE | State machine with TTS suppression |
| Windows UI: BackupStatusWidget | ✅ DONE | Shows upload progress, segment list |

## Key Implementation Details

### Audio Upload Flow

1. **Android** records audio in 30s segments
2. **Android** UploadQueueManager uploads with:
   - SHA256 checksum
   - Exponential backoff (1s, 2s, 4s, 8s, 16s max)
   - Max 5 retries
   - Concurrent upload limit (3)

3. **Backend** receives upload:
   - Validates checksum
   - Saves to `artifacts/meetings/{id}/audio/raw/`
   - Updates `audio_segments` table
   - Emits `audio.segment.uploaded` or `audio.segment.upload_failed` event

### Upload State Machine

```
┌─────────┐  upload_success  ┌───────────┐
│ pending │ ──────────────► │ uploaded  │
└────┬────┘                 └───────────┘
     │
     │ upload_failed
     ▼
┌─────────┐  reset_failed  ┌─────────┐
│ failed  │ ─────────────► │ pending │
└─────────┘                └─────────┘
```

### Wake Word State Machine

```
┌─────────┐  enable   ┌───────────┐  detected  ┌────────────────┐
│  IDLE   │ ────────► │ LISTENING │ ─────────► │ COMMAND_WINDOW │
└─────────┘           └───────────┘            └───────┬────────┘
                           ▲                        │
                           │ cooldown_end           │ timeout
                           │                        ▼
                      ┌──────────┐            ┌──────────┐
                      │ COOLDOWN │ ◄──────────│          │
                      └──────────┘            │          │
                           ▲                  │          │
                           │ tts_end          │ SUPPRESSED│
                           │                  │ (TTS play)│
                      ┌────────────┐          └──────────┘
                      │ SUPPRESSED │ ◄── tts_start
                      └────────────┘
```

## Files Changed

**Backend (Python):**
- `meeting.py` - Added upload status constants, get_audio_segment, get_failed_audio_segments
- `v2_api.py` - Added audio upload endpoints, manifest, pending, reset-failed

**Android (Kotlin):**
- `UploadQueueManager.kt` - Upload queue with retry logic
- `WakeWordStateMachine.kt` - State machine for wake word detection

**Windows (Python):**
- `windows_meeting_gui.py` - Added BackupStatusWidget

## API Endpoints

### POST /v2/meetings/{meeting_id}/audio:upload

**Request:** multipart/form-data
- `segment_id`: string (required)
- `seq`: integer (required)
- `checksum`: string (SHA256, optional but recommended)
- `audio`: binary (required)

**Response:**
```json
{
  "ok": true,
  "segment_id": "seg-xxx",
  "upload_status": "uploaded",
  "checksum_verified": true,
  "size_bytes": 12345,
  "path": "artifacts/meetings/mtg-xxx/audio/raw/seg-xxx.wav"
}
```

### GET /v2/meetings/{meeting_id}/audio/manifest

**Response:**
```json
{
  "ok": true,
  "manifest": {
    "meeting_id": "mtg-xxx",
    "total_segments": 10,
    "uploaded_count": 7,
    "pending_count": 2,
    "failed_count": 1,
    "segments": [...]
  }
}
```

### GET /v2/meetings/{meeting_id}/audio/pending

**Response:**
```json
{
  "ok": true,
  "pending_segments": [...],
  "failed_segments": [...],
  "pending_count": 2,
  "failed_count": 1,
  "total_needs_upload": 3
}
```

### POST /v2/meetings/{meeting_id}/audio:reset-failed

**Response:**
```json
{
  "ok": true,
  "reset_count": 1,
  "message": "Reset 1 failed segments to pending status"
}
```

## M2 Verification Checklist

- [x] Audio upload endpoint accepts multipart/form-data
- [x] Checksum validation works (mismatch returns 400)
- [x] Upload state machine transitions correctly
- [x] Manifest endpoint returns correct segment counts
- [x] Pending endpoint returns both pending and failed
- [x] Reset-failed endpoint resets failed segments
- [x] Events emitted for upload success/failure
- [x] Android UploadQueueManager exists with retry logic
- [x] WakeWordStateMachine has TTS suppression
- [x] Windows BackupStatusWidget shows segment status

## Next Steps (M3)

1. **Auto-Refinement Worker**: Background thread for faster-whisper
2. **Transcription Jobs Table**: Task state machine
3. **Post-Meeting Trigger**: Auto-create refinement task
4. **Refined JSONL Output**: Version-numbered transcripts
5. **TTS Suppression During Wake Word**: Integrate with playback

## Blockers

None - M2 is complete and ready for integration testing.

## Recommended Integration Tests

1. **Android → Backend Upload Flow**:
   - Start meeting on Android
   - Record audio segments
   - Verify segments uploaded to Windows
   - Check manifest shows correct counts

2. **Retry Flow**:
   - Simulate network failure
   - Verify Android retries with backoff
   - Verify failed segments can be reset

3. **Wake Word Integration**:
   - Verify state transitions during meeting
   - Verify TTS suppression works
   - Verify events reported to server
