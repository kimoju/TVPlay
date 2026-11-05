#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_APK="${ROOT_DIR}/dist/TVPlay-release.apk"
APK_PATH="${1:-${OTA_APK_PATH:-${DEFAULT_APK}}}"

if [[ ! -f "${APK_PATH}" ]]; then
  echo "Error: APK file not found at ${APK_PATH}" >&2
  echo "Run ./scripts/build-tvplay.sh first or pass the APK path as the first argument." >&2
  exit 1
fi

discover_ota_base_url() {
  python3 - <<'PY' 2>/dev/null || true
import json, socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.settimeout(1.2)
for host in ("255.255.255.255", "10.0.0.30"):
    try:
        s.sendto(b"DISCOVER_OTA", (host, 9988))
    except Exception:
        pass
data, _ = s.recvfrom(4096)
obj = json.loads(data.decode("utf-8"))
payload = obj.get("data", obj) if isinstance(obj.get("data"), dict) else obj
if payload.get("enabled", True) and payload.get("base_url"):
    print(payload["base_url"].rstrip("/"))
PY
}

DISCOVERED_URL=""
if [[ -z "${OTA_SERVER_URL:-}" ]]; then
  DISCOVERED_URL="$(discover_ota_base_url)"
fi

BASE_URL="${OTA_SERVER_URL:-${DISCOVERED_URL:-http://10.0.0.30:8080}}"
BASE_URL="${BASE_URL%/}"
PUBLISH_URL="${BASE_URL}/api/v1/ota/publish"

APP_KEY="${OTA_APP_KEY:-app.tvplay}"
CHANNEL="${OTA_CHANNEL:-stable}"
IS_MANDATORY="${OTA_MANDATORY:-0}"
OVERWRITE="${OTA_OVERWRITE:-1}"
ADMIN_PASSWORD="${OTA_ADMIN_PASSWORD:-admin123}"
API_TOKEN="${OTA_API_TOKEN:-}"
UPLOAD_MODE="${OTA_UPLOAD_MODE:-multipart}"

if [[ -n "${OTA_CHANGELOG:-}" ]]; then
  CHANGELOG="${OTA_CHANGELOG}"
else
  CHANGELOG="$(cd "${ROOT_DIR}" && git log -1 --pretty=%B 2>/dev/null | head -n 5 || echo "TVPlay 版本更新")"
fi

AUTH_HEADERS=()
if [[ -n "${API_TOKEN}" ]]; then
  AUTH_HEADERS+=(-H "X-API-Token: ${API_TOKEN}")
else
  AUTH_HEADERS+=(-H "X-Admin-Password: ${ADMIN_PASSWORD}")
fi

echo "Publishing ${APK_PATH} to LAN OTA server (${PUBLISH_URL})..."
echo "  app_key=${APP_KEY}  channel=${CHANNEL}  mandatory=${IS_MANDATORY}  overwrite=${OVERWRITE}"

if [[ "${UPLOAD_MODE}" == "stream" ]]; then
  FILE_NAME="$(basename "${APK_PATH}")"
  RESPONSE="$(curl -sS -X POST "${PUBLISH_URL}?app_key=${APP_KEY}&channel=${CHANNEL}&is_mandatory=${IS_MANDATORY}&overwrite=${OVERWRITE}" \
    "${AUTH_HEADERS[@]}" \
    -H "Content-Type: application/octet-stream" \
    -H "X-File-Name: ${FILE_NAME}" \
    --data-binary "@${APK_PATH}")"
else
  RESPONSE="$(curl -sS -X POST "${PUBLISH_URL}" \
    "${AUTH_HEADERS[@]}" \
    -F "file=@${APK_PATH}" \
    -F "app_key=${APP_KEY}" \
    -F "channel=${CHANNEL}" \
    -F "is_mandatory=${IS_MANDATORY}" \
    -F "overwrite=${OVERWRITE}" \
    -F "changelog=${CHANGELOG}")"
fi

python3 - "${RESPONSE}" <<'PY'
import json, sys
raw = sys.argv[1].strip()
try:
    obj = json.loads(raw)
except Exception:
    print(raw)
    sys.exit(1)
if not obj.get("success", obj.get("code") == 0):
    print("OTA Publish Failed:", raw, file=sys.stderr)
    sys.exit(1)
data = obj.get("data", {})
print("OTA Publish Succeeded (" + str(obj.get("action", "published")) + "):")
print(f"  App Key:      {data.get('app_key')}")
print(f"  Release ID:   {data.get('release_id')}")
print(f"  Version:      {data.get('version_name')} ({data.get('version_code')})")
print(f"  SHA-256:      {data.get('sha256')}")
print(f"  Download URL: {data.get('download_url')}")
print(f"  Shortcut URL: {data.get('shortcut_url')}")
print(f"  Page URL:     {data.get('page_url')}")
PY
