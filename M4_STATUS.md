# M4 Status: Speaker Diarization V1

**Status**: COMPLETE
**Completed**: 2026-03-14
**Previous Milestone**: M3 (Auto Transcription) - COMPLETE

## Implementation Summary

### 1. Database Layer (meeting.py)
- ✅ Added `meeting_segments_refined` table with speaker fields:
  - `speaker_cluster_id` - Speaker cluster identifier
  - `speaker_confidence` - Confidence score for speaker assignment
  - `speaker_name` - Human-assigned or historical name
  - `speaker_name_source` - Source of name (diarization/manual/history)
- ✅ Added `speaker_name_mappings` table for audit history
- ✅ Added CRUD operations for refined segments
- ✅ Added speaker name mapping operations

### 2. Transcription Worker (transcription_worker.py)
- ✅ Integrated diarization step into transcription pipeline
- ✅ Implemented pause-based speaker clustering (V1):
  - Detects speaker changes when pause > 2 seconds
  - Assigns sequential speaker IDs (speaker_0, speaker_1, ...)
  - Records confidence scores
- ✅ Stores refined segments with speaker info in database
- ✅ Applies historical speaker names from previous mappings
- ✅ Emits `speaker.identified` event when diarization completes

### 3. API Layer (v2_api.py)
- ✅ `GET /v2/meetings/{meeting_id}/refined` - Get refined segments
- ✅ `GET /v2/meetings/{meeting_id}/speakers` - Get speaker list
- ✅ `PATCH /v2/meetings/{meeting_id}/speakers/{speaker_cluster_id}` - Rename speaker
- ✅ `GET /v2/meetings/{meeting_id}/speakers/history` - Get rename history

### 4. Windows UI (windows_meeting_gui.py)
- ✅ Added `SpeakerPanelWidget` - Speaker list with rename functionality
- ✅ Added `RefinedTranscriptWidget` - Transcript view with speaker names
- ✅ Double-click or button to rename speakers
- ✅ Auto-refresh on meeting state changes

### 5. Migration & Tests
- ✅ Created `migrations/m4_speaker_diarization.sql`
- ✅ Added M4 unit tests (12 new tests)
- ✅ All 40 tests passing

## Acceptance Criteria

| Criterion | Status |
|-----------|--------|
| 会后稳定稿带说话人簇 ID | ✅ Complete |
| 支持人工改名并在历史会次复用 | ✅ Complete |
| 改名审计记录（保留历史映射） | ✅ Complete |
| Windows UI 支持说话人重命名 | ✅ Complete |

## Files Changed

- `meeting.py` - Added tables and methods for M4
- `transcription_worker.py` - Added diarization integration
- `v2_api.py` - Added M4 API endpoints
- `windows_meeting_gui.py` - Added speaker panel and refined transcript widgets
- `migrations/m4_speaker_diarization.sql` - New migration file
- `test_v2_api.py` - Added M4 tests

## Technical Notes

### V1 Diarization Approach
The V1 implementation uses a simple pause-based clustering:
- Speaker change is detected when there's a pause > 2 seconds between segments
- This is a lightweight approach that doesn't require external dependencies
- Future enhancement: Integrate `pyannote.audio` for more accurate diarization

### Speaker Name Sources
1. `diarization` - Auto-detected by diarization algorithm
2. `manual` - User-assigned via UI or API
3. `history` - Reused from historical mapping

## Next Steps (M5)

M5 will focus on:
- Image upload and OpenClaw analysis integration
- Image-to-timeline linking
