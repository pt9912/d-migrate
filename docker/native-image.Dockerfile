# Lokale GraalVM-Native-Image-Umgebung (Linux) fuer die Metadaten-Schleife des GraalVM-Slices
# (docs/planning/in-progress/graalvm-native-image-distribution.md, Phase F).
#
# Warum ein eigenes Dockerfile und keine Stage in der Haupt-Dockerfile: dort leiten ALLE Stages von
# `gradle:8.12-jdk21` ab. native-image braucht eine GraalVM-Toolchain, die dieses Image nicht hat —
# eine GraalVM-basierte Stage wuerde die Toolchain-Annahme der Datei brechen. Ausserdem wuerde eine
# angehaengte Stage die "letzte Stage"-Semantik von `docker build .` verschieben.
#
# Warum ueberhaupt lokal: eine CI-Messrunde kostet ~8 min (Linux) bis ~25 min (3 OS) plus
# Dispatch-Overhead. Metadaten-Arbeit ist iterativ — lokal sind es Minuten (gemessen: 270 s gegen
# 468 s in CI, bei 20 Kernen statt ~4). CI wird damit wieder die Bestaetigung auf allen drei
# Plattformen statt das Experimentierwerkzeug.
#
# HERMETISCH wie der Haupt-Build: die Quellen werden KOPIERT, nicht gemountet. Ein Bind-Mount
# liesse Gradle `.gradle/`, `.kotlin/` und `build/` in den Arbeitsbaum schreiben; die
# Haupt-Dockerfile vermeidet das durchgaengig, und `.dockerignore` haelt den Kontext klein.

# Gradle-Quelle: dasselbe Image und dieselbe Version wie jede Stage der Haupt-Dockerfile.
FROM gradle:8.12-jdk21 AS gradle-dist

# GraalVM 25 als Basis. Noetig fuer das GraalVM Reachability Metadata Repository: dessen
# vereinheitlichtes `reachability-metadata.json`-Schema kennt GraalVM 21.0.2 nicht
# ("provides a reachability-metadata schema, but your GraalVM installation does not").
FROM ghcr.io/graalvm/native-image-community:25 AS native-build

# findutils liefert `xargs`. Gradle 8.12 verlangt es in SEINEM Startskript
# (/opt/gradle/bin/gradle Zeile 222, "xargs is not available") — also nicht nur im Wrapper. Das
# gradle-Basis-Image bringt findutils mit, das minimale GraalVM-Image nicht; ohne diese Zeile
# kommt der Build gar nicht erst in Gang.
RUN microdnf install -y findutils \
    && microdnf clean all

# Gradle aus dem offiziellen Image uebernehmen statt den Wrapper zu benutzen: die Haupt-Dockerfile
# ruft ebenfalls `gradle --no-daemon`, und der Wrapper wuerde die Distribution bei jedem frischen
# Container neu herunterladen, obwohl das Basis-Image sie fertig mitbringt.
COPY --from=gradle-dist /opt/gradle /opt/gradle
ENV GRADLE_HOME=/opt/gradle

# ZWEI JDKs, bewusst getrennt:
#   JAVA_HOME    = JDK 21 -> hier laufen Gradle und der Kotlin-Compiler
#   GRAALVM_HOME = GraalVM 25 -> hier laeuft native-image
#
# Grund: Kotlin 2.1.20 kann auf JDK 25 nicht STARTEN. Sein mitgeliefertes IntelliJ-`JavaVersion`
# scheitert am Parsen der Versionsnummer:
#   java.lang.IllegalArgumentException: 25.0.2
#     at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse
#     at ...JavaVersionUtilsKt.isAtLeastJava9
#     at ...KotlinCoreEnvironment.<init>
# Das ist kein GraalVM-, Gradle- oder Plugin-Problem, sondern die Laufzeit-JVM des Compilers.
#
# Die Trennung ist moeglich, weil `toolchainDetection=false` gesetzt ist: das native-build-tools-
# Plugin nimmt dann GRAALVM_HOME statt einer Gradle-Toolchain ("GraalVM location read from
# environment variable: GRAALVM_HOME"). Das Projekt bleibt damit auf seinem JDK-21-Ziel — der
# Sprung auf 25 betrifft ausschliesslich den native-image-Schritt.
COPY --from=gradle-dist /opt/java/openjdk /opt/jdk21
ENV JAVA_HOME=/opt/jdk21
ENV GRAALVM_HOME=/usr/lib64/graalvm/graalvm-community-java25
ENV PATH="/opt/jdk21/bin:/opt/gradle/bin:${PATH}"

WORKDIR /src
COPY . .

# core = reduzierter NativeMain, full = voller MainKt (Messkonfiguration Phase F.0).
ARG NATIVE_ENTRYPOINT=full
# Leer = GraalVM-Default `Throw`. `Warn` ist der Diagnosemodus (s. make/native.mk), nie fuer ein
# ausgeliefertes Binary.
ARG NATIVE_MISSING_REG_MODE=
RUN gradle --no-daemon :adapters:driving:cli:nativeCompile \
      -PnativeEntrypoint=${NATIVE_ENTRYPOINT} \
      $(test -n "${NATIVE_MISSING_REG_MODE}" \
        && echo "-PnativeMissingRegistrationMode=${NATIVE_MISSING_REG_MODE}" || true)

# Artefakt-Auslieferung wie die release-assets-Stage der Haupt-Dockerfile: die Stage gibt das
# Artefakt auf stdout aus, der Aufrufer leitet es um. Kein Mount, kein Schreiben in den Arbeitsbaum.
# nosemgrep: config.semgrep.missing-user -- ephemere lokale Build-Stage (cat eines Build-Artefakts), nie ein publiziertes Runtime-Image
ENTRYPOINT ["cat", "/src/adapters/driving/cli/build/native/nativeCompile/d-migrate"]

# ---- Stage: native-agent — Reachability-Metadaten per Tracing-Agent erheben (Phase F.2) --------
#
# Der Agent instrumentiert die JVM und zeichnet Reflection-, Ressourcen-, JNI- und Proxy-Zugriffe
# auf. Er ist die EINZIGE Quelle, die diese Schicht sieht: GRMR zeigte keine Wirkung, und
# `-H:MissingRegistrationReportingMode=Warn` aendert die geworfenen Fehler nicht (beides in
# F.0-Runde 1/2 gemessen).
#
# Bewusst dieselben Sonden wie der native Lauf (scripts/native-probe.sh): was der Agent nicht
# ausfuehrt, kann er nicht aufzeichnen — die erzeugten Metadaten waeren fuer die nicht beruehrte
# Flaeche still unvollstaendig.
FROM native-build AS native-agent

# installDist liefert den Laufzeit-Klassenpfad, den der Agent instrumentieren soll.
RUN gradle --no-daemon :adapters:driving:cli:installDist

# Audit einschalten (LN-027, `logging.audit`, Default false). Zweck ist NICHT die Metadatenerhebung,
# sondern der DECKUNGSNACHWEIS: eine Zeile je ausgefuehrter Operation. Fehlt eine erwartete Flaeche
# im Audit-Log, wurde sie nicht getraced — und das faellt maschinell auf statt geglaubt zu werden.
# Praezedenz aus F.0: `calib-schema.yaml` liess `migrate` fachlich blocken (DB entstand, aber kein
# DDL) und `export flyway` beruehrt die echte Flyway-Library nie. Beide Male sah der Lauf richtig aus.
RUN printf 'logging:\n  audit:\n    enabled: true\n    file: /tmp/agent/audit.log\n' \
    > /src/.d-migrate.yaml \
    && mkdir -p /tmp/agent

# Wrapper: startet die CLI auf der JVM unter dem Agenten. native-probe.sh ruft sein "Binary" mit
# den Sondenargumenten auf — ein Skript erfuellt denselben Vertrag wie das native Binary.
RUN printf '#!/usr/bin/env bash\nexec java \\\n  -agentlib:native-image-agent=config-merge-dir=/tmp/agent/config \\\n  -cp "/src/adapters/driving/cli/build/install/d-migrate/lib/*" \\\n  dev.dmigrate.cli.MainKt "$@"\n' \
    > /usr/local/bin/dmigrate-agent \
    && chmod +x /usr/local/bin/dmigrate-agent

# config-merge-dir braucht ein vorhandenes Verzeichnis; die Sonden mergen dann hinein.
RUN mkdir -p /tmp/agent/config \
    && printf '[]\n' > /tmp/agent/config/reflect-config.json \
    && printf '[]\n' > /tmp/agent/config/jni-config.json \
    && printf '[]\n' > /tmp/agent/config/proxy-config.json \
    && printf '[]\n' > /tmp/agent/config/serialization-config.json \
    && printf '{"resources":{"includes":[]},"bundles":[]}\n' > /tmp/agent/config/resource-config.json

RUN /src/scripts/native-probe.sh /usr/local/bin/dmigrate-agent /tmp/agent/f0-report.md || true
RUN tar cf /tmp/agent-out.tar -C /tmp/agent config audit.log f0-report.md 2>/dev/null \
    || tar cf /tmp/agent-out.tar -C /tmp/agent config f0-report.md

# nosemgrep: config.semgrep.missing-user -- ephemere lokale Build-Stage (cat eines Build-Artefakts), nie ein publiziertes Runtime-Image
ENTRYPOINT ["cat", "/tmp/agent-out.tar"]
