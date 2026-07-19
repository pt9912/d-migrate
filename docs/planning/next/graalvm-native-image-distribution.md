# GraalVM Native Image (Linux/macOS/Windows) — 1.0.0-Stable-Gate

**Status**: Entwurf (2026-07-19 — Scope aus dem Roadmap-1.0.0-Stable-Gate abgeleitet; noch **kein**
Machbarkeits-Spike gelaufen, daher Phasen mit vorgelagertem Feasibility-Schnitt).

**Trigger**: Die [Roadmap](../in-progress/roadmap.md) führt in **Milestone 1.0.0 — Stable Release** drei
noch offene ⛔-Zeilen, alle Distribution/Build: **GraalVM Native Image (Linux, macOS, Windows)**, Docker
Hub und SDKMAN. Der RC-Feature-Milestone ist feature-komplett (RC1 als Prerelease veröffentlicht); das
Native-Image ist damit das **einzige technisch tiefe** der drei verbleibenden Gates. Dieser Plan deckt
nur das Native-Image ab (Docker Hub/SDKMAN sind separate, kleine Distributions-Schnitte).

**Aktivierungsbedingung** (Move nach `in-progress/`): Der Machbarkeits-Spike (Phase A) ist gelaufen und
die Scope-Gabel „volle Adapter-Fläche vs. Core-CLI-Subset" (offene Frage 1) ist entschieden.

---

## 1. Ausgangslage

- Die CLI (`adapters/driving/cli`, `application`-Plugin, `mainClass = dev.dmigrate.cli.MainKt`, Fat-JAR
  via shadow, OCI-Image via **jib**) zieht eine **große** Runtime-Fläche: alle drei Treiber (+ Profiling),
  `formats`, `formats-parquet`, `integrations` (Tool-Export Flyway/Liquibase/Django/Knex), `persistence-jdbc`,
  `streaming`, `audit-logging`, `text-icu`, `connection-config`, `storage-file`, `storage-s3`, `mcp` sowie
  extern clikt, logback-classic, slf4j, snakeyaml-engine.
- Viele davon sind **reflection-/resource-/JNI-lastig** und damit die eigentliche Native-Image-Arbeit:
  logback + snakeyaml-engine (Reflection), AWS SDK v2 via `storage-s3` (Reflection), ICU4J via `text-icu`
  (Locale-/Daten-Resources), `formats-parquet` (parquet-hadoop + hadoop-common — Hadoop ist notorisch
  native-image-feindlich), `integrations` (Liquibase/Flyway — sehr reflection-/resourcelastig), der
  **sqlite-jdbc-JNI-Nativelib** (extrahiert eine `.so`/`.dll`/`.dylib` zur Laufzeit).
- **Positiv — es gibt schon Native-Image-Disziplin im Code:** `adapters/driven/formats-parquet/build.gradle.kts`
  vermeidet bewusst Protobuf-Reflection/„zusaetzliche Native-Image-Last"; `adapters/driven/storage-s3/build.gradle.kts`
  nennt ausdrücklich das „1.0.0-Native-Image-Cut"-Footprint-Ziel. Der Plan baut auf dieser Vorarbeit auf.
- **JNA ist rein transitiv (clikt/mordant) und inert** (kein `Native.load()` im Produktiv-Default; kein
  eigener Gradle-Eintrag). Der frische `keychain:`-Zugriff ist **native-frei** (nur `ProcessBuilder`, keine
  Reflection) — der Credential-Pfad belastet das Native-Image also **nicht**.
- **Noch kein** `org.graalvm.buildtools.native`-Plugin im Build; **kein** committetes Reachability-Metadata.

## 2. Werkzeug und Ansatz

- **Gradle-Plugin** `org.graalvm.buildtools.native` auf dem CLI-Modul: `nativeCompile`-Task auf dem
  Application-/Fat-JAR-Entrypoint.
- **Metadaten** zweigleisig: (a) **GraalVM Reachability Metadata Repository** für verbreitete Libs
  (logback, snakeyaml, AWS SDK, sqlite-jdbc, …), (b) der **Tracing-Agent** (`-agentlib:native-image-agent`)
  über einen **repräsentativen CLI-Durchlauf**, um projektspezifische Reflection/Resource/JNI/Proxy-Config
  zu erzeugen und zu committen. Der repräsentative Durchlauf existiert bereits als Korpus: die
  `examples/sample-db/`-Smokes (Pagila IDENTICAL/Cross-Dialect/SQLite/Spatial + TPC) decken schema generate,
  data export/import/transfer, reverse, profile und cross-dialect ab.
- **Reproduzierbarkeit**: Native-Build im Container/CI mit gepinnter GraalVM-Version (Toolchain), nicht auf
  Entwickler-Laptops als Quelle der Wahrheit.

## 3. Phasen

### Phase A — Machbarkeits-Spike (Linux, Core-CLI) + Scope-Entscheidung
- Plugin einziehen, `nativeCompile` auf Linux für einen **Core-CLI-Pfad** (schema/data mit den drei
  Treibern + `formats`, ohne Parquet/S3/ICU/Tool-Export) zum Laufen bringen; Tracing-Agent über die
  Kern-Smokes fahren.
- **Pro reflection-lastiger Fläche** feststellen, ob sie mit vertretbarem Metadaten-Aufwand native-fähig
  ist: `formats-parquet` (Hadoop), `storage-s3` (AWS-SDK), `text-icu` (ICU-Daten), `integrations`
  (Liquibase/Flyway), `sqlite-jdbc` (JNI-Extraktion).
- **Ergebnis**: Feasibility-Report + Entscheidung zur **offenen Frage 1** (volle Fläche vs. Core-Subset).
  Ohne diesen Schnitt wandert der Plan nicht nach `in-progress/`.

### Phase B — Reachability-Metadaten Core (Linux grün)
- GRMR aktivieren + agent-erzeugte `reachability-metadata`/`reflect-config`/`resource-config`/
  `jni-config`/`proxy-config` committen (koloziert beim CLI-Modul).
- Core-CLI-Native-Binary besteht die Kern-Smokes auf Linux (schema generate, data export/import/transfer,
  cross-dialect, reverse, profile).
- **JNA-Inertheit verifizieren**: das Binary enthält keinen erreichbaren `Native.load()`-Pfad (sonst
  bräuchte es JNA-Native-Image-Config — genau das soll vermieden bleiben).

### Phase C — Schwergewichts-Flächen (je Fläche eine Entscheidung)
- Für jede in Phase A als kritisch markierte Fläche **eine** dokumentierte Entscheidung: (i) native-fähig
  machen (Metadaten/Substitutions), (ii) im Native-Binary als **nicht unterstützt** mit sauberer
  Laufzeit-Meldung führen (JVM-Fat-JAR bleibt der Pfad dafür), oder (iii) JVM-only lassen. Kein stiller
  Bruch. Kandidaten mit hohem Risiko: Parquet/Hadoop, Liquibase/Flyway-Export.

### Phase D — 3-OS-CI-Matrix
- GitHub-Actions-Matrix `nativeCompile` für **Linux, macOS, Windows** (Toolchain je OS; Windows braucht
  MSVC-Umgebung). Entscheidungen: statisches/`mostly-static`-Linken auf Linux (Portabilität),
  arm64-Abdeckung (macOS-arm64, Linux-arm64) — mindestens x64 je OS als 1.0.0-Ziel.

### Phase E — Release-Integration
- Native-Binaries je OS als **zusätzliche** GitHub-Release-Assets (+ SHA-256), analog zu Fat-JAR/OCI/
  Homebrew — Native ersetzt **nichts**, es ergänzt. `docs/user/releasing.md` um die Native-Asset-Klasse
  ergänzen; Per-OS-Smoke (mindestens ein Core-Smoke) vor Attach.

## 4. Offene Fragen / Entscheidungen

1. **Volle Adapter-Fläche vs. Core-CLI-Subset** im Native-Binary — die Kern-Gabel. Hadoop/Parquet, AWS-SDK,
   ICU-Daten und Liquibase/Flyway-Export sind die Risiken. Ein Core-Subset (schema/data/formats/Treiber)
   ist deutlich wahrscheinlicher in 1.0.0 zu schaffen; die Schwergewichte blieben dann JVM-Fat-JAR-only.
2. **`sqlite-jdbc`-JNI**: Extrahiert zur Laufzeit eine Nativelib — funktioniert das aus einem
   Native-Image-Binary, oder braucht es Build-Zeit-Einbettung/Substitution?
3. **Statisches Linken (Linux)**: `--static`/`--static-nolibc` (musl) für ein portables Binary vs.
   dynamisch gegen glibc.
4. **Architekturen**: nur x64 je OS für 1.0.0, oder auch arm64 (Apple Silicon, Linux-arm64)?
5. **MCP im Native-Binary**: der `mcp serve`-Pfad (stdio) unter Native-Image — mit im Core-Scope oder
   JVM-only?

## 5. Vorbedingungen

- **GraalVM-Toolchain in CI** (gepinnte Version, je OS).
- **Repräsentativer Smoke-Korpus** — vorhanden (`examples/sample-db/`), dient als Agent-Trace-Quelle und
  als Per-OS-Akzeptanz.
- **Scope-Entscheidung (offene Frage 1)** vor dem Move nach `in-progress/`.

## 6. Akzeptanzkriterien

- `nativeCompile` erzeugt je Ziel-OS (mind. x64 Linux/macOS/Windows) ein lauffähiges `d-migrate`-Binary.
- Das Binary besteht — im entschiedenen Scope — die Kern-Smokes: schema generate, data export/import/
  transfer mit Zeilen-Parität, mindestens ein Cross-Dialect-Sprung, reverse, profile.
- Startup-Zeit und Binärgröße gemessen und im Report festgehalten (der Native-Image-Payoff).
- JNA bleibt **unerreichbar** (verifiziert) — keine JNA-Native-Image-Config nötig.
- Native-Binaries hängen als versionierte, SHA-256-geprüfte Assets am GitHub-Release; `releasing.md` deckt
  die neue Asset-Klasse ab.

## 7. Abgrenzung

- **Keine Library-Artefakte** — der Library-Publish ist per
  [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md) /
  [ADR 0036](../../adr/0036-library-artefakte-github-packages.md) nach 2.0.0 verschoben; 1.0.0 liefert
  CLI, OCI-Image und MCP.
- **Native ersetzt weder** das Fat-JAR **noch** das OCI-Image (jib) — es ist eine **zusätzliche**
  Distributionsklasse.
- **Docker Hub** und **SDKMAN** sind eigene, kleine Distributions-Gates (Docker Hub ist über
  `DOCKERHUB_TOKEN`/`releasing.md` schon teil-verdrahtet) — nicht Teil dieses Plans.
- Profiling-DataSketches bleibt ein bewusster Carve-Out (s. [Roadmap](../in-progress/roadmap.md),
  Stand-Notiz zu RC1), post-1.0.0 — kein Native-Image-Thema.
