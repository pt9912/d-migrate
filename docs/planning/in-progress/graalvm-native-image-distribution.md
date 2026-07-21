# GraalVM Native Image (Linux/macOS/Windows)

**Status**: **IN UMSETZUNG** (`in-progress/`). **Ziel-Scope seit 2026-07-20: voller Funktionsumfang** —
das Native-Binary soll dasselbe können wie die JVM-CLI (Schwelle in Abschnitt 0).

**Ob Native ein 1.0.0-Gate bleibt, ist OFFEN** (Frage 6). Alle abgeleiteten Aussagen im Dokument führen
auf diesen Satz zurück; wo unten „1.0.0-Ziel" steht, ist es unter diesem Vorbehalt zu lesen.

**Ausführungsreihenfolge**: A, B, D, E **(erledigt — E jedoch nur unter der Annahme, dass Native
optional ist)** → F **(F.0/F.1/F.2/F.3/F.4 erledigt — die VOLLE Fläche läuft nativ, inkl. `mcp serve`
+ S3; F.1-Entrypoint-Rückbau erledigt 2026-07-21: `NativeMain.kt` entfernt, native `mainClass` fest
auf `MainKt`, `nativeEntrypoint`-Schalter raus)** → G **(vermutlich hinfällig — kein Ausschluss in
Sicht, alle Flächen laufen)** → H (nur falls Frage 6 = „Native bleibt 1.0.0-Gate"). Die Buchstaben
sind historisch gewachsen.

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

Der reduzierte Zweit-Entrypoint `NativeMain.kt` wurde in **Phase F.1 gelöscht** (erledigt 2026-07-21).
Der native Entrypoint ist jetzt `dev.dmigrate.cli.MainKt` — derselbe wie beim Fat-JAR. Damit entfielen
**drei** Divergenzen, die der zweite Kommandobaum eingeschleppt hatte und die am 2026-07-20 beim
Abgleich gegen `spec/cli-spec.md` gefunden wurden:

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

## Wiedereinstieg (EOD 2026-07-20) — morgen hier anknüpfen

**Die volle CLI läuft nativ auf allen drei Plattformen — ALLE Flächen, kein Ausschluss.** 17 von 17
Sonden grün; die zwei Subprozess-E2Es (`McpRealCliSubprocessTest`, `McpS3SubprocessE2ETest`) grün
gegen das Binary. F.0/F.2/F.3/F.4 sind erledigt.

### F.1 (Entrypoint zusammenführen) — ✅ ERLEDIGT 2026-07-21

**War kritisch, mit Release-Relevanz:** Der ausgelieferte Default war `nativeEntrypoint=core` =
`NativeMainKt` = das **reduzierte** Binary; der Tag-Pfad in `native-image.yml` baute damit **das alte
Subset**, nicht die volle CLI. F.1 macht die Voll-Scope-Entscheidung wirksam. Umgesetzt: `mainClass`
fest auf `dev.dmigrate.cli.MainKt`, `NativeMain.kt` gelöscht, den `nativeEntrypoint`-Schalter (gradle +
`native-image.yml`-Input/Env) entfernt, Kover-Exclude `dev.dmigrate.cli.Native*` auf **beiden** Seiten
raus (build.gradle.kts + excludes-ledger.md), Per-OS-Smoke auf `--source`-Syntax der vollen CLI,
`releasing.md` 4.4.2 + README nachgezogen. Der `full`-Bau ist jetzt der einzige; der Per-OS-Smoke gatet
ihn (DB-frei). Ongoing native-probe/native-e2e-CI-Gating bleibt der Slice `native-e2e-regression-gate`.

### Was heute Nachmittag dazukam (nach dem „Phase F — Ergebnisse"-Commit)

- **`mcp serve` nativ gefixt** (`3817e572`): leeres Fehlerobjekt → dreistufig (lsp4j-TypeAdapter +
  MCP-DTO-Konstruktoren, beide handgepflegt in `cli-manual/`). Ursache rückwärts über einen DEBUG-Log
  in `McpServiceImpl.renderError` gefunden.
- **S3 nativ gefixt** (`ec9fa2fd`): `--enable-url-protocols=http,https` — das AWS SDK parst seine
  Endpoint-URL über `java.net.URL`, native-image aktiviert http/https nicht per Default. Voller
  S3-Round-Trip gegen echtes SeaweedFS grün.
- **GraalVM 25 statt 21.0.2** (`57829f31`): nötig, weil GRMR 1.0.7 das neue `reachability-metadata.json`-
  Schema verlangt, das 21 nicht kennt. Kotlin 2.1.20 kann auf JDK 25 nicht STARTEN → **JAVA_HOME (JDK 21,
  Gradle+Kotlin) / GRAALVM_HOME (25, native-image) getrennt**, im Dockerfile UND Workflow.
- **GRMR aktiviert** (1.0.7) — aber **nachweislich wirkungslos** für unsere Blocker (HikariCP:
  unterstützt laut `make native-check-lib`, greift bei uns trotzdem nicht; lsp4j: nicht unterstützt).
  Bleibt an, kostet nichts, trägt nichts. Metadaten sind Agent-erhoben + handgepflegt.
- **`native-e2e` über COPY --from** (`afb1ebcd`): Stage `integration-test-native` holt das Binary aus
  `d-migrate:native-build`, kein Host-`build/`-Extract. `make native-e2e` fährt die Subprozess-E2Es.
- **`make native-check-lib LIB=…`** (`8ff76038`): GRMR-Unterstützungsabfrage, Skript SHA-gepinnt.
- **Doku-Diagnose-Härtung** bleibt: `McpServiceImpl.renderError` DEBUG-Log, S3-Store-SizeMismatch-WARN,
  E2E-Clue mit Kind-stderr.

### Lokale Schleife (~4 min/Runde, alles make+docker, GraalVM 25 im Image)
```
make native-build        # volles Binary bauen: -PnativeEntrypoint=full (Default im Makefile)
make native-probe        # 17 Sonden dagegen (ICU, sqlite-JNI, DDL, Parquet, Tool-Export, mcp, S3)
make native-agent        # Reachability-Metadaten per Tracing-Agent erheben -> META-INF/.../cli/
make native-e2e          # Subprozess-E2Es gegen das Binary (COPY --from, kein Host-build/)
make native-check-lib LIB=com.zaxxer:HikariCP:6.2.1   # GRMR-Unterstützung pruefen
```

**Keine Kompatibilitätszusage für bisherige Artefakte.** Es hängt noch **kein** Native-Asset an einem
Release; Dispatch-Läufe liefern reine Workflow-Artefakte. F.1 ändert die Aufrufsyntax (positionales
`FILE` → `--source`) ohne Übergangsfrist.

**Gotchas (belegt):**
- ~~`workflow_dispatch` greift erst nach Default-Branch-Registrierung~~ — **erledigt 2026-07-20**
  (`05f1a229`). **Verallgemeinert:** alles Default-Branch-Gebundene (auch die Dependabot-Config) wirkt
  nur, wenn es auf `main` liegt.
- **Nicht die shadowJar** nativ bauen — immer über den echten Modul-Classpath (Gradle-Plugin).
- **Ein grüner `nativeCompile` ist KEIN Blocker-Nachweis.** Fehlende Reflection-/Resource-Metadaten
  fallen typischerweise erst **bei Ausführung** auf — das gilt **unabhängig von `--no-fallback`**.
  `--no-fallback` bewirkt lediglich, dass kein JVM-abhängiges Fallback-Image erzeugt wird: entweder ein
  eigenständiges Binary oder ein Buildfehler. Es „verschiebt" nichts in die Laufzeit (frühere Fassungen
  dieses Plans behaupteten das). Konsequenz bleibt dieselbe: kommandoweise ausführen (F.0 AP 3/AP 4).

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
  Wurzel-Command. Damit gaten ServiceLoader-Auflösung, die Konstruktion der drei Treiber und die des
  ICU-Providers **jeden Aufruf** — sie sind nicht kommando-lokal. Der **ServiceLoader ist die tragendste
  Reflection-Naht der CLI**.
- **Genau unterscheiden, was der Startpfad beweist.** Er zeigt *Klassen-Erreichbarkeit und
  Provider-Konstruktion*, **nicht** die Funktion von JNI oder Ressourcen: `SqliteDriver` hat keinen
  Konstruktor-Body und lädt beim Start **keine** Nativelib; `IcuUnicodeTextService` ist ebenfalls
  konstruktionsseitig leer — ICU wird erst in `normalize`/`graphemeCount` benutzt. sqlite-JNI und
  ICU-Daten-Resources brauchen deshalb **echte Nutzung** zum Nachweis, nicht `--help`.
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
- **JNA: kein `Native.load()` im eigenen Repo**, der `keychain:`-Zugriff ist native-frei (nur
  `ProcessBuilder`). **Korrektur 2026-07-21:** „rein transitiv und inert" stimmte NICHT — clikt zieht
  mordant-omnibus, das den JNA-Terminal-Provider per ServiceLoader registriert, den native-image
  reachable machte (40 `com.sun.jna.*`-Klassen im Binary, verifiziert per `-H:+PrintAnalysisCallTree`).
  Seit 2026-07-21 sind `net.java.dev.jna:jna` + `mordant-jvm-jna` aus dem CLI-Modul ausgeschlossen →
  JNA nachweislich unreachable (Details: F.2 / Akzeptanzkriterien).
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
- **`export flyway` erreicht die Flyway-Library NICHT.** `ToolExportWiring.exporterFor` konstruiert den
  projekteigenen `FlywayMigrationExporter` — reines Rendering, kein `org.flywaydb`-Import im CLI- oder
  `integrations`-Quellpfad. Die **echte** Flyway-Library läuft ausschließlich über den
  `JdbcMigrationRunner` beim JDBC-Server-State-Start von `mcp serve`
  (`McpServeWiring.applyOrValidateMigrations`). Für Flyway-Metadaten braucht der Korpus deshalb einen
  **MCP-Start mit PostgreSQL-Server-State und Migrate/Validate** — ein Tool-Export-Lauf oder ein
  MCP-Standard-Handshake erzeugt sie nicht.
- **Agent-Anbindung ist Container-Plumbing.** Die Smokes rufen die CLI als Compose-Service gegen das
  Image `d-migrate:dev` auf, nicht als nackte JVM. Der Agent muss in den containerisierten JVM-Start
  injiziert, die erzeugte Config aus dem Container herausgereicht und über ~19 Skripte per
  `--config-merge-dir` gemergt werden.
- ✅ **Testnaht für das Binary GELIEFERT** (`6dc4e916`): `DMIGRATE_CLI_BIN` schaltet
  `test/e2e-cli`-`RealCliSubprocess` auf das Native-Binary um; `scripts/test-integration-docker.sh`
  reicht es in den Container. Die frühere Sorge „nur zwei Testklassen" war für den Zweck genau richtig:
  die **Subprozess**-E2Es (`McpS3SubprocessE2ETest`, `McpRealCliSubprocessTest`, MCP-Szenarien) sind
  der Kern — die in-process-E2Es (`DMigrate()` direkt) bringen gegen das Binary nichts. Genau diese
  Suite hat **zwei native Defekte gefunden, die der Sondenlauf nicht fand**: `mcp serve` (leeres
  Fehlerobjekt) und die S3-Operation (`--enable-url-protocols`). **Offen ist die CI-Verdrahtung** →
  eigener Slice [`native-e2e-regression-gate`](../next/native-e2e-regression-gate.md).
- **Reproduzierbarkeit**: Native-Build in CI mit **patch-genau gepinnter** GraalVM-Version, nicht auf
  Entwickler-Laptops als Quelle der Wahrheit. Bis 2026-07-20 stand dort `java-version: '21'` — nur die
  Action war gepinnt, die JDK-Version floatete. Das ist deshalb keine Kosmetik, weil committete
  Reachability-Metadaten sonst still gegen eine andere GraalVM-Version laufen. Jetzt `21.0.2`.

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
- ⚠️ **Abgeschlossen nur unter der Annahme „Native ist optional" (Frage 6 offen).** Die heutige Mechanik
  ist bewusst **nicht** release-blockierend: `release-homebrew.yml` publiziert das Release unabhängig,
  der Native-Workflow wartet nur darauf und lädt **nachträglich** hoch; `releasing.md` erklärt ein
  fehlendes Native-Asset ausdrücklich für zulässig („der Release selbst bleibt gültig"). **Wird Frage 6
  mit „Native bleibt 1.0.0-Gate" beantwortet, ist Phase E NICHT abgeschlossen** — dann fehlt Phase H.

### Phase F — Voller Funktionsumfang (der verbleibende Kern)

> **Ergebnis 2026-07-20: F.0 beantwortet, F.2 im Kern geliefert, F.3 als Nebenprodukt erledigt.**
> Die volle CLI laeuft nativ auf **allen drei Plattformen** — je neun von neun Sonden auf Exit 0
> (Lauf 29727572204). Zusammenfassung in „Phase F — Ergebnisse" unten; die Unterabschnitte behalten
> ihren urspruenglichen Wortlaut als Nachweis dessen, was vorher geplant und angenommen war.

Ersetzt die frühere Phase C. Statt „pro Fläche entscheiden, ob sie rausfliegt" gilt: **pro Fläche
native-fähig machen.** Ein Ausschluss ist die Ausnahme und wandert nach Phase G.

#### F.0 — Messung der vollen Fläche (blockiert alles Weitere)
Die Konfiguration „voller Entrypoint gegen echten Modul-Classpath" ist **ungetestet**: Phase A probierte
nur die shadowJar (brach an einem *Verpackungsfehler* ab — keine Machbarkeitsaussage), Phase B baute den
reduzierten Entrypoint.

**Scope-Grenze (wichtig, sonst zirkulär):** F.0 kann nur prüfen, was der **heutige** Korpus hergibt.
Die kommando-lokalen Flächen (F.4) fehlen dort und werden erst in F.2 ergänzt — F.0 kann für sie also
weder Blocker noch Reihenfolge liefern. F.0 ist deshalb auf **Bau- und Startpfad-Triage** begrenzt; die
F.4-Reihenfolge ist Output der **Korpus-Erweiterung**, nicht von F.0.

- **AP 1 — Bau**: `mainClass` auf `dev.dmigrate.cli.MainKt`, `nativeCompile` fahren. Als
  `workflow_dispatch`-Eingabe, damit `develop` keinen wissentlich roten Workflow trägt.
- **AP 2 — Startpfad-Triage**: `d-migrate --version`/`--help` ausführen. Das ist der schärfste
  *verfügbare* Einzeltest, weil es ServiceLoader-Auflösung, Treiber-**Instanziierung** und die
  ICU-Provider-Konstruktion auslöst (s. Abschnitt 1). Schlägt es fehl, ist alles Weitere gegenstandslos.
  **Grenze des Beweises**: es zeigt *Klassen-Erreichbarkeit und Provider-Konstruktion* — **nicht**, dass
  sqlite-JNI oder ICU-Ressourcen funktionieren (s. AP 3).
- **AP 3 — Ausführung dessen, was `--help` nicht beweist**: `SqliteDriver` lädt konstruktionsseitig
  **keine** Nativelib, `IcuUnicodeTextService` hat einen leeren Konstruktor — beide brauchen echte
  Nutzung. Also mindestens: eine SQLite-Operation gegen eine Datei (löst die JNI-Extraktion aus) und ein
  Pfad über `normalize`/`graphemeCount` (löst ICU-Daten-Resources aus).
- **AP 4 — Kern-Kommandos gegen den heutigen Korpus**: schema- und data-Pfade kommandoweise ausführen
  und Laufzeitfehler protokollieren. Der Build allein ist kein Blocker-Nachweis (s. Gotcha zu
  `--no-fallback`).
- **AP 5 — Was steckt drin**: Aus dem Build-Report belegen, was tatsächlich erreichbar ist. Die bisherige
  Aussage „das Binary bleibt JDBC-frei" stützte sich allein auf ein Größendelta.
- **AP 6 — Ressourcenbudget**: Bau-Zeit und Speicherbedarf messen. Das `timeout-minutes: 60` ist am
  **reduzierten** Entrypoint gemessen; der volle Bau zieht Hadoop, ICU und AWS SDK. Der Phase-A-Spike lief
  lokal auf 31 GiB RAM, GitHub-Runner haben rund 7 GB — ein `-J-Xmx`-Bedarf oder OOM ist ein absehbarer
  Ausfallmodus.
- **Ergebnis**: Blockerliste und Reihenfolge **für den Startpfad (F.3)** plus Ressourcenbudget. Für
  Frage 6 ist das die halbe Grundlage; die andere Hälfte liefert die Korpus-Erweiterung in F.2.

#### F.1 — Entrypoint zusammenführen  ✅ erledigt 2026-07-21
Rückbau-Liste, vollständig umgesetzt (fail-closed-Gates hängen daran):
- ✅ `graalvmNative`-`mainClass` dauerhaft auf `dev.dmigrate.cli.MainKt`.
- ✅ `NativeMain.kt` gelöscht.
- ✅ **Kover-Exclude auf BEIDEN Seiten** entfernt: der Gradle-Eintrag `"dev.dmigrate.cli.Native*"` in
  `adapters/driving/cli/build.gradle.kts` **und** die Zeile in
  [`docs/coverage/excludes-ledger.md`](../../coverage/excludes-ledger.md).
  `scripts/verify-kover-excludes-ledger.py` prüft **in beide Richtungen** — nur eine Seite zu entfernen
  bräche `make coverage-excludes-check`.
- ✅ Per-OS-Smoke in `native-image.yml` auf die spec-konforme `--source`-Syntax der vollen CLI
  umgestellt (Assertion „Validation passed" statt des alten `valid=true`); F.0-Mess-Steps entfernt.
- ✅ `docs/user/releasing.md` 4.4.2 nachgeführt (volle CLI statt Core-Subset).
- ✅ [`docs/planning/in-progress/README.md`](README.md) nachgeführt.
- ✅ Tote `planning/next/`-Pfadverweise in `adapters/driving/cli/build.gradle.kts`: bereits vor F.1
  bereinigt (keine mehr vorhanden).

#### F.2 — Metadaten-Grundlage  ✅ im Kern erledigt 2026-07-20
- ✅ **GRMR aktiviert** (`metadataRepository`, version 1.0.7) — **aber wirkungslos** für unsere
  Blocker (s. „Phase F — Ergebnisse"). Bleibt an, trägt nichts. Prüfhilfe: `make native-check-lib`.
- ✅ **Korpus erweitert** (`scripts/native-probe.sh`, 17 Sonden) — alle Flächen abgedeckt.
- ✅ **Agent-Anbindung** gebaut: `make native-agent` (`docker/native-image.Dockerfile`-Stage
  `native-agent`, Tracing-Agent unter `${GRAALVM_HOME}/bin/java`, `logging.audit` als
  Deckungsnachweis). Metadaten committet unter `META-INF/native-image/dev.dmigrate/cli/`
  (`reachability-metadata.json`, GraalVM-25-Format) + handgepflegt in `cli-manual/`.
- ✅ **`DMIGRATE_CLI_BIN`-Override GELIEFERT** (`6dc4e916`); CI-Verdrahtung ausgelagert:
  [`native-e2e-regression-gate`](../next/native-e2e-regression-gate.md).
- ✅ **JNA-Inertheit verifiziert (2026-07-21)** — und war zunaechst NICHT gegeben: der
  Reachability-Report (`-H:+PrintAnalysisCallTree`) zeigte `com.sun.jna.Native.invoke*` reachable,
  eingeschleppt ueber den JNA-ServiceLoader-Provider von mordant-omnibus. Behoben durch Ausschluss von
  `net.java.dev.jna:jna` + `mordant-jvm-jna` in `adapters/driving/cli/build.gradle.kts`; danach
  `com.sun.jna.Native` 593 → 0 Strings im Binary, Terminal-Ausgabe unveraendert (mordant nutzt
  ffm/graal-ffi auf JVM bzw. nativ). Stale jna-Eintraege aus der Agent-Metadata (reachability-metadata.json)
  entfernt.
- ⬜ **Footprint-Nebenwirkung**: committete `META-INF/native-image/**`-Ressourcen landen auch im
  Fat-JAR und im jib-Image. Ob das gegen das storage-s3-Footprint-Ziel zählt, **noch offen**.

#### F.3 — Startpfad-Flächen (blockierend, direkt nach F.1)
Diese Flächen gaten **jeden** Aufruf inklusive `--help` (Begründung in Abschnitt 1) und sind daher
**nicht** parallelisierbar mit F.4:
- **ServiceLoader-Treiberregistry** (`DatabaseDriverRegistry.loadAll()`) — Reflection-Naht Nummer eins.
- **`sqlite-jdbc`-JNI** — die zur Laufzeit extrahierte Nativelib (Frage 2). sqlite-jdbc bringt eigene
  Native-Image-Metadaten mit; Ausgangspunkt günstig, aber unbelegt.
- **PostgreSQL-/MySQL-JDBC** — reines Java, gelten als unkritisch; zu belegen, nicht anzunehmen.
- **`text-icu` (ICU4J)** — eager am Wurzel-Command, Locale-/Daten-Resources.

#### F.4 — Kommando-lokale Flächen  ✅ erledigt 2026-07-20
Alle nativ funktionsfähig, per Sonde und/oder E2E belegt:
- ✅ `formats-parquet` (parquet-hadoop) — `data export --format parquet` erzeugt eine echte Datei.
- ✅ **Flyway via `persistence-jdbc`** — Tool-Export (Renderer) läuft; die echte Flyway-Library läuft
  nur über `mcp serve` mit JDBC-Server-State (nicht via `export flyway`, s. Abschnitt 2).
- ✅ `storage-s3` (AWS SDK v2, `url-connection-client`) — voller Round-Trip gegen SeaweedFS grün, nach
  `--enable-url-protocols=http,https` (`ec9fa2fd`).
- ✅ `mcp` (`mcp serve`, stdio) — `initialize`-Handshake korrekt, nach lsp4j-/DTO-Registrierung
  (`3817e572`).

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

### Phase H — Release-Orchestrierung (nur falls Frage 6 = „Native bleibt 1.0.0-Gate")

Heute ist der Native-Build **nicht** release-blockierend (Begründung unter Phase E). Solange Native
optional ist, ist das korrekt und gewollt: ein Zusatzkanal soll keinen Release aufhalten — dieselbe
Logik wie beim Docker-Hub-Spiegel.

**Wird Native zum Gate, kehrt sich das um** und diese Phase wird nötig:
- Reihenfolge umdrehen oder koppeln: der Release darf erst entstehen bzw. erst sichtbar werden, wenn
  alle OS-Legs grün sind (Job-Abhängigkeit zwischen `native-image.yml` und `release-homebrew.yml` oder
  Zusammenführung in einen Workflow).
- `fail-fast: false` neu bewerten: heute darf ein rotes Leg die anderen weiterlaufen lassen und der
  Release bleibt gültig — als Gate müsste ein rotes Leg den Release verhindern.
- `docs/user/releasing.md` 4.4.2/4.8 nachziehen: dort steht ausdrücklich „der Release selbst bleibt
  gültig", was dann nicht mehr stimmt.
- Wirkung auf die Release-Dauer beachten: der Native-Bau lag bei 5–8 min je OS **am reduzierten**
  Entrypoint; der volle Bau ist unbekannt (F.0 AP 6) und läge dann auf dem kritischen Pfad jedes Releases.

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

## Phase F — Ergebnisse (2026-07-20): volle CLI laeuft nativ

**Kernergebnis:** Der volle Funktionsumfang ist nativ machbar — gemessen, nicht geschaetzt. Je neun
von neun Sonden auf Exit 0 unter Linux, macOS und Windows (Lauf 29727572204; Windows anfangs sieben,
die beiden SQLite-Sonden scheiterten an einem Pfadfehler der Sonde selbst, nicht am Binary).
Abgedeckt: Startpfad, ICU-Daten, sqlite-JNI in Schreib- **und** Lesepfad, DDL-Rendering fuer alle drei
Dialekte, JSON-Ausgabe. `--version` liefert die echte Version statt `unknown`.

**Damit ist die Grundlage der frueheren Subset-Entscheidung widerlegt.** Hadoop/Parquet waren nie ein
Build-Blocker; die tatsaechlichen Blocker waren gewoehnliche Metadaten-Arbeit.

### Was getragen hat — und was nicht

| Mittel | Ergebnis |
| --- | --- |
| **Tracing-Agent** (`make native-agent`) | ✅ **Das war die Loesung.** reflect 199 / jni 205 / resource 46 Zeilen, committet unter `META-INF/native-image/dev.dmigrate/cli/` |
| `-H:IncludeResourceBundles=messages.messages` | ✅ ohne das stirbt jedes Subkommando im Clikt-Dispatch |
| Handgepflegte `HikariConfig`-Registrierung | ✅ s. u. |
| **GRMR** (`metadataRepository`) | ❌ **keine messbare Wirkung**, weder ohne noch mit gepinnter `version` (0.3.15, beides gemessen) |
| **`-H:MissingRegistrationReportingMode=Warn`** | ❌ **wirkungslos** fuer `forQueriedOnlyExecutable`; kein `-XX:`-Laufzeitpendant |

### Die zirkulaere Fehlerkette (das lehrreichste Detail)

Der Hikari-Blocker war ein **Folgefehler der fehlenden `logback.xml`-Ressource**. Nicht registriert →
logback faellt auf seinen eingebauten Default **DEBUG** zurueck → `HikariConfig.logConfiguration()`
laeuft → `PropertyElf.getProperty` → `Method.invoke(getCredentials)` →
`MissingReflectionRegistrationError`. Auf der JVM gilt `logback.xml` mit `root level="WARN"`, der Pfad
laeuft nie — **deshalb konnte der Agent diese Aufrufe prinzipiell nicht aufzeichnen**. Der Stacktrace
zeigte auf Hikari, die Ursache lag bei logback.

**Bleibende Fragilitaet:** schaltet ein Nutzer DEBUG-Logging ein, kehrt der Fehler zurueck — das
Binary braeche ausgerechnet bei der Fehlerdiagnose. Deshalb zusaetzlich handgepflegt registriert
unter `META-INF/native-image/dev.dmigrate/cli-manual/`, bewusst **getrennt** von der Agent-Ausgabe,
die jeder Lauf ueberschreibt.

### Lokale Schleife statt CI-Rundlauf

`make native-build` / `native-binary` / `native-probe` / `native-diagnose` / `native-agent`
(`docker/native-image.Dockerfile`, `make/native.mk`, `scripts/native-probe.sh`). Rundenzeit ~4 min
lokal gegen ~8 min (Linux) bis ~26 min (macOS) in CI. Hermetisch per COPY wie der Haupt-Build, kein
Bind-Mount. Das Sondenskript ist **eine** Quelle fuer lokal und CI.

### Ressourcenbudget (AP 6) — mit einer selbst verursachten Regression

| Plattform | Runner | native-image | GC-Anteil |
| --- | --- | --- | --- |
| Linux (CI) | 16 GB / 4 Kerne | ~5 min | — |
| Windows (CI) | 16 GB / 4 Kerne | ~4,5 min | — |
| macOS (CI) | **7 GB / 3 Kerne** | **26m20s** | **45,9 %** |
| lokal | 31 GB / 20 Kerne | 1m35s | 12,8 % |

`MaxRAMPercentage=60` war auf der 31-GB-Maschine grosszuegig (16,57 GB), auf dem macOS-Runner aber
schaedlich (3,74 GB, 253 GCs). Auf 80 % korrigiert. **Lehre:** ein fester Prozentsatz wirkt auf
kleinen Maschinen ganz anders als auf grossen — nicht aus einer Einzelmessung verallgemeinern.

### Deckungsnachweis per Audit — und seine blinden Flecken

Der Agent-Lauf faehrt mit eingeschaltetem `logging.audit`: eine Zeile je ausgefuehrter Operation
belegt, was **wirklich** lief. Das fand sofort etwas — nur 2 von 9 Sonden im Log, weil Audit nur an
**DB-Operationen** verdrahtet ist. **Konsequenz fuer F.4: `mcp serve` und Tool-Export sind ebenfalls
nicht auditiert**, also gerade dort, wo der Nachweis am noetigsten waere.

Motiv fuer den Nachweis: zweimal taeuschte ein Kommando eine Abdeckung vor, die es nicht hatte —
`calib-schema.yaml` liess `schema migrate` fachlich blocken (DB entstand, aber **kein DDL**), und
`export flyway` beruehrt die echte Flyway-Library **nie** (projekteigener Renderer).

### Offen (Stand mittags — durch Nachmittagsarbeit teils überholt, s. Wiedereinstieg)

- ~~**F.1** (Entrypoint zusammenfuehren)~~ — **erledigt 2026-07-21** (s. Wiedereinstieg oben): der
  native Bau ist jetzt ausschliesslich die volle CLI (`MainKt`).
- ~~F.4~~ — **erledigt am Nachmittag**: Parquet, Tool-Export, `mcp serve`, S3, `data profile` sind
  getraced UND nativ funktionsfähig; `mcp serve` und S3 waren defekt und wurden gefixt (`3817e572`,
  `ec9fa2fd`).
- **Binaergroesse ~190 MB** (Core-Subset war 67 MB) — Produktfrage, nicht entschieden (Frage 7).

## 4. Offene Fragen / Entscheidungen

1. ✅ **Entschieden 2026-07-20 (Projekt-Eigner): voller Funktionsumfang**, Schwelle in Abschnitt 0.
2. ✅ **Beantwortet 2026-07-20: `sqlite-jdbc`-JNI funktioniert nativ**, ohne Build-Zeit-Einbettung
   oder Substitution — Schreib- und Lesepfad je Exit 0 auf allen drei Plattformen. Der Windows-Beleg
   ist indirekt und dadurch besonders belastbar: eine fehlerhafte Pfadangabe ergab `SQLITE_CANTOPEN`,
   also einen Fehlercode **aus der nativen Bibliothek selbst** — sie war folglich geladen.
3. **Statisches Linken (Linux)**: `--static`/`--static-nolibc` (musl) vs. dynamisch gegen glibc.
4. **Architekturen**: Ist-Stand ist eine Architektur je OS (`linux-x64`, `macos-arm64`, `windows-x64`).
   Offene Restfrage: **linux-arm64 ja/nein?**
5. ✅ **Beantwortet 2026-07-20: `mcp serve` läuft nativ** (`3817e572`) — stdio-`initialize`-Handshake
   liefert das korrekte `result`. Der Defekt (leeres Fehlerobjekt) brauchte handgepflegte
   lsp4j-TypeAdapter- + MCP-DTO-Konstruktor-Registrierung; per E2E gegen das Binary verifiziert.
6. **Offen, Eigner-Entscheidung: bleibt Native ein 1.0.0-Gate?** **Die Grundlage ist jetzt
   VOLLSTÄNDIG** (nicht mehr „halb"): alle Flächen laufen nativ, inkl. Parquet/Tool-Export/`mcp
   serve`/S3. Die Frage ist damit sauber entscheidbar. **Konsequenz:** ein „Ja" macht **Phase H**
   nötig und Phase E unfertig, weil die heutige Mechanik einen roten Native-Build toleriert.
7. **Offen: Binaergroesse.** **Gemessen 2026-07-21 (volle CLI, MainKt):** Binary **182 MB**
   (189.926.472 B) gegen 67 MB beim Core-Subset. Fuer eine CLI-Distributionsklasse ist das viel; ob
   akzeptabel oder nacharbeitsbeduerftig (etwa durch `--gc=serial`, Ausschluss ungenutzter Adapter oder
   UPX), ist nicht entschieden. **Einordnung:** als **Container** relativiert es sich — das native
   OCI-Image ist **357 MB und damit kleiner als das JVM-OCI-Image (516 MB)** (kein JRE). **Startup-Payoff
   (host-direkt, Median N=20):** `--version` 14 ms nativ vs 319 ms JVM, `--help` 12 vs 334 ms,
   `schema validate --source` 17 vs 402 ms — durchweg **~20-28x schneller** (≈300-390 ms je Aufruf
   gespart). Das ist der eigentliche Native-Payoff und ein Gegengewicht zur Groesse.
   **Entscheidung 2026-07-21 (Eigner): das native Image bleibt wie es ist** (357 MB: Binary 190 MB +
   SpatiaLite-Deps 89 MB + ubuntu-Basis 78 MB) — Paritaet mit dem JVM-Image (516 MB, traegt dasselbe
   SpatiaLite + JRE), keine Verschlankung. Nicht gezogene Hebel (dokumentiert, nicht verworfen):
   SpatiaLite optional (−89 MB, aber Paritaets-Luecke), debian-slim-Basis (−48 MB, braucht
   glibc-passenden Bau), static/musl + scratch (Basis ~0, aber bricht die dynamische `mod_spatialite`
   und haengt an Frage 3). Binary-Schrumpfung (`-O`/UPX/Adapter-Ausschluss) bleibt als eigener Hebel offen.
8. **Spec-Lücken, die das Akzeptanzkriterium berühren**: `spec/cli-spec.md` listet
   `--sqlite-named-sequences` nicht in der `generate`-Flag-Tabelle (obwohl implementiert) und fordert
   `--source -` (stdin) für `schema validate`, was der Code nicht kann. „Identische Aufrufsyntax nach
   cli-spec.md" setzt voraus, dass die Spec selbst stimmt — beides ist separat zu klären.

## 5. Vorbedingungen

- **GraalVM-Toolchain in CI** — **erfüllt; Ist-Stand ist GraalVM 25** (nicht mehr 21.0.2). Der Sprung
  kam am Nachmittag mit GRMR 1.0.7 (verlangt das neue Metadaten-Schema, das 21 nicht kennt). Kotlin
  2.1.20 läuft weiter auf **JDK 21** (getrenntes `JAVA_HOME`), nur native-image auf 25 (`GRAALVM_HOME`).
- **Repräsentativer Smoke-Korpus** — **erfüllt**: `scripts/native-probe.sh` deckt jetzt alle Flächen ab
  (17 Sonden inkl. Parquet, Tool-Export, `mcp serve`, S3, `data profile`); die Subprozess-E2Es ergänzen
  echte S3-/MCP-Operationen. Historischer „teilweise"-Stand war der Mittags-Stand.
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
- ✅ Startup-Zeit und Binärgröße gemessen und festgehalten (der Native-Image-Payoff) — **erledigt
  2026-07-21**, Zahlen in Frage 7 (Startup ~20-28x schneller; Binary 182 MB, natives OCI-Image
  357 MB < JVM 516 MB).
- ✅ JNA bleibt **unerreichbar** (verifiziert **2026-07-21** — war reachable via mordant-omnibus,
  jetzt per Dependency-Ausschluss behoben; s. F.2).
- Native-Binaries hängen als versionierte, SHA-256-geprüfte Assets am GitHub-Release; `releasing.md`
  deckt die Asset-Klasse ab.

## 7. Abgrenzung

- **Keine Library-Artefakte** — der Library-Publish ist per
  [ADR 0037](../../adr/0037-database-agnostic-first-staffelung.md) /
  [ADR 0036](../../adr/0036-library-artefakte-github-packages.md) nach 2.0.0 verschoben.
- **Native ersetzt weder** das Fat-JAR **noch** das OCI-Image (jib) — es ist eine **zusätzliche**
  Distributionsklasse. Das bleibt auch bei voller Parität gültig: Fat-JAR und OCI bedienen andere
  Einsatzformen (JVM-Umgebungen, Container-Orchestrierung), nicht einen Funktionsrückstand des Binaries.
- **Native Distributionsformen (ergänzt 2026-07-21):** neben den rohen Binaries am GitHub-Release
  (linux/macos/windows, `native-image.yml`) gibt es jetzt auch ein **natives Container-Image**
  `…:X.Y.Z-native` (+ bewegliches `:native` bei Stable) auf GHCR/Docker-Hub, gebaut vom Job
  `native-image` in `build.yml` (amd64, Stage `native-runtime`). Es tritt **neben** das JVM-OCI-Image,
  ersetzt es nicht (Details: [`docs/user/releasing.md`](../../user/releasing.md) 4.4.3).
- **Docker Hub** und **SDKMAN** sind eigene Distributions-Gates — nicht Teil dieses Plans (das native
  Image nutzt lediglich den bestehenden `build.yml`-Docker-Hub-Spiegel mit).
- Profiling-DataSketches bleibt ein bewusster Carve-Out (s. [Roadmap](roadmap.md)), post-1.0.0.
