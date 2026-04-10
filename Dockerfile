# syntax=docker/dockerfile:1.7

# ---------------------------------------------------------------------------
# d-migrate — Dockerfile for building and testing the project
#
# Usage:
#   Build image (runs `./gradlew build`, which includes tests):
#     docker build -t d-migrate:dev .
#
#   Build image, skipping tests (faster, assembly only):
#     docker build -t d-migrate:dev --build-arg GRADLE_TASKS="assemble :d-migrate-cli:installDist" .
#
#   Run only a build-stage subset (for example Phase-A tests) without producing
#   the final runtime image:
#     docker build --target build \
#       --build-arg GRADLE_TASKS=":hexagon:core:test :adapters:driven:driver-common:test" \
#       -t d-migrate:phase-a .
#
#   Run the CLI from the final stage:
#     docker run --rm -v "$(pwd):/work" d-migrate:dev schema validate --source /work/schema.yaml
#
#   Extract build artifacts (distribution tar) from the `build` stage:
#     docker build --target build -t d-migrate:build .
#     docker create --name d-migrate-tmp d-migrate:build
#     docker cp d-migrate-tmp:/src/d-migrate-cli/build/distributions ./dist
#     docker rm d-migrate-tmp
# ---------------------------------------------------------------------------

# ---- Stage 1: build & test -------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build

# Gradle tasks executed during the image build. Override with --build-arg to
# skip tests or run a different subset (e.g. "assemble :d-migrate-cli:installDist"
# or "check").
# Important: a full multi-stage `docker build` reaches the runtime stage below,
# so `GRADLE_TASKS` must produce `:d-migrate-cli:installDist`. If you want to
# run only a subset that does not build the CLI distribution, use
# `docker build --target build ...` instead.
ARG GRADLE_TASKS="build :d-migrate-cli:installDist"

ENV GRADLE_USER_HOME=/gradle-cache \
    GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.welcome=never"

WORKDIR /src

# Copy the Gradle wrapper and build definitions first so that the dependency
# resolution layer can be cached when only source files change.
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY hexagon/core/build.gradle.kts                hexagon/core/build.gradle.kts
COPY hexagon/ports/build.gradle.kts               hexagon/ports/build.gradle.kts
COPY adapters/driven/driver-common/build.gradle.kts adapters/driven/driver-common/build.gradle.kts
COPY d-migrate-driver-postgresql/build.gradle.kts d-migrate-driver-postgresql/build.gradle.kts
COPY d-migrate-driver-mysql/build.gradle.kts      d-migrate-driver-mysql/build.gradle.kts
COPY d-migrate-driver-sqlite/build.gradle.kts     d-migrate-driver-sqlite/build.gradle.kts
COPY d-migrate-formats/build.gradle.kts           d-migrate-formats/build.gradle.kts
COPY d-migrate-streaming/build.gradle.kts         d-migrate-streaming/build.gradle.kts
COPY d-migrate-cli/build.gradle.kts               d-migrate-cli/build.gradle.kts

RUN chmod +x ./gradlew

RUN --mount=type=cache,target=/gradle-cache \
    ./gradlew --no-daemon help

# Copy the remaining sources and run the requested Gradle tasks. The Gradle
# caches are mounted so that repeated builds reuse downloaded dependencies.
COPY . .

RUN --mount=type=cache,target=/gradle-cache \
    ./gradlew --no-daemon ${GRADLE_TASKS}

# ---- Stage 2: runtime ------------------------------------------------------
# Uses the same JRE base as the Jib image produced by :d-migrate-cli:jibDockerBuild
FROM eclipse-temurin:21-jre-noble AS runtime

LABEL org.opencontainers.image.title="d-migrate" \
      org.opencontainers.image.description="Database-agnostic CLI tool for schema migration and data management" \
      org.opencontainers.image.source="https://github.com/pt9912/d-migrate" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /opt/d-migrate

# Install the distribution produced by the `application` plugin.
COPY --from=build /src/d-migrate-cli/build/install/d-migrate-cli/ /opt/d-migrate/

ENV PATH="/opt/d-migrate/bin:${PATH}" \
    JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"

WORKDIR /work
VOLUME ["/work"]

ENTRYPOINT ["d-migrate-cli"]
CMD ["--help"]
