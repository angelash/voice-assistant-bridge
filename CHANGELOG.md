# Changelog

All notable changes to Voice Assistant Bridge will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [2026.03.14] - 2026-03-14

### M6 - Reports and Release Preparation

### Added
- Three-layer report generator (brief/action/deep)
  - Brief: One-paragraph summary for quick scanning
  - Action: Extracted action items and decisions
  - Deep: Full structured report with timeline and speakers
- 7-day cleanup guard with safety rules
  - Only deletes successfully uploaded data
  - Writes audit log before deletion
  - Automatic retry with max 3 attempts
- Stability and performance test suite
  - Long meeting stability tests (60+ min simulation)
  - Network recovery tests
  - Concurrent meeting isolation tests
  - Storage pressure tests
- Release and rollback scripts
- Release/rollback documentation

### Previous Milestones

## [2026.03.13] - M5 Complete

### Added
- Image original upload pipeline
- Android image capture/upload
- Backend thumbnail generation
- OpenClaw image analysis integration

## [2026.03.13] - M4 Complete

### Added
- Speaker diarization V1
- Pause-based speaker clustering
- Speaker name mapping and audit
- Manual speaker rename support

## [2026.03.12] - M3 Complete

### Added
- Background transcription worker
- faster-whisper integration
- refined.jsonl output format
- Job queue and status tracking
- TTS playback wakeword suppression

## [2026.03.11] - M2 Complete

### Added
- Audio upload API with checksum verification
- Upload state machine (pending/uploaded/failed)
- Upload retry queue with exponential backoff
- Historical meeting list and status panel
- Wakeword event reporting

## [2026.03.10] - M1 Complete

### Added
- Meeting sessions and events tables
- POST /v2/meetings API
- POST /v2/meetings/{id}/mode API
- GET /v2/events/stream WebSocket
- Android meeting mode UI
- Audio segment local storage
- PCM distribution bus
- Wakeword state machine skeleton
- Windows meeting control console

### Infrastructure
- Initial project structure
- SQLite database schema
- Basic test framework
