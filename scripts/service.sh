#!/usr/bin/env bash
# Keep MiBot running on this machine as a service of your own user: it starts at boot and restarts
# if it stops. Nothing here needs sudo.
#
#   scripts/service.sh hub       the server (settings in .env beside this checkout)
#   scripts/service.sh worker    the worker for another machine (settings in ~/.mibot/worker.env)
#   scripts/service.sh hub off   stop it and remove the service (same for worker)
#
# Linux uses a systemd user unit, macOS a LaunchAgent. Optional on Linux: MEMORY_MAX=300M limits the
# service and everything it starts (the worker's agents included) to that much memory.
# Run it again after pulling new code: it rebuilds and restarts.
set -euo pipefail
role="${1:-}"; action="${2:-on}"
case "$role" in hub|worker) ;; *) echo "usage: scripts/service.sh hub|worker [off]"; exit 2 ;; esac

repo="$(cd "$(dirname "$0")/.." && pwd)"
name="mibot-$role"
node="$(command -v node || true)"
[ -n "$node" ] || { echo "node is not on the PATH. Node 22 is needed."; exit 1; }
node_dir="$(dirname "$node")"

if [ "$role" = hub ]; then
  settings="$repo/.env"; entry="dist/index.js"; run_args=("--env-file=$settings" "$entry")
else
  settings="${MIBOT_WORKER_ENV:-$HOME/.mibot/worker.env}"; entry="dist/worker/main.js"; run_args=("$entry")
fi

os="$(uname -s)"
unit="$HOME/.config/systemd/user/$name.service"
plist="$HOME/Library/LaunchAgents/com.mibot.$role.plist"

if [ "$action" = off ]; then
  if [ "$os" = Darwin ]; then
    launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
    rm -f "$plist"
  else
    systemctl --user disable --now "$name" 2>/dev/null || true
    rm -f "$unit"
    systemctl --user daemon-reload
  fi
  echo "$name is stopped and removed."
  exit 0
fi

example=".env.example"; [ "$role" = worker ] && example="worker.env.example"
[ -f "$settings" ] || { echo "No settings at $settings. Create that file first, starting from $example in the checkout."; exit 1; }

echo "Building..."
if [ "$role" = hub ]; then (cd "$repo" && npm run build >/dev/null); else (cd "$repo" && npm run build -w server >/dev/null); fi
mkdir -p "$HOME/.mibot"
log="$HOME/.mibot/$role.log"

# The hub takes a moment to open its port. Wait for it, so that whatever is run next (a sign-in
# link, say) finds it there.
started() {
  if [ "$role" = hub ]; then
    val() { { grep -E "^$1=" "$settings" || true; } | tail -1 | cut -d= -f2-; }
    host="$(val HOST)"; port="$(val PORT)"
    up=""
    for _ in $(seq 1 60); do
      if curl -fsS -o /dev/null --max-time 2 "http://${host:-127.0.0.1}:${port:-8787}/api/health" 2>/dev/null; then up=1; break; fi
      sleep 1
    done
    [ -n "$up" ] || { echo "$name was started but did not answer within a minute. See $log"; exit 1; }
  fi
  echo "$name is running. Log: $log"
}

if [ "$os" = Darwin ]; then
  mkdir -p "$(dirname "$plist")"
  args=""; for a in "$node" "${run_args[@]}"; do args="$args    <string>$a</string>"$'\n'; done
  cat > "$plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>com.mibot.$role</string>
  <key>ProgramArguments</key>
  <array>
$args  </array>
  <key>WorkingDirectory</key><string>$repo/server</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$node_dir:$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string>
    <key>MIBOT_WORKER_ENV</key><string>$settings</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$log</string>
  <key>StandardErrorPath</key><string>$log</string>
</dict>
</plist>
PLIST
  launchctl bootout "gui/$(id -u)" "$plist" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "$plist"
  started
else
  mkdir -p "$(dirname "$unit")"
  cat > "$unit" <<UNIT
[Unit]
Description=MiBot $role
After=network-online.target

[Service]
WorkingDirectory=$repo/server
Environment=PATH=$node_dir:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
Environment=MIBOT_WORKER_ENV=$settings
ExecStart=$node ${run_args[*]}
Restart=always
RestartSec=5
StandardOutput=append:$log
StandardError=append:$log
${MEMORY_MAX:+MemoryMax=$MEMORY_MAX}

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable "$name" >/dev/null
  systemctl --user restart "$name"
  started
  me="$(id -un)"
  if [ "$(loginctl show-user "$me" -p Linger --value 2>/dev/null)" != yes ]; then
    echo "To keep it running while you are logged out, run this once:  sudo loginctl enable-linger $me"
  fi
fi
