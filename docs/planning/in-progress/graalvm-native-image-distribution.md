# GraalVM Native Image (Linux/macOS/Windows)

**Status**: **IN UMSETZUNG** (`in-progress/`). **Ziel-Scope seit 2026-07-20: voller Funktionsumfang** —
das Native-Binary soll dasselbe können wie die JVM-CLI (Schwelle in Abschnitt 0).

**Ob Native ein 1.0.0-Gate bleibt, ist OFFEN** (Frage 6). Alle abgeleiteten Aussagen im Dokument führen
auf diesen Satz zurück; wo unten „1.0.0-Ziel" steht, ist es unter diesem Vorbehalt zu lesen.

**Ausführungsreihenfolge**: A, B, D, E **(erledigt)** → **F (offen, der gesamte verbleibende Kern)** →
G (nur falls F Ausschlüsse übriglässt). Die Buchstaben sind historisch gewachsen; die einzige offene
Arbeit trägt den höchsten.

**Trigger**: Die [Roadmap](roadmap.md) führt in **Milestone 1.0.0 — Stable Release** drei
noch offene ⛔-Zeilen, alle Distribution/Build: **GraalVM Native Image (Linux, macOS, Windows)**, Docker
Hub und SDKMAN. Der RC-Feature-Milestone ist feature-komplett (RC1 als Prerelease veröffentlicht); das
Native-Image ist das **einzige technisch tiefe** der drei. Dieser Plan deckt nur das Native-Image ab.

---

## 0. Scope-Entscheidung (2026-07-20) — voller Funktionsumfang

**Entscheider: Projekt-Eigner. Entscheidung: Das Native-Binary trägt den vollen Funktionsumfang der
CLI**, nicht ein Subset.

**Diese Entscheidung ersetzt die frühere Festlegung „Core-CLI-Subset zuerst".** Jene war am 2026-07-19
aus dem Phase-A-Spike heraus getroffen und im Plan als entschieden geführt worden — sie war jedoch eine
Selbsteinschätzung der Analyse, keine Eigner-Entscheidung, obwohl der Abschnitt „Vorbedingungen" genau
dafür ein Tor vorsah. Der Fehler wird hier korrigiert und die Historie stehen gelassen.

**Was gültig bleibt:** die *Messungen* aus Phase A und B. **Was entfällt:** die daraus gezogene
Folgerung, die Schwergewichts-Flächen aus dem ersten Native-Cut auszuschließen.

**Begründung:** Wer `d-migrate` als Binary installiert, erwartet `d-migrate` — nicht eine Teilmenge, die
bei einem Kommando behauptet, es gäbe es nicht.

### Schwelle (macht die Entscheidung falsifizierbar)

Ohne benannte Schwelle wäre „voller Funktionsumfang" nicht prüfbar und stünde im Widerspruch zu jedem
Ausschluss. Es gilt:

- **Nicht verhandelbar**: die Kommandogruppen aus [`spec/cli-spec.md`](../../../spec/cli-spec.md)
  (`schema`, `data`, `export`, `mcp`, `config`) sind im Native-Binary **vollständig vorhanden und
  funktionsfähig**. Fehlt eine Gruppe, ist die Voll-Scope-Zusage verletzt.
- **Verhandelbar, aber begründungspflichtig**: einzelne Subkommandos oder Formatoptionen unterhalb der
  Gruppenebene. Ein Ausschluss dort braucht Phase G (ADR + Carve-Out-Eintrag + Laufzeit-Meldung).
- **Nie zulässig**: stilles Fehlen. Ein Kommando, das die JVM-CLI kennt, muss das Binary ebenfalls
  kennen — es darf nur begründet ablehnen.

### Konsequenz für den bisherigen Bau

Der reduzierte Entrypoint `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/NativeMain.kt` wird
**zurückgebaut** (Phase F.1). Der native Entrypoint wird `dev.dmigrate.cli.MainKt` — derselbe wie beim
Fat-JAR. Damit entfallen **drei** Divergenzen, die der zweite Kommandobaum eingeschleppt hatte und die
am 2026-07-20 beim Abgleich gegen `spec/cli-spec.md` gefunden wurden:

1. **Spec-Bruch in der Aufrufsyntax.** Die Spec schreibt `--source <path>` vor
   ([`spec/cli-spec.md`](../../../spec/cli-spec.md), Abschnitte zu `schema validate`/`schema generate`);
   `NativeMain.kt` nutzt ein positionales `FILE`.
2. **`schema generate` überspringt die Validierung.** Der echte `SchemaGenerateRunner` fährt vor dem
   Rendern `SchemaValidator` (Exit 3) und den dialektspezifischen `PreGenerationValidator` (Exit 3) und
   baut `DdlGenerationOptions` (spatialProfile, mysql-/sqlite-Sequence-Modi, `deterministic`,
   `SOURCE_DATE_EPOCH`). `NativeMain.kt` tut nichts davon.
3. **Acht fehlende `generate`-Flags**: `--output`, `--report`, `--generate-rollback`, `--deterministic`,
   `--spatial-profile`, `--split`, `--mysql-named-sequences`, `--sqlite-named-sequences`.
   Ebenso fehlt die globale Option `--output-format json` (in `Main.kt` am Wurzel-Command).
   Parse-Fehler ergeben in der JVM-CLI Exit 7 (`SchemaValidateWiring`), im Native-Baum nicht.

**Nicht auf dieser Liste — bewusst korrigiert:** `--source -` (stdin) für `schema validate`. Die
JVM-CLI kann das **ebenfalls nicht** (`SchemaValidateCommand` nutzt `.path(mustExist = true)`, was `-`
ablehnt). Das ist eine **bestehende Lücke zwischen Spec und Code**, nicht von `NativeMain.kt`
eingeschleppt — der Entrypoint-Merge löst sie **nicht**. Sie gehört als eigener Befund verfolgt, nicht
in diesen Plan.

### Was von der bisherigen Arbeit trägt

| Artefakt | Scope-abhängig? |
| --- | --- |
| Gradle-Plugin, GraalVM-Toolchain-Anbindung, `--initialize-at-build-time`-Rezept | nein — trägt |
| CI-Mechanik in [`.github/workflows/native-image.yml`](../../../.github/workflows/native-image.yml): 3-OS-Matrix, `uname`-Ableitung, Artefakt-Upload, `attach-release`-Job | nein — trägt |
| Toolchain-Findings, shadowJar-Falle | nein — trägt |
| `NativeMain.kt` (reduzierter Kommandobaum) | **ja — wird gelöscht** |
| `mainClass.set("dev.dmigrate.cli.NativeMainKt")` in [`adapters/driving/cli/build.gradle.kts`](../../../adapters/driving/cli/build.gradle.kts) | **ja — muss auf `MainKt`** |
| Smoke-Steps in `native-image.yml` (positionale Syntax) | **ja — auf `--source` umstellen** |
| Abschnitt 4.4.2 in [`docs/user/releasing.md`](../../user/releasing.md) (beschreibt Core-Subset) | **ja — muss nachgeführt werden** |

---

## Wiedereinstieg (Stand 2026-07-20)

**Geliefert und verifiziert (auf `develop`):**
- Gradle-Plugin `org.graalvm.buildtools.native` (0.10.3) mit `--no-fallback` und
  `--initialize-at-build-time=ch.qos.logback,org.slf4j`, `toolchainDetection=false` (opt-in, hält den
  JDK-21-Build unberührt).
- 3-OS-CI-Matrix — **alle drei Legs grün verifiziert** (Dispatch-Lauf 29717820742, 2026-07-20:
  ubuntu 5m34s, windows 7m41s, macos 8m11s). Artefakte bestätigen `macos-latest` = **arm64**.
- Release-Asset-Anhang (Phase E) — verifiziert erst am nächsten Tag-Cut (nur bei `tags: v*` aktiv).
- `workflow_dispatch` registriert (Config-Commit `05f1a229` auf `main`).

**Nächster Schritt: Phase F.0** (Messung der vollen Fläche). Details unten.

**Lokal reproduzieren** (native-image läuft **nicht** im docker-Build — braucht GraalVM):
```
# GraalVM CE 21 (Community) als JAVA_HOME + GRAALVM_HOME, dann:
DMIGRATE_ALLOW_LOCAL_GRADLE=1 ./gradlew :adapters:driving:cli:nativeCompile
# Binary: adapters/driving/cli/build/native/nativeCompile/d-migrate
```

**Keine Kompatibilitätszusage für bisherige Artefakte.** Es hängt noch **kein** Native-Asset an einem
Release; Dispatch-Läufe liefern reine Workflow-Artefakte. F.1 ändert die Aufrufsyntax (positionales
`FILE` → `--source`) ohne Übergangsfrist.

**Gotchas (belegt):**
- ~~`workflow_dispatch` greift erst nach Default-Branch-Registrierung~~ — **erledigt 2026-07-20**
  (`05f1a229`). **Verallgemeinert:** alles Default-Branch-Gebundene (auch die Dependabot-Config) wirkt
  nur, wenn es auf `main` liegt.
- **Nicht die shadowJar** nativ bauen — immer über den echten Modul-Classpath (Gradle-Plugin).
- **`--no-fallback` verschiebt Fehler in die Laufzeit.** Fehlende Reflection-/Resource-Metadaten brechen
  **nicht** den Build. Ein grüner `nativeCompile` ist **kein** Blocker-Nachweis (s. F.0).

## 1. Ausgangslage

- Die CLI (`adapters/driving/cli`, `mainClass = dev.dmigrate.cli.MainKt`, Fat-JAR via shadow, OCI-Image
  via **jib**) zieht eine große Runtime-Fläche: `hexagon:core`/`application`/`profiling`,
  `driver-common`, alle drei Treiber (+ Profiling), `formats`, `formats-parquet`, `integrations`,
  `persistence-jdbc`, `persistence-memory`, `streaming`, `audit-logging`, `text-icu`,
  `connection-config`, `storage-file`, `storage-s3`, `mcp` sowie extern clikt, logback-classic, slf4j,
  snakeyaml-engine.
- **Der Startpfad ist die kritischste Fläche.** `Main.kt` ruft `registerDrivers()` →
  `RuntimeBootstrap.initialize()` → `DatabaseDriverRegistry.loadAll()` →
  `java.util.ServiceLoader.load(DatabaseDriver::class.java)` **unbedingt vor dem Argument-Parsing auf,
  auch bei `--help`/`--version`**. Ebenso ist `IcuUnicodeTextService()` eine **eager property** am
  Wurzel-Command. Damit gaten ServiceLoader, die drei JDBC-Treiber (inkl. sqlite-JNI) und ICU **jeden
  Aufruf** — sie sind nicht kommando-lokal. Der **ServiceLoader ist die tragendste Reflection-Naht der
  CLI**.
- Reflection-/resource-/JNI-lastig sind: logback + snakeyaml-engine, AWS SDK v2 via `storage-s3`,
  ICU4J via `text-icu`, `formats-parquet` (parquet-hadoop + hadoop-common — Hadoop ist notorisch
  native-image-feindlich), **Flyway via `persistence-jdbc`** und der **sqlite-jdbc-JNI-Nativelib**
  (extrahiert eine `.so`/`.dll`/`.dylib` zur Laufzeit).
- **Korrektur zu früheren Fassungen dieses Plans:** Flyway hängt **nicht** an `integrations` — dieses
  Modul hat genau eine Dependency (`api(project(":hexagon:ports"))`), der Tool-Export ist reines
  Rendering. Flyway kommt aus [`adapters/driven/persistence-jdbc/build.gradle.kts`](../../../adapters/driven/persistence-jdbc/build.gradle.kts).
  Und **netty ist im Repo gar nicht vorhanden**: [`adapters/driven/storage-s3/build.gradle.kts`](../../../adapters/driven/storage-s3/build.gradle.kts)
  schließt `netty-nio-client` per `configurations.all { exclude(...) }` aus (Transport ist
  `url-connection-client`); `mcp` nutzt ktor-**CIO**.
- **Positiv — Native-Image-Disziplin existiert schon:** [`adapters/driven/formats-parquet/build.gradle.kts`](../../../adapters/driven/formats-parquet/build.gradle.kts)
  vermeidet bewusst Protobuf-Reflection; `storage-s3` nennt ausdrücklich ein Footprint-Ziel.
- **JNA ist rein transitiv und inert** (kein Gradle-Eintrag, kein `Native.load()` im Repo). Der
  `keychain:`-Zugriff ist native-frei (nur `ProcessBuilder`).
- **Kein committetes Reachability-Metadata**; GRMR ist im Plugin **nicht** aktiviert.

## 2. Werkzeug und Ansatz

- **Gradle-Plugin** `org.graalvm.buildtools.native` (geliefert), `nativeCompile` künftig auf
  `dev.dmigrate.cli.MainKt`.
- **Metadaten** zweigleisig: (a) **GraalVM Reachability Metadata Repository** für verbreitete Libs
  (logback, snakeyaml, AWS SDK, sqlite-jdbc), (b) der **Tracing-Agent**
  (`-agentlib:native-image-agent`) über repräsentative CLI-Durchläufe.
- **Der vorhandene Korpus reicht dafür NICHT aus.** Die `examples/sample-db/`-Smokes decken
  `schema reverse/validate/generate/compare/migrate/rollback` und `data transfer/export/import` ab —
  also den Kern. Sie enthalten aber **null** Läufe für `data profile`, Parquet-Export, Tool-Export
  (flyway/liquibase/django/knex), `mcp serve` und S3-Ziele. Genau diese Flächen braucht Phase F.4.
  Korpus-Erweiterung ist damit ein eigener Arbeitspunkt (F.2), keine Voraussetzung, die schon erfüllt
  wäre.
- **Agent-Anbindung ist Container-Plumbing.** Die Smokes rufen die CLI als Compose-Service gegen das
  Image `d-migrate:dev` auf, nicht als nackte JVM. Der Agent muss in den containerisierten JVM-Start
  injiziert, die erzeugte Config aus dem Container herausgereicht und über ~19 Skripte per
  `--config-merge-dir` gemergt werden.
- **Testnaht für das Binary**: `test/e2e-cli` startet die echte CLI bereits als Subprozess
  (`RealCliSubprocess`), fest verdrahtet auf `java -cp <classpath> dev.dmigrate.cli.MainKt`. Ein
  `DMIGRATE_CLI_BIN`-Override ist der billigste Weg, die **bestehende E2E-Suite** gegen das Native-Binary
  zu fahren — besser als neue ad-hoc-Shell-Smokes.
- **Reproduzierbarkeit**: Native-Build in CI mit gepinnter GraalVM-Version, nicht auf Entwickler-Laptops
  als Quelle der Wahrheit.

## 3. Phasen

### Phase A — Machbarkeits-Spike (abgeschlossen 2026-07-19)
Gelaufen. Messungen unter „Phase A — Ergebnisse". Die dort gezogene Scope-Folgerung ist durch
Abschnitt 0 ersetzt.

### Phase B — Kern-Viabilität (abgeschlossen 2026-07-19)
Gelaufen. Der Kern ist native-image-fähig, das Rezept steht. Der dabei gebaute reduzierte Entrypoint
wird in F.1 zurückgebaut.

### Phase D — 3-OS-CI-Matrix (abgeschlossen 2026-07-20)
✅ Alle drei Legs in CI grün verifiziert (Dispatch-Lauf 29717820742). `fail-fast: false`,
`timeout-minutes: 60`, job-weites `shell: bash`; `graalvm/setup-graalvm` bringt auf Windows den
MSVC-Kontext mit.

**Architektur-Ist**: `linux-x64`, **`macos-arm64`**, `windows-x64` — ein macOS-x64-Leg existiert nicht,
weil `macos-latest` arm64 ist. Ziel ist damit „mindestens **eine** Architektur je OS", nicht „x64 je OS".
Offene Architektur-/Linkfragen stehen ausschließlich unter Fragen 3 und 4 (keine Doppelbuchführung hier).

### Phase E — Release-Integration (abgeschlossen 2026-07-20)
✅ Eigener `attach-release`-Job sammelt die Artefakte aller Legs (`download-artifact` mit `pattern` +
`merge-multiple`) und hängt sie per `gh release upload --clobber` an. Namensschema
`d-migrate-<version>-<os>-<arch>[.exe]` + `.sha256`; OS/Architektur zur Laufzeit aus `uname`.
- **Bewusst ein einzelner Uploader** — hält `contents: write` auf einen Job begrenzt.
- **Bewusst kein `gh release create`** — das Release kommt aus `release-homebrew.yml`; der Job wartet
  (10 × 30 s) und wird danach rot.
- Doku: `docs/user/releasing.md` 4.4.2, Verifikation 4.8, Release-Checkliste.
- **Verifikation erst am nächsten Tag-Cut.**

### Phase F — Voller Funktionsumfang (der verbleibende Kern)

Ersetzt die frühere Phase C. Statt „pro Fläche entscheiden, ob sie rausfliegt" gilt: **pro Fläche
native-fähig machen.** Ein Ausschluss ist die Ausnahme und wandert nach Phase G.

#### F.0 — Messung der vollen Fläche (blockiert alles Weitere)
Die Konfiguration „voller Entrypoint gegen echten Modul-Classpath" ist **ungetestet**: Phase A probierte
nur die shadowJar (brach an einem *Verpackungsfehler* ab — keine Machbarkeitsaussage), Phase B baute den
reduzierten Entrypoint.

- **AP 1 — Bau**: `mainClass` auf `dev.dmigrate.cli.MainKt`, `nativeCompile` fahren. Als
  `workflow_dispatch`-Eingabe, damit `develop` keinen wissentlich roten Workflow trägt.
- **AP 2 — Laufzeit, der eigentliche Nachweis**: Das erzeugte Binary **Kommando für Kommando** gegen den
  Korpus ausführen und Laufzeitfehler protokollieren. Wegen `--no-fallback` bricht fehlende
  Metadaten-Abdeckung erst hier — **ein grüner Build ist kein Blocker-Nachweis.**
- **AP 3 — Startpfad zuerst**: `d-migrate --version` und `--help` sind der schärfste Einzeltest, weil sie
  ServiceLoader, alle drei Treiber und ICU auslösen (s. Abschnitt 1). Schlägt das fehl, ist alles Weitere
  gegenstandslos.
- **AP 4 — Was steckt drin**: Aus dem Build-Report belegen, was tatsächlich erreichbar ist. Die bisherige
  Aussage „das Binary bleibt JDBC-frei" stützte sich allein auf ein Größendelta.
- **AP 5 — Ressourcenbudget**: Bau-Zeit und Speicherbedarf messen. Das `timeout-minutes: 60` ist am
  **reduzierten** Entrypoint gemessen; der volle Bau zieht Hadoop, ICU und AWS SDK. Der Phase-A-Spike lief
  lokal auf 31 GiB RAM, GitHub-Runner haben rund 7 GB — ein `-J-Xmx`-Bedarf oder OOM ist ein absehbarer
  Ausfallmodus.
- **Ergebnis**: konkrete Blockerliste plus Reihenfolge für F.3/F.4 — beides Voraussetzung, um Frage 6
  seriös zu beantworten.

#### F.1 — Entrypoint zusammenführen
Rückbau-Liste, vollständig (fail-closed-Gates hängen daran):
- `graalvmNative`-`mainClass` dauerhaft auf `dev.dmigrate.cli.MainKt`.
- `NativeMain.kt` löschen.
- **Kover-Exclude auf BEIDEN Seiten** entfernen: der Gradle-Eintrag `"dev.dmigrate.cli.Native*"` in
  `adapters/driving/cli/build.gradle.kts` **und** die Zeile in
  [`docs/coverage/excludes-ledger.md`](../../coverage/excludes-ledger.md).
  `scripts/verify-kover-excludes-ledger.py` prüft **in beide Richtungen** — nur eine Seite zu entfernen
  bricht `make coverage-excludes-check`.
- Per-OS-Smokes in `native-image.yml` auf die spec-konforme Syntax (`--source`) umstellen.
- `docs/user/releasing.md` 4.4.2 nachführen (beschreibt das Binary noch als Core-Subset mit
  „`reverse`, `compare`, `migrate`, `data` sind nicht enthalten") — steht unter dem `docs-check`-Gate.
- [`docs/planning/in-progress/README.md`](README.md) nachführen (nennt den Slice noch „reduzierter
  Core-Entrypoint", „Offen: macOS/Windows-Legs + Release-Assets" — beides geliefert).
- Tote Pfadverweise in `adapters/driving/cli/build.gradle.kts` korrigieren: zwei Kommentare zeigen noch
  auf diesen Plan unter `planning/next/`, er liegt aber seit `b259cfad` in `planning/in-progress/`.

#### F.2 — Metadaten-Grundlage
- **GRMR aktivieren** (`metadataRepository` im `graalvmNative`-Block).
- **Korpus erweitern** um Trace-Läufe für die in Abschnitt 2 genannten Lücken: `data profile`,
  Parquet-Export, Tool-Export (vier Ziele), `mcp serve`-Handshake, ICU-Normalisierungspfad, S3-Ziel.
  Ohne diese Erweiterung kann der Agent die von F.4 benötigten Metadaten nicht erzeugen.
- **Agent-Anbindung** bauen (Container-Injektion, Config-Herausreichung, `--config-merge-dir` über alle
  Skripte — s. Abschnitt 2).
- Erzeugte `reachability-metadata`/`reflect-config`/`resource-config`/`jni-config`/`proxy-config` beim
  CLI-Modul kolozieren und committen.
- **`DMIGRATE_CLI_BIN`-Override** in `test/e2e-cli` einziehen, damit die bestehende E2E-Suite gegen das
  Binary läuft.
- **JNA-Inertheit verifizieren**: kein erreichbarer `Native.load()`-Pfad im Binary.
- **Nebenwirkung prüfen**: committete `META-INF/native-image/**`-Ressourcen landen auch im Fat-JAR und
  im jib-Image. Ob das gegen das Footprint-Ziel aus `storage-s3` zählt, ist zu klären.

#### F.3 — Startpfad-Flächen (blockierend, direkt nach F.1)
Diese Flächen gaten **jeden** Aufruf inklusive `--help` (Begründung in Abschnitt 1) und sind daher
**nicht** parallelisierbar mit F.4:
- **ServiceLoader-Treiberregistry** (`DatabaseDriverRegistry.loadAll()`) — Reflection-Naht Nummer eins.
- **`sqlite-jdbc`-JNI** — die zur Laufzeit extrahierte Nativelib (Frage 2). sqlite-jdbc bringt eigene
  Native-Image-Metadaten mit; Ausgangspunkt günstig, aber unbelegt.
- **PostgreSQL-/MySQL-JDBC** — reines Java, gelten als unkritisch; zu belegen, nicht anzunehmen.
- **`text-icu` (ICU4J)** — eager am Wurzel-Command, Locale-/Daten-Resources.

#### F.4 — Kommando-lokale Flächen
**Ungeordnete Menge** — die Reihenfolge ist Output von F.0, nicht Vorgabe (frühere Fassungen dieses Plans
leiteten sie aus Metadaten-Eintragszählungen ab, also aus genau der Methode, die F.0 ersetzen soll):
- `formats-parquet` (parquet-hadoop + hadoop-common)
- **Flyway via `persistence-jdbc`** (nicht `integrations`, s. Abschnitt 1)
- `storage-s3` (AWS SDK v2, `url-connection-client` — kein netty)
- `mcp` (`mcp serve`, stdio) — ungeprüft; s. auch Frage 5

### Phase G — Ausschluss-Mechanik (nur falls F Ausschlüsse übriglässt)

Diese Phase existiert nur, wenn F eine Fläche nicht native-fähig bekommt. Sie ist **kein Nebensatz,
sondern echte Arbeit** — im Code existiert dafür heute **nichts**: keine
`org.graalvm.nativeimage.ImageInfo`-Nutzung, kein „nicht verfügbar"-Pfad in
`adapters/driving/cli/src/main`, kein Exit-Code in der Spec.

Arbeitspunkte, falls gebraucht:
- Image-Detektions-Prädikat (Native vs. JVM).
- Verfügbarkeits-Registry pro Kommando.
- **Exit-Code in [`spec/cli-spec.md`](../../../spec/cli-spec.md) vergeben** — Spec-Änderung, kein
  Implementierungsdetail.
- Meldungstext **plus i18n-Bundle-Eintrag** (die CLI ist lokalisiert).
- Tests.
- **ADR** für den permanenten Ausschluss **und** Eintrag in
  [`carveout.md`](carveout.md) (dort führt das Projekt bewusste Scope-Cuts).

Bedingung bleibt: das Binary **kennt** das Kommando und lehnt begründet ab — kein stilles Fehlen
(Schwelle in Abschnitt 0).

## Phase A — Ergebnisse (Spike 2026-07-19)

> Die **Messungen** bleiben gültig; die daraus damals gezogene Scope-Folgerung ist durch Abschnitt 0
> ersetzt. Die Zahlen stammen aus einem lokalen Spike und sind **im Repo nicht nachprüfbar**.

Lokaler Spike mit GraalVM CE 21.0.2 + gcc 13.3, 31 GiB RAM.

**Toolchain:** Das Build-/CI-Image `gradle:8.12-jdk21` hat **kein** GraalVM → CI braucht eine
GraalVM-/Mandrel-Toolchain je Ziel-OS.

**Nicht die shadowJar füttern:** `native-image` auf die Fat-JAR bricht in Phase [1/8] ab — sqlite-jdbc
erzwingt per gebündelter Config das Feature `org.sqlite.nativeimage.SqliteJdbcFeature`, dessen Klasse im
gemergten Jar nur als Multi-Release-Eintrag liegt. **Einordnung:** das war ein *Verpackungsfehler*, keine
Aussage über die Machbarkeit der vollen Fläche — die wurde danach nie gegen den echten Classpath probiert
(daher F.0).

**Kein fundamentaler Wall:** Mit ausgeschlossener sqlite-Config läuft die Analyse an und stoppt am
Standard-Punkt: logback/slf4j-Klassen „unintentionally initialized at build time" → Init-Direktiven.

**Metadaten-Landschaft** (Zählungen nicht nachprüfbar):
- **Native-image-bewusst**: `software.amazon.awssdk` (aws-core), sqlite-jdbc, mordant (inkl.
  `mordant-jvm-graal-ffi` = JNA-freie GraalVM-Variante), jansi.
- **Null Metadaten**: Hadoop (12.392 Einträge), Parquet (4.418), ICU4J (5.689), Flyway (498).
- **Korrektur:** Liquibase ist nicht im CLI-Runtime-Jar (0 Einträge) — nur Flyway ist präsent, und zwar
  über `persistence-jdbc`.

## Phase B — Ergebnisse (2026-07-19): Kern-Native-Viabilität BEWIESEN

> Gültig als Machbarkeitsnachweis **für den Kern** und als Rezept. Der reduzierte Entrypoint wird nach
> Abschnitt 0 zurückgebaut. Messwerte spike-lokal, nicht nachprüfbar.

Minimaler Entrypoint (`SchemaFileResolver` → `SchemaValidator`), also Core + `formats` (snakeyaml),
**ohne** ICU/Treiber/clikt/Parquet. `native-image` lief durch alle 8 Phasen (Exit 0) → 42-MB-Binary,
korrekt gegen `examples/sample-db/calib-schema.yaml`.

**Rezept:**
- `--initialize-at-build-time=ch.qos.logback,org.slf4j` — der logback/slf4j-Standardfix.
- **snakeyaml braucht KEINE manuelle Reflection-Config** (Typen wurden automatisch registriert).
- **sqlite** nur über den echten Modul-Classpath, nicht die Fat-JAR.

**Wichtige Einschränkung:** Der Nachweis gilt für einen Entrypoint **ohne** ServiceLoader, ohne Treiber
und ohne ICU — also gerade ohne die Flächen, die Abschnitt 1 als Startpfad-kritisch ausweist. Er trägt
**nicht** auf die volle Fläche.

## 4. Offene Fragen / Entscheidungen

1. ✅ **Entschieden 2026-07-20 (Projekt-Eigner): voller Funktionsumfang**, Schwelle in Abschnitt 0.
2. **`sqlite-jdbc`-JNI** — jetzt in **F.3** (Startpfad), nicht hinter den kommando-lokalen Flächen:
   die Treiberregistrierung läuft bei jedem Aufruf. Funktioniert die Laufzeit-Extraktion der Nativelib
   aus einem Native-Image-Binary, oder braucht es Build-Zeit-Einbettung/Substitution?
3. **Statisches Linken (Linux)**: `--static`/`--static-nolibc` (musl) vs. dynamisch gegen glibc.
4. **Architekturen**: Ist-Stand ist eine Architektur je OS (`linux-x64`, `macos-arm64`, `windows-x64`).
   Offene Restfrage: **linux-arm64 ja/nein?**
5. **MCP im Native-Binary**: `mcp serve` (stdio) ist ungeprüft — Bearbeitung in **F.4**.
6. **Offen, Eigner-Entscheidung: bleibt Native ein 1.0.0-Gate?** Voller Funktionsumfang bedeutet
   Metadaten-Arbeit mit offenem Ausgang. **F.0 liefert die Grundlage** — vorher ist die Frage nicht
   seriös beantwortbar.
7. **Spec-Lücken, die das Akzeptanzkriterium berühren**: `spec/cli-spec.md` listet
   `--sqlite-named-sequences` nicht in der `generate`-Flag-Tabelle (obwohl implementiert) und fordert
   `--source -` (stdin) für `schema validate`, was der Code nicht kann. „Identische Aufrufsyntax nach
   cli-spec.md" setzt voraus, dass die Spec selbst stimmt — beides ist separat zu klären.

## 5. Vorbedingungen

- **GraalVM-Toolchain in CI** (gepinnt, je OS) — **erfüllt**.
- **Repräsentativer Smoke-Korpus** — **teilweise erfüllt**: der Kern ist abgedeckt, die
  Schwergewichts-Flächen (Parquet, Tool-Export, MCP, ICU, S3, `data profile`) **nicht**. Erweiterung ist
  Arbeitspunkt in F.2.
- **Scope-Entscheidung** — **erfüllt** (Abschnitt 0).

## 6. Akzeptanzkriterien

- `nativeCompile` erzeugt je Ziel-OS ein lauffähiges `d-migrate`-Binary (mindestens eine Architektur je
  OS: `linux-x64`, `macos-arm64`, `windows-x64`).
- **Das Binary bietet dieselbe Kommandofläche wie die JVM-CLI**, mit identischer Aufrufsyntax nach
  `spec/cli-spec.md` und identischen Exit-Codes — **mit Ausnahme der per Phase G dokumentierten
  Ausschlüsse. Undokumentierte Abweichungen sind Defekte.** Die nicht verhandelbare Untergrenze steht in
  Abschnitt 0.
- **Akzeptanz zweistufig**, weil `macos-latest`/`windows-latest` keine Linux-Container fahren:
  - **Linux**: volle DB-gestützte Matrix — schema generate, data export/import/transfer mit
    Zeilen-Parität, mindestens ein Cross-Dialect-Sprung, reverse, profile.
  - **macOS/Windows**: SQLite- und dateibasierte Pfade (serverlos, damit ohne Container lauffähig) plus
    Startpfad-Smoke. Deutlich mehr als `--help`, aber nicht die volle Matrix.
- Startup-Zeit und Binärgröße gemessen und festgehalten (der Native-Image-Payoff).
- JNA bleibt **unerreichbar** (verifiziert).
- Native-Binaries hängen als versionierte, SHA-256-geprüfte Assets am GitHub-Release; `releasing.md`
  deckt die Asset-Klasse ab.

## 7. Abgrenzung

- **Keine Library-Artefakte** — der Library-Publish ist per
  [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md) /
  [ADR 0036](../../adr/0036-library-artefakte-github-packages.md) nach 2.0.0 verschoben.
- **Native ersetzt weder** das Fat-JAR **noch** das OCI-Image (jib) — es ist eine **zusätzliche**
  Distributionsklasse. Das bleibt auch bei voller Parität gültig: Fat-JAR und OCI bedienen andere
  Einsatzformen (JVM-Umgebungen, Container-Orchestrierung), nicht einen Funktionsrückstand des Binaries.
- **Docker Hub** und **SDKMAN** sind eigene Distributions-Gates — nicht Teil dieses Plans.
- Profiling-DataSketches bleibt ein bewusster Carve-Out (s. [Roadmap](roadmap.md)), post-1.0.0.
