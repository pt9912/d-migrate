# Implementierungsplan: Phase C - Port-Vertraege und dialektspezifische Adapter

> **Milestone**: 0.7.5 - Daten-Profiling
> **Phase**: C (Port-Vertraege und dialektspezifische Adapter)
> **Status**: Draft (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.5.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.3 bis 4.6, Abschnitt 5, Abschnitt 6 Phase C,
> Abschnitt 8, Abschnitt 11, Abschnitt 12; `docs/ImpPlan-0.7.5-A.md`;
> `docs/ImpPlan-0.7.5-B.md`; `docs/roadmap.md` Milestone 0.7.5;
> `docs/profiling.md`; `docs/design.md` Abschnitt 3.6

---

## 1. Ziel

Phase C baut den ersten produktionsnahen Codepfad fuer 0.7.5 ausserhalb des
reinen Domaenenkerns: Profiling-Ports und dialektspezifische Adapter, die
reale Datenbanken fuer den in Phase B definierten Profiling-Vertrag
erschliessen.

Der Teilplan liefert bewusst noch keinen `DataProfileRunner`, kein neues
CLI-Kommando und keine Report-Serialisierung. Er schafft die Adapter- und
Portgrundlage, auf der die spaeteren Phasen D bis F aufsetzen.

Nach Phase C soll klar und testbar gelten:

- `hexagon:profiling` definiert explizite Outbound-Ports fuer Profiling
- PostgreSQL, MySQL und SQLite implementieren je einen belastbaren ersten
  Adapter-Satz
- Schema-Introspection baut auf derselben JDBC-Metadatenbasis wie
  `SchemaReader` auf, liefert aber eine eigene Profiling-Projektion
- Aggregat-Queries bilden den roadmap-konformen Kennzahlensatz ab
- `TargetTypeCompatibility` wird pro Dialekt ueber dokumentierte
  Vollscan-Pruefungen und nicht ueber Heuristiken gespeist
- SQLite-Sonderfaelle werden ueber dokumentierte Kotlin-Fallbacks oder `null`
  behandelt, nicht durch Fantasiewerte
- die Adapter bleiben side-effect-frei:
  - keine CLI-Logik
  - keine Dateischreiblogik
  - keine Report-Writer

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und Dokumentation:

- Phase A hat den Modulrahmen und die Schichtgrenzen fuer 0.7.5 gezogen:
  - `hexagon:profiling` als eigenes Modul
  - `DatabaseDriver` bleibt unveraendert
  - zentrale spaetere Verdrahtung statt verteilter `when (dialect)`-Logik
- Phase B definiert den fachlichen Profiling-Kern:
  - `LogicalType`
  - `TargetLogicalType`
  - `DatabaseProfile`
  - `TableProfile`
  - `ColumnProfile`
  - `ProfileWarning`
  - `TargetTypeCompatibility`
  - `WarningEvaluator`
- `docs/profiling.md` beschreibt fuer den Adapterlayer bereits ein groesseres
  Zielbild mit:
  - `SchemaIntrospectionPort`
  - `ProfilingDataPort`
  - `LogicalTypeResolverPort`
  - Query-Profiling
  - spaeteren Struktur- und Normalisierungsanalysen
- `docs/implementation-plan-0.7.5.md` reduziert den produktiven 0.7.5-Scope
  bewusst auf:
  - Datenbank-/Tabellen-Profiling
  - ersten Introspection-Scope
  - ersten Aggregat-Scope
  - ersten Kompatibilitaets-Scope
- Im aktuellen Arbeitsstand existiert fuer `hexagon:profiling` bereits ein
  Modul-Build, aber noch kein produktiver Port- oder Adaptercode.
- In den Driver-Modulen gibt es heute noch keine Profiling-Klassen fuer:
  - `SchemaIntrospection`
  - `ProfilingData`
  - `LogicalTypeResolver`

Konsequenz fuer Phase C:

- Die groesste Luecke ist jetzt nicht mehr der fachliche Vertrag, sondern der
  echte Zugriff auf JDBC-Metadaten und Aggregat-Queries.
- Wenn Phase C hier zu breit wird, rutscht sie sofort in Query-Profiling,
  Struktur-Findings oder spaetere Semantik.
- Wenn sie zu schmal bleibt, koennen Phase D und E keinen belastbaren
  Datenbankpfad aufbauen.

---

## 3. Scope fuer Phase C

### 3.1 In Scope

- Definition der Profiling-Outbound-Ports in `hexagon:profiling`:
  - `SchemaIntrospectionPort`
  - `ProfilingDataPort`
  - `LogicalTypeResolverPort`
- Definition der ersten projektspezifischen Introspection-Projektion, z. B.:
  - `TableSchema`
  - `ColumnSchema`
  - einfache Constraint-/Key-Profiltypen, soweit fuer 0.7.5 noetig
- Implementierung von Adapter-Klassen fuer:
  - PostgreSQL
  - MySQL
  - SQLite
- Implementierung des ersten roadmap-konformen Introspection-Scopes
- Implementierung des ersten roadmap-konformen Aggregat-Scope
- Implementierung des ersten roadmap-konformen
  `TargetTypeCompatibility`-Scope
- Unit- und Integrations-Tests fuer die neuen Adapter

### 3.2 Bewusst nicht Teil von Phase C

- `DataProfileRunner`
- `DataProfileCommand`
- JSON-/YAML-Writer fuer Profil-Reports
- Query-Profiling ueber `--query`
- FD-Discovery, `StructuralFinding`, `NormalizationProposal`
- spaetere semantische oder LLM-basierte Analyse
- HTML-/GUI- oder sonstige Ausgabepfade
- Erweiterung von `DatabaseDriver`

Praezisierung:

Phase C erschliesst echte Datenbanken fuer den Phase-B-Kern. Sie baut noch
nicht den Driving-Adapter oder die End-to-End-Ausgabe darueber.

---

## 4. Leitentscheidungen fuer Phase C

### 4.1 Die neuen Profiling-Ports leben in `hexagon:profiling`, nicht in `hexagon:ports`

Phase C setzt den Phase-A-Rahmen technisch um:

- `SchemaIntrospectionPort`
- `ProfilingDataPort`
- `LogicalTypeResolverPort`

liegen im Profiling-Modul und nicht als Erweiterung von `DatabaseDriver`.

Verbindliche Folge:

- `DatabaseDriver` bleibt unveraendert
- die Driver-Module implementieren die Profiling-Ports direkt
- die spaetere Verdrahtung erfolgt ueber den in Phase A vorgesehenen
  separaten Profiling-Lookup

### 4.2 Schema-Introspection ist eine eigene Projektion und kein Wrapper um `SchemaReader`

Profiling braucht rohe DB-Typen und profiling-spezifische Metadaten.

Verbindliche Folge:

- `SchemaIntrospectionPort` liefert keine `SchemaDefinition`
- die Profiling-Projektion enthaelt mindestens:
  - Tabellenname
  - Spaltenname
  - `dbType`
  - `nullable`
  - PK/FK/Unique-Basisdaten
- gemeinsame JDBC-Metadatenhelfer aus 0.6.0 duerfen wiederverwendet werden
- eine reine 1:1-Delegation auf `schemaReader().read(...)` ist fuer 0.7.5
  nicht akzeptabel

### 4.3 Die ersten Adapter bleiben in den bestehenden Driver-Modulen

Phase C fuehrt kein neues `adapters:driven:profiling`-Modul ein.

Verbindliche Folge:

- PostgreSQL-, MySQL- und SQLite-Profilingklassen leben jeweils in den
  bestehenden Driver-Modulen
- die Adapter koennen dort unmittelbar vorhandene JDBC-Helfer,
  Metadatenqueries und Dialektwissen nutzen
- die Modulgrenzen bleiben konsistent mit dem bestehenden Driver-Zuschnitt

### 4.4 Der erste Aggregat-Scope bleibt klein, aber voll belastbar

Phase C implementiert genau den Kennzahlensatz, den der Masterplan fuer den
Initial-Slice verlangt:

- `rowCount`
- `nonNullCount`
- `nullCount`
- `distinctCount`
- `duplicateValueCount` nur fuer nicht-null Duplikat-Zeilen jenseits der
  ersten Auspraegung
- `minLength` / `maxLength` fuer textfoermige Werte
- `minValue` / `maxValue`
- `topValues` in deterministischer Sortierung
- numerische Grundstatistiken fuer numerische Spalten
- temporale Min/Max-Werte fuer Datums-/Zeitspalten

Verbindliche Folge:

- kein Query-Profiling
- keine Struktur-Findings
- keine sample-basierten Pattern-Statistiken als Pflichtbestandteil dieser
  Phase
- kein spaeterer Zuschnitt von `docs/profiling.md` darf rueckwirkend so
  gelesen werden, als haette Phase C bereits mehr geliefert

### 4.5 `TargetTypeCompatibility` wird ueber echte Vollscan-Pruefungen gespeist

Phase B hat den Vertrag modelliert; Phase C muss ihn jetzt belastbar fuettern.

Verbindliche Folge:

- pro Zieltyp aus dem Kernset wird ueber alle nicht-null Werte geprueft
- `TargetTypeCompatibility` bleibt dabei kompatibel zum Phase-B-Mindestvertrag
  und enthaelt weiterhin mindestens:
  - Zieltyp
  - `checkedValueCount`
  - `compatibleCount`
  - `incompatibleCount`
  - optionale deterministische `exampleInvalidValues`
  - einen Bestimmungsstatus wie `FULL_SCAN` oder `UNKNOWN`
- `compatibleCount` und `incompatibleCount` duerfen nicht aus `min/max`,
  `distinctCount` oder Beispielwerten abgeschaetzt werden
- SQL-seitige Praedikate/Casts oder dokumentierte Kotlin-Fallbacks muessen pro
  Dialekt dieselbe Semantik liefern
- nicht belastbar auswertbare Zieltypen werden als `UNKNOWN`/`null`
  ausgewiesen oder aus dem Kernset entfernt, aber nicht heuristisch
  "kompatibel" geraten

### 4.6 SQLite bekommt explizite Fallback-Regeln

SQLite hat bei Statistik- und Typfunktionen weniger eingebaute Werkzeuge als
PostgreSQL oder MySQL.

Verbindliche Folge:

- fehlende Funktionen wie `stddev` duerfen ueber einen dokumentierten
  Kotlin-Fallback aufgefuellt werden oder `null` bleiben
- Fallbacks muessen deterministisch und testbar sein
- Fallbacks duerfen keine semantisch anderen Zahlen liefern als die
  dokumentierte Berechnungsregel

### 4.7 Adapter bleiben side-effect-frei

Die neuen Profiling-Adapter liefern Daten an den Kern und spaetere Runner,
nicht an Dateien oder Terminals.

Verbindliche Folge:

- keine Dateischreiblogik
- keine CLI-Ausgabe
- keine Report-Serialisierung
- keine Root-/Path-Logik

---

## 5. Arbeitspakete

### C.1 Profiling-Ports in `hexagon:profiling` definieren

Mindestens noetig:

- `SchemaIntrospectionPort`
- `ProfilingDataPort`
- `LogicalTypeResolverPort`

Ziel:

- spaetere Services und Runner arbeiten gegen explizite Outbound-Ports und
  nicht gegen direkte JDBC-Helfer

### C.2 Profiling-Projektion fuer Introspection definieren

Die Port-Schnitt fuer Introspection braucht eine eigene Projektion.

Mindestens noetig:

- `TableSchema`
- `ColumnSchema`
- einfache Constraint-/Key-Typen, soweit fuer 0.7.5 erforderlich

Ziel:

- roher `dbType` und profiling-spezifische Metadaten bleiben bis in den
  Profiling-Pfad erhalten

### C.3 PostgreSQL-Adapter implementieren

Mindestens noetig:

- `PostgresSchemaIntrospectionAdapter`
- `PostgresProfilingDataAdapter`
- `PostgresLogicalTypeResolver`

Ziel:

- ein kompletter erster Adapter-Satz fuer PostgreSQL

### C.4 MySQL-Adapter implementieren

Mindestens noetig:

- `MysqlSchemaIntrospectionAdapter`
- `MysqlProfilingDataAdapter`
- `MysqlLogicalTypeResolver`

Ziel:

- ein kompletter erster Adapter-Satz fuer MySQL

### C.5 SQLite-Adapter implementieren

Mindestens noetig:

- `SqliteSchemaIntrospectionAdapter`
- `SqliteProfilingDataAdapter`
- `SqliteLogicalTypeResolver`

Ziel:

- ein kompletter erster Adapter-Satz fuer SQLite inklusive dokumentierter
  Fallback-Regeln

### C.6 Ersten Introspection-Scope umsetzen

Verpflichtender Scope:

- Tabellenliste
- Spaltenliste
- `dbType`
- `nullable`
- Primaerschluessel
- Foreign Keys
- Unique Constraints, soweit leicht verfuegbar

Ziel:

- Phase D kann spaeter `TableProfile` ohne Rueckgriff auf `SchemaReader`
  zusammenbauen

### C.7 Ersten Aggregat-Scope umsetzen

Verpflichtender Scope:

- `rowCount`
- `nonNullCount`
- `nullCount`
- `distinctCount`
- `duplicateValueCount` nur fuer nicht-null Duplikat-Zeilen jenseits der
  ersten Auspraegung
- `minLength` / `maxLength` fuer textfoermige Werte
- `minValue` / `maxValue`
- `topValues` in deterministischer Sortierung
- numerische Grundstatistiken fuer numerische Spalten
- temporale Min/Max-Werte fuer Datums-/Zeitspalten

Ziel:

- Phase D kann daraus den roadmap-konformen Profil-Report aufbauen

### C.8 Ersten Kompatibilitaets-Scope umsetzen

Verpflichtender Scope:

- Vollscan-Pruefung pro Zieltyp aus dem Kernset
- `checkedValueCount`, `compatibleCount`, `incompatibleCount`
- Bestimmungsstatus wie `FULL_SCAN` oder `UNKNOWN`
- dokumentierte SQL-Praedikate/Casts oder Kotlin-Fallbacks
- deterministische `exampleInvalidValues`

Ziel:

- `TargetTypeCompatibility` wird mit belastbaren Zahlen statt Heuristiken
  gespeist

### C.9 Adapter-Tests aufbauen

Mindestens erforderlich:

- Unit-Tests fuer Resolver und Hilfslogik
- SQLite-Integrationstests gegen `:memory:`
- PostgreSQL-/MySQL-Integrationstests via Testcontainers
- Tests fuer:
  - deterministische `topValues`
  - `duplicateValueCount` nur fuer nicht-null Duplikat-Zeilen jenseits der
    ersten Auspraegung
  - `stddev`-Fallback
  - Kompatibilitaetspruefungen inklusive `checkedValueCount` und
    Bestimmungsstatus

Ziel:

- die Adapter sind belastbar, bevor Phase D Runner und Services darauf
  aufsetzt

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/port/...`
- `adapters/driven/driver-postgresql/src/main/kotlin/...`
- `adapters/driven/driver-mysql/src/main/kotlin/...`
- `adapters/driven/driver-sqlite/src/main/kotlin/...`
- `adapters/driven/driver-postgresql/src/test/kotlin/...`
- `adapters/driven/driver-mysql/src/test/kotlin/...`
- `adapters/driven/driver-sqlite/src/test/kotlin/...`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/implementation-plan-0.7.5.md`
- `docs/ImpPlan-0.7.5-A.md`
- `docs/ImpPlan-0.7.5-B.md`
- `docs/profiling.md`

---

## 7. Akzeptanzkriterien

- [ ] `SchemaIntrospectionPort`, `ProfilingDataPort` und
      `LogicalTypeResolverPort` sind in `hexagon:profiling` definiert.
- [ ] PostgreSQL, MySQL und SQLite haben je einen funktionierenden
      Adapter-Satz.
- [ ] Introspection liefert eine eigene Profiling-Projektion mit rohem
      `dbType` statt Rueckprojektion aus `SchemaReader`.
- [ ] Der erste Aggregat-Scope ist fuer alle drei Dialekte implementiert.
- [ ] Der Kernvertrag fuer `TargetTypeCompatibility` wird pro Dialekt ueber
      dokumentierte Vollscan-Pruefungen belastbar gefuellt und enthaelt
      mindestens `checkedValueCount`, `compatibleCount`,
      `incompatibleCount`, deterministische `exampleInvalidValues` und einen
      Bestimmungsstatus wie `FULL_SCAN` oder `UNKNOWN`.
- [ ] `duplicateValueCount` und `topValues` folgen der im Masterplan
      festgezogenen deterministischen Semantik.
- [ ] SQLite-Sonderfaelle sind ueber dokumentierte Fallbacks oder `null`
      sauber behandelt.
- [ ] Phase C fuehrt keine CLI-, Writer- oder Query-Profiling-Logik ein.

---

## 8. Risiken

### R1 - `SchemaReader` wird als falscher Shortcut missbraucht

Wenn Phase C nur die Outputs von `SchemaReader` weiterverarbeitet, gehen rohe
DB-Typen und profiling-spezifische Metadaten verloren.

### R2 - Aggregat-Scope waechst unkontrolliert in spaetere Zielbilder hinein

`docs/profiling.md` enthaelt mehr als der 0.7.5-Kern. Ohne harte Begrenzung
driftet Phase C schnell in Pattern-, Struktur- oder Query-Logik.

### R3 - Dialektunterschiede fuehren zu scheinbar gleichen, aber semantisch anderen Zahlen

Gerade bei `distinct`, Textlaengen, `stddev`, Casts und Typpruefungen muessen
die Adapter dieselbe dokumentierte Bedeutung liefern.

### R4 - `TargetTypeCompatibility` wird doch heuristisch gebaut

Sobald Phase C Vollscan-Zahlen aus Aggregatabkuerzungen oder Beispielen
ableitet, ist der Phase-B-Vertrag gebrochen.

### R5 - SQLite-Fallbacks werden undokumentiert oder nichttestbar

Wenn SQLite-Ausnahmen nur "irgendwie funktionieren", wird Phase D spaeter auf
instabilen Zahlen aufsetzen.

---

## 9. Abschluss-Checkliste

- [ ] Die Profiling-Ports sind im neuen Modul definiert.
- [ ] Die Driver-Module enthalten je Dialekt einen belastbaren ersten
      Profiling-Adapter-Satz.
- [ ] `SchemaIntrospection` bleibt eine eigene Projektion und kein
      `SchemaReader`-Wrapper.
- [ ] Der erste Aggregat-Scope ist roadmap-konform und klar begrenzt.
- [ ] `TargetTypeCompatibility` wird ueber echte Vollscan-Pruefungen und nicht
      ueber Heuristiken gespeist.
- [ ] SQLite-Sonderfaelle sind dokumentiert und getestet.
- [ ] Phase C bleibt frei von CLI-, Report- und Query-Profiling-Implementierung.
