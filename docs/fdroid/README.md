# Reproducible builds (F-Droid)

LightCraft Cloud is not on F-Droid and has no F-Droid recipe: the one upstream's fork had named
upstream's application id, repository and signing key, so it was removed here. The build is still
set up to be reproducible, as upstream's is, so a recipe could be added later.
The store listing text (title, descriptions, changelogs) is in `fastlane/metadata/android/en-US/`.

## What makes the build reproducible

- **One Rust release** (`rust-toolchain.toml`), **one cargo-ndk** (4.1.2) and **one NDK**
  (29.0.14206865) everywhere: CI, F-Droid and your machine.
- **One Go release** for librclone (`apps/lightcraft-android/librclone/GO_VERSION`, with the sha256
  of its download), installed by `scripts/install-go.sh` in CI and in F-Droid's prebuild (Debian's
  Go is too old for rclone); rclone itself is pinned by `go.mod` and `go.sum`. Gradle builds it
  into `build/` (F-Droid's scanner rejects binaries in the source tree), with `-trimpath` and no
  build id, so the checkout path leaves no trace in it either.
- **No build paths in the Rust library**: the Gradle task that runs cargo-ndk remaps the checkout
  and the Cargo home (`--remap-path-prefix`), so where it was built leaves no trace.
- **No AGP extras that differ**: no VCS info and no dependency metadata block in the APK
  (F-Droid's checker rejects the latter), and no baseline profile.
- **One APK per ABI**, each with its own version code: version code × 10 + 1 (armeabi-v7a) or
  + 2 (arm64-v8a). F-Droid builds each with `lightcraft.unsigned=true` and compares it with ours.

Releases are built by the release workflow on Linux, as F-Droid's are, so Windows line endings in
a local checkout don't matter.

## A release

1. Set `lightcraft.version` and `lightcraft.versionCode` in
   `apps/lightcraft-android/android/gradle.properties` (the build checks they agree: the code is
   `((major·100 + minor)·100 + patch)·100 + (pre-release number, or 99)`, e.g. 0.1.0 → 10099,
   0.1.0-beta → 10001).
2. Commit, tag `v<version>` and push the tag; the workflow refuses a tag that isn't the version in
   gradle.properties. Publish the draft release it makes.
