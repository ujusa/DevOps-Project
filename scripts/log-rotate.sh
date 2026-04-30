#!/usr/bin/env bash

set -euo pipefail

LOG_DIR="${LOG_DIR:-/var/log/nodejs-app}"
ARCHIVE_DIR="${ARCHIVE_DIR:-/var/log/nodejs-app/archive}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

mkdir -p "$ARCHIVE_DIR"

log "Compressing old logs..."

find "$LOG_DIR" -type f -name "*.log" -mtime +1 -exec gzip {} \;

log "Moving compressed logs to archive..."

find "$LOG_DIR" -type f -name "*.gz" -exec mv {} "$ARCHIVE_DIR" \;

log "Deleting archived logs older than $RETENTION_DAYS days..."

find "$ARCHIVE_DIR" -type f -name "*.gz" -mtime +"$RETENTION_DAYS" -delete

log "Log rotation completed."