#!/usr/bin/env bash
set -euo pipefail

APP_NAME="${APP_NAME:-team-rag-knowledge-base}"
APP_USER="${APP_USER:-teamrag}"
DEPLOY_DIR="${DEPLOY_DIR:-/opt/team-rag-knowledge-base}"
JAR_SOURCE="${JAR_SOURCE:-target/team-rag-knowledge-base-1.0.0.jar}"
SERVICE_SOURCE="${SERVICE_SOURCE:-deploy/team-rag.service}"
SERVICE_NAME="${SERVICE_NAME:-team-rag.service}"

if [ ! -f "$JAR_SOURCE" ]; then
  echo "Jar not found: $JAR_SOURCE" >&2
  exit 1
fi

if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="${SUDO:-sudo}"
fi

release_id="$(date +%Y%m%d%H%M%S)"
release_dir="$DEPLOY_DIR/releases/$release_id"

if ! id "$APP_USER" >/dev/null 2>&1; then
  $SUDO useradd --system --home-dir "$DEPLOY_DIR" --shell /usr/sbin/nologin "$APP_USER"
fi

$SUDO mkdir -p "$release_dir"
$SUDO cp "$JAR_SOURCE" "$release_dir/app.jar"
$SUDO chown -R "$APP_USER:$APP_USER" "$DEPLOY_DIR"
$SUDO ln -sfn "$release_dir" "$DEPLOY_DIR/current"

if [ -f "$SERVICE_SOURCE" ]; then
  $SUDO cp "$SERVICE_SOURCE" "/etc/systemd/system/$SERVICE_NAME"
fi

$SUDO systemctl daemon-reload
$SUDO systemctl enable "$SERVICE_NAME"
$SUDO systemctl restart "$SERVICE_NAME"
$SUDO systemctl --no-pager --full status "$SERVICE_NAME"

echo "$APP_NAME deployed: $release_dir"
