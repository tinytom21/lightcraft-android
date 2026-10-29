#!/usr/bin/env bash
# Builds librclone.so (rclone's C library, https://rclone.org/) for the cloud library, from the Go
# module in librclone/ (go.mod and go.sum pin rclone, GO_VERSION pins Go):
#   - the Android ABIs, into <out>/<abi>/librclone.so (Gradle runs this and packages the result)
#   - the host (linux/amd64), into librclone-host/ (for `cargo test` of lightcraft-cloud-client)
# Needs the pinned Go (scripts/install-go.sh installs it; else $GO_ROOT/bin, PATH, ~/.local/go) and,
# for Android, the NDK ($ANDROID_NDK_HOME, else $ANDROID_HOME/ndk/29.0.14206865, else the newest).
#
#   scripts/build-librclone.sh                      # arm64-v8a armeabi-v7a + host; Android into librclone/out
#   scripts/build-librclone.sh host                 # the host library only
#   scripts/build-librclone.sh -o DIR arm64-v8a     # chosen ABIs, into DIR/<abi>/
#   LIBRCLONE_FULL=1 scripts/build-librclone.sh     # every rclone backend (about 3x the size)
#   LIBRCLONE_STRICT=1 ...                          # fail unless Go is exactly GO_VERSION (releases do)
#
# The library is rclone's own librclone C API (RcloneInitialize / RcloneRPC / RcloneFreeString /
# RcloneFinalize) built from a main package of our own: same exports, but only the backends a photo
# library needs (librclone/backends.go) and no FUSE mount or plugin loader.
# Reproducible: -trimpath, no VCS stamp, no build id and a committed go.sum, so the same commit and the same Go and
# NDK give the same bytes wherever it is built.
set -euo pipefail

MIN_SDK="${MIN_SDK:-29}" # = minApi in android/app/build.gradle.kts
NDK_VERSION="29.0.14206865" # = ndkVersion in android/app/build.gradle.kts
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$here/librclone/out"
host_out="$here/librclone-host"
want="$(tr -d '[:space:]' <"$here/librclone/GO_VERSION")"

if [ "${1:-}" = "-o" ]; then out="$(mkdir -p "$2" && cd "$2" && pwd)"; shift 2; fi
targets=("$@")
[ ${#targets[@]} -gt 0 ] || targets=(arm64-v8a armeabi-v7a host)

for root in "${GO_ROOT:-}" "$HOME/.cache/lightcraft/go-$want/go"; do
  [ -z "$root" ] || [ ! -x "$root/bin/go" ] || { PATH="$root/bin:$PATH"; break; }
done
command -v go >/dev/null || PATH="$HOME/.local/go/bin:$PATH"
command -v go >/dev/null || { echo "go not found: run scripts/install-go.sh, or set GO_ROOT" >&2; exit 1; }
have="$(go env GOVERSION)"
if [ "$have" != "go$want" ]; then
  if [ -n "${LIBRCLONE_STRICT:-}" ]; then
    echo "librclone needs Go $want exactly (librclone/GO_VERSION), found $have at $(command -v go): run scripts/install-go.sh" >&2
    exit 1
  fi
  echo "warning: building with $have; releases use go$want" >&2
fi

export GOPATH="${GOPATH:-$HOME/.cache/go}" GOCACHE="${GOCACHE:-$HOME/.cache/go-build}" GOMODCACHE="${GOMODCACHE:-$HOME/.cache/go/pkg/mod}"
export GOTOOLCHAIN=local GOFLAGS=-mod=readonly CGO_ENABLED=1
tags=""
[ -z "${LIBRCLONE_FULL:-}" ] || tags="librclone_full"

llvm=""
android_target=0
for t in "${targets[@]}"; do [ "$t" = host ] || android_target=1; done
if [ "$android_target" = 1 ]; then
  ndk="${ANDROID_NDK_HOME:-}"
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  if [ -z "$ndk" ] && [ -d "$sdk/ndk/$NDK_VERSION" ]; then ndk="$sdk/ndk/$NDK_VERSION"; fi
  if [ -z "$ndk" ] && [ -d "$sdk/ndk" ]; then ndk="$(ls -d "$sdk"/ndk/*/ | sort -V | tail -1)"; fi
  [ -n "$ndk" ] || { echo "Android NDK not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
  llvm="${ndk%/}/toolchains/llvm/prebuilt/linux-x86_64/bin"
fi

cd "$here/librclone"
build() { # <out file> <extra env...>
  local file="$1"; shift
  mkdir -p "$(dirname "$file")"
  env "$@" go build -p 2 -buildmode=c-shared -trimpath -buildvcs=false -tags "$tags" -ldflags "-s -w -extldflags=-Wl,-z,max-page-size=16384 -buildid=" -o "$file" .
  rm -f "${file%.so}.h"
  ls -l "$file" | awk '{printf "%s  %.1f MB\n", $9, $5/1048576}'
}

for t in "${targets[@]}"; do
  case "$t" in
    host)        build "$host_out/librclone.so" GOOS=linux GOARCH=amd64 ;;
    arm64-v8a)   build "$out/arm64-v8a/librclone.so" GOOS=android GOARCH=arm64 CC="$llvm/aarch64-linux-android$MIN_SDK-clang" ;;
    armeabi-v7a) build "$out/armeabi-v7a/librclone.so" GOOS=android GOARCH=arm GOARM=7 CC="$llvm/armv7a-linux-androideabi$MIN_SDK-clang" ;;
    x86_64)      build "$out/x86_64/librclone.so" GOOS=android GOARCH=amd64 CC="$llvm/x86_64-linux-android$MIN_SDK-clang" ;;
    *) echo "unknown target $t (host|arm64-v8a|armeabi-v7a|x86_64)" >&2; exit 1 ;;
  esac
done
