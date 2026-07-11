#!/usr/bin/env bash
# Download the PocketBase binary into backend/ for local development or a
# server deploy. Usage: bash scripts/get-pocketbase.sh [version]
set -euo pipefail

VERSION="${1:-0.30.0}"
cd "$(dirname "$0")/../backend"

OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) ARCH="amd64" ;;
  aarch64 | arm64) ARCH="arm64" ;;
esac

URL="https://github.com/pocketbase/pocketbase/releases/download/v${VERSION}/pocketbase_${VERSION}_${OS}_${ARCH}.zip"
echo "Downloading ${URL}"
curl -fL -o pocketbase.zip "$URL"
unzip -o pocketbase.zip pocketbase
rm pocketbase.zip
chmod +x pocketbase
echo "Done. Start the backend with: npm run backend"
echo "First run: create the superuser account when prompted, then configure"
echo "SMTP (Settings > Mail settings) so OTP codes and mirrored emails send."
