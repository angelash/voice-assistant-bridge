-- V2 Meeting Mode Tables
-- Migration: Add meeting support tables

-- Meeting sessions table
CREATE TABLE IF NOT EXISTS meeting_sessions (
    meeting_id TEXT PRIMARY KEY,
    client_id TEXT NOT NULL,
    session_id TEXT,
    status TEXT NOT NULL DEFAULT 'IDLE',
    mode TEXT DEFAULT NULL,
    started_at TEXT,
    ended_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    meta_json TEXT
);

-- Meeting events table (append-only event log)
CREATE TABLE IF NOT EXISTS meeting_events (
    event_id TEXT PRIMARY KEY,
    meeting_id TEXT NOT NULL,
    source TEXT NOT NULL,
    event_type TEXT NOT NULL,
    seq INTEGER,
    ts_client TEXT,
    ts_server TEXT NOT NULL,
    payload TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_meeting_events_meeting ON meeting_events(meeting_id, ts_server);
CREATE INDEX IF NOT EXISTS idx_meeting_events_type ON meeting_events(event_type);
CREATE INDEX IF NOT EXISTS idx_meeting_sessions_status ON meeting_sessions(status);

-- Audio segments table
CREATE TABLE IF NOT EXISTS audio_segments (
    segment_id TEXT PRIMARY KEY,
    meeting_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    local_path TEXT,
    checksum TEXT,
    size_bytes INTEGER,
    duration_ms INTEGER,
    started_at TEXT,
    sealed_at TEXT,
    upload_status TEXT DEFAULT 'pending',
    uploaded_at TEXT,
    upload_error TEXT,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audio_segments_meeting ON audio_segments(meeting_id, seq);
CREATE INDEX IF NOT EXISTS idx_audio_segments_upload ON audio_segments(upload_status);
