# GraalVM Native Image (Linux/macOS/Windows) — 1.0.0-Stable-Gate

**Status**: **IN UMSETZUNG** (`in-progress/`, 2026-07-19 — Scope aus dem Roadmap-1.0.0-Stable-Gate
abgeleitet). Wiedereinstieg unten. **Phase-A-Spike
gelaufen 2026-07-19**; Scope-Gabel entschieden (**Core-CLI-Subset zuerst**). **Phase-B-Kern-Viabilität
BEWIESEN 2026-07-19** — der Kern native-kompiliert grün. **Punkt 1+2 GELIEFERT 2026-07-19:** nativer
Entrypoint `NativeMain.kt` + Gradle-Plugin → `nativeCompile` erzeugt reproduzierbar ein grünes
65-MB-`d-migrate`-Binary (`schema validate` läuft); normaler Build unberührt. **`schema validate` + `schema generate` laufen nativ**, Linux-CI (`native-image.yml`) grün verifiziert.
Ergebnisse in „Phase A/B — Ergebnisse" unten. **2026-07-20: 3-OS-Matrix (Phase D) + Release-Asset-Anhang
(Phase E) geliefert — Verifikation der macOS-/Windows-Legs via Dispatch-Lauf ausstehend.** Verbleibend
danach = optionale weitere Kommandos und die Linien-Entscheidungen (statisches Linken, Linux-arm64).

**Trigger**: Die [Roadmap](roadmap.md) führt in **Milestone 1.0.0 — Stable Release** drei
noch offene ⛔-Zeilen, alle Distribution/Build: **GraalVM Native Image (Linux, macOS, Windows)**, Docker
Hub und SDKMAN. Der RC-Feature-Milestone ist feature-komplett (RC1 als Prerelease veröffentlicht); das
Native-Image ist damit das **einzige technisch tiefe** der drei verbleibenden Gates. Dieser Plan deckt
nur das Native-Image ab (Docker Hub/SDKMAN sind separate, kleine Distributions-Schnitte).

**Aktivierungsbedingung** (Move nach `in-progress/`): Der Machbarkeits-Spike (Phase A) ist gelaufen und
die Scope-Gabel „volle Adapter-Fläche vs. Core-CLI-Subset" (offene Frage 1) ist entschieden.

---

## Wiedereinstieg (Stand 2026-07-19) — morgen hier anknüpfen

**Ist-Stand (geliefert + verifiziert, alle auf `develop`, CI grün):**
- Nativer Core-Entrypoint `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/NativeMain.kt` (clikt,
  DB-frei) + Gradle-Plugin
  `graalvmNative` auf `NativeMainKt` (in `adapters/driving/cli/build.gradle.kts`). Rezept steckt schon
  im Plugin: `--initialize-at-build-time=ch.qos.logback,org.slf4j`, `toolchainDetection=false`.
- Nativ lauffähig: `d-migrate schema validate <FILE>` und
  `d-migrate schema generate <FILE> --target {postgresql|mysql|sqlite}`. `generate` ist **DB-frei** —
  konstruiert die reinen `*DdlGenerator` direkt (kein `DatabaseDriver`/JDBC/sqlite-JNI), Binary ~67 MB.
- CI: `.github/workflows/native-image.yml` (Linux, `ubuntu-latest`, tag/dispatch) — in echter GitHub-CI
  **grün verifiziert** (setup-graalvm → nativeCompile → Smoke).

**Lokal reproduzieren** (native-image läuft **nicht** im docker-Build — braucht GraalVM):
```
# GraalVM CE 21 (Community) als JAVA_HOME + GRAALVM_HOME, dann:
DMIGRATE_ALLOW_LOCAL_GRADLE=1 ./gradlew :adapters:driving:cli:nativeCompile
# Binary: adapters/driving/cli/build/native/nativeCompile/d-migrate
./…/d-migrate schema validate examples/sample-db/calib-schema.yaml
./…/d-migrate schema generate examples/sample-db/calib-schema.yaml --target postgresql
```

**Nächste Schritte (priorisiert):**
1. ✅ **macOS/Windows-Matrix-Legs GELIEFERT** (2026-07-20) — `matrix.os` auf
   `[ubuntu-latest, macos-latest, windows-latest]`, job-weites `defaults.run.shell: bash` (auf Windows
   Git-Bash, damit das `gradlew`-Shellskript statt `gradlew.bat` läuft und alle Legs identische
   Steps haben);
   `setup-graalvm` bringt den MSVC-Kontext mit. **Verifikation via Dispatch-Lauf ausstehend** —
   macOS/Windows sind lokal nicht nachbaubar, der Per-OS-Smoke ist dort die einzige Selbstvalidierung.
2. ✅ **Phase E — Release-Assets GELIEFERT** (2026-07-20) — eigener `attach-release`-Job (`needs:
   build-native`, nur bei `tags: v*`, `contents: write`), der die Artefakte aller Legs einsammelt und
   per `gh release upload --clobber` anhängt. Er **erstellt kein Release**, sondern wartet auf das von
   `release-homebrew.yml` erzeugte (10 × 30 s) — Titel/Notes/Prerelease bleiben in einer Hand.
   `docs/user/releasing.md` 4.4.2 + Verifikation 4.8 + Checkliste ergänzt. Native **ergänzt**
   Fat-JAR/OCI/Homebrew.
3. **Weitere native Kommandos** (optional/dünn): die DB-freie Fläche ist mit validate/generate weitgehend
   ausgeschöpft; additiv wären `schema generate`-Flags (`--generate-rollback`, `--output`, `--split`).
   reverse/compare/migrate/data brauchen eine DB → **nicht** Core-Subset.

**Asset-Namensschema** (Phase E): `d-migrate-<version>-<os>-<arch>[.exe]` + `.sha256`, also
`…-linux-x64`, `…-macos-arm64`, `…-windows-x64.exe`. OS und Architektur werden **zur Laufzeit aus
`uname`** abgeleitet, nicht in der Matrix hartcodiert — GitHub hat die macOS-Runner bereits auf arm64
umgestellt, ein festes Label würde bei der nächsten Umstellung still lügen. Dispatch-Läufe (ohne Tag)
tragen statt der Version die Commit-Kurz-SHA und hängen nichts an ein Release.

**Gotchas (in dieser Session gelernt):**
- ~~`workflow_dispatch` greift erst nach Default-Branch-Registrierung~~ — **erledigt 2026-07-20**:
  `native-image.yml` liegt per Config-Commit `05f1a229` auf `main`, `gh workflow list` zeigt „Native
  Image" (vorher 404). `gh workflow run native-image.yml --ref develop` funktioniert jetzt; ein
  temporärer `push: develop`-Trigger ist nicht mehr nötig. **Verallgemeinert:** alles
  Default-Branch-Gebundene (auch die Dependabot-Config) wirkt nur, wenn es auf `main` liegt — `main`
  lag 64 Commits hinter `develop`.
- **Nicht die shadowJar** nativ bauen (sqlite-`SqliteJdbcFeature` als Multi-Release-Eintrag bricht in
  Phase [1/8]) — immer über den echten Modul-Classpath (Gradle-Plugin).
- `Native*`-Klassen bleiben **Kover-exkludiert** (`build.gradle.kts` + `docs/coverage/excludes-ledger.md`).

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
- ✅ **Linux-Leg geliefert + in CI grün verifiziert** (2026-07-19): `.github/workflows/native-image.yml`
  (separat von `build.yml`, weil native-image nicht cross-kompiliert + GraalVM-Toolchain statt Docker +
  zu schwer für jeden PR). Trigger `workflow_dispatch` + `tags: v*` (wie `release-homebrew.yml`);
  `graalvm/setup-graalvm` (Community JDK 21), `./gradlew :adapters:driving:cli:nativeCompile` (mit
  `DMIGRATE_ALLOW_LOCAL_GRADLE`), Smoke (`schema validate` → `valid=true`) und Binary-Artifact-Upload.
  Matrix aktuell nur `ubuntu-latest`. **Einmalig per temporärem `push: develop`-Trigger grün gelaufen**
  (GraalVM in CI provisioniert, nativeCompile + Smoke bestanden), Trigger danach wieder entfernt —
  `workflow_dispatch` greift erst nach Default-Branch-Registrierung (nächster Release-Merge).
- ✅ **macOS-/Windows-Legs GELIEFERT** (2026-07-20, Verifikation via Dispatch ausstehend): Matrix auf
  alle drei OS, `fail-fast: false`, `timeout-minutes: 60`, job-weites `shell: bash`. Der Per-OS-Smoke
  deckt jetzt **beide** nativen Kommandos ab (`--help`, `schema validate`, `schema generate`) und läuft
  gegen das **gestagte** Binary, also exakt das Artefakt, das ans Release geht.
- **Offen:** statisches/`mostly-static`-Linken auf Linux (Portabilität); Linux-arm64 (macOS-arm64
  kommt durch die Runner-Umstellung ohnehin). Mindestens x64 je OS bleibt das 1.0.0-Ziel.

### Phase E — Release-Integration
- ✅ **GELIEFERT** (2026-07-20, Verifikation erst am nächsten Tag-Cut): eigener `attach-release`-Job
  sammelt die Artefakte aller Legs (`download-artifact` mit `pattern` + `merge-multiple`) und hängt sie
  per `gh release upload --clobber` an. Namensschema `d-migrate-<version>-<os>-<arch>[.exe]` + `.sha256`.
- **Bewusst ein einzelner Uploader** statt Upload aus jedem Matrix-Leg: hält `contents: write` auf einen
  Job begrenzt und vermeidet drei gleichzeitige Uploads auf dasselbe Release.
- **Bewusst kein `gh release create`**: das Release kommt aus `release-homebrew.yml`; der Job wartet
  darauf (10 × 30 s) und wird danach rot. Titel/Notes/Prerelease-Flag bleiben in einer Hand.
- Native ersetzt **nichts**, es ergänzt Fat-JAR/OCI/Homebrew. `docs/user/releasing.md`: Abschnitt 4.4.2
  (Asset-Tabelle + Einordnung + Dispatch-Rezept), Verifikation 4.8, Release-Checkliste.

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
1. ✅ **Nativer Core-Entrypoint im Repo GELIEFERT** (`NativeMain.kt`, `dev.dmigrate.cli.NativeMainKt`):
   reduzierter clikt-Baum `d-migrate schema validate <file>` **ohne** die eager-ICU-`DMigrate`-Wurzel
   und ohne Export-/Parquet-/S3-Pfade. Reachability beschneidet ICU/Parquet/AWS automatisch. Die
   Native-Shells sind Kover-exkludiert (+Ledger); die Logik (`SchemaFileResolver`/`SchemaValidator`)
   ist in core/formats getestet.
2. ✅ **Gradle-Plugin `org.graalvm.buildtools.native` (0.10.3) GELIEFERT + reproduzierbar grün:**
   `graalvmNative`-Block auf `NativeMainKt`, `--initialize-at-build-time=ch.qos.logback,org.slf4j`,
   `toolchainDetection=false` (hält den JDK-21-Build unberührt). `./gradlew :adapters:driving:cli:nativeCompile`
   → **BUILD SUCCESSFUL**, 65-MB-Binary; `schema validate` läuft (`tables=6 valid=true`, Exit 0). Der
   normale Build (test/koverVerify/detekt) bleibt grün — das Plugin ist opt-in.
3. ✅ **GraalVM-CI (Linux) GELIEFERT:** `.github/workflows/native-image.yml` baut + smoked das
   Core-Binary auf `ubuntu-latest` (`graalvm/setup-graalvm`), tag-/dispatch-getriggert. Damit ist der
   native Build **CI-gedeckt** (Linux) statt nur lokal. macOS/Windows-Legs offen (Phase D).
4. ✅ **`schema generate` GELIEFERT** (`d-migrate schema generate <FILE> --target <dialect>`): **DB-frei**
   — statt die vollen Treiber via `DatabaseDriverRegistry`/ServiceLoader zu registrieren (die JDBC +
   sqlite-JNI reinzögen), konstruiert `NativeMain` die **reinen `*DdlGenerator` direkt**. Das
   Native-Binary bleibt JDBC-/sqlite-JNI-frei (nur +2 MB → 67 MB), **kein** neues native-image-Config.
   Lokal grün: PG/MySQL/SQLite live gerendert (`CREATE TYPE … ENUM …` etc.), unbekannter Dialekt → Exit 2.
   **Nächst:** weitere Kern-Kommandos + macOS/Windows-Matrix-Legs (Phase D).

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
- Profiling-DataSketches bleibt ein bewusster Carve-Out (s. [Roadmap](roadmap.md),
  Stand-Notiz zu RC1), post-1.0.0 — kein Native-Image-Thema.
