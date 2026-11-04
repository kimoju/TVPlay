#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_ASSETS_DIR="${ROOT_DIR}/.private/runtime-assets"
export DIPLAY_AUTH_ASSETS_DIR="${DIPLAY_AUTH_ASSETS_DIR:-${DEFAULT_ASSETS_DIR}}"

PK8_FILE="${DIPLAY_AUTH_ASSETS_DIR}/offline-mfi/identity.pk8"
P7B_FILE="${DIPLAY_AUTH_ASSETS_DIR}/offline-mfi/certificate.p7b"

if [[ ! -f "${PK8_FILE}" || ! -f "${P7B_FILE}" ]]; then
  echo "Error: MFi authentication assets not found in ${DIPLAY_AUTH_ASSETS_DIR}/offline-mfi/" >&2
  echo "Expected:" >&2
  echo "  - ${PK8_FILE}" >&2
  echo "  - ${P7B_FILE}" >&2
  exit 1
fi

cd "${ROOT_DIR}"
./gradlew :app:assembleStandaloneDebug "$@"

APK_SOURCE="${ROOT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
DIST_DIR="${ROOT_DIR}/dist"
mkdir -p "${DIST_DIR}"
APK_DEST="${DIST_DIR}/TVPlay-standalone-debug.apk"
cp -f "${APK_SOURCE}" "${APK_DEST}"

echo "Built standalone TVPlay APK:"
echo "  ${APK_DEST}"
