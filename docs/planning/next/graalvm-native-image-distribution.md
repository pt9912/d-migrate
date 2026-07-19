# GraalVM Native Image (Linux/macOS/Windows) — 1.0.0-Stable-Gate

**Status**: Entwurf (2026-07-19 — Scope aus dem Roadmap-1.0.0-Stable-Gate abgeleitet). **Phase-A-Spike
gelaufen 2026-07-19**; Scope-Gabel entschieden (**Core-CLI-Subset zuerst**). **Phase-B-Kern-Viabilität
BEWIESEN 2026-07-19** — der Kern (Schema-YAML → Modell → Validierung) native-kompiliert grün und läuft
(42-MB-Binary). Ergebnisse in „Phase A/B — Ergebnisse" unten. Verbleibend = Packaging (nativer
Core-Entrypoint + Gradle-Plugin + GraalVM-CI-Toolchain), nicht Machbarkeit.

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

## Phase A — Ergebnisse (Spike 2026-07-19)

Lokaler Spike mit GraalVM CE 21.0.2 (`native-image`) + gcc 13.3, 31 GiB RAM. Schnellster Weg zu echten
Blockern: Direkt-Probe von `native-image` auf die lokal gebaute CLI-Fat-JAR (ohne Plugin).

**Toolchain (Finding bestätigt):** Das Build-/CI-Image `gradle:8.12-jdk21` hat **kein** GraalVM → CI
braucht eine GraalVM-/Mandrel-Toolchain je Ziel-OS. Lokal baut ein triviales Programm in ~18 s zu einem
14-MB-Binary — Toolchain inkl. C-Linker (gcc/zlib) funktioniert.

**Nicht die shadowJar füttern:** `native-image` auf die 137-MB-Fat-JAR bricht sofort in Phase [1/8] ab —
sqlite-jdbc erzwingt per gebündelter Config das Feature `org.sqlite.nativeimage.SqliteJdbcFeature`, dessen
Klasse im gemergten Jar nur als Multi-Release-Eintrag (unter META-INF/versions/9) liegt und nicht gefunden
wird. **Konsequenz:** gegen den echten Modul-Classpath bauen (Gradle-Plugin `nativeCompile`), nicht gegen
das gemergte Jar — ohnehin der vorgesehene Weg.

**Kein fundamentaler Wall — Standard-Tuning:** Mit ausgeschlossener sqlite-Config läuft Phase [2/8]
Analyse an (~22 s) und stoppt am nächsten Standard-Punkt: 10 **logback/slf4j**-Klassen wurden
„unintentionally initialized at build time" → brauchen `--initialize-at-run-time`-Direktiven. Das ist die
häufigste, gut gelöste native-image-Frage (reine Config), kein Code-Wall.

**Metadaten-Landschaft (entscheidend für Scope):**
- **Native-image-bewusst** (bringen Metadaten mit): netty, `software.amazon.awssdk` (aws-core),
  sqlite-jdbc, mordant (inkl. `mordant-jvm-graal-ffi` = **JNA-freie** GraalVM-Variante → bestätigt: JNA
  bleibt unter native-image inert), jansi.
- **Null Metadaten** (die eigentliche Kosten-/Risikofläche): **Hadoop (12.392 Einträge)**, Parquet
  (4.418), ICU4J (5.689, braucht Locale-Resource-Config), Flyway (498). Jede braucht agent-gesammelte
  Metadaten oder eine Ausschluss-Entscheidung.
- **Korrektur zum Planentwurf:** Liquibase ist **nicht** im CLI-Runtime-Jar (0 Einträge) — ein Blocker
  weniger als angenommen; nur Flyway ist als Tool-Export-Fläche präsent.

**Scope-Entscheidung (offene Frage 1): Core-CLI-Subset zuerst.** Der Kern (schema/data, drei Treiber,
`formats`, clikt/mordant) ist analysierbar und weitgehend native-image-bewusst → er wird das
1.0.0-Native-Binary. Die Null-Metadaten-Schwergewichte (Parquet/Hadoop, ICU-abhängige Textfeatures,
Flyway-/Tool-Export, voller S3/netty-Stack) kommen **nicht** in den ersten Native-Cut; sie bleiben der
JVM-Fat-JAR-Pfad (mit sauberer Laufzeit-Meldung im Binary), bis je Fläche eine Metadaten-Investition
begründet ist. Hadoop (12k Klassen, keine Metadaten, notorisch native-feindlich) spricht klar für
Ausschluss statt Aufnahme in 1.0.0.

**Bereit für `in-progress/`** (Spike gelaufen + Scope entschieden). Phase B braucht dann: das Plugin auf
einem **Core-Entrypoint** (Schwergewichts-Module aus dem Native-Classpath ausgeschnitten),
logback/slf4j-Init-Direktiven, sqlite über den echten Classpath, GraalVM-Toolchain in CI.

## Phase B — Ergebnisse (2026-07-19): Kern-Native-Viabilität BEWIESEN

Lokal (GraalVM CE 21.0.2) mit einem **minimalen Java-Entrypoint**, der nur den Kern anspricht
(`SchemaFileResolver.codecForPath(p).read(…)` → `SchemaValidator().validate(schema)`) — also
Core + `formats` (snakeyaml), **ohne** ICU/Treiber/clikt/Parquet. Reachability-Analyse zieht damit
nur den Kern; die Schwergewichte werden nicht kompiliert (bestätigt die Prune-Hypothese).

**Ergebnis: grün.** `native-image` lief durch alle 8 Phasen (Exit 0) → **42-MB-Binary**, das gegen
`examples/sample-db/calib-schema.yaml` korrekt lief: `tables=6 valid=true errors=0`, Exit 0.

**Rezept (was der Kern braucht):**
- `--initialize-at-build-time=ch.qos.logback,org.slf4j` — der logback/slf4j-Standardfix. Der Kern
  initialisiert einen Logger statisch (build-time); `--initialize-at-run-time` kollidiert damit, die
  build-time-Deklaration löst den Konsistenz-Fehler.
- **snakeyaml braucht KEINE manuelle Reflection-Config** — 2.790 Typen wurden automatisch für Reflection
  registriert; der YAML-Parse-Pfad ist out-of-the-box native-fähig.
- **sqlite** nur über den echten Modul-Classpath (Gradle-Plugin), nicht die Fat-JAR — im Probe per
  `--exclude-config` umgangen (die gebündelte `native-image.properties` zeigt im gemergten Jar auf die
  Multi-Release-Klasse, die nicht gefunden wird; s. Phase-A-Ergebnisse).

**Damit ist die Feasibility keine Frage mehr** — der Kern ist native-image-fähig. Verbleibend ist reines
**Packaging**, nicht Machbarkeit:
1. **Nativer Core-Entrypoint im Repo** — ein `main`, der nur die Kern-Kommandos verdrahtet (schema
   validate/generate) **ohne** die eager-ICU-Zeile (`Main.kt` `IcuUnicodeTextService()`) und ohne die
   Export-/Parquet-/S3-/daten-schweren Pfade. `schema generate` braucht zusätzlich die gefüllte
   `DatabaseDriverRegistry` (erreichbar; Native-Viabilität als nächster Probe zu bestätigen).
2. **Gradle-Plugin `org.graalvm.buildtools.native`** auf diesem Entrypoint mit obigem Rezept →
   reproduzierbarer `nativeCompile`.
3. **GraalVM-Toolchain in CI** (Phase D) — bis dahin ist der native Build nur lokal verifizierbar
   (CI-Image `gradle:8.12-jdk21` hat kein GraalVM).

## 4. Offene Fragen / Entscheidungen

1. ✅ **Entschieden (Phase-A-Spike): Core-CLI-Subset zuerst** (statt voller Adapter-Fläche). Begründung
   s. „Phase A — Ergebnisse": Hadoop/Parquet/ICU/Flyway haben **null** native-image-Metadaten; der Kern
   ist analysierbar und native-image-bewusst.
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
