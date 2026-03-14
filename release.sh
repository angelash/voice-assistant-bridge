#!/bin/bash
# Voice Assistant Bridge - Release Script
# Usage: ./release.sh [version] [options]
#
# Options:
#   --skip-tests    Skip running tests before release
#   --dry-run       Show what would be done without making changes
#   --push          Push to remote after creating tag

set -e

# Configuration
REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
CHANGELOG_FILE="CHANGELOG.md"
VERSION_FILE="VERSION"

# Parse arguments
VERSION="${1:-}"
SKIP_TESTS=false
DRY_RUN=false
PUSH=false

for arg in "$@"; do
    case $arg in
        --skip-tests) SKIP_TESTS=true ;;
        --dry-run) DRY_RUN=true ;;
        --push) PUSH=true ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Validate version
if [ -z "$VERSION" ]; then
    # Auto-generate version based on date
    VERSION=$(date +%Y.%m.%d)
    log_info "No version specified, using date-based version: $VERSION"
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && [[ ! "$VERSION" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}$ ]]; then
    log_error "Invalid version format: $VERSION"
    log_info "Expected format: X.Y.Z or YYYY.MM.DD"
    exit 1
fi

cd "$REPO_DIR"

# Check for uncommitted changes
if [ "$DRY_RUN" = false ]; then
    if ! git diff-index --quiet HEAD --; then
        log_error "Uncommitted changes detected. Please commit or stash first."
        git status --short
        exit 1
    fi
fi

# Pull latest
log_info "Pulling latest changes..."
if [ "$DRY_RUN" = false ]; then
    git pull origin main || log_warn "Could not pull from origin"
fi

# Run tests
if [ "$SKIP_TESTS" = false ]; then
    log_info "Running tests..."
    if [ "$DRY_RUN" = false ]; then
        python -m pytest test_v2_api.py test_stability.py -v --tb=short || {
            log_error "Tests failed. Fix issues before release."
            exit 1
        }
    else
        log_info "[DRY RUN] Would run: python -m pytest test_v2_api.py test_stability.py -v"
    fi
else
    log_warn "Skipping tests (--skip-tests)"
fi

# Update version file
log_info "Updating version file..."
if [ "$DRY_RUN" = false ]; then
    echo "$VERSION" > "$VERSION_FILE"
    git add "$VERSION_FILE"
fi

# Generate changelog entry
log_info "Generating changelog..."
if [ "$DRY_RUN" = false ]; then
    # Get commits since last tag
    LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
    if [ -n "$LAST_TAG" ]; then
        CHANGES=$(git log --oneline "$LAST_TAG"..HEAD)
    else
        CHANGES=$(git log --oneline -20)
    fi
    
    # Prepend to changelog
    CHANGELOG_ENTRY="## [$VERSION] - $(date +%Y-%m-%d)

### Changes
$CHANGES

"
    
    if [ -f "$CHANGELOG_FILE" ]; then
        # Insert after header
        sed -i "1a\\$CHANGELOG_ENTRY" "$CHANGELOG_FILE"
    else
        echo "# Changelog" > "$CHANGELOG_FILE"
        echo "$CHANGELOG_ENTRY" >> "$CHANGELOG_FILE"
    fi
    
    git add "$CHANGELOG_FILE"
fi

# Create git tag
TAG_NAME="v$VERSION"
log_info "Creating tag: $TAG_NAME"
if [ "$DRY_RUN" = false ]; then
    git tag -a "$TAG_NAME" -m "Release $TAG_NAME"
fi

# Commit version update
log_info "Committing release..."
if [ "$DRY_RUN" = false ]; then
    git commit -m "chore: release $TAG_NAME"
fi

# Push to remote
if [ "$PUSH" = true ]; then
    log_info "Pushing to remote..."
    if [ "$DRY_RUN" = false ]; then
        git push origin main
        git push origin "$TAG_NAME"
    else
        log_info "[DRY RUN] Would push: git push origin main && git push origin $TAG_NAME"
    fi
else
    log_info "Skipping push (--push not specified)"
    log_info "To push later: git push origin main && git push origin $TAG_NAME"
fi

log_info "Release $TAG_NAME created successfully!"

if [ "$DRY_RUN" = true ]; then
    log_warn "This was a DRY RUN. No changes were made."
fi
