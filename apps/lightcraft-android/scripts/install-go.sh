#!/usr/bin/env bash
# Installs the Go release librclone/GO_VERSION pins (downloaded from go.dev, checked against
# librclone/GO_LINUX_AMD64_SHA256) and prints its root. CI and F-Droid's recipe run this, outside the
# source tree: rclone needs a newer Go than Debian ships.
#
#   scripts/install-go.sh [dir]     # default $HOME/.cache/lightcraft/go-<version>
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(tr -d '[:space:]' <"$here/librclone/GO_VERSION")"
sha256="$(tr -d '[:space:]' <"$here/librclone/GO_LINUX_AMD64_SHA256")"
dest="${1:-$HOME/.cache/lightcraft/go-$version}"

if [ "$("$dest/go/bin/go" env GOVERSION 2>/dev/null || true)" != "go$version" ]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL -o "$tmp/go.tar.gz" "https://go.dev/dl/go$version.linux-amd64.tar.gz"
  echo "$sha256  $tmp/go.tar.gz" | sha256sum -c - >&2
  rm -rf "$dest"
  mkdir -p "$dest"
  tar -xzf "$tmp/go.tar.gz" -C "$dest"
fi
echo "$dest/go"
