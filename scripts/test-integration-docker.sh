#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

CACHE_VOLUME="${DMIGRATE_GRADLE_CACHE_VOLUME:-d-migrate-gradle-cache}"
DEFAULT_TASKS="-PintegrationTests test koverVerify"
IMAGE_TAG="d-migrate-integration-test:latest"

usage() {
    cat <<'EOF'
Run integration tests in a disposable Docker container built from the
`integration-test` stage of the project Dockerfile (JDK 21 + Python 3
+ Django + Node.js).

Usage:
  ./scripts/test-integration-docker.sh
  ./scripts/test-integration-docker.sh :adapters:driven:driver-postgresql:test
  ./scripts/test-integration-docker.sh -PintegrationTests :adapters:driven:integrations:test

Environment:
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

#docker volume rm -f ${CACHE_VOLUME}

#remove build folders
#find . -type d -name "build" -prune -exec rm -rf {} +



echo "Building integration test image from Dockerfile (stage: integration-test)..."
docker build --target integration-test -t "${IMAGE_TAG}" "${REPO_ROOT}"

echo "Running integration tests in container:"
echo "  image:        ${IMAGE_TAG}"
echo "  repo:         ${REPO_ROOT}"
echo "  gradle tasks: ${GRADLE_TASKS}"

exec docker run --rm ${TTY_FLAG} \
    --network=host \
    -v /var/run/docker.sock:/var/run/docker.sock \
    -v "${CACHE_VOLUME}:/gradle-cache" \
    -e GRADLE_USER_HOME=/gradle-cache \
    -e DOCKER_HOST=unix:///var/run/docker.sock \
    -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
    -w /src \
    "${IMAGE_TAG}" \
    bash -lc "gradle --no-daemon ${GRADLE_TASKS}"
