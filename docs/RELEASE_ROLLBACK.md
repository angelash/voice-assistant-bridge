# Release & Rollback Guide

## Overview

This document covers release procedures and rollback strategies for Voice Assistant Bridge V2.

## Release Process

### Prerequisites

1. All tests passing (`pytest test_v2_api.py test_stability.py`)
2. Working tree clean (no uncommitted changes)
3. Milestone status documents updated (M1_STATUS.md - M6_STATUS.md)

### Creating a Release

```bash
# Option 1: Interactive
./release.sh

# Option 2: Specify version
./release.sh 1.0.0

# Option 3: Date-based version (auto-generated)
./release.sh

# Option 4: With push to remote
./release.sh 1.0.0 --push

# Option 5: Skip tests (emergency release only)
./release.sh 1.0.0 --skip-tests
```

### Release Script Actions

1. Validates version format
2. Checks for uncommitted changes
3. Pulls latest from remote
4. Runs test suite (unless `--skip-tests`)
5. Updates VERSION file
6. Generates CHANGELOG entry
7. Creates annotated git tag
8. Commits release metadata
9. Optionally pushes to remote (`--push`)

## Versioning Scheme

We use **date-based versioning** for internal releases:

- Format: `YYYY.MM.DD` (e.g., `2026.03.14`)
- Multiple releases per day: `YYYY.MM.DD.N` (e.g., `2026.03.14.2`)

For public releases, use **semantic versioning**:

- Format: `MAJOR.MINOR.PATCH` (e.g., `1.0.0`)
- Major: Breaking changes
- Minor: New features, backward compatible
- Patch: Bug fixes

## Rollback Procedures

### Quick Rollback

```bash
# List recent releases
./rollback.sh

# Or specify version
./rollback.sh 1.0.0
```

### Manual Rollback

```bash
# 1. Check available tags
git tag -l "v*"

# 2. Checkout specific version
git checkout v1.0.0

# 3. If needed, create rollback branch
git checkout -b rollback-to-1.0.0
```

### Database Rollback

If database schema changed between versions:

```bash
# 1. Backup current database
cp bridge_state.db bridge_state.db.backup-$(date +%s)

# 2. Check migration history
ls -la migrations/

# 3. If needed, restore from backup
# cp bridge_state.db.backup-XXXX bridge_state.db
```

### Configuration Rollback

```bash
# Config is version-controlled
git checkout v1.0.0 -- config.json
```

## Deployment Checklist

### Pre-Deployment

- [ ] All tests passing
- [ ] Version bumped in VERSION file
- [ ] CHANGELOG.md updated
- [ ] Milestone status docs updated
- [ ] No pending database migrations (or migrations ready)

### Deployment Steps

```bash
# 1. Pull latest
git pull origin main

# 2. Verify version
cat VERSION

# 3. Backup database
cp bridge_state.db bridge_state.db.backup

# 4. Run any pending migrations
python -m migrations.apply_all

# 5. Restart services
# (Windows: restart the Python process)
# (Linux: systemctl restart voice-bridge)
```

### Post-Deployment Verification

- [ ] API health check: `curl http://localhost:8080/health`
- [ ] Meeting creation works
- [ ] Audio upload works
- [ ] Event streaming works
- [ ] Cleanup guard runs correctly

## Rollback Scenarios

### Scenario 1: API Breaking Changes

**Symptoms**: Clients getting 500 errors, incompatible responses

**Resolution**:
1. Rollback to previous version: `./rollback.sh PREVIOUS_VERSION`
2. Restart services
3. Notify clients of temporary outage

### Scenario 2: Database Corruption

**Symptoms**: SQLite errors, missing data

**Resolution**:
1. Stop services immediately
2. Restore from backup: `cp bridge_state.db.backup bridge_state.db`
3. Rollback to known-good version
4. Restart services

### Scenario 3: Performance Degradation

**Symptoms**: Slow responses, timeouts

**Resolution**:
1. Check logs for errors
2. If due to recent change, rollback: `./rollback.sh PREVIOUS_VERSION`
3. If data-related, run cleanup: `python cleanup_guard.py`
4. Monitor performance after fix

### Scenario 4: Storage Full

**Symptoms**: Disk space warnings, write failures

**Resolution**:
1. Run cleanup with aggressive settings:
   ```bash
   python cleanup_guard.py --retention-days 3
   ```
2. If still full, manually delete old meetings:
   ```bash
   # List old meetings
   ls -la artifacts/meetings/
   
   # Delete specific meeting (be careful!)
   rm -rf artifacts/meetings/mtg-XXX
   ```

## Emergency Contacts

- **Development Lead**: Check COMMITTERS file
- **Issue Tracker**: GitHub Issues
- **Documentation**: README.md, DETAILED_DESIGN_V2.md

## Recovery Scripts

### Reset to Clean State

```bash
# WARNING: This destroys all data!
rm -f bridge_state.db
rm -rf artifacts/meetings/*
python -c "from meeting import MeetingStore; MeetingStore(Path('bridge_state.db'))"
```

### Export Meeting Data

```bash
# Export all meetings to JSON
python -c "
from meeting import MeetingStore
from pathlib import Path
import json

store = MeetingStore(Path('bridge_state.db'))
meetings = store.list_meetings(limit=1000)
print(json.dumps(meetings, indent=2, default=str))
" > meetings_export.json
```

### Import Meeting Data

```bash
# Import from JSON export
python -c "
from meeting import MeetingStore
from pathlib import Path
import json

store = MeetingStore(Path('bridge_state.db'))
with open('meetings_export.json') as f:
    meetings = json.load(f)
# Note: Import logic depends on export format
"
```

---

*Last updated: 2026-03-14*
*Version: M6*
