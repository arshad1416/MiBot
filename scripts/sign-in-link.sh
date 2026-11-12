#!/usr/bin/env bash
# Print a link that signs one browser in to the app, once, within 15 minutes. Run ON the machine the
# app runs on, as the user that runs it.
#   bash sign-in-link.sh you@example.com
# This is the sign-in when Google sign-in is not set up (no Composio key), or not working. The address must be in ALLOWED_EMAILS
# (or already have an account). The key it uses never leaves this machine.
set -euo pipefail
email="${1:?usage: sign-in-link.sh <email>}"
# The app's .env: $ENV_FILE, else the one beside this checkout, else the Orgo install's.
here="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$([ -f "$here/.env" ] && echo "$here/.env" || echo /opt/hq/app/.env)}"
val() { { grep -E "^$1=" "$ENV_FILE" || true; } | tail -1 | cut -d= -f2-; }
key="$(printf 'owner-login' | openssl dgst -sha256 -hmac "$(val SESSION_SECRET)" -r | cut -d' ' -f1)"
host="$(val HOST)"
curl -fsS -X POST "http://${host:-127.0.0.1}:$(val PORT)/api/auth/owner" \
  -H "authorization: Bearer $key" -H 'content-type: application/json' \
  -d "{\"email\":\"$email\",\"link\":true}" | sed -E 's/.*"url":"([^"]+)".*/\1/'
echo
