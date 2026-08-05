#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ANDROID_PROJECT_DIR="${REPO_ROOT}/android-app"
GRADLE_RUNNER="${ANDROID_PROJECT_DIR}/scripts/run-gradle-single-flight.sh"
GRADLE_WRAPPER="${ANDROID_PROJECT_DIR}/gradlew"
LOCAL_CONFIG="${REPO_ROOT}/.firebase-distribution.local"
TELEGRAM_API_PROPERTIES="${ANDROID_PROJECT_DIR}/telegram-api.properties"
TELEGRAM_DATA_SOURCE="${TELEGRAM_DATA_SOURCE:-real}"
GRADLE_TIMEOUT_SECONDS="${GRADLE_TIMEOUT_SECONDS:-1800}"

BUILD_MODE="full"
TASK_NAME=""
APK_PATH=""
TEMP_DIR=""
RELEASE_NOTES_FILE=""
FIREBASE_APP_ID_VALUE="${FIREBASE_APP_ID:-}"
FIREBASE_TESTER_EMAIL_VALUE="${FIREBASE_TESTER_EMAIL:-}"

declare -a CHECK_RESULTS=()
declare -a FIREBASE_COMMAND=()

usage() {
    cat <<'USAGE'
Usage:
  ./scripts/distribute-local.sh "Task name"
  ./scripts/distribute-local.sh "Task name" --fast

Builds the Android debug APK locally and uploads it to Firebase App Distribution.

Options:
  --fast, -f  Skip unit tests and lint; build and upload only.
  --help, -h  Show this help.

Configuration precedence:
  Environment variables, .firebase-distribution.local, google-services.json App ID.

Telegram data source:
  TELEGRAM_DATA_SOURCE=real  Use the real TDLib gateway (default).
  TELEGRAM_DATA_SOURCE=fake  Use the local fake repository for UI-only loops.
USAGE
}

die() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

info() {
    printf '%s\n' "$*"
}

trim_config_value() {
    local value="$1"

    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "${#value}" -ge 2 ]]; then
        case "${value}" in
            \"*\") value="${value:1:${#value}-2}" ;;
            \'*\') value="${value:1:${#value}-2}" ;;
        esac
    fi
    printf '%s' "${value}"
}

read_local_config_value() {
    local wanted_key="$1"
    local line=""
    local key=""

    [[ -f "${LOCAL_CONFIG}" ]] || return 0
    while IFS= read -r line || [[ -n "${line}" ]]; do
        line="${line#"${line%%[![:space:]]*}"}"
        [[ -z "${line}" || "${line}" == \#* ]] && continue
        [[ "${line}" == *=* ]] || continue

        key="${line%%=*}"
        key="$(trim_config_value "${key}")"
        [[ "${key}" == "${wanted_key}" ]] || continue
        trim_config_value "${line#*=}"
        return 0
    done < "${LOCAL_CONFIG}"
}

validate_telegram_configuration() {
    local api_id=""
    local api_hash=""

    [[ "${TELEGRAM_DATA_SOURCE}" == "real" ]] || return 0
    [[ -f "${TELEGRAM_API_PROPERTIES}" ]] || die \
        "Real Telegram build requires ${TELEGRAM_API_PROPERTIES}. Copy the example and fill apiId/apiHash."

    api_id="$(awk -F= '/^[[:space:]]*apiId[[:space:]]*=/ { value=$2; gsub(/[[:space:]]/, "", value); print value; exit }' "${TELEGRAM_API_PROPERTIES}")"
    api_hash="$(awk -F= '/^[[:space:]]*apiHash[[:space:]]*=/ { value=$2; gsub(/[[:space:]]/, "", value); print value; exit }' "${TELEGRAM_API_PROPERTIES}")"

    [[ "${api_id}" =~ ^[0-9]+$ && "${api_id}" != "0" ]] || die \
        "telegram-api.properties contains an invalid apiId."
    [[ "${api_hash}" =~ ^[0-9A-Fa-f]{32}$ ]] || die \
        "telegram-api.properties contains an invalid apiHash format; expected 32 hexadecimal characters."
}

find_google_services_json() {
    local candidate=""
    local known_path=""

    for known_path in \
        "${ANDROID_PROJECT_DIR}/app/google-services.json" \
        "${ANDROID_PROJECT_DIR}/google-services.json" \
        "${REPO_ROOT}/google-services.json"; do
        if [[ -f "${known_path}" ]]; then
            printf '%s' "${known_path}"
            return 0
        fi
    done

    candidate="$(find "${ANDROID_PROJECT_DIR}" -type f -name google-services.json \
        -not -path '*/build/*' -print -quit 2>/dev/null || true)"
    if [[ -n "${candidate}" ]]; then
        printf '%s' "${candidate}"
    fi
    return 0
}

cleanup() {
    if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
        rm -rf "${TEMP_DIR}"
    fi
}

run_with_timeout() {
    # GNU timeout creates a separate process group; on macOS that can suspend
    # Gradle while it restores terminal settings after a successful build.
    if [[ "$(uname -s)" == "Darwin" && -t 1 && -t 2 ]] && command -v perl >/dev/null 2>&1; then
        perl -e 'alarm shift; exec @ARGV' "${GRADLE_TIMEOUT_SECONDS}" "$@"
    elif command -v gtimeout >/dev/null 2>&1; then
        gtimeout "${GRADLE_TIMEOUT_SECONDS}" "$@"
    elif command -v timeout >/dev/null 2>&1; then
        timeout "${GRADLE_TIMEOUT_SECONDS}" "$@"
    elif command -v perl >/dev/null 2>&1; then
        perl -e 'alarm shift; exec @ARGV' "${GRADLE_TIMEOUT_SECONDS}" "$@"
    else
        die "No timeout command is available; install coreutils or ensure perl is available."
    fi
}

run_gradle_step() {
    local label="$1"
    shift

    info "Running ${label}..."
    if ! (
        cd "${ANDROID_PROJECT_DIR}"
        run_with_timeout "${GRADLE_RUNNER}" "$@" \
            --no-daemon \
            --no-configuration-cache \
            --no-parallel \
            --max-workers=1 \
            --console=plain \
            --stacktrace
    ); then
        die "${label} failed; Firebase upload was not attempted."
    fi
    CHECK_RESULTS+=("- ${label}: PASS")
}

find_debug_apk() {
    local candidate=""
    local preferred=""
    local count=0

    while IFS= read -r candidate; do
        [[ -n "${candidate}" ]] || continue
        count=$((count + 1))
        if [[ "${candidate}" == */debug/app-debug.apk ]]; then
            preferred="${candidate}"
        elif [[ -z "${preferred}" && "${candidate}" == */debug/*.apk ]]; then
            preferred="${candidate}"
        elif [[ -z "${preferred}" ]]; then
            preferred="${candidate}"
        fi
    done < <(
        find "${ANDROID_PROJECT_DIR}/app/build/outputs/apk" \
            -type f -name '*.apk' \
            -not -path '*/androidTest/*' \
            -not -path '*/test/*' \
            -print 2>/dev/null | sort
    )

    [[ "${count}" -gt 0 && -n "${preferred}" ]] || \
        die "No debug APK was found below android-app/app/build/outputs/apk."
    APK_PATH="${preferred}"
    if [[ "${count}" -gt 1 ]]; then
        info "Found ${count} eligible APKs; selected ${APK_PATH}."
    fi
}

write_release_notes() {
    local branch=""
    local commit_hash=""
    local commit_message=""
    local working_tree=""
    local result=""

    branch="$(git -C "${REPO_ROOT}" symbolic-ref --short -q HEAD 2>/dev/null || true)"
    if [[ -z "${branch}" ]]; then
        branch="DETACHED ($(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || printf 'unknown'))"
    fi
    commit_hash="$(git -C "${REPO_ROOT}" rev-parse --short HEAD 2>/dev/null || printf 'unknown')"
    commit_message="$(git -C "${REPO_ROOT}" log -1 --pretty=%s 2>/dev/null || printf 'unknown')"
    if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain 2>/dev/null || true)" ]]; then
        working_tree="DIRTY - contains uncommitted changes"
    else
        working_tree="CLEAN"
    fi

    {
        printf 'Task: %s\n' "${TASK_NAME}"
        printf 'Branch: %s\n' "${branch}"
        printf 'Commit: %s\n' "${commit_hash}"
        printf 'Commit message: %s\n' "${commit_message}"
        printf 'Working tree: %s\n' "${working_tree}"
        printf '\nBuild mode: %s\n' "${BUILD_MODE}"
        printf 'Telegram data source: %s\n' "${TELEGRAM_DATA_SOURCE}"
        printf 'Checks:\n'
        for result in "${CHECK_RESULTS[@]}"; do
            printf '%s\n' "${result}"
        done
        printf '\nGenerated locally: %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
    } > "${RELEASE_NOTES_FILE}"

    RELEASE_BRANCH="${branch}"
    RELEASE_COMMIT="${commit_hash}"
    RELEASE_WORKING_TREE="${working_tree}"
}

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --help|-h)
            usage
            exit 0
            ;;
        --fast|-f)
            BUILD_MODE="fast"
            ;;
        --)
            shift
            [[ "$#" -eq 1 ]] || die "Expected exactly one task name."
            TASK_NAME="$1"
            shift
            break
            ;;
        -*)
            die "Unknown option: $1 (use --help for usage)."
            ;;
        *)
            [[ -z "${TASK_NAME}" ]] || die "Expected exactly one task name."
            TASK_NAME="$1"
            ;;
    esac
    shift
done

[[ -n "${TASK_NAME}" ]] || {
    usage >&2
    exit 2
}
[[ -d "${ANDROID_PROJECT_DIR}" ]] || die "Android project not found at ${ANDROID_PROJECT_DIR}."
[[ -x "${GRADLE_WRAPPER}" ]] || die "Gradle wrapper is missing or not executable: ${GRADLE_WRAPPER}."
[[ -x "${GRADLE_RUNNER}" ]] || die "Gradle single-flight runner is missing or not executable: ${GRADLE_RUNNER}."
[[ "${GRADLE_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]*$ ]] || \
    die "GRADLE_TIMEOUT_SECONDS must be a positive integer."
case "${TELEGRAM_DATA_SOURCE}" in
    real|fake) ;;
    *) die "TELEGRAM_DATA_SOURCE must be real or fake." ;;
esac
validate_telegram_configuration

if [[ -z "${FIREBASE_APP_ID_VALUE}" && -f "${LOCAL_CONFIG}" ]]; then
    FIREBASE_APP_ID_VALUE="$(read_local_config_value FIREBASE_APP_ID)"
fi
if [[ -z "${FIREBASE_TESTER_EMAIL_VALUE}" && -f "${LOCAL_CONFIG}" ]]; then
    FIREBASE_TESTER_EMAIL_VALUE="$(read_local_config_value FIREBASE_TESTER_EMAIL)"
fi

if [[ -z "${FIREBASE_APP_ID_VALUE}" ]]; then
    GOOGLE_SERVICES_JSON="$(find_google_services_json)"
    if [[ -n "${GOOGLE_SERVICES_JSON}" ]]; then
        FIREBASE_APP_ID_VALUE="$(sed -n 's/.*"mobilesdk_app_id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "${GOOGLE_SERVICES_JSON}" | sed -n '1p')"
    fi
fi

[[ -n "${FIREBASE_APP_ID_VALUE}" ]] || die \
    "FIREBASE_APP_ID is missing. Set it in the environment or .firebase-distribution.local."
[[ -n "${FIREBASE_TESTER_EMAIL_VALUE}" ]] || die \
    "FIREBASE_TESTER_EMAIL is missing. Set it in the environment or .firebase-distribution.local."

if command -v firebase >/dev/null 2>&1; then
    FIREBASE_COMMAND=(firebase)
    firebase --version >/dev/null 2>&1 || die "Firebase CLI is installed but could not run."
elif command -v npx >/dev/null 2>&1; then
    FIREBASE_COMMAND=(npx --yes firebase-tools)
    "${FIREBASE_COMMAND[@]}" --version >/dev/null 2>&1 || die \
        "Firebase CLI is unavailable. Install firebase-tools or run: firebase login"
else
    die "Firebase CLI is unavailable. Install Firebase CLI or ensure npx is available."
fi

TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/telegram-drive-distribution.XXXXXX")"
RELEASE_NOTES_FILE="${TEMP_DIR}/release-notes.txt"
trap cleanup EXIT INT TERM

if [[ "${BUILD_MODE}" == "full" ]]; then
    run_gradle_step "Unit tests" :app:testDebugUnitTest "-PtelegramDataSource=${TELEGRAM_DATA_SOURCE}"
    run_gradle_step "Android lint" :app:lintDebug "-PtelegramDataSource=${TELEGRAM_DATA_SOURCE}"
fi
run_gradle_step "Debug APK build" :app:assembleDebug "-PtelegramDataSource=${TELEGRAM_DATA_SOURCE}"
CHECK_RESULTS+=('- Debug APK discovery: PASS')
find_debug_apk
write_release_notes

info "Uploading ${APK_PATH} to Firebase App Distribution..."
if ! (
    cd "${REPO_ROOT}"
    "${FIREBASE_COMMAND[@]}" appdistribution:distribute "${APK_PATH}" \
        --app "${FIREBASE_APP_ID_VALUE}" \
        --testers "${FIREBASE_TESTER_EMAIL_VALUE}" \
        --release-notes-file "${RELEASE_NOTES_FILE}"
); then
    die "Firebase upload failed. If authentication is missing, run: firebase login"
fi

printf '\nREADY_FOR_DEVICE_VERIFICATION\n'
printf 'APK path: %s\n' "${APK_PATH}"
printf 'Firebase upload: PASS\n'
printf 'Build mode: %s\n' "${BUILD_MODE}"
printf 'Branch: %s\n' "${RELEASE_BRANCH}"
printf 'Commit: %s\n' "${RELEASE_COMMIT}"
printf 'Working tree: %s\n' "${RELEASE_WORKING_TREE}"
printf 'Manual verification: install the distributed APK, launch it, and verify the task on a device.\n'
