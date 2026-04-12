#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

IMAGE="${DMIGRATE_INTEGRATION_IMAGE:-eclipse-temurin:21-jdk-noble}"
CACHE_VOLUME="${DMIGRATE_GRADLE_CACHE_VOLUME:-d-migrate-gradle-cache}"
DEFAULT_TASKS="-PintegrationTests test koverVerify"

usage() {
    cat <<'EOF'
Run the Testcontainers-based integration tests in a disposable Docker container.

Usage:
  ./scripts/test-integration-docker.sh
  ./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
  ./scripts/test-integration-docker.sh -PintegrationTests :adapters:driven:driver-postgresql:test :adapters:driven:driver-mysql:test

Environment:
  DMIGRATE_INTEGRATION_IMAGE        Base image to use
                                    Default: eclipse-temurin:21-jdk-noble
  DMIGRATE_GRADLE_CACHE_VOLUME      Docker volume for Gradle caches
                                    Default: d-migrate-gradle-cache
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi

if [[ $# -gt 0 ]]; then
    GRADLE_TASKS="$*"
else
    GRADLE_TASKS="${DMIGRATE_GRADLE_TASKS:-$DEFAULT_TASKS}"
fi

if [[ ! -S /var/run/docker.sock ]]; then
    echo "Docker socket /var/run/docker.sock not found." >&2
    echo "This script expects a local Docker daemon and mounts the host socket into the test container." >&2
    exit 1
fi

TTY_FLAG=""
if [[ -t 0 && -t 1 ]]; then
    TTY_FLAG="-t"
fi

echo "Running integration tests in container:"
echo "  image:        ${IMAGE}"
echo "  repo:         ${REPO_ROOT}"
echo "  gradle tasks: ${GRADLE_TASKS}"

exec docker run --rm ${TTY_FLAG} \
    --network=host \
    -v "${REPO_ROOT}:/src" \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "${CACHE_VOLUME}:/gradle-cache" \
    -e GRADLE_USER_HOME=/gradle-cache \
    -e GRADLE_TASKS="${GRADLE_TASKS}" \
    -e DOCKER_HOST=unix:///var/run/docker.sock \
    -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    -w /src \
    "${IMAGE}" \
    bash -lc './gradlew --no-daemon ${GRADLE_TASKS}'
