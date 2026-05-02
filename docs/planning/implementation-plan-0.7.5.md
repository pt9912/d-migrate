# Implementierungsplan: Milestone 0.7.5 - Daten-Profiling

> Dieses Dokument beschreibt den konkreten Implementierungsplan fuer
> Milestone 0.7.5. Es dient als laufend gepflegte Spezifikation und
> Review-Grundlage waehrend der Umsetzung.
>
> Status: Draft (2026-04-15)
> Referenzen: `docs/planning/roadmap.md` Milestone 0.7.5, `spec/profiling.md`,
> `spec/design.md` Abschnitt 3.6 Daten-Profiling, `spec/architecture.md`,
> `spec/lastenheft-d-migrate.md` LF-004, bestehende Datenpfade
> `data export` / `data import` / `data transfer`.

---

## 1. Ziel

Milestone 0.7.5 erweitert d-migrate um einen neuen, deterministischen
Vorbereitungsbaustein vor eigentlichen Migrationslaeufen:
`d-migrate data profile`.

Das Ziel ist nicht, ein generisches BI- oder Observability-Werkzeug zu bauen,
sondern aus einer Live-Datenbank einen reproduzierbaren Profil-Report fuer
Migrationsentscheidungen abzuleiten:

- Kennzahlen pro Tabelle und Spalte
- datenqualitaetsbezogene Warnungen
- ein datenorientierter `LogicalType`
- erste Zieltyp-Kompatibilitaetspruefungen gegen das neutrale Typsystem
- JSON-/YAML-Ausgabe fuer Review, Automation und spaetere Toolketten

0.7.5 liefert damit eine Antwort auf die typische Vorfrage vor Reverse,
Generate, Transfer oder produktiver Migration:

- Welche Daten liegen tatsaechlich vor?
- Welche Anomalien und Platzhalterwerte existieren?
- Welche Spalten passen voraussichtlich nicht sauber in Zieltypen?

Wichtig:

- 0.7.5 ist kein Ersatz fuer `schema reverse`.
- 0.7.5 ist kein Data-Quality-Produkt mit Volltextprofiling, Sampling-UI oder
  ML-Interpretation.
- 0.7.5 bleibt ein CLI- und Report-Pfad, der auf vorhandener JDBC-
  Infrastruktur aufsetzt.

---

## 2. Ausgangslage

Stand im aktuellen Repo:

- `schema reverse` aus 0.6.0 kann Live-Schemata ueber `SchemaReader` in das
  neutrale Modell lesen.
- `data export`, `data import` und `data transfer` koennen mit Live-
  Datenbanken arbeiten, liefern aber keine Aggregat- oder Profiling-
  Kennzahlen.
- `DatabaseDriver` kapselt heute:
  - `ddlGenerator()`
  - `dataReader()`
  - `tableLister()`
  - `dataWriter()`
  - `urlBuilder()`
  - `schemaReader()`
- Die CLI-Hierarchie unter `data` kennt heute:
  - `export`
  - `import`
  - `transfer`
- `settings.gradle.kts` enthaelt noch kein Modul `hexagon:profiling`.
- `spec/cli-spec.md` enthaelt noch keinen produktiven Vertrag fuer
  `data profile`.

Wichtig fuer 0.7.5:

- 0.6.0 hat bereits die entscheidende Vorarbeit geleistet:
  dieselbe JDBC-Metadatenbasis, die `SchemaReader` nutzt, kann fuer
  Profiling wiederverwendet werden.
- Das bestehende Daten-Streaming ist fuer Profiling nur bedingt nutzbar:
  `DataReader` ist auf Zeilen-Streaming optimiert, nicht auf
  Aggregat-Queries, `topValues`, `distinctCount` oder Typstatistiken.
- `spec/profiling.md` beschreibt bereits ein groesseres Zielbild, inklusive
  Query-Profiling, Struktur-Findings, FD-Discovery, Normalisierungsanalyse und
  spaeterer LLM-Erweiterung.

Die groesste Luecke fuer 0.7.5 ist deshalb nicht JDBC an sich, sondern:

- ein eigenes Profiling-Domaenenmodell,
- eigene Outbound-Ports fuer Introspection und Aggregat-Queries,
- eine kleine Warning-Rule-Engine,
- ein neuer Runner- und CLI-Pfad,
- und eine klare Scope-Scheibe zwischen "roadmap-konformer Kern" und
  "spaeteres erweitertes Zielbild".

---

## 3. Scope

### 3.1 In Scope fuer 0.7.5

- neues Hexagon-Modul `hexagon:profiling`
- Domaenenmodell fuer:
  - `DatabaseProfile`
  - `TableProfile`
  - `ColumnProfile`
  - elementare Statistiktypen
  - `ProfileWarning`
  - `TargetTypeCompatibility`
- neue Outbound-Ports in `hexagon:profiling`:
  - `SchemaIntrospectionPort`
  - `ProfilingDataPort`
  - `LogicalTypeResolverPort`
- deterministisches Datenbank-/Tabellen-Profiling fuer:
  - PostgreSQL
  - MySQL
  - SQLite
- Warning-Rule-Engine fuer einen kleinen, belastbaren Anfangssatz an Regeln
- CLI-Kommando:
  - `d-migrate data profile`
- Report-Ausgabe nach JSON oder YAML
- Integration in Named Connections, URL-Parsing, Driver-Lookup und
  bestehende Exit-Code-Semantik der Datenpfade
- Unit-, Integrations- und E2E-Tests fuer den Kernpfad

### 3.2 Bewusst nicht Teil von 0.7.5

- Query-Profiling ueber `--query`
- FD-Discovery und `--analyze-normalization`
- `StructuralFinding`, `NormalizationProposal` und spaetere
  Normalisierungsvorschlaege
- LLM-basierte semantische Analyse
- Vollabdeckung aller `NeutralType`-Sonderfaelle in der ersten
  Kompatibilitaetsmatrix
- SQL Server, Oracle oder weitere Dialekte
- GUI, HTML-Reports oder interaktive Ausgabeformen
- automatische Ableitung oder Mutation neutraler Schemas aus Profiling-
  Ergebnissen

Begruendung:

Der Roadmap-Vertrag fuer 0.7.5 verlangt spaltenweise Kennzahlen,
Qualitaetswarnungen und Zieltyp-Kompatibilitaet als JSON-/YAML-Report.
Das ist die belastbare Initial-Scheibe. `spec/profiling.md` beschreibt ein
groesseres Zielbild; dieses Dokument reduziert den Milestone bewusst auf den
deterministischen Kern, damit 0.7.5 nicht gleichzeitig Profiling, Query-
Sandboxing, FD-Discovery und spaetere semantische Analyse ausliefern muss.

---

## 4. Leitentscheidungen

### 4.1 0.7.5 implementiert zuerst den deterministischen Kern, nicht das volle Endbild aus `profiling.md`

Verbindliche Konsequenz:

- Commit-Scope fuer 0.7.5 ist Datenbank-/Tabellen-Profiling.
- `--query` und `--analyze-normalization` werden fuer diesen Milestone nicht
  implementiert und auch nicht dokumentarisch als produktiv versprochen.
- `spec/profiling.md` bleibt das groessere Design-Zielbild; der produktive
  0.7.5-Vertrag wird spaeter in `spec/cli-spec.md` und `spec/design.md`
  bewusst auf den Kern zugeschnitten dokumentiert.

### 4.2 Profiling bekommt ein eigenes Hexagon-Modul

Die Roadmap nennt explizit `hexagon/profiling`. Das ist kein Appendix zu
`hexagon:core`, sondern ein eigener fachlicher Bereich mit:

- eigenem Domaenenmodell,
- eigenen Warning-Regeln,
- eigenen Port-Schnittstellen,
- und eigenen Services.

Verbindliche Konsequenz:

- `settings.gradle.kts` wird um `include("hexagon:profiling")` erweitert.
- Root-Kover muss das Modul aufnehmen.
- `hexagon:profiling` haengt mindestens von `hexagon:core` und
  `hexagon:ports` ab.

### 4.3 Profiling-Ports bleiben in `hexagon:profiling` und werden NICHT in `DatabaseDriver` hineingezogen

Das ist der wichtigste Architekturpunkt des Milestones.

`DatabaseDriver` liegt heute in `hexagon:ports`. Die neuen Profiling-Ports
sollen laut Design in `hexagon:profiling` liegen. Wuerden wir `DatabaseDriver`
um `schemaIntrospectionPort()` oder `profilingDataPort()` erweitern, entstuende
eine unnoetige und ungueltige Schichtkopplung zwischen `hexagon:ports` und
`hexagon:profiling`.

Verbindliche Konsequenz:

- `DatabaseDriver` bleibt fuer 0.7.5 unveraendert.
- Die Profiling-Runtime bekommt einen eigenen Lookup-Vertrag, z. B.:

```kotlin
data class ProfilingAdapterSet(
    val schemaIntrospection: SchemaIntrospectionPort,
    val profilingData: ProfilingDataPort,
    val logicalTypeResolver: LogicalTypeResolverPort,
)
```

- `DataProfileRunner` bekommt einen injizierten
  `(DatabaseDialect) -> ProfilingAdapterSet`-Lookup.
- Die konkrete Dialektverdrahtung bleibt trotzdem an genau einer zentralen
  Bootstrap-Stelle, analog zu `registerDrivers()` und
  `DatabaseDriverRegistry`:
  - z. B. ueber eine kleine `ProfilingAdapterRegistry` oder einen einmalig
    in `Main.kt` verdrahteten Lookup
  - nicht ueber verteilte `when (dialect)`-Bloecke in einzelnen Commands
    oder Runnern

### 4.4 Profiling nutzt dieselbe JDBC-Metadatenbasis wie `SchemaReader`, aber eine eigene Projektion

Profiling will den rohen DB-Typ erhalten. Das neutrale Reverse-Engineering-
Modell ist dafuer zu spaet in der Pipeline.

Verbindliche Konsequenz:

- `SchemaIntrospectionPort` liefert eine Profiling-spezifische Projektion,
  z. B. Tabellen- und Spaltenschemata mit `dbType`, `nullable`, PK/FK/Unique-
  Informationen.
- Driver-Adapter duerfen gemeinsame JDBC-Metadatenhelfer aus 0.6.0
  wiederverwenden.
- Profiling ruft NICHT erst `schemaReader().read(...)` auf, um danach wieder
  Informationen wie `dbType` zu rekonstruieren.

### 4.5 Der erste Report bleibt stabil, diff-freundlich und klein genug fuer Reviews

Profiling-Ausgabe ist nur dann nuetzlich, wenn sie zwischen Laeufen gut
vergleichbar bleibt.

Verbindliche Konsequenz:

- Tabellen werden stabil sortiert.
- Spalten werden in stabiler Schema-Reihenfolge oder eindeutig definierter
  Sortierung ausgegeben.
- `topValues` werden deterministisch ueber `ORDER BY cnt DESC, value ASC`
  gebildet.
- Dialektbedingte Felder ohne sichere Berechnung bleiben `null`, statt
  inkonsistente Heuristiken zu emittieren.
- Der Standard-Report fuer 0.7.5 enthaelt kein laufzeitvariables
  `generatedAt`-Feld; damit bleibt die Datei selbst diff-freundlich und fuer
  identische Eingangsdaten byte-reproduzierbar.
- Falls spaeter Provenienz-Zeitpunkte noetig werden, muessen sie entweder
  explizit opt-in sein oder klar ausserhalb des Determinismusvertrags liegen.

### 4.6 Zieltyp-Kompatibilitaet startet mit einem kuratierten Kernset

Die volle Abdeckung aller NeutralTypes waere fuer 0.7.5 zu breit.

Verbindliche Konsequenz:

- Der erste Kompatibilitaetssatz fokussiert auf die migrationsrelevantesten
  Zieltypen:
  - `integer`
  - `bigint`
  - `decimal`
  - `boolean`
  - `date`
  - `datetime`
  - `uuid`
  - `text`
- Komplexere Zieltypen wie `json`, Arrays, `geometry`, `binary` oder
  dialektspezifische Spezialformen werden nicht blockierend fuer 0.7.5.
- `TargetTypeCompatibility` bekommt fuer 0.7.5 einen expliziten,
  deterministischen Berechnungsvertrag:
  - keine Sampling-Heuristik
  - Pruefung ueber alle nicht-null Werte der Spalte
  - pro Zieltyp mindestens:
    - `checkedValueCount`
    - `compatibleCount`
    - `incompatibleCount`
    - optionale deterministische `exampleInvalidValues`
- Die Berechnung erfolgt ueber dokumentierte zieltypbezogene
  Validierungspraedikate bzw. sichere Cast-/Regex-Regeln pro Dialekt; wo das
  SQL-seitig nicht belastbar ausdrueckbar ist, ist ein deterministischer
  Kotlin-Fallback ueber denselben Vollscan zulaessig.
- Wenn ein Zieltyp fuer einen Dialekt im 0.7.5-Kern nicht belastbar per
  Vollscan bewertet werden kann, wird er nicht heuristisch "kompatibel"
  geraten, sondern als `unknown`/`null` ausgewiesen oder aus dem Kernset
  herausgenommen.
- Der Report darf den Kompatibilitaetsvektor spaeter additive erweitern, ohne
  den Kernvertrag zu brechen.

---

## 5. Zielarchitektur fuer 0.7.5

### 5.1 Modul- und Abhaengigkeitsbild

Geplanter Stand:

```text
hexagon:profiling
  -> hexagon:core
  -> hexagon:ports

hexagon:application
  -> hexagon:profiling

adapters:driven:driver-postgresql
  -> hexagon:profiling
adapters:driven:driver-mysql
  -> hexagon:profiling
adapters:driven:driver-sqlite
  -> hexagon:profiling

adapters:driven:formats
  -> hexagon:profiling

adapters:driving:cli
  -> hexagon:profiling
```

### 5.2 Paketstruktur

```text
hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/
  model/
  types/
  rules/
  port/
  service/
```

Wichtige Klassen:

- `DatabaseProfile`
- `TableProfile`
- `ColumnProfile`
- `NumericStats`
- `TemporalStats`
- `ValueFrequency`
- `ProfileWarning`
- `LogicalType`
- `TargetLogicalType`
- `TargetTypeCompatibility`
- `SchemaIntrospectionPort`
- `ProfilingDataPort`
- `LogicalTypeResolverPort`
- `WarningEvaluator`
- `ProfileTableService`
- `ProfileDatabaseService`

### 5.3 CLI-/Application-Schnitt

Neu:

- `DataProfileCommand` in `adapters:driving:cli`
- `DataProfileRunner` plus Request-/Report-DTOs in `hexagon:application`

Vorgeschlagene Request-Form:

```kotlin
data class DataProfileRequest(
    val source: String,
    val format: String = "json",
    val output: Path? = null,
    val tables: List<String> = emptyList(),
    val schema: String? = null,
    val topN: Int = 10,
    val cliConfigPath: Path? = null,
    val quiet: Boolean,
)
```

### 5.4 Report-Serialisierung

Der Profil-Report ist kein Schema und kein DataChunk-Format. Deshalb bekommt
er einen kleinen dedizierten Writer in `adapters:driven:formats`, z. B.:

- `ProfileReportWriter`

Er nutzt die vorhandenen Serialisierungsbibliotheken:

- Jackson fuer JSON
- SnakeYAML Engine fuer YAML

---

## 6. Phasenplan

### Phase A - Scope hartziehen und Modulgeruest anlegen

Ziel:

- den 0.7.5-Kern explizit begrenzen,
- das neue Modul sauber einhaengen,
- und Build/Kover auf den spaeteren Codepfad vorbereiten.

Arbeitspakete:

- `settings.gradle.kts` um `hexagon:profiling` erweitern
- `build.gradle.kts` Root-Kover um `project(":hexagon:profiling")` erweitern
- `hexagon/profiling/build.gradle.kts` anlegen
- `hexagon/application/build.gradle.kts` um `implementation(project(":hexagon:profiling"))` erweitern
- driver- und CLI-Module um `implementation(project(":hexagon:profiling"))` erweitern
- `spec/cli-spec.md`-Luecke fuer `data profile` im Plan dokumentieren

Akzeptanzkriterien:

- [ ] Das neue Modul ist buildbar und im Multi-Module-Build eingebunden.
- [ ] Die Abhaengigkeiten laufen ohne Zyklus.
- [ ] Kover betrachtet das neue Modul.

### Phase B - Domaenenmodell, Typen und Rule-Engine

Ziel:

- einen kleinen, voll testbaren Profiling-Kern ohne JDBC bauen.

Arbeitspakete:

- `LogicalType` und `TargetLogicalType` einfuehren
- `DatabaseProfile`, `TableProfile`, `ColumnProfile` und Statistiktypen modellieren
- `ProfileWarning`, `WarningCode`, `Severity` einfuehren
- `WarningEvaluator` plus erste Regeln implementieren
- `TargetTypeCompatibility` und Kern-Kompatibilitaetspruefer einfuehren

`TargetTypeCompatibility` modelliert fuer 0.7.5 mindestens:

- Zieltyp
- `checkedValueCount`
- `compatibleCount`
- `incompatibleCount`
- optionale deterministische `exampleInvalidValues`
- einen klaren Bestimmungsstatus wie `FULL_SCAN` oder `UNKNOWN`

Verpflichtender erster Regelkatalog:

- hohe Null-Rate
- leere Strings
- Blank-Strings
- sehr hohe Kardinalitaet
- sehr niedrige Kardinalitaet
- Dubletten bei nicht-null Werten
- ungueltige Werte fuer Zieltyp-Kandidaten
- einfache Platzhalterwerte bei textbasierten Spalten

Akzeptanzkriterien:

- [ ] Alle Domaenenklassen sind reine Kotlin-Modelle ohne JDBC-Abhaengigkeit.
- [ ] `WarningEvaluator` ist komplett unit-testbar.
- [ ] Die erste Zieltyp-Kompatibilitaetsmatrix ist dokumentiert, ihr
      Vollscan-Berechnungsvertrag ist festgelegt, und die Kernregeln sind
      getestet.

### Phase C - Port-Vertraege und dialektspezifische Adapter

Ziel:

- die Daten fuer den Profiling-Kern aus realen Datenbanken beschaffen.

Arbeitspakete:

- `SchemaIntrospectionPort` definieren
- `ProfilingDataPort` definieren
- `LogicalTypeResolverPort` definieren
- fuer PostgreSQL, MySQL und SQLite je Dialekt implementieren:
  - `*SchemaIntrospectionAdapter`
  - `*ProfilingDataAdapter`
  - `*LogicalTypeResolver`

Verpflichtender erster Introspection-Scope:

- Tabellenliste
- Spaltenliste
- `dbType`
- `nullable`
- Primaerschluessel
- Foreign Keys
- Unique Constraints, soweit leicht verfuegbar

Verpflichtender erster Aggregat-Scope:

- `rowCount`
- `nonNullCount`
- `nullCount`
- `distinctCount`
- `duplicateValueCount`
- `minLength` / `maxLength` fuer textfoermige Werte
- `minValue` / `maxValue`
- `topValues`
- numerische Grundstatistiken fuer numerische Spalten
- temporale Min/Max-Werte fuer Datums-/Zeitspalten

Verpflichtender erster Kompatibilitaets-Scope:

- pro Zieltyp aus dem Kernset eine deterministische Vollscan-Pruefung ueber
  alle nicht-null Werte
- SQL-seitige Praedikate/Casts oder dokumentierte Kotlin-Fallbacks muessen
  pro Dialekt dasselbe semantische Ergebnis liefern
- `compatibleCount` und `incompatibleCount` duerfen nicht aus `min/max`,
  `distinctCount` oder blossen Beispielwerten abgeschaetzt werden
- optionale `exampleInvalidValues` muessen deterministisch begrenzt und
  sortiert sein

Dialektregeln:

- SQLite darf fehlende Funktionen wie `stddev` ueber Kotlin-Fallbacks
  auffuellen oder `null` lassen.
- `duplicateValueCount` zaehlt nur nicht-null Duplikat-Zeilen jenseits der
  ersten Auspraegung.
- `topValues` muessen deterministisch sortiert sein.

Akzeptanzkriterien:

- [ ] Jeder der drei Dialekte hat einen lauffaehigen Adapter-Satz.
- [ ] Introspection und Aggregat-Queries laufen ohne direkten Rueckgriff auf
      `SchemaReader`-Outputs.
- [ ] Der Kernvertrag fuer `TargetTypeCompatibility` ist pro Dialekt ueber
      dokumentierte Vollscan-Pruefungen belastbar und testbar.
- [ ] SQLite-Sonderfaelle fuehren nicht zu falschen Fantasiewerten.

### Phase D - Application-Services und Runner

Ziel:

- den JDBC-/Port-Layer zu einem stabilen Use-Case zusammensetzen.

Arbeitspakete:

- `ProfileTableService` implementieren
- `ProfileDatabaseService` implementieren
- `DataProfileRunner` im Stil bestehender Runner implementieren
- Fehlerhierarchie fuer Profiling einfuehren, z. B.:
  - `ProfilingException`
  - `SchemaIntrospectionError`
  - `ProfilingQueryError`
  - `TypeResolutionError`
- Exit-Code-Mapping definieren:
  - `0` Erfolg
  - `2` CLI-Fehler
  - `4` Verbindungsfehler
  - `5` Profiling-Ausfuehrungsfehler
  - `7` Konfigurations-/URL-/Registry-Fehler

Besonderheiten:

- `NamedConnectionResolver`, `ConnectionUrlParser` und
  `HikariConnectionPoolFactory` werden genauso wiederverwendet wie bei
  `data export` und `schema reverse`.
- `schema`-Filter wird fuer 0.7.5 nur fuer PostgreSQL verbindlich unterstuetzt.
  Bei MySQL/SQLite fuehrt ein explizites `--schema` zu Exit `2`, statt still
  ignoriert zu werden.

Akzeptanzkriterien:

- [ ] Der Runner ist ohne echte Datenbank voll unit-testbar.
- [ ] Saemtliche Exit-Code-Zweige sind ueber Tests abgedeckt.
- [ ] Die Lookup-Strategie fuer Profiling-Adapter vermeidet eine
      `DatabaseDriver`-/Modul-Zyklusfalle und bleibt auf eine zentrale
      Bootstrap-Stelle begrenzt.

### Phase E - CLI und Report-Writing

Ziel:

- den neuen Use-Case produktiv ueber `d-migrate data profile` erreichbar machen.

Arbeitspakete:

- `DataCommand` um `DataProfileCommand()` erweitern
- Help-Text und Argument-Parsing implementieren
- JSON als Default-Format setzen
- stdout als Default-Ausgabe unterstuetzen
- Datei-Ausgabe ueber `ProfileReportWriter`
- Fehlerausgabe ueber bestehende Formatter-/CLI-Konventionen

Verbindlicher CLI-Vertrag fuer 0.7.5:

```bash
d-migrate data profile --source <url-or-name> [--tables a,b] [--schema public] [--top-n 10] [--format json|yaml] [--output <path>]
```

Pflicht-/Optionssatz:

- `--source` Pflicht
- `--format` optional, Default `json`
- `--output` optional, Default stdout
- `--tables` optional, Default alle
- `--schema` optional, nur PostgreSQL
- `--top-n` optional, Default `10`

Nicht Teil des 0.7.5-CLI-Vertrags:

- `--query`
- `--analyze-normalization`

Akzeptanzkriterien:

- [ ] `data profile --help` ist ueber die Root-CLI erreichbar.
- [ ] Fehlende oder ungueltige Flags liefern Exit `2`.
- [ ] JSON- und YAML-Ausgabe transportieren fuer dieselben Daten denselben
      Informationsgehalt.

### Phase F - Tests, Smokes und Doku-Abgleich

Ziel:

- den Milestone gegen reale Laufzeitumgebungen absichern und den finalen
  Nutzervertrag dokumentieren.

Arbeitspakete:

- Domain-Unit-Tests fuer Model, Rules, Compatibility
- Service-Unit-Tests fuer Runner und Use-Cases
- SQLite-Integrationspfad gegen `:memory:`
- PostgreSQL-/MySQL-Integrationspfade via Testcontainers
- CLI-Round-Trip-Tests fuer:
  - stdout JSON
  - Dateiausgabe YAML
  - Tabellenfilter
  - `--schema` auf PostgreSQL
  - Fehlerpfade
- Doku aktualisieren:
  - `spec/cli-spec.md`
  - `spec/design.md`
  - `spec/architecture.md`
  - ggf. `docs/user/guide.md`

Akzeptanzkriterien:

- [ ] `hexagon:profiling` erreicht eine belastbare Non-Integration-Coverage.
- [ ] Mindestens ein echter PostgreSQL- und ein echter MySQL-Lauf bestaetigen
      die Adapterpfade.
- [ ] Die Abschlussdoku beschreibt nur den tatsaechlich gelieferten 0.7.5-Scope.

---

## 7. Konkrete Datei- und Modulziele

### 7.1 Neue Dateien / Module

- `docs/planning/implementation-plan-0.7.5.md`
- `hexagon/profiling/build.gradle.kts`
- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/...`
- `hexagon/profiling/src/test/kotlin/dev/dmigrate/profiling/...`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileCommand.kt`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/ProfileReportWriter.kt`

### 7.2 Dialektspezifische neue Adapter

PostgreSQL:

- `PostgresSchemaIntrospectionAdapter`
- `PostgresProfilingDataAdapter`
- `PostgresLogicalTypeResolver`

MySQL:

- `MysqlSchemaIntrospectionAdapter`
- `MysqlProfilingDataAdapter`
- `MysqlLogicalTypeResolver`

SQLite:

- `SqliteSchemaIntrospectionAdapter`
- `SqliteProfilingDataAdapter`
- `SqliteLogicalTypeResolver`

### 7.3 Bestehende Dateien mit Anpassungen

- `settings.gradle.kts`
- `build.gradle.kts`
- `hexagon/application/build.gradle.kts`
- `adapters/driving/cli/build.gradle.kts`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`
  - falls die zentrale Profiling-Verdrahtung analog zu `registerDrivers()`
    dort gebootstrapt wird
- `adapters/driven/driver-postgresql/build.gradle.kts`
- `adapters/driven/driver-mysql/build.gradle.kts`
- `adapters/driven/driver-sqlite/build.gradle.kts`
- `adapters/driven/formats/build.gradle.kts`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliHelpAndBootstrapTest.kt`

---

## 8. Teststrategie fuer 0.7.5

### 8.1 Unit-Tests im Profiling-Modul

- `WarningEvaluatorTest`
- einzelne Rule-Tests
- `TargetTypeCompatibilityEvaluatorTest`
- `LogicalType`-/Mapping-nahe reine Tests, soweit ohne JDBC moeglich

### 8.2 Application-Tests

- `DataProfileRunnerTest`
- `ProfileDatabaseServiceTest`
- `ProfileTableServiceTest`

Fokus:

- Exit-Code-Mapping
- Tabellenfilter
- `topN`
- Fehlerpfade
- deterministische Sortierung

### 8.3 Integrations-Tests pro Dialekt

SQLite:

- echte `:memory:`-DB
- Grundkennzahlen
- Text-/Numerik-/Temporalwerte
- Fallback fuer `stddev`

PostgreSQL:

- Testcontainers
- Schema-Filter
- `topValues`
- Typ-Resolver

MySQL:

- Testcontainers
- numerische und textuelle Kennzahlen
- Typ-Resolver

### 8.4 E2E-CLI-Tests

- `data profile --source ...` nach JSON auf stdout
- `data profile --source ... --format yaml --output ...`
- `data profile --tables users,orders`
- Fehlverhalten bei ungueltigem `--schema` auf SQLite/MySQL
- Named-Connection-Pfad ueber `.d-migrate.yaml`

### 8.5 Determinismus

Verbindlich zu testen:

- stabile Tabellenreihenfolge
- stabile Spaltenreihenfolge
- stabile `topValues`-Sortierung
- keine laufzeitvariablen Felder im Default-Report

---

## 9. Dokumentationsfolgen

Nach erfolgreicher Implementierung muessen die regulaeren Dokumente den
tatsaechlich gelieferten 0.7.5-Vertrag abbilden.

Pflichtupdates:

- `spec/cli-spec.md`
  - neuer Abschnitt `data profile`
  - Flags
  - Exit-Codes
- `spec/design.md`
  - 3.6 Daten-Profiling auf finalen Stand ziehen
- `spec/architecture.md`
  - neues Hexagon-Modul `profiling`
  - neue Adapter in den Driver-Modulen

Wichtig:

- `spec/profiling.md` beschreibt heute einen groesseren Zielraum. Vor oder mit
  dem Milestone muss klar markiert werden, welche Teile fuer 0.7.5 produktiv
  sind und welche spaeter bleiben.

---

## 10. Verifikationskommandos

Nach Umsetzung sollten mindestens folgende Kommandos gruene Ergebnisse
liefern:

```bash
./gradlew :hexagon:profiling:test
./gradlew :hexagon:application:test --tests "*DataProfileRunnerTest*"
./gradlew :adapters:driving:cli:test --tests "*CliHelpAndBootstrapTest*"
./gradlew :adapters:driving:cli:test --tests "*CliDataProfile*"
./gradlew test -PintegrationTests
```

Manuelle CLI-Smokes:

```bash
d-migrate data profile --help
d-migrate data profile --source postgresql://localhost/mydb --tables users --format json
d-migrate data profile --source staging-db --format yaml --output /tmp/profile.yaml
```

---

## 11. Risiken

### R1 - Modulzyklus ueber `DatabaseDriver`

Wenn Profiling-Ports vorschnell an `DatabaseDriver` gehaengt werden, entsteht
eine ungesunde Schichtkopplung. Der Plan verhindert das bewusst ueber einen
separaten Profiling-Lookup.

### R2 - Query-Kosten wachsen auf grossen Tabellen schnell

`COUNT(DISTINCT ...)`, `topValues` und numerische Aggregate koennen teuer sein.
Darum startet 0.7.5 mit einem kleinen Satz deterministischer Kennzahlen und
ohne FD-Discovery.

### R3 - Dialektdifferenzen fuehren leicht zu scheinbar gleichen, aber semantisch verschiedenen Zahlen

Gerade bei `distinct`, Textlaengen, numerischen Funktionen und temporalen
Typen unterscheiden sich Dialekte subtil. Der Plan priorisiert deshalb
portablen Kern vor maximaler Statistikbreite.

### R4 - Zieltyp-Kompatibilitaet kann scope-maessig explodieren

Eine Vollmatrix ueber alle NeutralTypes, Dialekte und Sonderfaelle waere fuer
0.7.5 zu gross. Deshalb ist die erste Kompatibilitaetsmatrix bewusst kuratiert.

### R5 - `spec/profiling.md` ist groesser als der Roadmap-Kern

Ohne klare Scope-Markierung wuerde der Milestone mehr versprechen als er
liefern kann. Dieser Plan zieht deshalb bewusst eine kleinere, roadmap-konforme
Linie.

---

## 12. Abschluss-Checkliste

- [ ] `hexagon:profiling` ist als Modul eingebunden und buildbar.
- [ ] `d-migrate data profile` ist ueber die Root-CLI erreichbar.
- [ ] PostgreSQL, MySQL und SQLite haben je einen funktionierenden
      Adapter-Satz fuer Introspection, Profiling-Queries und Logical-Type-
      Aufloesung.
- [ ] JSON- und YAML-Reports sind fuer dieselben Eingangsdaten stabil und
      reproduzierbar formatiert.
- [ ] Der Initial-Report enthaelt spaltenweise Kennzahlen, Warnungen und
      Zieltyp-Kompatibilitaet.
- [ ] Exit-Codes `0/2/4/5/7` sind dokumentiert und getestet.
- [ ] `spec/cli-spec.md`, `spec/design.md` und `spec/architecture.md` sind auf
      dem finalen 0.7.5-Stand.
- [ ] Nicht gelieferte Endbild-Teile aus `spec/profiling.md` sind nicht
      versehentlich als produktiver Vertrag dargestellt.
