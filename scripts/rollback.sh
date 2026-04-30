#!/usr/bin/env bash

set -euo pipefail

ECS_CLUSTER="${ECS_CLUSTER:?ECS_CLUSTER is required}"
ECS_SERVICE="${ECS_SERVICE:?ECS_SERVICE is required}"
PREVIOUS_TASK_DEFINITION="${PREVIOUS_TASK_DEFINITION:?PREVIOUS_TASK_DEFINITION is required}"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

log "Rolling back ECS service to previous task definition..."

aws ecs update-service \
  --cluster "$ECS_CLUSTER" \
  --service "$ECS_SERVICE" \
  --task-definition "$PREVIOUS_TASK_DEFINITION"

aws ecs wait services-stable \
  --cluster "$ECS_CLUSTER" \
  --services "$ECS_SERVICE"

log "Rollback completed successfully."