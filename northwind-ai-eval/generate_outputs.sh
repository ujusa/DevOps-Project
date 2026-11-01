#!/usr/bin/env bash
# Usage: bash generate_outputs.sh [user_outputs.json] [ground_truth.json] [--runs N] [--baseline]
# Requires OPENAI_API_KEY (optional: OPENAI_MODEL, OPENAI_BASE_URL).
set -euo pipefail
cd "$(dirname "$0")"
[ -f python/northwind.db ] || python3 python/build_db.py
gradle -q --console=plain generateOutputs -PappArgs="$*"
