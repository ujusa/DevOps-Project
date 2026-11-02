#!/usr/bin/env bash
# Usage: bash evaluate.sh [user_outputs.json] [ground_truth.json]
set -euo pipefail
cd "$(dirname "$0")"
[ -f python/northwind.db ] || python3 python/build_db.py
gradle -q --console=plain evaluate -PappArgs="$*"
