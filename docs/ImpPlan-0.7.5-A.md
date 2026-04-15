# Implementierungsplan: Phase A - Scope hartziehen und Modulgeruest anlegen

> **Milestone**: 0.7.5 - Daten-Profiling
> **Phase**: A (Scope hartziehen und Modulgeruest anlegen)
> **Status**: Draft (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.5.md` Abschnitt 1, Abschnitt 2,
> Abschnitt 3, Abschnitt 4, Abschnitt 5, Abschnitt 6 Phase A, Abschnitt 7,
> Abschnitt 9, Abschnitt 11, Abschnitt 12; `docs/roadmap.md` Milestone 0.7.5;
> `docs/profiling.md`; `docs/design.md` Abschnitt 3.6;
> `settings.gradle.kts`; `build.gradle.kts`;
> `hexagon/application/build.gradle.kts`;
> `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt`

---

## 1. Ziel

Phase A zieht den 0.7.5-Kern aus dem Masterplan in eine belastbare
Umsetzungsbasis, bevor Profiling-Domaenenmodell, JDBC-Adapter oder das neue
CLI-Kommando implementiert werden.

Der Teilplan liefert bewusst noch keinen fachlichen Profiling-Code. Ergebnis
von Phase A ist eine saubere Build-, Modul- und Scope-Basis, auf der die
spaeteren Phasen B bis F ohne Architekturdrift oder implizite Scope-Ausweitung
aufsetzen koennen.

Konkret soll nach Phase A klar und widerspruchsfrei feststehen:

- dass 0.7.5 bewusst nur den deterministischen Kern des Profilings liefert
  und nicht das volle Zielbild aus `docs/profiling.md`
- dass `hexagon:profiling` als neues Modul eingefuehrt wird
- dass dieses Modul ohne Schichtzyklen in den bestehenden Multi-Module-Build
  eingehaengt wird
- dass `DatabaseDriver` fuer 0.7.5 unveraendert bleibt
- dass die Dialektverdrahtung fuer Profiling zentral im Bootstrap bleibt und
  nicht in verteilte `when (dialect)`-Bloecke abrutscht
- dass Root-Kover und die beteiligten Modulabhaengigkeiten den spaeteren
  Profiling-Codepfad bereits tragen
- dass die Doku-Luecke fuer `data profile` in `docs/cli-spec.md` als Teil des
  Milestones explizit benannt ist

---

## 2. Ausgangslage

Aktueller Stand in Repo und Dokumentation:

- `docs/roadmap.md` definiert fuer 0.7.5:
  - Modul `hexagon/profiling`
  - `SchemaIntrospectionPort`
  - `ProfilingDataPort`
  - `LogicalTypeResolverPort`
  - Zieltyp-Kompatibilitaet
  - CLI-Kommando `d-migrate data profile`
- `docs/profiling.md` beschreibt bereits ein deutlich groesseres Zielbild
  inklusive:
  - Query-Profiling
  - Normalisierungsanalyse
  - Struktur-Findings
  - spaeterer semantischer Analyse
- `docs/implementation-plan-0.7.5.md` reduziert diesen Zielraum bereits
  bewusst auf den roadmap-konformen Kern.
- `settings.gradle.kts` enthaelt heute:
  - `hexagon:core`
  - `hexagon:ports`
  - `hexagon:application`
  aber noch kein `hexagon:profiling`
- Root-Kover in `build.gradle.kts` kennt das kuenftige Profiling-Modul noch
  nicht
- `hexagon/application/build.gradle.kts` haengt heute nur von:
  - `hexagon:core`
  - `hexagon:ports`
  ab
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt` enthaelt
  heute den zentralen Bootstrap ueber:
  - `registerDrivers()`
  - `buildRootCommand()`
- `DatabaseDriver` in `hexagon:ports` kapselt heute:
  - `ddlGenerator()`
  - `dataReader()`
  - `tableLister()`
  - `dataWriter()`
  - `urlBuilder()`
  - `schemaReader()`
  aber keine Profiling-spezifischen Ports
- `docs/cli-spec.md` enthaelt derzeit noch keinen Abschnitt fuer
  `data profile`

Konsequenz:

- Ohne Phase A besteht die Gefahr, dass die Implementierung gleichzeitig
  Build-Struktur, Scope-Grenzen und Profiling-Architektur ad hoc auslegt.
- Besonders kritisch waere eine vorschnelle Erweiterung von
  `DatabaseDriver`, weil das neue Profiling-Modul laut Zielarchitektur nicht in
  `hexagon:ports` zurueckdiffundieren soll.
- Ebenso kritisch waere ein zu breiter Einstieg in Query-Profiling oder
  Normalisierungsanalyse, nur weil diese Begriffe bereits im Design-Dokument
  vorkommen.

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- verbindliche Einengung des 0.7.5-Lieferumfangs auf den deterministischen
  Kern
- Einfuehrung des neuen Gradle-Moduls `hexagon:profiling` auf
  Build-/Verzeichnis- und Abhaengigkeitsebene
- Vorbereitung der Modulabhaengigkeiten fuer:
  - `hexagon:application`
  - `adapters:driving:cli`
  - `adapters:driven:driver-postgresql`
  - `adapters:driven:driver-mysql`
  - `adapters:driven:driver-sqlite`
  - `adapters:driven:formats`
- Aufnahme des neuen Moduls in Root-Kover
- Festlegung, dass Profiling-Verdrahtung zentral im Bootstrap bleibt
- explizite Dokumentation, dass `DatabaseDriver` in 0.7.5 NICHT erweitert
  wird
- Benennung der offenen CLI-Spec-Luecke fuer `data profile`

### 3.2 Bewusst nicht Teil von Phase A

- Domaenenmodell fuer `DatabaseProfile`, `TableProfile`, `ColumnProfile`
- `WarningEvaluator` und Profiling-Regeln
- `SchemaIntrospectionPort`, `ProfilingDataPort`,
  `LogicalTypeResolverPort` im Code
- dialektspezifische Profiling-Adapter
- `DataProfileRunner` oder `DataProfileCommand`
- JSON-/YAML-Serialisierung des Profil-Reports
- Integrationstests oder E2E-Tests
- finale Produktivpflege von `docs/cli-spec.md`, `docs/design.md` oder
  `docs/architecture.md`

Praezisierung:

Phase A schafft die Schienen, auf denen die spaeteren Phasen fahren. Sie
liefert noch keinen fachlichen Profiling-Pfad.

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 0.7.5 ist zunaechst der roadmap-konforme Profiling-Kern

Phase A fixiert die wichtigste Scope-Grenze des Milestones:

- 0.7.5 liefert Datenbank-/Tabellen-Profiling
- 0.7.5 liefert noch kein Query-Profiling
- 0.7.5 liefert noch keine FD-Discovery oder Normalisierungsanalyse
- 0.7.5 liefert noch keine LLM- oder semantische Analyse

Verbindliche Folge:

- `docs/profiling.md` bleibt groesseres Zielbild
- spaetere Phasen B bis F muessen sich an den kleineren 0.7.5-Vertrag halten
- Phase A verhindert, dass spaetere Implementierungsschritte Design-Zielbild
  und Release-Vertrag vermischen

### 4.2 `hexagon:profiling` wird als eigenes Modul eingefuehrt

Die Roadmap nennt das Modul explizit. Phase A fixiert deshalb:

- neues Include in `settings.gradle.kts`
- neues `hexagon/profiling/build.gradle.kts`
- Kover-Aufnahme im Root-Build

Verbindliche Folge:

- Profiling bleibt klar vom bestehenden Core getrennt
- der neue Bereich bekommt eigene Paket- und Abhaengigkeitsgrenzen
- spaetere fachliche Erweiterungen landen nicht verteilt in `core`,
  `application` und `cli`, ohne gemeinsamen fachlichen Ort

### 4.3 `DatabaseDriver` bleibt unveraendert

Phase A fixiert den wichtigsten Architektur-Schutz:

- `DatabaseDriver` in `hexagon:ports` wird fuer 0.7.5 nicht um
  Profiling-Funktionen erweitert

Begruendung:

- die neuen Profiling-Ports liegen laut Zielbild in `hexagon:profiling`
- eine Erweiterung von `DatabaseDriver` wuerde die Modulgrenzen verwaschen
- dadurch drohte eine Rueckkopplung vom neuen Modul in die bestehende
  Port-Schicht

Verbindliche Folge:

- die spaetere Profiling-Runtime braucht einen separaten Lookup-Vertrag
- die Umsetzung darf nicht "bequem" neue Methoden in `DatabaseDriver`
  addieren, nur weil die Dialektinformation dort bereits existiert

### 4.4 Dialektverdrahtung bleibt zentral im Bootstrap

Phase A fixiert:

- Profiling-Adapter werden nicht in einzelnen Commands oder Runnern per
  verteiltem `when (dialect)` zusammengesucht
- die konkrete Verdrahtung bleibt an genau einer Bootstrap-Stelle
- diese Stelle ist konzeptionell analog zu `registerDrivers()` in `Main.kt`

Verbindliche Folge:

- spaetere Phasen koennen entweder:
  - eine kleine `ProfilingAdapterRegistry` einfuehren
  - oder einen einmalig in `Main.kt` verdrahteten Lookup aufbauen
- die Architektur bleibt konsistent mit dem existierenden Bootstrap-Muster

### 4.5 Kover und Modulabhaengigkeiten werden vor fachlichem Code vorbereitet

Phase A fixiert:

- das neue Modul wird in Root-Kover aufgenommen
- `hexagon:application` wird auf `hexagon:profiling` vorbereitet
- driver-, format- und CLI-Module bekommen die noetigen `implementation(...)`
  Abhaengigkeiten auf das neue Modul

Verbindliche Folge:

- spaetere Phasen muessen keine Build-Grundsatzentscheidungen mehr zwischen
  fachlichen Implementierungsschritten verstecken
- neue Tests laufen vom ersten Profiling-Code an im regulaeren Build mit

### 4.6 Die CLI-Spec-Luecke wird explizit als Milestone-Arbeit benannt

`docs/cli-spec.md` kennt aktuell noch keinen Vertrag fuer `data profile`.
Phase A fixiert deshalb noch nicht den finalen Inhalt, wohl aber die
Arbeitspflicht:

- der neue CLI-Vertrag ist Teil des 0.7.5-Milestones
- die spaetere Implementierung darf nicht still "erst Code, dann vielleicht
  Doku" arbeiten

---

## 5. Arbeitspakete

### A.1 `settings.gradle.kts`

Einpflegen von:

- `include("hexagon:profiling")`

Ziel:

- das neue Modul ist Teil des offiziellen Multi-Module-Builds

### A.2 Root-Build / Kover

Anpassung von `build.gradle.kts`:

- Aufnahme von `project(":hexagon:profiling")` in Root-Kover

Ziel:

- Profiling ist vom ersten Commit an Teil der globalen Coverage-Betrachtung

### A.3 `hexagon/profiling/build.gradle.kts`

Anlegen des neuen Modul-Builds mit mindestens:

- Kotlin/JVM-Standardkonventionen des Repos
- Abhaengigkeiten auf:
  - `:hexagon:core`
  - `:hexagon:ports`

Ziel:

- spaetere Domaenen- und Port-Klassen koennen ohne Build-Provisorien
  implementiert werden

### A.4 Modulabhaengigkeiten in bestehenden Projekten

Vorbereitung der spaeteren Verbraucher:

- `hexagon/application/build.gradle.kts`
- `adapters/driving/cli/build.gradle.kts`
- `adapters/driven/driver-postgresql/build.gradle.kts`
- `adapters/driven/driver-mysql/build.gradle.kts`
- `adapters/driven/driver-sqlite/build.gradle.kts`
- `adapters/driven/formats/build.gradle.kts`

Ziel:

- die spaeteren Phasen B bis F koennen Profiling-Klassen regulaer referenzieren

### A.5 Bootstrap-/Wiring-Entscheidung dokumentieren

In Phase A wird noch keine konkrete Registry implementiert, aber die Richtung
fuer spaetere Phasen verbindlich festgelegt:

- zentrale Profiling-Verdrahtung analog zu `registerDrivers()`
- keine verteilten `when (dialect)`-Bloecke in Commands oder Runnern
- `DatabaseDriver` bleibt unveraendert

Direkte Referenzbasis:

- [Main.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt)

### A.6 Doku-Luecke fuer `data profile` festhalten

Phase A dokumentiert explizit, dass `docs/cli-spec.md` fuer 0.7.5 spaeter
erweitert werden muss.

Ziel:

- spaetere Phasen arbeiten nicht an einem unsichtbaren oder vergessenen
  Dokumentations-Delta vorbei

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `settings.gradle.kts`
- `build.gradle.kts`
- `hexagon/profiling/build.gradle.kts`
- `hexagon/application/build.gradle.kts`
- `adapters/driving/cli/build.gradle.kts`
- `adapters/driven/driver-postgresql/build.gradle.kts`
- `adapters/driven/driver-mysql/build.gradle.kts`
- `adapters/driven/driver-sqlite/build.gradle.kts`
- `adapters/driven/formats/build.gradle.kts`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/implementation-plan-0.7.5.md`
- `docs/roadmap.md`
- `docs/profiling.md`
- `docs/cli-spec.md`
- [Main.kt](/Development/d-migrate/adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/Main.kt)

---

## 7. Akzeptanzkriterien

- [ ] `settings.gradle.kts` bindet `hexagon:profiling` in den Multi-Module-
      Build ein.
- [ ] `build.gradle.kts` nimmt `hexagon:profiling` in Root-Kover auf.
- [ ] `hexagon/profiling/build.gradle.kts` existiert und ist buildbar.
- [ ] `hexagon:application` ist fuer spaetere Profiling-Runner vorbereitet.
- [ ] CLI-, Driver- und Format-Module haben die noetigen
      `implementation(project(":hexagon:profiling"))`-Abhaengigkeiten.
- [ ] Die neuen Abhaengigkeiten laufen ohne Modulzyklus.
- [ ] Es ist dokumentiert, dass `DatabaseDriver` fuer 0.7.5 unveraendert
      bleibt.
- [ ] Es ist dokumentiert, dass Dialektverdrahtung fuer Profiling zentral im
      Bootstrap bleiben muss.
- [ ] Es ist dokumentiert, dass `docs/cli-spec.md` spaeter um
      `data profile` ergaenzt werden muss.

---

## 8. Risiken

### R1 - Modulzyklus ueber Bequemlichkeitsverdrahtung

Wenn spaetere Phasen Profiling direkt in `DatabaseDriver` oder in
verteilte CLI-`when`-Bloecke druecken, wird der Architekturgewinn von
Phase A sofort wieder aufgeweicht.

### R2 - Scope-Drift aus `docs/profiling.md`

Das groessere Zielbild kann dazu verleiten, schon vor Phase B Query-Profiling,
Normalisierung oder semantische Analyse mitzudenken. Phase A muss die kleinere
0.7.5-Linie deshalb bewusst hartziehen.

### R3 - Build-Glue wird zu spaet erledigt

Wenn das neue Modul erst mitten in Phase B oder C sauber in Build/Kover und die
Consumer-Module eingezogen wird, vermischt sich fachliche Arbeit mit
Strukturreparaturen. Phase A zieht diese Basisarbeit absichtlich vor.

---

## 9. Abschluss-Checkliste

- [ ] `hexagon:profiling` ist als Modul geplant und in allen betroffenen
      Build-Dateien sauber verankert.
- [ ] Root-Kover betrachtet das neue Modul.
- [ ] Die spaetere Profiling-Verdrahtung ist als zentrale Bootstrap-Aufgabe
      und nicht als verteilte Command-Logik festgelegt.
- [ ] `DatabaseDriver` ist ausdruecklich aus dem Scope der Phase-A-
      Erweiterungen herausgenommen.
- [ ] Die Doku-Luecke fuer `data profile` in `docs/cli-spec.md` ist als
      verbindliche Folgearbeit sichtbar.
