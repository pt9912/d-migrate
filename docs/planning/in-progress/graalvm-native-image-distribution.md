# GraalVM Native Image (Linux/macOS/Windows) — 1.0.0-Stable-Gate

**Status**: **IN UMSETZUNG** (`in-progress/`). **Ziel-Scope seit 2026-07-20: voller Funktionsumfang** —
das Native-Binary soll dasselbe können wie die JVM-CLI. Verpackung (Gradle-Plugin, 3-OS-CI-Matrix,
Release-Assets) ist **geliefert und grün**; der inhaltliche Kern — Reachability-Metadaten für die
reflection-lastigen Adapter — steht noch aus.

**Trigger**: Die [Roadmap](roadmap.md) führt in **Milestone 1.0.0 — Stable Release** drei
noch offene ⛔-Zeilen, alle Distribution/Build: **GraalVM Native Image (Linux, macOS, Windows)**, Docker
Hub und SDKMAN. Der RC-Feature-Milestone ist feature-komplett (RC1 als Prerelease veröffentlicht); das
Native-Image ist damit das **einzige technisch tiefe** der drei verbleibenden Gates. Dieser Plan deckt
nur das Native-Image ab (Docker Hub/SDKMAN sind separate, kleine Distributions-Schnitte).

---

## 0. Scope-Entscheidung (2026-07-20) — voller Funktionsumfang

**Entscheider: Projekt-Eigner. Entscheidung: Das Native-Binary trägt den vollen Funktionsumfang der
CLI**, nicht ein Subset.

**Diese Entscheidung ersetzt die frühere Festlegung „Core-CLI-Subset zuerst".** Jene war am 2026-07-19
aus dem Phase-A-Spike heraus getroffen und im Plan als entschieden geführt worden — sie war jedoch eine
Selbsteinschätzung der Analyse, keine Eigner-Entscheidung, obwohl Abschnitt „Vorbedingungen" genau dafür
ein Tor vorsah. Der Fehler wird hier korrigiert und die Historie stehen gelassen, statt sie zu
überschreiben.

**Was gültig bleibt:** die *Messungen* aus Phase A und B (Metadaten-Zählungen, Toolchain-Findings,
logback-Rezept, shadowJar-Falle). Das sind Fakten und bleiben unten dokumentiert.
**Was entfällt:** die daraus gezogene Folgerung, die Schwergewichts-Flächen aus dem 1.0.0-Cut
auszuschließen.

**Begründung der Eigner-Entscheidung:** Wer `d-migrate` als Binary installiert, erwartet `d-migrate` —
nicht eine Teilmenge, die bei einem Kommando behauptet, es gäbe es nicht. Ein reduziertes Binary unter
demselben Namen ist ein Produktbruch, kein Verpackungsdetail.

### Konsequenz für den bisherigen Bau

Der reduzierte Entrypoint `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/NativeMain.kt` wird
**zurückgebaut**. Der native Entrypoint ist künftig `dev.dmigrate.cli.MainKt` — derselbe wie beim
Fat-JAR. Damit lösen sich vier Divergenzen auf, die der zweite Kommandobaum eingeschleppt hatte und die
am 2026-07-20 beim Abgleich gegen [`spec/cli-spec.md`](../../../spec/cli-spec.md) gefunden wurden:

1. **Spec-Bruch in der Aufrufsyntax.** Die CLI-Spec schreibt `--source <path>` vor;
   `NativeMain.kt` nutzt ein positionales `FILE`. Gleicher Kommandoname, inkompatible Aufrufe.
2. **`schema generate` überspringt die Validierung.** Der echte `SchemaGenerateRunner` fährt vor dem
   Rendern `SchemaValidator` (Exit 3) und den dialektspezifischen `PreGenerationValidator` (Exit 3) und
   baut `DdlGenerationOptions` (spatialProfile, mysql-/sqlite-Sequence-Modi, `deterministic`,
   `SOURCE_DATE_EPOCH`). `NativeMain.kt` tut nichts davon — das Binary rendert DDL für Schemas, die die
   JVM-CLI ablehnt.
3. **`validate` weicht in den Fehlerpfaden ab.** Parse-Fehler ergeben in der JVM-CLI Exit 7; ebenso
   fehlen `--output-format json` und `--source -` (stdin).
4. **Fehlende Flags** auf `generate`: `--output`, `--report`, `--generate-rollback`, `--deterministic`,
   `--spatial-profile`, `--split`, `--mysql-named-sequences`, `--sqlite-named-sequences`.

Mit `MainKt` als Entrypoint entfallen alle vier **strukturell** — es gibt dann nur noch eine CLI.

### Was von der bisherigen Arbeit trägt

| Artefakt | Scope-abhängig? |
| --- | --- |
| Gradle-Plugin + `nativeCompile`-Rezept in [`adapters/driving/cli/build.gradle.kts`](../../../adapters/driving/cli/build.gradle.kts) | nein — trägt |
| [`.github/workflows/native-image.yml`](../../../.github/workflows/native-image.yml), 3-OS-Matrix, Release-Anhang | nein — trägt |
| logback-/slf4j-Init-Direktiven, Toolchain-Findings, shadowJar-Falle | nein — trägt |
| `NativeMain.kt` (reduzierter Kommandobaum) | **ja — wird zurückgebaut** |

---

## Wiedereinstieg (Stand 2026-07-20)

**Geliefert und verifiziert (alle auf `develop`):**
- Gradle-Plugin `org.graalvm.buildtools.native` (0.10.3), `graalvmNative`-Block mit
  `--initialize-at-build-time=ch.qos.logback,org.slf4j` und `toolchainDetection=false` (hält den
  JDK-21-Build unberührt; das Plugin ist opt-in).
- 3-OS-CI-Matrix in `native-image.yml` — **alle drei Legs grün verifiziert** (Dispatch-Lauf
  29717820742, 2026-07-20): Linux, macOS und Windows bauen und smoken je ihr eigenes Binary.
- Release-Asset-Anhang (Phase E) — verifiziert erst am nächsten Tag-Cut, da nur bei `tags: v*` aktiv.
- `workflow_dispatch` ist registriert (Config-Commit `05f1a229` auf `main`).

**Nächster Schritt: Messung der vollen Fläche (Phase F.0).** Die volle Adapter-Fläche wurde **nie**
gegen den echten Modul-Classpath probiert: Phase A probierte nur die shadowJar (brach an einem
Verpackungsfehler ab, ohne Aussage über Machbarkeit), Phase B baute den reduzierten Entrypoint. Die
Konfiguration, die jetzt das Ziel ist, ist damit ungetestet. Details unten in Phase F.0.

**Lokal reproduzieren** (native-image läuft **nicht** im docker-Build — braucht GraalVM):
```
# GraalVM CE 21 (Community) als JAVA_HOME + GRAALVM_HOME, dann:
DMIGRATE_ALLOW_LOCAL_GRADLE=1 ./gradlew :adapters:driving:cli:nativeCompile
# Binary: adapters/driving/cli/build/native/nativeCompile/d-migrate
```

**Gotchas (belegt):**
- ~~`workflow_dispatch` greift erst nach Default-Branch-Registrierung~~ — **erledigt 2026-07-20**:
  `native-image.yml` liegt per Config-Commit `05f1a229` auf `main`, `gh workflow list` zeigt „Native
  Image" (vorher 404). **Verallgemeinert:** alles Default-Branch-Gebundene (auch die Dependabot-Config)
  wirkt nur, wenn es auf `main` liegt — `main` lag 64 Commits hinter `develop`.
- **Nicht die shadowJar** nativ bauen (sqlite-`SqliteJdbcFeature` als Multi-Release-Eintrag bricht in
  Phase [1/8]) — immer über den echten Modul-Classpath (Gradle-Plugin).
- Verbleibende `Native*`-Klassen sind **Kover-exkludiert**
  ([`docs/coverage/excludes-ledger.md`](../../coverage/excludes-ledger.md)); mit dem Rückbau von
  `NativeMain.kt` fallen diese Einträge weg und müssen aus dem Ledger entfernt werden.

## 1. Ausgangslage

- Die CLI (`adapters/driving/cli`, `application`-Plugin, `mainClass = dev.dmigrate.cli.MainKt`, Fat-JAR
  via shadow, OCI-Image via **jib**) zieht eine **große** Runtime-Fläche: alle drei Treiber (+ Profiling),
  `formats`, `formats-parquet`, `integrations` (Tool-Export Flyway/Liquibase/Django/Knex), `persistence-jdbc`,
  `streaming`, `audit-logging`, `text-icu`, `connection-config`, `storage-file`, `storage-s3`, `mcp` sowie
  extern clikt, logback-classic, slf4j, snakeyaml-engine.
- Viele davon sind **reflection-/resource-/JNI-lastig** und damit die eigentliche Native-Image-Arbeit:
  logback + snakeyaml-engine (Reflection), AWS SDK v2 via `storage-s3` (Reflection), ICU4J via `text-icu`
  (Locale-/Daten-Resources), `formats-parquet` (parquet-hadoop + hadoop-common — Hadoop ist notorisch
  native-image-feindlich), `integrations` (Flyway — sehr reflection-/resourcelastig), der
  **sqlite-jdbc-JNI-Nativelib** (extrahiert eine `.so`/`.dll`/`.dylib` zur Laufzeit).
- **Positiv — es gibt schon Native-Image-Disziplin im Code:** [`adapters/driven/formats-parquet/build.gradle.kts`](../../../adapters/driven/formats-parquet/build.gradle.kts)
  vermeidet bewusst Protobuf-Reflection/„zusaetzliche Native-Image-Last"; [`adapters/driven/storage-s3/build.gradle.kts`](../../../adapters/driven/storage-s3/build.gradle.kts)
  nennt ausdrücklich das „1.0.0-Native-Image-Cut"-Footprint-Ziel. Der Plan baut auf dieser Vorarbeit auf.
- **JNA ist rein transitiv (clikt/mordant) und inert** (kein `Native.load()` im Produktiv-Default; kein
  eigener Gradle-Eintrag). Der `keychain:`-Zugriff ist **native-frei** (nur `ProcessBuilder`, keine
  Reflection) — der Credential-Pfad belastet das Native-Image also **nicht**.
- **Kein committetes Reachability-Metadata**; GRMR ist im Plugin noch **nicht** aktiviert.

## 2. Werkzeug und Ansatz

- **Gradle-Plugin** `org.graalvm.buildtools.native` auf dem CLI-Modul (geliefert), `nativeCompile` auf
  `dev.dmigrate.cli.MainKt`.
- **Metadaten** zweigleisig: (a) **GraalVM Reachability Metadata Repository** für verbreitete Libs
  (logback, snakeyaml, AWS SDK, sqlite-jdbc, …), (b) der **Tracing-Agent** (`-agentlib:native-image-agent`)
  über einen **repräsentativen CLI-Durchlauf**, um projektspezifische Reflection/Resource/JNI/Proxy-Config
  zu erzeugen und zu committen. Der repräsentative Durchlauf existiert bereits als Korpus: die
  `examples/sample-db/`-Smokes (Pagila IDENTICAL/Cross-Dialect/SQLite/Spatial + TPC) decken schema generate,
  data export/import/transfer, reverse, profile und cross-dialect ab.
- **Reproduzierbarkeit**: Native-Build im Container/CI mit gepinnter GraalVM-Version (Toolchain), nicht auf
  Entwickler-Laptops als Quelle der Wahrheit.

## 3. Phasen

### Phase A — Machbarkeits-Spike (abgeschlossen 2026-07-19)
Gelaufen. Messungen unter „Phase A — Ergebnisse". Die dort gezogene Scope-Folgerung ist durch
Abschnitt 0 ersetzt.

### Phase B — Kern-Viabilität (abgeschlossen 2026-07-19)
Gelaufen. Der Kern ist native-image-fähig, das Rezept steht. Der dabei gebaute reduzierte Entrypoint
wird in Phase F.1 zurückgebaut.

### Phase D — 3-OS-CI-Matrix (abgeschlossen 2026-07-20)
- ✅ Alle drei Legs (`ubuntu-latest`, `macos-latest`, `windows-latest`) **in CI grün verifiziert**
  (Dispatch-Lauf 29717820742). `fail-fast: false`, `timeout-minutes: 60`, job-weites `shell: bash`
  (auf Windows Git-Bash, damit das `gradlew`-Shellskript statt `gradlew.bat` läuft).
  `graalvm/setup-graalvm` bringt auf Windows den MSVC-Kontext mit.
- **Offen:** statisches/`mostly-static`-Linken auf Linux (Portabilität); Linux-arm64 (macOS-arm64
  kommt durch die Runner-Umstellung ohnehin). Mindestens x64 je OS bleibt das 1.0.0-Ziel.

### Phase E — Release-Integration (abgeschlossen 2026-07-20)
- ✅ Eigener `attach-release`-Job sammelt die Artefakte aller Legs (`download-artifact` mit `pattern` +
  `merge-multiple`) und hängt sie per `gh release upload --clobber` an. Namensschema
  `d-migrate-<version>-<os>-<arch>[.exe]` + `.sha256`; OS und Architektur werden **zur Laufzeit aus
  `uname`** abgeleitet, nicht in der Matrix hartcodiert.
- **Bewusst ein einzelner Uploader** statt Upload aus jedem Matrix-Leg: hält `contents: write` auf einen
  Job begrenzt und vermeidet drei gleichzeitige Uploads auf dasselbe Release.
- **Bewusst kein `gh release create`**: das Release kommt aus `release-homebrew.yml`; der Job wartet
  darauf (10 × 30 s) und wird danach rot. Titel/Notes/Prerelease-Flag bleiben in einer Hand.
- Doku: [`docs/user/releasing.md`](../../user/releasing.md) Abschnitt 4.4.2, Verifikation 4.8,
  Release-Checkliste.
- **Verifikation erst am nächsten Tag-Cut** — der Job ist nur bei `tags: v*` aktiv.

### Phase F — Voller Funktionsumfang (der verbleibende Kern)

Ersetzt die frühere Phase C. Statt „pro Fläche entscheiden, ob sie rausfliegt" gilt jetzt: **pro Fläche
native-fähig machen.** Ein Ausschluss ist die Ausnahme und braucht eine eigene Begründung (s. F.5).

#### F.0 — Messung der vollen Fläche (erster Schritt, blockiert alles Weitere)
Die Konfiguration „voller Entrypoint gegen echten Modul-Classpath" ist **ungetestet**. Bevor Aufwand
geschätzt wird, wird gemessen:
- `mainClass` im `graalvmNative`-Block auf `dev.dmigrate.cli.MainKt` zeigen lassen und `nativeCompile`
  fahren (per `workflow_dispatch`-Eingabe, damit `develop` keinen wissentlich roten Workflow trägt).
- **Ergebnis**: konkrete Blockerliste statt Metadaten-Zählungen. Ersetzt „Hadoop hat 12.392 Einträge und
  gilt als feindlich" durch „an diesen Stellen bricht es".
- Zusätzlich aus dem Build-Report belegen, **was tatsächlich im Binary landet** — insbesondere, ob JDBC
  und der sqlite-JNI-Pfad erreichbar sind. Die bisherige Aussage „das Binary bleibt JDBC-frei" stützte
  sich allein auf ein Größendelta, nicht auf Evidenz.

#### F.1 — Entrypoint zusammenführen
- `graalvmNative`-`mainClass` dauerhaft auf `dev.dmigrate.cli.MainKt`.
- `NativeMain.kt` löschen; zugehörige Kover-Excludes aus
  [`docs/coverage/excludes-ledger.md`](../../coverage/excludes-ledger.md) entfernen.
- Per-OS-Smokes in `native-image.yml` auf die spec-konforme Syntax (`--source`) umstellen.

#### F.2 — Metadaten-Grundlage
- **GRMR aktivieren** (`metadataRepository` im `graalvmNative`-Block) — deckt logback, snakeyaml,
  AWS SDK, sqlite-jdbc, netty ohne Eigenarbeit ab.
- **Tracing-Agent** über den `examples/sample-db/`-Korpus fahren; erzeugte
  `reachability-metadata`/`reflect-config`/`resource-config`/`jni-config`/`proxy-config` beim CLI-Modul
  kolozieren und committen.
- **JNA-Inertheit verifizieren**: das Binary enthält keinen erreichbaren `Native.load()`-Pfad.

#### F.3 — Schwergewichts-Flächen native-fähig machen
Reihenfolge nach gemessenem Risiko aus F.0. Kandidaten, absteigend:
- **`formats-parquet`** (parquet-hadoop + hadoop-common) — höchstes Risiko, null Metadaten.
- **`text-icu`** (ICU4J) — Locale-/Daten-Resources, null Metadaten.
- **`integrations`** (Flyway-Tool-Export) — null Metadaten, aber kleinere Fläche.
- **`storage-s3`** (AWS SDK v2 + netty) — bringt teilweise eigene Metadaten mit.
- **`mcp`** (`mcp serve`, stdio) — bislang ungeprüft.

#### F.4 — Datenbank-Pfade nativ
- **`sqlite-jdbc`-JNI**: die zur Laufzeit extrahierte Nativelib ist die offene Frage 2. Ohne Lösung kein
  `data`-Kommando gegen SQLite. sqlite-jdbc bringt eigene Native-Image-Metadaten mit — der Ausgangspunkt
  ist also günstig, aber unbelegt.
- PostgreSQL- und MySQL-JDBC sind reines Java und gelten als unkritisch — ebenfalls zu belegen, nicht
  anzunehmen.

#### F.5 — Falls eine Fläche doch nicht native-fähig ist
Dann ist das ein **permanenter Funktionsausschluss** im Native-Binary und gehört in einen **ADR**, nicht
in eine Plan-Notiz. Bedingungen: das Binary muss das Kommando **kennen** und beim Aufruf klar melden,
dass es im Native-Build nicht verfügbar ist (Verweis auf Fat-JAR/OCI) — **kein stiller Bruch**, kein
fehlendes Kommando.

## Phase A — Ergebnisse (Spike 2026-07-19)

> Die **Messungen** dieses Abschnitts sind weiter gültig. Die daraus damals gezogene
> Scope-Folgerung ist durch Abschnitt 0 ersetzt.

Lokaler Spike mit GraalVM CE 21.0.2 (`native-image`) + gcc 13.3, 31 GiB RAM. Schnellster Weg zu echten
Blockern: Direkt-Probe von `native-image` auf die lokal gebaute CLI-Fat-JAR (ohne Plugin).

**Toolchain (Finding bestätigt):** Das Build-/CI-Image `gradle:8.12-jdk21` hat **kein** GraalVM → CI
braucht eine GraalVM-/Mandrel-Toolchain je Ziel-OS. Lokal baut ein triviales Programm in ~18 s zu einem
14-MB-Binary — Toolchain inkl. C-Linker (gcc/zlib) funktioniert.

**Nicht die shadowJar füttern:** `native-image` auf die 137-MB-Fat-JAR bricht sofort in Phase [1/8] ab —
sqlite-jdbc erzwingt per gebündelter Config das Feature `org.sqlite.nativeimage.SqliteJdbcFeature`, dessen
Klasse im gemergten Jar nur als Multi-Release-Eintrag (unter META-INF/versions/9) liegt und nicht gefunden
wird. **Konsequenz:** gegen den echten Modul-Classpath bauen (Gradle-Plugin `nativeCompile`), nicht gegen
das gemergte Jar. **Wichtig für die Einordnung:** das war ein *Verpackungsfehler*, keine Aussage über die
Machbarkeit der vollen Fläche — die volle Fläche wurde danach nie gegen den echten Classpath probiert
(daher Phase F.0).

**Kein fundamentaler Wall — Standard-Tuning:** Mit ausgeschlossener sqlite-Config läuft Phase [2/8]
Analyse an (~22 s) und stoppt am nächsten Standard-Punkt: 10 **logback/slf4j**-Klassen wurden
„unintentionally initialized at build time" → brauchen Init-Direktiven. Das ist die häufigste, gut
gelöste native-image-Frage (reine Config), kein Code-Wall.

**Metadaten-Landschaft:**
- **Native-image-bewusst** (bringen Metadaten mit): netty, `software.amazon.awssdk` (aws-core),
  sqlite-jdbc, mordant (inkl. `mordant-jvm-graal-ffi` = **JNA-freie** GraalVM-Variante → bestätigt: JNA
  bleibt unter native-image inert), jansi.
- **Null Metadaten** (die eigentliche Kosten-/Risikofläche): **Hadoop (12.392 Einträge)**, Parquet
  (4.418), ICU4J (5.689, braucht Locale-Resource-Config), Flyway (498). Jede braucht agent-gesammelte
  Metadaten (Phase F.3).
- **Korrektur zum Planentwurf:** Liquibase ist **nicht** im CLI-Runtime-Jar (0 Einträge) — ein Blocker
  weniger als angenommen; nur Flyway ist als Tool-Export-Fläche präsent.

## Phase B — Ergebnisse (2026-07-19): Kern-Native-Viabilität BEWIESEN

> Gültig als Machbarkeitsnachweis für den Kern und als Rezept. Der dabei entstandene reduzierte
> Entrypoint wird nach Abschnitt 0 zurückgebaut.

Lokal (GraalVM CE 21.0.2) mit einem **minimalen Java-Entrypoint**, der nur den Kern anspricht
(`SchemaFileResolver.codecForPath(p).read(…)` → `SchemaValidator().validate(schema)`) — also
Core + `formats` (snakeyaml), **ohne** ICU/Treiber/clikt/Parquet.

**Ergebnis: grün.** `native-image` lief durch alle 8 Phasen (Exit 0) → **42-MB-Binary**, das gegen
`examples/sample-db/calib-schema.yaml` korrekt lief: `tables=6 valid=true errors=0`, Exit 0.

**Rezept (was der Kern braucht):**
- `--initialize-at-build-time=ch.qos.logback,org.slf4j` — der logback/slf4j-Standardfix. Der Kern
  initialisiert einen Logger statisch (build-time); `--initialize-at-run-time` kollidiert damit, die
  build-time-Deklaration löst den Konsistenz-Fehler.
- **snakeyaml braucht KEINE manuelle Reflection-Config** — 2.790 Typen wurden automatisch für Reflection
  registriert; der YAML-Parse-Pfad ist out-of-the-box native-fähig.
- **sqlite** nur über den echten Modul-Classpath (Gradle-Plugin), nicht die Fat-JAR.

**Daraus gebaut (2026-07-19/20):** Gradle-Plugin 0.10.3 mit `graalvmNative`-Block, GraalVM-CI, 3-OS-Matrix
und Release-Anhang — alles scope-unabhängig und weiter gültig. Der reduzierte Entrypoint `NativeMain.kt`
mit `schema validate`/`schema generate` ist der scope-abhängige Teil und geht in Phase F.1 zurück.

## 4. Offene Fragen / Entscheidungen

1. ✅ **Entschieden 2026-07-20 (Projekt-Eigner): voller Funktionsumfang** — s. Abschnitt 0.
   Die frühere Antwort „Core-CLI-Subset zuerst" war eine Selbsteinschätzung der Analyse und ist ersetzt.
2. **`sqlite-jdbc`-JNI** (jetzt **blockierend**, vorher gegenstandslos): Extrahiert zur Laufzeit eine
   Nativelib — funktioniert das aus einem Native-Image-Binary, oder braucht es Build-Zeit-Einbettung/
   Substitution? Ohne Lösung kein `data`-Kommando gegen SQLite.
3. **Statisches Linken (Linux)**: `--static`/`--static-nolibc` (musl) für ein portables Binary vs.
   dynamisch gegen glibc.
4. **Architekturen**: nur x64 je OS für 1.0.0, oder auch arm64 (Apple Silicon, Linux-arm64)?
5. **MCP im Native-Binary** (jetzt **muss mit**, vorher optional): der `mcp serve`-Pfad (stdio) unter
   Native-Image — bislang ungeprüft.
6. **Offen, Eigner-Entscheidung: bleibt Native ein 1.0.0-Gate?** Der volle Funktionsumfang bedeutet
   Metadaten-Arbeit für Parquet/Hadoop, ICU und Tool-Export mit offenem Ausgang. Ob 1.0.0 darauf wartet
   oder die Roadmap-Zeile nach 1.1.0 wandert, ist nicht entschieden. Phase F.0 liefert die Grundlage —
   erst danach ist die Frage seriös beantwortbar.

## 5. Vorbedingungen

- **GraalVM-Toolchain in CI** (gepinnte Version, je OS) — erfüllt.
- **Repräsentativer Smoke-Korpus** — vorhanden (`examples/sample-db/`), dient als Agent-Trace-Quelle und
  als Per-OS-Akzeptanz.
- **Scope-Entscheidung** — erfüllt durch Abschnitt 0.

## 6. Akzeptanzkriterien

- `nativeCompile` erzeugt je Ziel-OS (mind. x64 Linux/macOS/Windows) ein lauffähiges `d-migrate`-Binary.
- **Das Binary bietet dieselbe Kommandofläche wie die JVM-CLI**, mit identischer Aufrufsyntax nach
  [`spec/cli-spec.md`](../../../spec/cli-spec.md) und identischen Exit-Codes. Abweichungen sind Defekte.
- Das Binary besteht die Kern-Smokes: schema generate, data export/import/transfer mit Zeilen-Parität,
  mindestens ein Cross-Dialect-Sprung, reverse, profile.
- Jeder verbleibende Funktionsausschluss ist per ADR begründet und meldet sich zur Laufzeit klar
  (Phase F.5) — kein stiller Bruch, kein fehlendes Kommando.
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
