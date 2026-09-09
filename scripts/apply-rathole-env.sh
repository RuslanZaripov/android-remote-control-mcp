#!/usr/bin/env bash
# Apply rathole tunnel settings from the project .env to the connected device via the
# ADB_CONFIGURE broadcast (README: "Headless Setup via ADB"). Zero app-code dependency.
#
# .env values must not contain spaces (the file is sourced, like the Makefile test targets).
#
# Usage: scripts/apply-rathole-env.sh [--start] [--dry-run]
#   --start    also broadcast ADB_START_SERVER after applying the settings
#   --dry-run  validate .env and print the command (secrets masked); needs no adb
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${RATHOLE_ENV_FILE:-$(dirname "$SCRIPT_DIR")/.env}"
RECEIVER_COMPONENT="com.danielealbano.androidremotecontrolmcp.services.mcp.AdbConfigReceiver"
ACTION_CONFIGURE="com.danielealbano.androidremotecontrolmcp.ADB_CONFIGURE"
ACTION_START_SERVER="com.danielealbano.androidremotecontrolmcp.ADB_START_SERVER"

START_SERVER=0
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --start)   START_SERVER=1 ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "ERROR: unknown option '$arg' (usage: apply-rathole-env.sh [--start] [--dry-run])" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Run: cp .env.example .env, then fill in the RATHOLE_* values." >&2
  exit 1
fi
# Project's own gitignored KEY=VALUE file.
source "$ENV_FILE"

missing=()
for var in RATHOLE_SERVER_ADDR RATHOLE_SERVER_PUBLIC_KEY RATHOLE_TOKEN RATHOLE_PUBLIC_URL; do
  [[ -n "${!var:-}" ]] || missing+=("$var")
done
if (( ${#missing[@]} > 0 )); then
  echo "ERROR: missing or empty in $ENV_FILE: ${missing[*]}" >&2
  exit 1
fi

mask() {
  local v="$1"
  if (( ${#v} <= 16 )); then printf '****'; else printf '%s...%s' "${v:0:4}" "${v: -4}"; fi
}

if (( DRY_RUN )); then
  echo "[dry-run] configure: adb shell am broadcast -a $ACTION_CONFIGURE -n <app-id>/$RECEIVER_COMPONENT \\"
  echo "  --ez tunnel_enabled true --es tunnel_provider RATHOLE \\"
  echo "  --es rathole_server_addr '$RATHOLE_SERVER_ADDR' --es rathole_server_public_key '$(mask "$RATHOLE_SERVER_PUBLIC_KEY")' \\"
  echo "  --es rathole_token '$(mask "$RATHOLE_TOKEN")' --es rathole_public_url '$RATHOLE_PUBLIC_URL'"
  if (( START_SERVER )); then
    echo "[dry-run] start server: adb shell am broadcast -a $ACTION_START_SERVER -n <app-id>/$RECEIVER_COMPONENT"
  fi
  exit 0
fi

detect_app_id() {
  local pkgs id
  pkgs="$(adb shell pm list packages)"
  for id in com.danielealbano.androidremotecontrolmcp.gms.debug \
            com.danielealbano.androidremotecontrolmcp.foss.debug \
            com.danielealbano.androidremotecontrolmcp; do
    if grep -q "^package:$id\$" <<<"$pkgs"; then printf '%s' "$id"; return 0; fi
  done
  echo "ERROR: app not installed (tried gms.debug, foss.debug, release)." >&2
  return 1
}

connected="$(adb devices | grep -c $'\tdevice$' || true)"
if [[ "$connected" -eq 0 ]]; then echo "ERROR: no adb device connected (check: adb devices)." >&2; exit 1; fi
if [[ "$connected" -gt 1 ]]; then echo "ERROR: multiple adb devices connected; disconnect all but one." >&2; exit 1; fi

app_id="$(detect_app_id)"

adb shell am broadcast \
  -a "$ACTION_CONFIGURE" \
  -n "$app_id/$RECEIVER_COMPONENT" \
  --ez tunnel_enabled true \
  --es tunnel_provider RATHOLE \
  --es rathole_server_addr "$RATHOLE_SERVER_ADDR" \
  --es rathole_server_public_key "$RATHOLE_SERVER_PUBLIC_KEY" \
  --es rathole_token "$RATHOLE_TOKEN" \
  --es rathole_public_url "$RATHOLE_PUBLIC_URL"

if (( START_SERVER )); then
  adb shell am broadcast -a "$ACTION_START_SERVER" -n "$app_id/$RECEIVER_COMPONENT"
fi

echo "Rathole settings applied to $app_id."
echo "Note: if the MCP server was already running, restart it to pick up the tunnel config (rerun with --start or use the app UI)."
