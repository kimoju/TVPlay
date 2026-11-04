#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
ASSETS_DIR="${TVPLAY_AUTH_ASSETS_DIR:-${DIPLAY_AUTH_ASSETS_DIR:-${DEFAULT_ASSETS_DIR}}}"

PK8_FILE="${ASSETS_DIR}/offline-mfi/identity.pk8"
P7B_FILE="${ASSETS_DIR}/offline-mfi/certificate.p7b"

if [[ ! -f "${PK8_FILE}" || ! -f "${P7B_FILE}" ]]; then
  echo "Error: MFi authentication assets not found in ${ASSETS_DIR}/offline-mfi/" >&2
  echo "Expected:" >&2
  echo "  - ${PK8_FILE}" >&2
  echo "  - ${P7B_FILE}" >&2
  exit 1
fi

cd "${ROOT_DIR}"
./gradlew :app:assembleStandaloneRelease :app:assembleStandaloneDebug "$@"

DIST_DIR="${ROOT_DIR}/dist"
mkdir -p "${DIST_DIR}"

RELEASE_APK_SOURCE="${ROOT_DIR}/app/build/outputs/apk/release/app-release.apk"
DEBUG_APK_SOURCE="${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"

cp -f "${RELEASE_APK_SOURCE}" "${DIST_DIR}/TVPlay-release.apk"
cp -f "${DEBUG_APK_SOURCE}" "${DIST_DIR}/TVPlay-debug.apk"
cp -f "${DEBUG_APK_SOURCE}" "${DIST_DIR}/TVPlay-standalone-debug.apk"

echo "Built TVPlay APKs:"
echo "  Release: ${DIST_DIR}/TVPlay-release.apk"
echo "  Debug:   ${DIST_DIR}/TVPlay-debug.apk"
