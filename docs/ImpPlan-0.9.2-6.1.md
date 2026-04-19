# Implementierungsplan: 0.9.2 - Arbeitspaket 6.1 `DDL-Modell phasenfaehig machen`

> **Milestone**: 0.9.2 - Beta: DDL-Phasen und importfreundliche
> Schema-Artefakte
> **Arbeitspaket**: 6.1 (`DDL-Modell phasenfaehig machen`)
> **Status**: Draft (2026-04-19)
> **Referenz**: `docs/implementation-plan-0.9.2.md` Abschnitt 4.3,
> Abschnitt 4.3a, Abschnitt 5.1, Abschnitt 6.1, Abschnitt 7, Abschnitt
> 8 und Abschnitt 9.2;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`;
> `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`.

---

## 1. Ziel

Arbeitspaket 6.1 zieht den fachlichen Kern des 0.9.2-Splits in das
DDL-Portmodell:

- Statements tragen einen expliziten Phasenbezug
- Notes und `skippedObjects` koennen denselben Phasenbezug transportieren
- `DdlResult` liefert sowohl die heutige Gesamtsicht als auch eine
  konsistente Phasensicht fuer spaetere SQL-, JSON- und Report-Ausgabe

Das Arbeitspaket liefert bewusst noch keinen sichtbaren CLI- oder
Dateivertrag. Es schafft die Modellgrundlage, auf der 6.2 bis 6.4
aufsetzen.

Nach 6.1 soll klar gelten:

- das DDL-Modell kann `PRE_DATA` und `POST_DATA` ausdruecken
- bestehender Single-Rendercode bleibt ohne fachliche Verhaltensaenderung
  lauffaehig
- Generatoren koennen zunaechst schrittweise auf explizite
  Phasenzuordnung umgestellt werden, weil sinnvolle Defaults vorhanden
  sind
- SQL-, JSON- und Report-Layer muessen keine eigene
  Phasenableitungslogik erfinden

---

## 2. Ausgangslage

Der aktuelle Portschnitt in
`hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
ist noch flach:

- `DdlResult` enthaelt nur `statements` und `skippedObjects`
- `notes` werden implizit aus allen Statements aggregiert
- `DdlStatement` kennt nur `sql` und `notes`
- `TransformationNote` und `SkippedObject` kennen keinen Phasenbezug
- globale, nicht an ein Statement gebundene DDL-Notes haben im Modell
  noch keinen eigenen Platz
- `render()` liefert nur einen Gesamtstring

Das ist fuer den bisherigen Single-Output ausreichend, reicht aber fuer
den 0.9.2-Split nicht:

- SQL-Ausgabe braucht `pre-data` und `post-data`
- JSON und Report muessen denselben Phasenbezug spiegeln
- globale Diagnoseeintraege duerfen nicht versehentlich einer falschen
  Phase zugeschlagen werden
- Phasenhilfen muessen dieselbe Reihenfolge- und Formatlogik wie
  `render()` beibehalten, damit spaetere Output-Layer keinen eigenen
  Rendervertrag definieren

Gleichzeitig darf 6.1 den Bestandspfad nicht destabilisieren:

- bestehende Generatoren erzeugen heute weiterhin
  `DdlStatement(sql, notes)`
- `ManualActionRequired` mappt Notes und Skips aktuell ohne Phase
- spaetere Arbeitspakete sollen auf dem neuen Modell aufbauen koennen,
  ohne dass 6.1 bereits alle Generatoren fachlich komplett umsortieren
  muss

---

## 3. Scope fuer 6.1

### 3.1 In Scope

- `DdlPhase` als Modelltyp mit mindestens:
  - `PRE_DATA`
  - `POST_DATA`
- Erweiterung von:
  - `DdlStatement`
  - `TransformationNote`
  - `SkippedObject`
  um ein Phasenfeld gemaess Masterplan
- Erweiterung von `DdlResult` um:
  - Gesamt-Rendering wie heute
  - phasenbezogenes Rendering
  - phasenbezogene Aggregation von Notes
  - phasenbezogene Aggregation von `skippedObjects`
- Rueckwaertskompatible Defaults fuer bestehende Aufrufer
- Portseitige Tests fuer Modell- und Renderverhalten

### 3.2 Bewusst nicht Teil von 6.1

- sichtbare CLI-Option `--split`
- Ausgabeformat fuer JSON oder Report
- finale Objektzuordnung aller Generatorobjekte zu `PRE_DATA` oder
  `POST_DATA`
- Dateinamens- oder Outputpfadlogik
- Rollback-Split

Praezisierung:

6.1 loest "kann das DDL-Modell Phasen ausdruecken?", nicht "welcher
Nutzervertrag wird bereits sichtbar?".

---

## 4. Leitentscheidungen

### 4.1 Der Phasenbezug lebt im Portmodell

Die Phase wird nicht nur spaeter im Renderer oder im Runner abgeleitet.

Verbindliche Folge:

- `DdlStatement` ist die primaere Quelle fuer den Phasenbezug
- `TransformationNote` und `SkippedObject` koennen dieselbe Information
  transportieren
- `DdlResult` bekommt zusaetzlich `globalNotes`, damit nicht an
  Statements gebundene Diagnoseeintraege im Modell explizit darstellbar
  sind
- alle spaeteren Ausgabepfade greifen auf dieselbe Modellwahrheit zu

### 4.2 `DdlStatement.phase` bekommt einen konservativen Default

Verbindliche Entscheidung gemaess Masterplan:

- `DdlStatement.phase` defaultet auf `DdlPhase.PRE_DATA`

Begruendung:

- bestehender Code mit `DdlStatement(sql, notes)` bleibt korrekt
  kompilierbar
- fuer den Single-Pfad ist der Default semantisch neutral
- fuer den Split ist `PRE_DATA` der konservative Ausgangspunkt fuer
  strukturelle DDL

### 4.3 Notes und Skips bleiben nullable

Verbindliche Entscheidung gemaess Masterplan:

- `TransformationNote.phase: DdlPhase? = null`
- `SkippedObject.phase: DdlPhase? = null`

Begruendung:

- nicht jede Diagnose gehoert fachlich zu genau einer Phase
- globale Warnungen oder Analysehinweise sollen im Modell moeglich
  bleiben
- spaetere JSON-/Report-Codecs koennen `phase` nur dann serialisieren,
  wenn sie fachlich bekannt ist

### 4.4 Statement-Notes erben die Phase des Statements

Das Modell soll fuer den Normalfall nicht doppelt gepflegt werden.

Verbindliche Folge:

- `DdlResult` fuehrt fuer 6.1 ein eigenes Feld
  `globalNotes: List<TransformationNote> = emptyList()` ein
- Notes, die an einem `DdlStatement` haengen, werden bei Aggregation und
  Phasenfilterung der Statement-Phase zugeordnet
- ein separates `note.phase` ist nur fuer freistehende oder explizit
  abweichende Diagnoseeintraege relevant
- `notesForPhase(phase)` besteht genau aus:
  - allen Notes von Statements dieser Phase in Statement-Reihenfolge
  - plus explizit phasengebundenen globalen Notes derselben Phase in
    ihrer Ursprungsreihenfolge
- dieselbe Note darf in einer einzelnen Aggregationssicht hoechstens
  einmal vorkommen; 6.1 fuehrt keine implizite Duplikation derselben
  Instanz ein

### 4.5 Der Single-Pfad bleibt unveraendert

Verbindliche Folge:

- `render()` bleibt als Gesamtansicht erhalten
- bestehende Aufrufer muessen nicht sofort auf `renderPhase(...)`
  umgestellt werden
- neue Hilfen duerfen den bisherigen Output nicht still veraendern
- `renderPhase(phase)` ist ein stabiler Teilmengen-Render von
  `render()`:
  - Statements bleiben in derselben relativen Reihenfolge wie in
    `DdlResult.statements`
  - die Formatierung pro Statement ist identisch zu `DdlStatement.render()`
  - zwischen Statements gelten dieselben Trenner wie bei `render()`
  - eine leere Phasensicht rendert als leerer String ohne Zusatz-
    Leerzeilen

### 4.6 `null` ist global, nie implizit phasengebunden

Verbindliche Folge:

- `phase = null` bedeutet ausschliesslich "global/unbekannt"
- phasenbezogene Hilfsfunktionen behandeln `null` nie als `PRE_DATA`
  oder `POST_DATA`
- spaetere JSON- und Report-Layer muessen dieselbe Semantik
  uebernehmen:
  - in Phasensichten wird `null` nicht materialisiert
  - in der Gesamtsicht darf `phase` fehlen, aber niemals implizit
    defaulten

---

## 5. Konkrete Arbeitsschritte

### 5.1 Portmodell erweitern

- `enum class DdlPhase` in den DDL-Port einfuehren
- `DdlResult` um `globalNotes: List<TransformationNote> = emptyList()`
  erweitern
- `DdlStatement` um `phase: DdlPhase = DdlPhase.PRE_DATA` erweitern
- `TransformationNote` um `phase: DdlPhase? = null` erweitern
- `SkippedObject` um `phase: DdlPhase? = null` erweitern

### 5.2 `DdlResult` um Phasenhilfen ergaenzen

- Gesamtansicht beibehalten:
  - `notes` als deterministische Aggregation aus Statement-Notes plus
    `globalNotes`
  - `render()`
- neue Hilfen einfuehren:
  - `renderPhase(phase: DdlPhase): String`
  - `statementsForPhase(phase: DdlPhase)`
  - `notesForPhase(phase: DdlPhase)`
  - `skippedObjectsForPhase(phase: DdlPhase)`

Wichtig:

- `renderPhase(...)` arbeitet als deterministische Filteransicht ueber
  `DdlResult.statements`
  - Reihenfolge bleibt identisch zur Originalreihenfolge in
    `statements`
  - Formatierung und Trenner sind exakt mit `render()` kompatibel
  - bei leerem Ergebnis wird `""` geliefert
- `notes` in der Gesamtsicht bestehen aus:
  - allen Statement-Notes in Statement-Reihenfolge
  - danach allen `globalNotes` in ihrer Ursprungsreihenfolge
- `notesForPhase(...)` muss Statement-Notes ueber deren Statement-Phase
  einsammeln
- `notesForPhase(...)` darf zusaetzlich explizit phasengebundene globale
  Notes aus `globalNotes` enthalten
- die Reihenfolge in `notesForPhase(...)` ist deterministisch:
  - zuerst Notes aus Statements der Phase in Statement-Reihenfolge
  - danach explizit phasengebundene globale Notes aus `globalNotes` in ihrer
    Ursprungsreihenfolge
- dieselbe Note darf in einer Sicht nicht doppelt auftauchen; falls
  Statement-Notes bereits ueber das Statement eingesammelt wurden, werden
  sie nicht ein zweites Mal als globale Note angehaengt
- freistehende Notes mit `phase = null` duerfen in einer Phasensicht
  nicht versehentlich auftauchen
- `skippedObjectsForPhase(...)` filtert ausschliesslich explizit
  phasengebundene Skips; `phase = null` bleibt Gesamtsicht

### 5.3 `ManualActionRequired` mitziehen

- `toNote()` soll optional eine Phase weitergeben koennen
- `toSkipped()` soll optional eine Phase weitergeben koennen

Ziel:

- spaetere Generatorarbeit kann strukturierte `ACTION_REQUIRED`-Hinweise
  ohne Zusatzmapping korrekt in `PRE_DATA` oder `POST_DATA` einordnen

### 5.4 Bestehende Aufrufer minimal anpassen

- nur dort Anpassungen vornehmen, wo neue Konstruktorfelder den Compiler
  oder Tests beruehren
- keine vorgezogene fachliche Umverteilung auf `POST_DATA`, solange 6.3
  diese Zuordnung noch nicht festgezogen hat
- bestaetigen, dass auch angrenzende Consumer des DDL-Modells
  unveraendert funktionieren:
  - Report Writer
  - CLI-/Runner-Helfer
  - Migration-/Normalizer-Pfade
  - Integrations-Exporter-Tests

### 5.5 Tests ergaenzen

Mindestens abzudecken:

- Default von `DdlStatement.phase` ist `PRE_DATA`
- `DdlResult.notes` aggregiert Statement-Notes und `globalNotes`
  deterministisch
- `render()` bleibt im Single-Fall unveraendert
- `renderPhase(PRE_DATA)` und `renderPhase(POST_DATA)` filtern korrekt
- Statement-Notes werden der Statement-Phase zugeschlagen
- explizit phasengebundene `globalNotes` erscheinen in
  `notesForPhase(...)` genau einmal und in definierter Reihenfolge
- freistehende Notes oder Skips mit `null` erscheinen nicht in einer
  Phasensicht
- `skippedObjectsForPhase(...)` gibt Objekte mit `phase = null` nicht
  zurueck
- die betroffene Repo-Testsuite bleibt gruen, nicht nur die
  Port-Tests

---

## 6. Verifikation

Pflichtfaelle fuer 6.1:

- Porttests beweisen die Default- und Filtersemantik von `DdlPhase`
- ein bestehender Single-Render-Test bleibt unveraendert oder wird
  explizit als Rueckwaertskompatibilitaet fortgefuehrt
- `DdlResult.notes` bleibt als Gesamtaggregation nutzbar und umfasst
  Statement-Notes plus `globalNotes`
- `renderPhase(PRE_DATA)` liefert nur Statements mit `PRE_DATA`
- `renderPhase(POST_DATA)` liefert nur Statements mit `POST_DATA`
- `renderPhase(...)` behaelt die relative Reihenfolge aus
  `DdlResult.statements` exakt bei
- `renderPhase(...)` nutzt dieselben Trenner wie `render()` und liefert
  bei leerer Sicht exakt `""`
- Notes aus einem `POST_DATA`-Statement tauchen nicht in der
  `PRE_DATA`-Sicht auf
- `notesForPhase(...)` hat eine definierte Reihenfolge zwischen
  Statement-Notes und globalen phasengebundenen Notes
- `notesForPhase(...)` dupliziert keine bereits ueber Statements
  aggregierten Notes
- `SkippedObject.phase = null` erzeugt keine implizite Zuordnung
- `skippedObjectsForPhase(PRE_DATA|POST_DATA)` liefert niemals Skips mit
  `phase = null`
- die betroffenen Modul- und Regressionstests ausserhalb von
  `ports-read` bleiben gruen

Erwuenschte Zusatzfaelle:

- Test fuer leere Phasenansicht ohne ueberfluessige Leerzeilen
- Test fuer gemischte Statement-/Global-Notes im selben `DdlResult`
- Test dafuer, dass JSON-/Report-nahe Mapper `phase = null` in
  Phasensichten nicht auf `PRE_DATA` defaulten

---

## 7. Betroffene Codebasis

Direkt betroffen:

- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/DdlGenerator.kt`
- `hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/ManualActionRequired.kt`
- zugehoerige Tests unter `hexagon/ports-read/src/test/...`
- Generatoren und DDL-Helfer unter
  `adapters/driven/driver-*/src/main/kotlin/...`
- Report-/Format-Pfade unter
  `adapters/driven/formats/src/main/kotlin/...`
- CLI-/Runner-Pfade unter
  `adapters/driving/cli/src/main/kotlin/...` und
  `hexagon/application/src/main/kotlin/...`

Wahrscheinlich indirekt mit betroffen:

- Integrations-Exporter und ihre Tests unter
  `adapters/driven/integrations/src/test/kotlin/...`
- Migration-/Normalizer-Pfade unter
  `hexagon/application/src/test/kotlin/...`
- Report-, CLI- und Generator-Tests unter
  `adapters/driven/formats/src/test/...`,
  `adapters/driving/cli/src/test/...` und
  `adapters/driven/driver-*/src/test/...`
- weitere DDL-Modell-Consumer in angrenzenden Modulen, die dank
  Default-Parametern voraussichtlich unveraendert kompilieren, aber in
  der Regression sichtbar bleiben sollen

---

## 8. Risiken und Abgrenzung

### 8.1 Ein zu frueher Generator-Umbau mischt 6.1 und 6.3

Risiko:

- wenn 6.1 bereits breite fachliche Objektzuordnungen implementiert,
  verschwimmt die Grenze zur Generatorphase 6.3

Mitigation:

- in 6.1 nur Modell, Defaults und Hilfsfunktionen ziehen
- explizite `POST_DATA`-Zuordnung nur dort vornehmen, wo sie fuer
  Kompilierbarkeit oder offensichtliche Strukturfaelle noetig ist

### 8.2 Null- und Default-Semantik kann spaetere Codecs irritieren

Risiko:

- JSON- oder Report-Layer koennten `null` versehentlich als
  `PRE_DATA` interpretieren

Mitigation:

- im Teilplan klar festhalten: `null` bedeutet "global/unbekannt", nicht
  "implizit pre-data"
- phasenbezogene Helfer muessen `null` explizit ausfiltern
- spaetere Mapper fuer JSON/Report duerfen `null` nur in der
  Gesamtsicht transportieren, nie als implizite Phasenbelegung

### 8.3 Rueckwaertskompatibilitaet des Single-Pfads kann unbemerkt kippen

Risiko:

- bereits kleine Renderaenderungen an `DdlResult` oder `DdlStatement`
  koennen Golden Masters unnoetig verschieben

Mitigation:

- `render()` semantisch unveraendert lassen
- bestaetigende Tests fuer den Bestandspfad beibehalten
