-- M4: Speaker Diarization Tables
-- Migration: Add refined segments with speaker info and speaker name mappings

-- Refined meeting segments with speaker information
CREATE TABLE IF NOT EXISTS meeting_segments_refined (
    segment_ref_id TEXT PRIMARY KEY,
    meeting_id TEXT NOT NULL,
    audio_segment_id TEXT,
    seq INTEGER NOT NULL,
    start_ts REAL NOT NULL,
    end_ts REAL NOT NULL,
    text TEXT NOT NULL,
    speaker_cluster_id TEXT,
    speaker_confidence REAL,
    speaker_name TEXT,
    speaker_name_source TEXT,
    revision INTEGER DEFAULT 1,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Speaker name mappings (audit history for rename operations)
CREATE TABLE IF NOT EXISTS speaker_name_mappings (
    mapping_id TEXT PRIMARY KEY,
    meeting_id TEXT NOT NULL,
    speaker_cluster_id TEXT NOT NULL,
    old_name TEXT,
    new_name TEXT NOT NULL,
    source TEXT NOT NULL,
    changed_by TEXT,
    changed_at TEXT NOT NULL,
    notes TEXT
);

-- Indexes for refined segments
CREATE INDEX IF NOT EXISTS idx_refined_segments_meeting ON meeting_segments_refined(meeting_id, seq);
CREATE INDEX IF NOT EXISTS idx_refined_segments_speaker ON meeting_segments_refined(meeting_id, speaker_cluster_id);

-- Indexes for speaker mappings
CREATE INDEX IF NOT EXISTS idx_speaker_mappings_meeting ON speaker_name_mappings(meeting_id);
CREATE INDEX IF NOT EXISTS idx_speaker_mappings_cluster ON speaker_name_mappings(speaker_cluster_id);
