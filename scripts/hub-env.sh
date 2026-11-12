#!/usr/bin/env bash
# Write a first .env for a hub on a machine of your own: its own screens (Xvfb), no Hermes agent
# that already runs there, and one address the app is reached at.
#
#   scripts/hub-env.sh https://name.tailnet.ts.net you@example.com
#
# The address is where browsers and the other machines reach this server, for example the one
# `tailscale serve --bg 8787` printed. The email is the account that may sign in.
# An existing .env is never touched. Edit the file afterwards for keys and anything else
# (.env.example lists every setting).
set -euo pipefail
url="${1:-}"; email="${2:-}"
[ -n "$url" ] && [ -n "$email" ] || { echo "usage: scripts/hub-env.sh <address of this server> <your email>"; exit 2; }
case "$url" in http://*|https://*) ;; *) echo "The address has to start with http:// or https://"; exit 2 ;; esac
url="${url%/}"

repo="$(cd "$(dirname "$0")/.." && pwd)"
file="$repo/.env"
[ ! -e "$file" ] || { echo "$file already exists. Nothing was changed."; exit 1; }
data="$HOME/.mibot"
mkdir -p "$data"

umask 077
cat > "$file" <<ENV
# MiBot hub. Written by scripts/hub-env.sh; .env.example lists every setting.
PORT=8787
HOST=127.0.0.1
APP_URL=$url
SERVER_URL=$url
SESSION_SECRET=$(openssl rand -hex 32)
DEV_LOGIN=0
ALLOWED_EMAILS=$email
DB_PATH=$data/mibot.db

# Agents: no Hermes agent of its own to keep on this machine, so every bot gets one.
HERMES_BIN=hermes
HERMES_URL=
ENGINES_DIR=$data/engines
HUB_SLOTS=2

# Screens: this machine's own, one per bot.
COMPUTER_PROVIDER=local
MAX_BOTS=4

# Voice and research on X need your xAI key in place of not-set.
XAI_API_KEY=not-set
XAI_MODEL=grok-4.7
COMPOSIO_API_KEY=
ENV
echo "Wrote $file (readable by you only). Next: scripts/service.sh hub"
