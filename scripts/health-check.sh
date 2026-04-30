#!/usr/bin/env bash

set -euo pipefail

APP_URL="${APP_URL:?APP_URL is required}"
MAX_RESPONSE_TIME="${MAX_RESPONSE_TIME:-2}"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log "Checking application health: $APP_URL"

HTTP_CODE=$(curl -o /dev/null -s -w "%{http_code}" "$APP_URL")
RESPONSE_TIME=$(curl -o /dev/null -s -w "%{time_total}" "$APP_URL")

if [[ "$HTTP_CODE" != "200" ]]; then
  log "Health check failed. HTTP code: $HTTP_CODE"
  exit 1
fi

if awk "BEGIN {exit !($RESPONSE_TIME > $MAX_RESPONSE_TIME)}"; then
  log "Health check failed. Response time too high: ${RESPONSE_TIME}s"
  exit 2
fi

log "Health check passed. HTTP $HTTP_CODE, response time ${RESPONSE_TIME}s"
exit 0