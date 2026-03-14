-- M5: Image Upload Pipeline Tables
-- Migration: Add meeting images table for original image uploads with analysis

-- Meeting images table
-- Stores original image metadata, paths, and analysis results
CREATE TABLE IF NOT EXISTS meeting_images (
    image_id TEXT PRIMARY KEY,
    meeting_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    original_path TEXT NOT NULL,
    thumbnail_path TEXT,
    filename TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    checksum TEXT NOT NULL,
    width INTEGER,
    height INTEGER,
    format TEXT,
    device_id TEXT,
    captured_at TEXT,
    uploaded_at TEXT NOT NULL,
    upload_status TEXT DEFAULT 'uploaded',
    analysis_status TEXT DEFAULT 'pending',
    analysis_result TEXT,
    analysis_error TEXT,
    analysis_at TEXT,
    created_at TEXT NOT NULL
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_meeting_images_meeting ON meeting_images(meeting_id, seq);
CREATE INDEX IF NOT EXISTS idx_meeting_images_status ON meeting_images(upload_status, analysis_status);

-- Note: analysis_result stores JSON blob from OpenClaw image analysis
-- Common analysis_result structure:
-- {
--   "description": "string - description of image content",
--   "labels": ["array", "of", "detected", "objects/concepts"],
--   "text_detected": {"text": "OCR text if any", "confidence": 0.95},
--   "faces": [{"count": N, "detected": true}],
--   "timestamps": {"meeting_time": "approximate meeting time if derivable"},
--   "openclaw_metadata": {"model": "gemini-3-pro-image-preview", "processed_at": "ISO timestamp"}
-- }
