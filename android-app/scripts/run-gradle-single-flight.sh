#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

LOCK_DIR="${TMPDIR:-/tmp}/telegram-drive-gradle.lock"
PID_FILE="${LOCK_DIR}/pid"

acquire_lock() {
  if mkdir "${LOCK_DIR}" 2>/dev/null; then
    echo "$$" > "${PID_FILE}"
    return 0
  fi

  if [[ -f "${PID_FILE}" ]]; then
    local lock_pid
    lock_pid="$(cat "${PID_FILE}" 2>/dev/null || echo "")"
    if [[ -n "${lock_pid}" ]] && ! kill -0 "${lock_pid}" 2>/dev/null; then
      echo "Cleaning up stale Gradle lock directory (PID ${lock_pid} not running)."
      rm -rf "${LOCK_DIR}" 2>/dev/null || true
      if mkdir "${LOCK_DIR}" 2>/dev/null; then
        echo "$$" > "${PID_FILE}"
        return 0
      fi
    fi
  fi

  echo "Another Telegram-Drive Gradle invocation is already active." >&2
  exit 75
}

acquire_lock

trap 'rm -rf "${LOCK_DIR}" 2>/dev/null || true' EXIT INT TERM

cd "${PROJECT_DIR}"
exec ./gradlew "$@"
