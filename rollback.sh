#!/bin/bash
# Voice Assistant Bridge - Rollback Script
# Usage: ./rollback.sh [version]
#
# Reverts to a previous release version.

set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# Get target version
TARGET_VERSION="${1:-}"
if [ -z "$TARGET_VERSION" ]; then
    log_info "Available releases:"
    git tag -l "v*" | sort -V | tail -10
    echo ""
    read -p "Enter version to rollback to (without 'v'): " TARGET_VERSION
fi

TAG_NAME="v$TARGET_VERSION"

cd "$REPO_DIR"

# Check if tag exists
if ! git tag -l | grep -q "^$TAG_NAME$"; then
    log_error "Tag $TAG_NAME not found"
    exit 1
fi

# Confirm rollback
log_warn "This will reset the working directory to $TAG_NAME"
read -p "Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    log_info "Rollback cancelled"
    exit 0
fi

# Stash any local changes
if ! git diff-index --quiet HEAD --; then
    log_info "Stashing local changes..."
    git stash push -m "pre-rollback-stash-$(date +%s)"
fi

# Checkout the tag
log_info "Checking out $TAG_NAME..."
git checkout "$TAG_NAME"

log_info "Rollback to $TAG_NAME complete"
log_info "To return to main: git checkout main"
