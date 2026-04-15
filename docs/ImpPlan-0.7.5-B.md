# Implementierungsplan: Phase B - Domaenenmodell, Typen und Rule-Engine

> **Milestone**: 0.7.5 - Daten-Profiling
> **Phase**: B (Domaenenmodell, Typen und Rule-Engine)
> **Status**: Implemented (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.5.md` Abschnitt 2,
> Abschnitt 3, Abschnitt 4.5 bis 4.6, Abschnitt 5, Abschnitt 6 Phase B,
> Abschnitt 8, Abschnitt 11, Abschnitt 12; `docs/ImpPlan-0.7.5-A.md`;
> `docs/roadmap.md` Milestone 0.7.5; `docs/profiling.md`;
> `docs/design.md` Abschnitt 3.6

---

## 1. Ziel

Phase B produktiviert den 0.7.5-Vertrag im fachlichen Profiling-Kern.
Ergebnis der Phase ist ein `hexagon:profiling`, das den datenorientierten
Kernvertrag ausdruecklich kennt und ohne JDBC, CLI oder Report-I/O testbar
modellieren kann.

Der Teilplan liefert bewusst noch keine Dialekt-Adapter, keine Aggregate-
Queries und kein neues CLI-Kommando. Er schafft die Domaenen- und
Regelgrundlage, auf der die spaeteren Phasen C bis F aufsetzen.

Nach Phase B soll im Profiling-Kern klar und testbar gelten:

- es gibt ein explizites Profiling-Modell statt lose verteilter Maps oder
  ad-hoc DTOs
- `LogicalType` und `TargetLogicalType` sind als eigene Profiling-Typen
  modelliert und nicht bloes ein Alias auf das neutrale Typsystem
- `DatabaseProfile`, `TableProfile` und `ColumnProfile` sind als stabile,
  serialisierbare Kernobjekte definiert
- `ProfileWarning`, `WarningCode` und `Severity` bilden einen kleinen,
  belastbaren Warnungsvertrag
- `WarningEvaluator` ist rein funktional und ohne JDBC unit-testbar
- `TargetTypeCompatibility` hat einen klaren fachlichen Mindestvertrag fuer
  spaetere Vollscan-Auswertungen
- der erste Regelkatalog ist bewusst klein, aber migrationsrelevant

---

## 2. Ausgangslage

Aktueller Stand der Codebasis:

- Phase A definiert fuer 0.7.5 bereits:
  - eigenes Modul `hexagon:profiling`
  - unveraenderten `DatabaseDriver`
  - zentrale spaetere Profiling-Verdrahtung
  - klar reduzierten Milestone-Scope gegenueber `docs/profiling.md`
- `hexagon:profiling` enthaelt bereits das produktive Domaenenmodell:
  - `LogicalType`, `TargetLogicalType`, `Severity`, `WarningCode`
  - `DatabaseProfile`, `TableProfile`, `ColumnProfile`
  - `ProfileWarning`, `TargetTypeCompatibility` mit `DeterminationStatus`
  - `NumericStats`, `TemporalStats`, `ValueFrequency`
- Die Rule-Engine ist implementiert:
  - `ColumnWarningRule`, `TableWarningRule`, `WarningEvaluator`
  - 8 Default-Regeln (migrationsrelevanter Katalog)
- 32+ Unit-Tests decken Typen, Modell und Regeln ab
- `docs/profiling.md` beschreibt bereits ein ausgebautes Zielbild mit:
  - Kernmodell
  - Statistiktypen
  - Warning-Regeln
  - Struktur-Findings
  - Normalisierungsvorschlaegen
  - spaeterer semantischer Analyse
- `docs/implementation-plan-0.7.5.md` grenzt fuer den produktiven 0.7.5-
  Vertrag bewusst ein:
  - kein Query-Profiling
  - keine FD-Discovery
  - keine LLM-Erweiterung
  - kuratiertes Kernset fuer Zieltyp-Kompatibilitaet

Konsequenz fuer Phase B:

- Das groesste Risiko ist nicht fehlende JDBC-Infrastruktur, sondern ein
  unscharfer fachlicher Kern.
- Wenn Modell, Typen und Regelvertrag jetzt nicht explizit gezogen werden,
  muessen spaetere JDBC-Adapter und Runner gegen implizite oder wechselnde
  Annahmen arbeiten.
- Besonders kritisch ist `TargetTypeCompatibility`: Ohne klaren fachlichen
  Mindestvertrag droht Phase C spaeter Zahlen zu liefern, deren Semantik im
  Kern nie sauber modelliert wurde.

---

## 3. Scope fuer Phase B

### 3.1 In Scope

- Einfuehrung der Profiling-Kerntypen:
  - `LogicalType`
  - `TargetLogicalType`
  - `Severity`
  - `WarningCode`
- Einfuehrung des Profiling-Domaenenmodells:
  - `DatabaseProfile`
  - `TableProfile`
  - `ColumnProfile`
  - erste Statistiktypen wie `NumericStats`, `TemporalStats`,
    `ValueFrequency`
- Einfuehrung von:
  - `ProfileWarning`
  - `TargetTypeCompatibility`
  - optionalem Bestimmungsstatus wie `FULL_SCAN` / `UNKNOWN`
- Definition des ersten kleinen Regelkatalogs
- Einfuehrung einer rein funktionalen Rule-Engine:
  - `ColumnWarningRule`
  - `TableWarningRule`
  - `WarningEvaluator`
- Unit-Tests fuer Modell, Typen, Regeln und Kompatibilitaetsvertrag

### 3.2 Bewusst nicht Teil von Phase B

- JDBC-Ports oder Dialekt-Adapter
- `SchemaIntrospectionPort`, `ProfilingDataPort`,
  `LogicalTypeResolverPort` im Code
- echte DB-Queries oder Vollscan-Implementierungen
- `DataProfileRunner`, `DataProfileCommand` oder andere CLI-Schalen
- JSON-/YAML-Writer
- `StructuralFinding`, `NormalizationProposal`, FD-Discovery
- `--query`, `--analyze-normalization`
- spaetere LLM- oder semantische Erweiterungen

Praezisierung:

Phase B modelliert den fachlichen Vertrag fuer Profiling. Sie implementiert
noch nicht den Runtime-Pfad, der diese Daten aus einer echten Datenbank liest.

---

## 4. Leitentscheidungen fuer Phase B

### 4.1 `LogicalType` bleibt ein eigenes Profiling-Konzept

Phase B fixiert:

- `LogicalType` ist nicht identisch mit `NeutralType`
- `LogicalType` klassifiziert beobachteten Dateninhalt
- `TargetLogicalType` beschreibt Zieltypen fuer spaetere
  Kompatibilitaetspruefungen

Verbindliche Folge:

- Profiling bleibt fachlich von Schema-Modellierung getrennt
- spaetere Dialekt-Resolver muessen auf Profiling-Typen mappen und nicht
  direkt auf `NeutralType`
- der Kern vermeidet eine zu enge Kopplung zwischen Profiling und
  Schema-Definition

### 4.2 Das Profiling-Modell bleibt klein, explizit und additiv erweiterbar

Phase B fuehrt nicht das komplette Endbild aus `docs/profiling.md` ein.

Verbindliche Folge:

- `DatabaseProfile`, `TableProfile` und `ColumnProfile` enthalten den
  roadmap-relevanten Kern
- spaetere Felder fuer Struktur-Findings oder Normalisierungsvorschlaege
  koennen additiv folgen
- Phase B verspricht keine Modellteile, die 0.7.5 bewusst noch nicht liefert

### 4.3 Warnungen bleiben ein einfacher, regelbasierter Vertrag

Phase B fixiert:

- Warnungen sind strukturierte Domaenenobjekte
- die Rule-Engine ist funktional und klein
- die Regelbewertung ist ohne I/O testbar

Verbindliche Folge:

- keine impliziten Warning-Strings aus spaeteren Services
- keine JDBC-Abhaengigkeit in `WarningEvaluator`
- einzelne Regeln bleiben austauschbar und gezielt testbar

### 4.4 `TargetTypeCompatibility` braucht schon in Phase B einen fachlichen Mindestvertrag

Phase B implementiert noch keine Vollscan-Adapter, aber sie fixiert bereits die
Semantik, die spaetere Phasen liefern muessen.

Verbindliche Folge:

- `TargetTypeCompatibility` enthaelt mindestens:
  - Zieltyp
  - `checkedValueCount`
  - `compatibleCount`
  - `incompatibleCount`
  - optionale deterministische `exampleInvalidValues`
  - einen Bestimmungsstatus wie `FULL_SCAN` oder `UNKNOWN`
- der Kernvertrag erlaubt spaeter keine sample-basierten Fantasiezahlen, wenn
  Vollscan versprochen ist
- unbekannte oder in 0.7.5 nicht belastbar auswertbare Zieltypen duerfen nicht
  still als kompatibel erscheinen

### 4.5 Der erste Regelkatalog bleibt bewusst migrationsrelevant

Phase B priorisiert Regeln, die vor einer Migration echte Relevanz haben:

- hohe Null-Rate
- leere Strings
- Blank-Strings
- sehr hohe Kardinalitaet
- sehr niedrige Kardinalitaet
- Dubletten bei nicht-null Werten
- ungueltige Werte fuer Zieltyp-Kandidaten
- einfache Platzhalterwerte bei textbasierten Spalten

Verbindliche Folge:

- Phase B driftet nicht in generische Data-Quality-Vollabdeckung ab
- die spaeteren Profiling-Services koennen mit einem kleinen, klaren
  Regelvertrag starten

---

## 5. Arbeitspakete

### B.1 `hexagon:profiling`-Grundtypen einfuehren

Im neuen Modul sind die elementaren fachlichen Typen zu modellieren:

- `LogicalType`
- `TargetLogicalType`
- `Severity`
- `WarningCode`

Ziel:

- die spaeteren Modelle und Regeln arbeiten auf expliziten Kern-Typen statt
  auf freien Strings

### B.2 Profiling-Kernmodell einfuehren

Mindestens noetig:

- `DatabaseProfile`
- `TableProfile`
- `ColumnProfile`
- `ProfileWarning`
- `TargetTypeCompatibility`
- erste Statistikobjekte:
  - `NumericStats`
  - `TemporalStats`
  - `ValueFrequency`

Wichtig:

- Das Modell soll serialisierbar und stabil sein, aber in Phase B noch keine
  Writer voraussetzen.
- Felder fuer spaetere Ausbaustufen werden nur dann vorgezogen, wenn sie den
  0.7.5-Kern nicht verwischen.

### B.3 `TargetTypeCompatibility` vertraglich absichern

Phase B zieht die spaetere Kompatibilitaetssemantik in einen expliziten
Kernvertrag.

Mindestens zu modellieren:

- Zieltyp
- `checkedValueCount`
- `compatibleCount`
- `incompatibleCount`
- optionale `exampleInvalidValues`
- Bestimmungsstatus, z. B.:
  - `FULL_SCAN`
  - `UNKNOWN`

Ziel:

- spaetere JDBC-Adapter muessen gegen einen klaren Typ und nicht gegen lose
  Annahmen implementieren

### B.4 Rule-Engine einfuehren

Mindestens noetig:

- `ColumnWarningRule`
- `TableWarningRule`
- `WarningEvaluator`

Regelkatalog fuer Phase B:

- hohe Null-Rate
- leere Strings
- Blank-Strings
- sehr hohe Kardinalitaet
- sehr niedrige Kardinalitaet
- Dubletten bei nicht-null Werten
- ungueltige Werte fuer Zieltyp-Kandidaten
- einfache Platzhalterwerte bei textbasierten Spalten

Wichtig:

- Die Regeln muessen auf dem Kernmodell arbeiten.
- JDBC, SQL oder Dialektlogik gehoeren noch nicht in diese Schicht.

### B.5 Tests im Profiling-Modul aufbauen

Mindestens erforderlich:

- Typ-/Enum-Tests fuer die neuen Profiling-Kerntypen
- Modelltests fuer Kernobjekte
- `WarningEvaluatorTest`
- einzelne Rule-Tests
- `TargetTypeCompatibility`-Vertragstests

Ziel:

- der fachliche Kern ist voll unit-testbar, bevor Phase C echte Datenquellen
  anschliesst

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/types/...`
- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/model/...`
- `hexagon/profiling/src/main/kotlin/dev/dmigrate/profiling/rules/...`
- `hexagon/profiling/src/test/kotlin/dev/dmigrate/profiling/...`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/implementation-plan-0.7.5.md`
- `docs/ImpPlan-0.7.5-A.md`
- `docs/roadmap.md`
- `docs/profiling.md`

---

## 7. Akzeptanzkriterien

- [x] `LogicalType`, `TargetLogicalType`, `Severity` und `WarningCode` sind
      als explizite Profiling-Kerntypen modelliert.
- [x] `DatabaseProfile`, `TableProfile` und `ColumnProfile` bilden den
      roadmap-konformen 0.7.5-Kern ab.
- [x] `ProfileWarning` ist ein strukturierter Warnungsvertrag und keine lose
      Stringliste.
- [x] `TargetTypeCompatibility` enthaelt den dokumentierten Mindestvertrag mit
      `checkedValueCount`, `compatibleCount`, `incompatibleCount`,
      Beispielwerten und Bestimmungsstatus.
- [x] `WarningEvaluator` ist ohne JDBC und ohne I/O voll unit-testbar.
- [x] Der erste Regelkatalog ist im Kern implementiert und getestet.
- [x] Phase B fuehrt keine Query-, FD- oder LLM-Scope-Erweiterung ein.

---

## 8. Risiken

### R1 - Modell driftet zu nah an `docs/profiling.md` als Gesamtendbild

Wenn Phase B bereits Struktur-Findings, Normalisierungsvorschlaege oder andere
spaetere Felder zwingend modelliert, wird der 0.7.5-Kern unnoetig breit.

### R2 - `LogicalType` und `NeutralType` werden unklar vermischt

Wenn Profiling-Typen nur als Alias oder Schatten des neutralen Typsystems
gebaut werden, droht spaeter eine ungesunde Kopplung zwischen Profiling und
Schema-Modell.

### R3 - `TargetTypeCompatibility` bleibt fachlich unterdefiniert

Wird der Kernvertrag hier zu weich formuliert, muss Phase C spaeter Zahlen
produzieren, deren Semantik nie sauber festgezogen wurde.

### R4 - Regeln ziehen zu frueh SQL- oder Adapterlogik in den Kern

Wenn Warnungsregeln schon JDBC- oder Dialektwissen voraussetzen, wird der
Profiling-Kern schwer testbar und Phase B verfehlt ihr Ziel.

---

## 9. Abschluss-Checkliste

- [x] Das Profiling-Modell ist im neuen Modul als expliziter Kernvertrag
      verankert.
- [x] `LogicalType` und `TargetLogicalType` sind sauber von `NeutralType`
      getrennt.
- [x] `TargetTypeCompatibility` ist fachlich belastbar modelliert.
- [x] Die Rule-Engine ist rein funktional und unit-testbar.
- [x] Der erste Regelkatalog ist klein, migrationsrelevant und dokumentiert.
- [x] Phase B bleibt frei von JDBC-, CLI- und Report-I/O-Implementierung.
