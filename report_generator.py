"""
Voice Assistant Bridge - Report Generator (M6)

Generates three-layer meeting reports:
- Brief: One-paragraph summary (for quick scanning)
- Action: Action items and decisions extracted from transcript
- Deep: Full structured report with timeline, speakers, and details

Uses existing meeting artifacts and transcript pipeline.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from meeting import (
    MeetingStore,
    now_iso,
)

logger = logging.getLogger(__name__)


# Report type constants
REPORT_TYPE_BRIEF = "brief"
REPORT_TYPE_ACTION = "action"
REPORT_TYPE_DEEP = "deep"


@dataclass
class ReportConfig:
    """Configuration for report generation."""
    
    # Brief report settings
    brief_max_sentences: int = 3
    brief_max_chars: int = 500
    
    # Action report settings
    action_keywords: list[str] = field(default_factory=lambda: [
        "需要", "待办", "跟进", "确认", "安排", "完成", "提交",
        "TODO", "Action", "下一步", "计划", "必须", "应该",
        "决定", "决议", "达成一致", "同意", "确定",
    ])
    
    # Deep report settings
    deep_include_timeline: bool = True
    deep_include_speakers: bool = True
    deep_topic_chunk_minutes: int = 5


class ReportGenerator:
    """
    Generate meeting reports from transcripts and artifacts.
    
    Uses refined transcript segments from the transcription pipeline.
    """
    
    def __init__(
        self,
        store: MeetingStore,
        artifacts_dir: Path = Path("artifacts/meetings"),
        config: Optional[ReportConfig] = None,
    ):
        self.store = store
        self.artifacts_dir = artifacts_dir
        self.config = config or ReportConfig()
        
    def generate_all_reports(self, meeting_id: str) -> dict[str, str]:
        """
        Generate all three report types for a meeting.
        
        Returns:
            Dict mapping report type to report content.
        """
        reports_dir = self.artifacts_dir / meeting_id / "reports"
        reports_dir.mkdir(parents=True, exist_ok=True)
        
        reports = {}
        
        # Generate each report type
        reports[REPORT_TYPE_BRIEF] = self.generate_brief_report(meeting_id)
        reports[REPORT_TYPE_ACTION] = self.generate_action_report(meeting_id)
        reports[REPORT_TYPE_DEEP] = self.generate_deep_report(meeting_id)
        
        # Save reports to disk
        for report_type, content in reports.items():
            report_path = reports_dir / f"{report_type}.md"
            report_path.write_text(content, encoding="utf-8")
            logger.info(f"Generated {report_type} report: {report_path}")
        
        return reports
    
    def generate_brief_report(self, meeting_id: str) -> str:
        """
        Generate a brief one-paragraph summary.
        
        Strategy:
        1. Get all transcript segments
        2. Extract first N sentences covering key topics
        3. Combine into concise summary
        """
        segments = self._load_transcript_segments(meeting_id)
        if not segments:
            return f"# 会议简报\n\n*暂无转录内容*\n\n生成时间: {now_iso()}"
        
        # Get meeting metadata
        meeting = self.store.get_meeting(meeting_id)
        duration = self._calculate_duration(segments)
        
        # Extract text from segments
        all_text = " ".join(s["text"] for s in segments)
        
        # Split into sentences
        sentences = self._split_sentences(all_text)
        
        # Take first N sentences, truncated to max_chars
        brief_sentences = sentences[:self.config.brief_max_sentences]
        brief_text = " ".join(brief_sentences)
        
        if len(brief_text) > self.config.brief_max_chars:
            brief_text = brief_text[:self.config.brief_max_chars - 3] + "..."
        
        # Count speakers if available
        speakers = self.store.get_speakers_for_meeting(meeting_id)
        speaker_count = len(speakers) if speakers else 1
        
        # Format report
        lines = [
            "# 会议简报",
            "",
            f"**会议ID**: {meeting_id}",
            f"**时长**: {self._format_duration(duration)}",
            f"**发言人数**: {speaker_count}",
            f"**生成时间**: {now_iso()}",
            "",
            "## 摘要",
            "",
            brief_text,
        ]
        
        return "\n".join(lines)
    
    def generate_action_report(self, meeting_id: str) -> str:
        """
        Generate action items and decisions report.
        
        Strategy:
        1. Scan transcript for action keywords
        2. Extract sentences containing decisions/action items
        3. Group by speaker if available
        """
        segments = self._load_transcript_segments(meeting_id)
        if not segments:
            return f"# 行动项报告\n\n*暂无转录内容*\n\n生成时间: {now_iso()}"
        
        # Get meeting metadata
        duration = self._calculate_duration(segments)
        
        # Find action items
        action_items = self._extract_action_items(segments)
        decisions = self._extract_decisions(segments)
        
        # Format report
        lines = [
            "# 行动项与决议报告",
            "",
            f"**会议ID**: {meeting_id}",
            f"**时长**: {self._format_duration(duration)}",
            f"**生成时间**: {now_iso()}",
            "",
        ]
        
        # Action items section
        lines.append("## 待办事项")
        lines.append("")
        if action_items:
            for idx, item in enumerate(action_items, 1):
                speaker = item.get("speaker_name") or item.get("speaker_cluster_id", "未知")
                lines.append(f"{idx}. [{speaker}] {item['text']}")
                if item.get("timestamp"):
                    lines.append(f"   - 时间: {self._format_timestamp(item['timestamp'])}")
        else:
            lines.append("*未识别到明确的行动项*")
        lines.append("")
        
        # Decisions section
        lines.append("## 决议事项")
        lines.append("")
        if decisions:
            for idx, item in enumerate(decisions, 1):
                speaker = item.get("speaker_name") or item.get("speaker_cluster_id", "未知")
                lines.append(f"{idx}. [{speaker}] {item['text']}")
                if item.get("timestamp"):
                    lines.append(f"   - 时间: {self._format_timestamp(item['timestamp'])}")
        else:
            lines.append("*未识别到明确的决议*")
        lines.append("")
        
        return "\n".join(lines)
    
    def generate_deep_report(self, meeting_id: str) -> str:
        """
        Generate comprehensive deep report.
        
        Includes:
        - Meeting overview
        - Timeline with topics
        - Speaker statistics
        - Full transcript with timestamps
        """
        segments = self._load_transcript_segments(meeting_id)
        meeting = self.store.get_meeting(meeting_id)
        
        if not segments:
            return f"# 详细报告\n\n*暂无转录内容*\n\n生成时间: {now_iso()}"
        
        # Calculate metrics
        duration = self._calculate_duration(segments)
        speakers = self.store.get_speakers_for_meeting(meeting_id)
        
        # Get images
        images = self.store.get_meeting_images(meeting_id)
        
        # Build timeline
        timeline = self._build_timeline(segments, self.config.deep_topic_chunk_minutes)
        
        # Format report
        lines = [
            "# 会议详细报告",
            "",
            "---",
            "",
            "## 会议概览",
            "",
            f"| 属性 | 值 |",
            f"|------|-----|",
            f"| 会议ID | {meeting_id} |",
            f"| 开始时间 | {meeting.get('started_at', 'N/A') if meeting else 'N/A'} |",
            f"| 结束时间 | {meeting.get('ended_at', 'N/A') if meeting else 'N/A'} |",
            f"| 总时长 | {self._format_duration(duration)} |",
            f"| 发言人数 | {len(speakers) if speakers else 1} |",
            f"| 转录段数 | {len(segments)} |",
            f"| 图片数量 | {len(images) if images else 0} |",
            "",
        ]
        
        # Speaker statistics
        if speakers:
            lines.append("## 发言人统计")
            lines.append("")
            lines.append("| 发言人 | 段数 | 平均置信度 |")
            lines.append("|--------|------|------------|")
            for speaker in speakers:
                name = speaker.get("speaker_name") or speaker.get("speaker_cluster_id", "未知")
                count = speaker.get("segment_count", 0)
                conf = speaker.get("avg_confidence", 0)
                lines.append(f"| {name} | {count} | {conf:.2f} |")
            lines.append("")
        
        # Timeline
        if self.config.deep_include_timeline:
            lines.append("## 时间轴")
            lines.append("")
            for chunk in timeline:
                start_time = self._format_timestamp(chunk["start"])
                end_time = self._format_timestamp(chunk["end"])
                preview = chunk["text_preview"]
                lines.append(f"### [{start_time} - {end_time}]")
                lines.append("")
                lines.append(f"{preview}")
                lines.append("")
        
        # Full transcript
        lines.append("## 完整转录")
        lines.append("")
        lines.append("```")
        for seg in segments:
            ts = self._format_timestamp(seg["start_ts"])
            speaker = seg.get("speaker_name") or seg.get("speaker_cluster_id", "")
            speaker_prefix = f"[{speaker}] " if speaker else ""
            lines.append(f"[{ts}] {speaker_prefix}{seg['text']}")
        lines.append("```")
        lines.append("")
        
        # Images section
        if images:
            lines.append("## 会议图片")
            lines.append("")
            for img in images:
                captured = img.get("captured_at", "N/A")
                lines.append(f"- 图片: {img.get('filename', 'unknown')} (拍摄于 {captured})")
                if img.get("analysis_result"):
                    try:
                        analysis = json.loads(img["analysis_result"])
                        if analysis.get("description"):
                            lines.append(f"  - 描述: {analysis['description']}")
                    except json.JSONDecodeError:
                        pass
            lines.append("")
        
        # Footer
        lines.append("---")
        lines.append(f"*报告生成时间: {now_iso()}*")
        lines.append("")
        
        return "\n".join(lines)
    
    # --- Helper methods ---
    
    def _load_transcript_segments(self, meeting_id: str) -> list[dict]:
        """Load transcript segments from store."""
        segments = self.store.get_refined_segments(meeting_id)
        if not segments:
            # Try loading from refined.jsonl
            transcript_path = self.artifacts_dir / meeting_id / "transcript" / "refined.jsonl"
            if transcript_path.exists():
                segments = []
                with open(transcript_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line:
                            segments.append(json.loads(line))
        return segments
    
    def _split_sentences(self, text: str) -> list[str]:
        """Split text into sentences (Chinese + English aware)."""
        # Pattern for Chinese and English sentence boundaries
        pattern = r'[。！？.!?]+'
        sentences = re.split(pattern, text)
        # Filter empty and clean up
        return [s.strip() for s in sentences if s.strip()]
    
    def _extract_action_items(self, segments: list[dict]) -> list[dict]:
        """Extract action items from segments."""
        action_keywords = [
            "需要", "待办", "跟进", "确认", "安排", "完成", "提交",
            "TODO", "Action", "下一步", "计划", "必须", "应该",
            "要去做", "去完成", "负责", "执行",
        ]
        return self._find_keyword_segments(segments, action_keywords)
    
    def _extract_decisions(self, segments: list[dict]) -> list[dict]:
        """Extract decisions from segments."""
        decision_keywords = [
            "决定", "决议", "达成一致", "同意", "确定", "定下",
            "敲定", "确认", "共识", "最终", "结论",
        ]
        return self._find_keyword_segments(segments, decision_keywords)
    
    def _find_keyword_segments(self, segments: list[dict], keywords: list[str]) -> list[dict]:
        """Find segments containing any of the keywords."""
        results = []
        for seg in segments:
            text = seg.get("text", "")
            for kw in keywords:
                if kw.lower() in text.lower():
                    results.append({
                        "text": text,
                        "timestamp": seg.get("start_ts"),
                        "speaker_cluster_id": seg.get("speaker_cluster_id"),
                        "speaker_name": seg.get("speaker_name"),
                    })
                    break  # Only add once per segment
        return results[:20]  # Limit to 20 items
    
    def _calculate_duration(self, segments: list[dict]) -> float:
        """Calculate total duration in seconds."""
        if not segments:
            return 0.0
        last_end = max(s.get("end_ts", 0) for s in segments)
        return last_end
    
    def _format_duration(self, seconds: float) -> str:
        """Format duration as HH:MM:SS."""
        hours = int(seconds // 3600)
        minutes = int((seconds % 3600) // 60)
        secs = int(seconds % 60)
        if hours > 0:
            return f"{hours:02d}:{minutes:02d}:{secs:02d}"
        return f"{minutes:02d}:{secs:02d}"
    
    def _format_timestamp(self, seconds: float) -> str:
        """Format timestamp as MM:SS."""
        minutes = int(seconds // 60)
        secs = int(seconds % 60)
        return f"{minutes:02d}:{secs:02d}"
    
    def _build_timeline(self, segments: list[dict], chunk_minutes: int) -> list[dict]:
        """Build timeline chunks from segments."""
        if not segments:
            return []
        
        chunk_seconds = chunk_minutes * 60
        chunks = []
        current_chunk = None
        
        for seg in segments:
            start = seg.get("start_ts", 0)
            chunk_idx = int(start // chunk_seconds)
            
            if current_chunk is None or current_chunk["idx"] != chunk_idx:
                if current_chunk:
                    chunks.append(current_chunk)
                current_chunk = {
                    "idx": chunk_idx,
                    "start": chunk_idx * chunk_seconds,
                    "end": (chunk_idx + 1) * chunk_seconds,
                    "segments": [],
                    "text_preview": "",
                }
            
            current_chunk["segments"].append(seg)
        
        if current_chunk:
            chunks.append(current_chunk)
        
        # Build text previews
        for chunk in chunks:
            texts = [s.get("text", "") for s in chunk["segments"][:5]]
            preview = " ".join(texts)
            if len(preview) > 200:
                preview = preview[:200] + "..."
            chunk["text_preview"] = preview
        
        return chunks


# CLI interface for testing
if __name__ == "__main__":
    import sys
    import sqlite3
    
    if len(sys.argv) < 2:
        print("Usage: python report_generator.py <meeting_id> [brief|action|deep|all]")
        sys.exit(1)
    
    meeting_id = sys.argv[1]
    report_type = sys.argv[2] if len(sys.argv) > 2 else "all"
    
    # Initialize
    db_path = Path("bridge_state.db")
    store = MeetingStore(db_path)
    generator = ReportGenerator(store)
    
    if report_type == "all":
        reports = generator.generate_all_reports(meeting_id)
        for rt, content in reports.items():
            print(f"\n{'='*60}")
            print(f"Report: {rt}")
            print('='*60)
            print(content)
    elif report_type == REPORT_TYPE_BRIEF:
        print(generator.generate_brief_report(meeting_id))
    elif report_type == REPORT_TYPE_ACTION:
        print(generator.generate_action_report(meeting_id))
    elif report_type == REPORT_TYPE_DEEP:
        print(generator.generate_deep_report(meeting_id))
    else:
        print(f"Unknown report type: {report_type}")
        sys.exit(1)
