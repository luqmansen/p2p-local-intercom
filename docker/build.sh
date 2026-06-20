#!/usr/bin/env bash
# Containerized Gradle build for VoxLnk.
#
# Builds the pinned Android toolchain image (once) and runs a Gradle task inside
# it, so the host needs neither a JDK nor the Android SDK. Gradle's downloads are
# kept in a named volume (voxgradle), not on the host home dir or in the repo.
#
# Usage:
#   ./docker/build.sh                       # default: :app:compileDebugKotlin
#   ./docker/build.sh :app:assembleDebug    # any Gradle task(s)
#   ./docker/build.sh :app:testDebugUnitTest
#
# Env overrides:
#   CONTAINER_ENGINE=docker ./docker/build.sh    # default: podman
#   IMAGE=voxbuild  VOLUME=voxgradle             # image tag / gradle cache volume

set -euo pipefail

ENGINE="${CONTAINER_ENGINE:-podman}"
IMAGE="${IMAGE:-voxbuild}"
VOLUME="${VOLUME:-voxgradle}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# SELinux relabel flag is needed on Fedora/RHEL-family hosts; harmless to omit elsewhere.
MOUNT_OPT=":z"
if [ "$ENGINE" = "docker" ]; then
  MOUNT_OPT=""
fi

# Default task if none given.
if [ "$#" -eq 0 ]; then
  set -- ":app:compileDebugKotlin"
fi

echo ">> Building image '$IMAGE' (cached after first run)..."
"$ENGINE" build -t "$IMAGE" "$REPO_ROOT/docker"

echo ">> Running: ./gradlew $* --console=plain --no-daemon"
"$ENGINE" run --rm \
  -v "$REPO_ROOT:/work${MOUNT_OPT}" \
  -v "$VOLUME:/gradle-home" \
  -e GRADLE_USER_HOME=/gradle-home \
  -w /work \
  "$IMAGE" ./gradlew "$@" --console=plain --no-daemon
