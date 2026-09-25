#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scope="${1:-all}"

run_node() {
  (cd "$root" && pnpm typecheck && pnpm lint && pnpm test)
}

run_legacy() {
  (cd "$root/legacy" && pnpm install --frozen-lockfile && pnpm typecheck && pnpm test)
}

run_android() {
  (cd "$root/apps/dhd-android" && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain)
}

run_benchmark() {
  (cd "$root/apps/coordinate-benchmark-android" && ./gradlew :app:assembleDebug --console=plain)
}

case "$scope" in
  node) run_node ;;
  legacy) run_legacy ;;
  android) run_android ;;
  benchmark) run_benchmark ;;
  all)
    run_node
    run_legacy
    run_android
    run_benchmark
    ;;
  *)
    echo "usage: scripts/verify.sh [all|node|legacy|android|benchmark]" >&2
    exit 2
    ;;
esac
