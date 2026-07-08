# a-check als Hexagon-Architektur-Gate + Befund-Bereinigung

> **Status:** Draft mit Scope (2026-07-06), **7× Review-überarbeitet**. R1 (3 Befunde):
> die geteilten Typen sind JDBC-gebunden, driver-common als A1/A2-Ziel erzeugt Zyklen,
> `make a-check` grün ≠ Neutralmodell entkoppelt → zwei Ziele (G1/G2) getrennt. R2 (3
> Befunde): Kopplung steckt auch in **Gradle-Deps** (a-check-blind), der Parquet-Bezug ist
> konkret `ValueSerializer.Warning`, und `ChunkSchema`/`DataExportFormat` liegen **schon**
> in ports-common (D3 für Datentypen entschieden → ports-common; Factory-Ausnahme siehe D3).
> R3 (3 Befunde): G1 braucht eine ADR-/Architektur-Ausnahme für `jdbcType`, D2 muss auch
> `asJdbc`-False-Green abdecken, und `Warning`-Sinks hängen nicht nur in Parquet.
> R4 (2 Befunde): `ValueSerializer.Warning` nicht als nested Adapter-Typ heben, sondern
> durch top-level Port-Typ ersetzen; striktes D2 betrifft alle `asJdbc`-Composition-Root-
> Stellen, nicht nur A1/A2. R5 (4 Befunde): A1 muss den JDBC-gebundenen
> `RunnerHookHandler` mitziehen, B unterscheidet `JdbcTypeHint` nach G1/G2, das
> Gradle-Grep-Gate klammert Root-/Kover-/Constraint-Aggregation aus, und das strenge
> D2-Gate sucht auch produktive `java.sql.`-/`javax.sql.`-FQNs ohne Import.
> R6 (2 Befunde): A4 ist kein kleiner Neutralisierungsblock, sondern Teil des
> A1/D2-JDBC-Ausführungsschnitts; `javax.sql` gehört konsistent zum JDBC-Tech-Scope.
> R7 (3 Befunde): G2 muss den Parquet-Manifest-/Bundle-Vertrag mit `jdbcType`
> mitentscheiden, Gradle-Ziel heißt „0 ungültige Adapter→Adapter-Kanten" unter
> `.a-check.yml`-Ausnahmen, und das Gradle-Gate filtert nach Konfiguration statt
> pauschal nach `testFixtures(project(...))`.
>
> **Status-Update 2026-07-07:** D1/D2 entschieden via
> [`ADR 0028`](../../adr/0028-a-check-architecture-gate-scope.md):
> **D1 = G1 zuerst** (`jdbcType: Int` bleibt eng begrenzte Interop-/Persistenz-
> Ausnahme, keine Typcode-Neutralisierung in diesem Slice), **D2 = streng**
> (Composition Roots bleiben frei von produktiver JDBC-Ausführung, `asJdbc` und
> direkter `JdbcDatabaseConnection`-Nutzung). A5, B und A3 sind umgesetzt:
> `ValueSerializationWarning`, `BundleClosure*`, `JdbcTypeHint`,
> `ValueDeserializer` und `ValueDeserializerFactory` liegen portseitig, streaming/
> parquet verlieren die produktiven lateral-adapter-Kanten, A3 nutzt den
> ratifizierten G1-Port-Wrapper `JdbcTypeCodes` statt `java.sql.Types`, A1/A4
> laufen ueber den driven `JdbcMigrationStatementExecutor`, und A2 reicht nur
> noch ein neutrales `DatabaseConnection`-Handle an die dialektspezifischen
> driven Probes weiter. `make a-check` steht bei 0 Befunden und ist in
> `gates`/`docker-gates` aktiviert.

## Ziel

Ein **sprach-weites** Hexagon-Schicht-Gate über [a-check](https://github.com/pt9912/a-check)
(extern, digest-gepinnt v0.12.0, netzlos, read-only), das das bestehende
[`ports-jdbc-free-gate`](../../../make/gate.mk) (ADR 0022, nur `hexagon:ports*`)
hexagon-weit ausdehnt und Adapter-Kopplung diszipliniert prüft.

## Zwei Ziele — bewusst getrennt

a-check erkennt Importe **textuell**. Daraus folgen zwei *verschiedene* Ziele, die
die erste Plan-Fassung fälschlich vermischt hat:

- **G1 — Gate grün:** kein `java.sql`-/`javax.sql`-*Import* in der falschen Schicht, keine
  lateral-adapter-Kante. Erreichbar durch Relokation/Interface-Extraktion.
- **G2 — Neutralmodell wirklich entkoppelt:** die *semantische* JDBC-Kopplung
  raus, d.h. **JDBC-Typcodes** (`java.sql.Types` als `Int`) durch ein neutrales
  Typ-Modell ersetzen.

**G2 ⊋ G1.** `TargetColumn.jdbcType: Int` (ports-write) und `JdbcTypeHint.jdbcType: Int`
(formats) tragen die JDBC-Kopplung **ohne** java.sql-Import — a-check sieht sie nicht.
Man kann G1 erreichen (Gate grün), ohne G2 zu berühren (falsch-grün). Der Slice muss
explizit sagen, welches Ziel er verfolgt (siehe Offene Entscheidungen D1).

> Bestehende Vorentscheidung: Das Repo hält `jdbcType: Int` **bewusst** aus `core`
> raus, aber in `ports`/`formats` (KDoc `TargetColumn`, `JdbcTypeHint`: „semantisch
> JDBC-coupled"; Regel „kein JDBC-Feld in core", L15 / 0.3.0). **G1** kann diese
> Vorentscheidung nur behalten, wenn eine ADR-/Architektur-Ausnahme die semantische
> JDBC-Kopplung gegen ADR 0022 / `spec/architecture.md` ratifiziert. **G2** würde
> die Vorentscheidung revidieren — das ist erst recht ADR-würdig, kein Nit.

## Ausgangslage (geliefert, uncommitted)

- **`make/a-check.mk`** (`--print-mk`, Pin v0.12.0 `@sha256:203df7ab…`), **`.a-check.yml`**
  (strenges Modell: feine Adapter-Globs + `adapter_sink: driver-common` +
  `composition_root: adapters/driving/**` + `java.sql`/`javax.sql`-tech mit
  `composition_root: forbid`;
  `resolution.fixed-root` ein root pro Modul), **`include make/a-check.mk`** (standalone
  `make a-check`, **nicht** in `gates`). Lauf: **12 Befunde**.

## Befund-Analyse (korrigiert)

### B) 7× lateral-adapter — geteilte Adapter-Modell-Typen

Dreieck **formats ↔ streaming ↔ formats-parquet** teilt Typen aus `format.data.*`
(+ `streaming.BundleClosure*`). Wichtig: **die Impls sind JDBC-gebunden** —
`ValueSerializer` importiert `java.sql.{Array,Blob,Clob,Date,Struct,Time,Timestamp}`,
`ValueDeserializer`/`TypeConverterRegistry` hängen an `java.sql.Types`. **Ein direkter
Umzug nach `hexagon/ports-*` erzeugt neue `tech-leak`s** (java.sql im Port).

**Port-Heimat = `hexagon:ports-common`** — das Package `dev.dmigrate.format.data` ist
**schon** ein Split-Package Port↔Adapter: `ChunkSchema` + `DataExportFormat` liegen
bereits dort (nur weiterverwenden, **nicht** heben — sonst unnötiger Churn). Die zu
hebenden Typen joinen dieses bestehende Package. (Damit ist **D3** faktisch entschieden.)

**Was pro Befund konkret importiert wird (nicht die Serialize-*Behavior*!):**
- **B1/B2** (parquet → `ValueSerializer`): der einzige Bezug ist **`ValueSerializer.Warning`**
  (verschachteltes DTO als `warningSink: ((ValueSerializer.Warning) -> Unit)?` in
  `ParquetChunkWriter[Factory]`) — parquet serialisiert **nicht** selbst. → Das
  verschachtelte DTO **nicht** mitsamt `ValueSerializer`-FQN heben, sondern durch einen
  top-level **Port-Typ** ersetzen, z.B. `ValueSerializationWarning` in `ports-common`.
  `ValueSerializer` bleibt wegen `java.sql` im formats-Adapter und verwendet diesen Port-Typ
  nur noch als Sink-Payload. Interface-Extraktion allein behebt B1/B2 **nicht**. Nicht
  nur Parquet anfassen: dieselbe Typ-Signatur hängt in `DataExportWiring`,
  `DefaultDataChunkWriterFactory`, `Csv`/`Json`/`Yaml`-Writern und Tests.
- **B3/B4** (parquet → `streaming.BundleClosureContext/Table`): neutrale Datentypen
  (referenzieren nur `format.data.ChunkSchema/DataExportFormat`, die schon im Port sind) →
  nach `ports-common` heben.
- **B5–B7** (streaming → `format.data.JdbcTypeHint`, `ValueDeserializer`): `ValueDeserializer`
  hat neutrale Signatur → **neutrales Interface** in `ports-common`, **Impl bleibt in
  formats**. `JdbcTypeHint(jdbcType: Int, …)` ist der Knoten: neutral-textuell (kein
  java.sql-Import), aber semantisch JDBC — Verhalten hängt an **D1**. Konkret:
  **G1** darf `JdbcTypeHint` als Port-DTO heben, aber nur mit ratifizierter
  `jdbcType`-Ausnahme; **G2** darf `JdbcTypeHint` nicht unverändert heben, sondern
  ersetzt ihn durch einen neutralen Type-Hint/Typcode, der keine `java.sql.Types`-
  Semantik mehr transportiert.
  **Buildbarkeits-Naht (kritisch):** streaming **konstruiert** den Deserializer heute selbst
  (`buildDeserializer(targetColumns, readOptions): ValueDeserializer` in
  `TableImportBindingSupport`, aufgerufen aus `TableImporter`). Interface-Extraktion allein
  bringt die formats-Dep **zurück** (der `new` braucht die konkrete Klasse). → Ein
  **`ValueDeserializerFactory`-Port**; **Impl in formats**; die **Composition Root
  (cli/mcp) injiziert** sie in streaming. Wichtig: wenn die Factory-Signatur
  `TargetColumn` (ports-write) und `FormatReadOptions` (ports-read) referenziert, gehört
  die Factory nach **`hexagon:ports-write`** (ports-write liest ports-read/common), nicht
  nach ports-common. Alternative: eine ports-common-eigene Request-DTO einführen, die
  keine ports-read/write-Typen referenziert. Erst dann verliert streaming die formats-Dep
  wirklich.

**Gradle-Ebene nicht vergessen (a-check scannt sie NICHT):** die Modul-Kopplung steckt
auch in den Build-Files — `streaming/build.gradle.kts` `api(project(":…:formats"))` (sogar
transitiv!) und `formats-parquet/build.gradle.kts` `implementation(project(":…:streaming"))`.
Ohne Umhängen dieser Deps auf die passenden Port-Module ist der Plan **modul-falsch-grün**:
0 lateral in Kotlin, aber weiter Adapter→Adapter auf Modulebene. (driver-common-Kanten
bleiben — das ist der gesegnete `adapter_sink`; Driving-Composition-Roots bleiben ebenfalls
erlaubt.)

### A) 5× JDBC-Tech (`java.sql`/`javax.sql` aktuell) — zwei Unterklassen

| # | Datei | Befund | Korrigierte Einordnung |
|---|---|---|---|
| A1 | `adapters/driving/cli/…/JdbcMigrationExecutor.kt` | tech-leak `java.sql.Connection`/`SQLException` (durch `forbid` in der composition_root) | **Umgesetzt:** CLI loest Target/Config und Pool auf; JDBC-Ausfuehrung, `asJdbc`, Transaktion/Rollback und Hook-Anwendung liegen im driven `JdbcMigrationStatementExecutor`. |
| A2 | `adapters/driving/cli/…/CheckPreflightProbeRunner.kt` | tech-leak `java.sql.Connection` (forbid) | **Umgesetzt:** Composition Root dispatcht nur noch per Dialekt auf driven Probe-Objekte und reicht ein neutrales `DatabaseConnection`-Handle weiter; JDBC-Unwrap passiert in den jeweiligen Treiberadaptern. |
| A3 | `hexagon/application/…/ImportTypeCompatibility.kt` | app-impurity `java.sql.Types` | **Umgesetzt nach D1/G1:** Application importiert kein `java.sql.Types` mehr, sondern nutzt den portseitigen `JdbcTypeCodes`-Wrapper. `TargetColumn.jdbcType: Int` bleibt als [ADR 0028](../../adr/0028-a-check-architecture-gate-scope.md)-Ausnahme bestehen; keine G2-Typcode-Neutralisierung. |
| A4 | `hexagon/application/…/RunnerHookHandler.kt` | app-impurity `java.sql.Statement`/`SQLException` | **Umgesetzt mit A1:** der JDBC-gebundene Hook-Applier liegt im driven `JdbcRunnerHookHandler`; `hexagon:application` importiert dafuer keine JDBC-API mehr. |
| A5 | `hexagon/profiling/…/service/ProfileTableService.kt` | app-impurity `SQLFeatureNotSupportedException` | neutrale Ausnahme statt JDBC-Exception (klein) |

## Entscheidungen (D1/D2 via ADR 0028 entschieden)

- **D1 — Typcode-Modell (G1 vs. G2):**
  - *(i) G1 — entschieden fuer diesen Slice:* Neutrale Interfaces + neutrale Datentypen in Ports heben; `jdbcType: Int`
    bleibt (konsistent mit der bestehenden `TargetColumn`-Vorentscheidung). Dazu darf
    `JdbcTypeHint` als Port-DTO gehoben werden. Gate grün, moderater Umfang; **A3** =
    nur `ImportTypeCompatibility` entkoppeln, `TargetColumn` bleibt. **Trotzdem
    ADR-/Architektur-Delta Pflicht:** ADR 0022 / `spec/architecture.md`
    formulieren „JDBC lebt in Adaptern" breiter als nur „kein `java.sql`-Import". G1
    ratifiziert daher eine eng begrenzte `jdbcType`-Ausnahme in Ports/Formats und
    deklariert ehrlich „Import-Relokation, keine Typcode-Neutralisierung".
  - *(ii) G2 — separater Folgeslice, nicht Teil dieses Gates:* Neutrales **Typ-Enum** ersetzt `java.sql.Types`-Codes durch die ganze
    Transfer-Pipeline (`TargetColumn`, `JdbcTypeHint`/dessen neutralen Ersatz,
    `TypeConverterRegistry`, Reverse-Engineering, das die Codes erzeugt). Zusätzlich
    ist der persistierte Parquet-Manifest-/Bundle-Vertrag betroffen:
    `ManifestColumn.jdbcType`, `ParquetManifestWriter` und `ParquetManifestReader`
    transportieren heute ebenfalls JDBC-Typcodes. G2 muss daher entweder eine
    Manifest-Migration/Kompatibilitätsstrategie liefern oder diesen Persistenzvertrag
    ausdrücklich als eng begrenztes Carve-out ratifizieren; sonst bleibt ein
    Typcode-Falsch-Grün außerhalb der Port-Signaturen. Großer Umbau, ADR-würdig,
    revidiert L15/0.3.0.
- **D2 — A1/A2 + Composition-Root-JDBC:** **entschieden: `forbid` bleibt** und
  cli-JDBC wird per Port/Registry rausrefactoren (streng, mehr Arbeit). Wichtig:
  `java.sql`-/`javax.sql`-Importe sind nur die sichtbaren Treffer.
  Die CLI unwrappt JDBC heute auch über `dev.dmigrate.driver.connection.asJdbc` ohne
  `java.sql`-/`javax.sql`-Import. Aktuelles Inventar: `CheckPreflightProbeRunner`,
  `JdbcMigrationExecutor`,
  `SqliteCastPreflightProbeRunner`, `MysqlSequenceCanonicityProbeRunner`,
  `SqliteLiveCatalogProbeRunner`, `SegmentAwareMigrationExecutor` (plus künftige Treffer).
  Ein separates Such-/Grep-Gate muss auch `asJdbc`/
  `JdbcDatabaseConnection` und produktive `java.sql.`-/`javax.sql.`-FQNs in `adapters/driving/**`
  verbieten (Kommentare/KDoc ausklammern, damit reine Doku-Referenzen nicht brechen) und
  alle diese Stellen per Port/Registry aus der Composition Root ziehen.
- **D3 — Port-Heimat** der geteilten format/streaming-Typen (B): **entschieden →
  `hexagon:ports-common`** (Package `format.data` liegt dort schon: `ChunkSchema`/
  `DataExportFormat`). Kein neues Modul. Ausnahme: write/import-spezifische Factory-
  Ports, deren Signatur `TargetColumn`/`FormatReadOptions` nutzt, leben in `ports-write`
  oder bekommen eine ports-common-eigene DTO-Signatur.

## Arbeitspakete (nach D1–D3)

1. **B (Kotlin + Gradle) — umgesetzt:**
   - Neuen top-level Port-Typ `ValueSerializationWarning` (ersetzt `ValueSerializer.Warning`
     in öffentlichen Signaturen), `BundleClosure*`, `JdbcTypeHint` + ein neutrales
     `ValueDeserializer`-Interface nach `ports-common` (`format.data`), Impls in
     formats. **Nur bei G1** bleibt `JdbcTypeHint(jdbcType: Int, …)` in dieser Form;
     **bei G2** stattdessen neutralen Type-Hint/Typcode einführen und alle
     `java.sql.Types`-Codes aus diesem Vertrag entfernen. Alle Warning-Sink-
     Signaturen mitziehen (`DataExportWiring`, `DefaultDataChunkWriterFactory`,
     `Csv`/`Json`/`Yaml`-Writer, Parquet-Writer/Factory, Tests).
   - Deserializer-Erzeugung entkoppeln: `streaming` bekommt eine Port-Factory/
     Provider-Dependency und konstruiert keine formats-Impl mehr selbst; die Factory
     lebt bei `build(targetColumns, readOptions)` in `ports-write` (oder verwendet eine
     ports-common-eigene Request-DTO); CLI/MCP verdrahten die konkrete formats-Factory.
   - Call-Sites in parquet/streaming auf Port-Typen ziehen (`make ast-grep`).
   - Gradle-Deps umhängen: `streaming` und `formats-parquet` verlieren
     `project(":…:formats")`/`project(":…:streaming")`; sie deklarieren die direkt
     genutzten Port-Module explizit (`hexagon:ports-common`, plus je nach Datei
     `hexagon:ports-read`/`hexagon:ports-write`; kein Vertrauen auf Transitives über
     `driver-common`/`hexagon:ports`).
   → 0 lateral-adapter (Kotlin) **und** 0 ungültige produktive Adapter→Adapter-
   Kante (Gradle) unter denselben Ausnahmen wie `.a-check.yml`:
   `adapters/driving/**` bleibt Composition Root, `adapters/driven/driver-common`
   bleibt Adapter-Sink.
2. **A5 — umgesetzt:** JDBC-Exception in profiling neutralisieren (klein).
3. **A1/A2/A4 + Composition-Root-JDBC nach D2 — umgesetzt fuer a-check:**
   - A1/A4: `MigrationExecutionTrace` und `MigrationStreamClassifier` sind portseitig
     verfuegbar; `JdbcMigrationStatementExecutor` uebernimmt JDBC-Unwrap,
     Transaktion/Rollback und `JdbcRunnerHookHandler`.
   - A2: `CheckPreflightProbeRunner` reicht `DatabaseConnection` an die
     dialektspezifischen driven Probes; diese unwrappen JDBC selbst.
   - Die weitergehende Zusatzregel gegen `asJdbc`/`JdbcDatabaseConnection` in
     `adapters/driving/**` bleibt eine separate Gate-Haertung jenseits des aktuellen
     a-check-Import-Gates.
4. **A3 — umgesetzt nach D1/G1:** `ImportTypeCompatibility` nutzt `JdbcTypeCodes`
   aus den Ports; `TargetColumn.jdbcType: Int` bleibt als
   [ADR 0028](../../adr/0028-a-check-architecture-gate-scope.md)-Ausnahme.
5. **Aktivierung — umgesetzt:** `make a-check` = Exit 0; a-check ist in
   `gates` + `docker-gates`. ADR
   (verallgemeinert ADR 0022, inklusive D1-`jdbcType`-Entscheid und D2-Composition-
   Root-Ausnahme/Strenge; Verhältnis zu `ports-jdbc-free-gate`: schneller Vorab-Check
   oder abgelöst?) + CHANGELOG/Handbuch.

## Akzeptanzkriterien

- `make a-check` = Exit 0 mit der strengen `.a-check.yml`.
- **Kein Modul-Falsch-Grün:** keine ungültige produktive Adapter→Adapter-`project(...)`-
  Dep mehr unter denselben Ausnahmen wie `.a-check.yml` (streaming/formats-parquet
  hängen an den direkt genutzten `hexagon:ports-*`-Modulen, nicht an
  formats/streaming);
  a-check sieht das nicht → separat prüfen. Ein Grep-Gate über `build.gradle.kts` muss
  dieselben Ausnahmen wie `.a-check.yml` modellieren: `adapters/driving/**` als
  Composition Root erlaubt, `adapters/driven/driver-common` als Adapter-Sink erlaubt,
  Test-Konfigurationen ausgeklammert. Das Gate filtert nach Dependency-Konfiguration
  (`testImplementation`, `testFixturesApi`, `testFixturesImplementation`, usw.), nicht
  pauschal nach dem Text `testFixtures(project(...))`: ein
  `implementation(testFixtures(project(":...")))` ist weiterhin eine produktive Kante
  und muss wie jede andere produktive Konfiguration bewertet werden. Das Gate darf nur
  produktive Modul-Dependency-Konfigurationen in Modul-`build.gradle.kts` bewerten;
  Root-/Coverage-Aggregation (`kover(project(...))`), Constraints und sonstige
  nicht-kompilierende Aggregator-Referenzen sind keine Architekturkante.
- **Kein Composition-Root-Falsch-Grün:** bei strengem D2 kein `java.sql`/`javax.sql` **und** kein
  `asJdbc`/`JdbcDatabaseConnection` in `adapters/driving/**`; das Zusatzgate sucht dabei
  auch produktive `java.sql.`-/`javax.sql.`-FQNs ohne Import und ignoriert Kommentare/KDoc. Bei
  pragmatischem D2 ist die erlaubte CLI-JDBC-Verdrahtung im ADR/Architekturtext
  ausdrücklich begrenzt.
- **Kein Typcode-Falsch-Grün:** bei Ziel G2 ist `jdbcType: Int` aus den Ports weg
  **und** der produktive Parquet-Manifest-/Bundle-Vertrag (`ManifestColumn.jdbcType`
  plus Writer/Reader-Kompatibilität) ist neutralisiert oder ausdrücklich als
  Persistenz-Carve-out ratifiziert; bei G1 ist im ADR/Architekturtext ratifiziert,
  dass die Typcode-Kopplung bewusst und eng begrenzt bleibt.
- Kein Docker-Build/Test-Regress (verschobene Typen verhaltensgleich).
- a-check in `gates`/`docker-gates`; ADR-Delta + Doku.

## Vorbedingungen

- a-check **≥ v0.12.0** (erfüllt). **D3 entschieden** (Datentypen: ports-common;
  write/import-spezifische Factory-Ausnahme siehe D3).
- **D1 + D2 entschieden** via ADR 0028 (D1: G1 zuerst; D2: streng).

## Closure

Geliefert am 2026-07-07:

- Alle 12 initialen `a-check`-Befunde sind behoben; `make a-check` meldet
  `gesamt: 0 Befund(e)`.
- `a-check` ist in `gates` und `docker-gates` aktiviert.
- Die G1/D2-Entscheidung ist in
  [ADR 0028](../../adr/0028-a-check-architecture-gate-scope.md) und
  `spec/architecture.md` dokumentiert.
- Follow-up bleibt bewusst getrennt: ein Zusatzgate gegen produktive
  `asJdbc`-/`JdbcDatabaseConnection`-Nutzung und produktive
  `java.sql.`-/`javax.sql.`-FQNs in `adapters/driving/**`.
