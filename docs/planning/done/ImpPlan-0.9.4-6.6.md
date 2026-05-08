# Implementierungsplan: 0.9.4 - Arbeitspaket 6.6 `Tests und Verifikation`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.6 (`Phase D/E: Tests und Verifikation`)
> **Status**: Done (2026-04-21)
> **Referenz**: `docs/planning/implementation-plan-0.9.4.md` Abschnitt 6.6,
> Abschnitt 7 und Abschnitt 9 (im Masterplan);
> `docs/planning/ImpPlan-0.9.4-6.1.md`;
> `docs/planning/ImpPlan-0.9.4-6.2.md`;
> `docs/planning/ImpPlan-0.9.4-6.3.md`;
> `docs/planning/ImpPlan-0.9.4-6.4.md`;
> `docs/planning/ImpPlan-0.9.4-6.5.md`;
> `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`;
> `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueriesTest.kt`;
> `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunnerTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaCompareTest.kt`;
> `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/CompareRendererOperandTest.kt`;
> `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceEmulationIntegrationTest.kt`.

---

## 1. Ziel

Arbeitspaket 6.6 zieht die verstreuten Test- und
Verifikationsanforderungen aus D1 bis D3, E1 und E2 in ein
verbindliches Testpaket zusammen. Der Teilplan beschreibt nicht neue
Produktlogik, sondern den Nachweis, dass Reverse, Compare, `W116` und
Supportobjekt-Unterdrueckung auf Unit-, Integrations-, Runner-,
Renderer-Unit- und CLI-Ebene stabil funktionieren.

6.6 liefert fuenf konkrete Ergebnisse:

- ein klares Testlayout fuer Driver-, Application- und CLI-Ebene
- reproduzierbare Nachweise fuer intakte und degradierte
  Sequence-Reverse-Pfade
- verbindliche Compare-Tests fuer operandseitiges `W116`
- mindestens einen echten Round-Trip
  `neutral -> generate -> MySQL -> reverse -> compare`
- eine sichtbare Verifikationsmatrix, welche 0.9.4-Pflichtfaelle durch
  welchen Testtyp abgesichert werden

Nach 6.6 soll klar gelten:

- Reverse und Compare sind fuer MySQL-Sequence-Emulation testseitig
  nicht nur plausibel, sondern belegbar stabil
- degradierte Faelle erzeugen reproduzierbar `W116`, ohne kuenstliche
  Diffs oder Exit-Code-Verschiebungen
- kanonische Supportobjekte tauchen im Compare nicht mehr als
  Tabellen-, Funktionen- oder Trigger-Rauschen auf

---

## 2. Ausgangslage

Die fachlichen Vertraege fuer 0.9.4 sind inzwischen ueber mehrere
Teilplaene verteilt:

- D1 beschreibt Reader-Vertrag, Gating und interne Supporterkennung
- D2 beschreibt `dmg_sequences`-Reverse und sequence-bezogene `W116`
- D3 beschreibt Trigger-basiertes Default-Reverse und spaltenbezogene
  `W116`
- E1 beschreibt Compare-, Runner- und Renderer-Vertrag
- E2 konsolidiert Nutzer- und Ledger-Dokumentation

Der Teststand dazu ist heute noch auf mehrere Ebenen verteilt:

- `MysqlSchemaReaderTest` hat bereits 63 Tests und deckt
  Supportobjekte, `W116`, Unterdrueckung sowie D2-/D3-Materialisierung
  breit ab
- `MysqlMetadataQueriesTest` hat bereits 37 Tests und deckt
  Support-Routine-, Trigger- und Metadatenzustandsklassen gezielt ab
- `SchemaCompareRunnerTest` hat bereits 29 Tests und deckt
  operandseitiges `W116`, Exit-Code-Vertrag, Validation-Trennung sowie
  file-vs-file, file-vs-db und db-vs-db-Grundpfade ab
- `CliSchemaCompareTest` hat bereits 19 Tests und deckt sichtbare
  JSON-/YAML-Operanddiagnostik und Nutzeroutput ab
- `CompareRendererOperandTest` hat bereits 11 Tests und deckt die
  direkte Renderer-Sicht fuer Plain, JSON und YAML inklusive
  Validation-Trennung ab
- `MysqlSequenceEmulationIntegrationTest` hat bereits 15 Tests und
  deckt echte MySQL-Round-Trip- und Degradationsfaelle gegen eine
  reale DB ab

6.6 startet damit nicht auf gruenem Feld. Der eigentliche offene Punkt
ist nicht "ob Tests existieren", sondern:

- ob die vorhandene Testbasis den finalen 0.9.4-Vertrag vollstaendig
  und ohne blinde Flecken abdeckt
- welche wenigen Delta-Faelle gegen Masterplan und Teilplaene noch
  explizit fehlen
- wie vorhandene Unit-, Integration-, Runner-, Renderer- und CLI-Tests
  zu einer sichtbaren Verifikationsmatrix zusammengezogen werden

Was fuer 6.6 noch fehlt, ist daher der verbindliche Audit- und
Delta-Schnitt:

- welche 0.9.4-Faelle werden auf welcher Testebene abgesichert
- welche Faelle verlangen zwingend echte MySQL-Integration statt nur
  Mock-/Snapshot-Tests
- welche Compare-Vertraege sind reine Runner-Tests und welche muessen
  bis zum CLI-Output durchgezogen werden
- welches Round-Trip-Szenario als harter Abschluss-Gate gilt

Konsequenz ohne 6.6:

- einzelne D1/D2/D3/E1-Vertraege waeren testseitig nur lokal
  abgesichert, aber nicht als Gesamtverhalten
- echte MySQL-Metadatenfaelle und Compare-Ausgabe koennten
  auseinanderlaufen
- 0.9.4 haette keinen klaren Nachweis, dass `W116` Diagnose bleibt und
  Compare trotzdem diff- und exit-stabil bleibt

---

## 3. Scope fuer 6.6

### 3.1 In Scope

- D1 bis D3 und E1 sind fachliche Voraussetzung fuer 6.6
- Audit und Delta-Nachzug in:
  - `MysqlSchemaReaderTest`
  - `MysqlMetadataQueriesTest`, falls Query- oder
    Metadatenklassifikation dort gezielt abgesichert werden muss
  - MySQL-Testcontainer-Integrationstests fuer Reverse und Compare
  - `SchemaCompareRunnerTest`
  - `CompareRendererOperandTest`
  - `CliSchemaCompareTest`
- verbindliche Round-Trip-Absicherung
  `neutral -> generate -> MySQL -> reverse -> compare`
- Nachweis fuer:
  - Sequence-Reverse
  - Trigger-basiertes Default-Reverse
  - `W116` bei degradierten Faellen
  - Supportobjekt-Unterdrueckung
  - operandseitige Notes in Plain/JSON/YAML
  - Exit-Code-Stabilitaet bei operandseitigem `W116`
- Zuordnung der 0.9.4-Pflichtfaelle auf Unit-, Integration-, Runner-,
  Renderer-Unit- und CLI-Testtypen

### 3.2 Bewusst nicht Teil von 6.6

- neue Produktionslogik in Reader, Reverse oder Compare
- Ausbau auf weitere Dialekte jenseits MySQL
- breit angelegte Performance- oder Lasttests
- rein redaktioneller Doku-Nachzug aus E2, ausser soweit Tests als
  fachliche Evidenz dafuer benoetigt werden

Praezisierung:

6.6 loest "wie wird der 0.9.4-Vertrag reproduzierbar belegt?", nicht
"welche neue Fachlogik fehlt noch?".

---

## 4. Leitentscheidungen

### 4.1 Tests folgen dem Produktvertrag, nicht der internen Zufallsform

Verbindliche Folge:

- Tests pruefen sichtbares Fachverhalten und klar definierte
  Aggregationsergebnisse
- reine Implementierungsdetails ohne Vertragsrelevanz werden nicht zum
  primaeren Testanker

### 4.2 Jede Testebene hat eine feste Rolle

Verbindliche Folge:

- Unit-Tests pruefen Klassifikation, Mapping, Aggregation und
  Unterdrueckungsregeln
- MySQL-Integrationstests pruefen echte JDBC-/Metadata-Verhaeltnisse,
  Quoting- und Markerrealitaet sowie den Round-Trip
- Runner-Tests pruefen Compare-Dokument, Operanddiagnosen und
  Exit-Codes
- Renderer-Unit-Tests pruefen die direkte Plain-/JSON-/YAML-
  Serialisierung unabhaengig vom CLI-Einstieg
- CLI-Tests pruefen sichtbaren Nutzervertrag ueber den echten
  Kommandoeingang

### 4.3 Ein echter Round-Trip ist Pflicht und kein optionaler Bonus

Verbindliche Folge:

- mindestens ein Testcontainer-basierter Fall muss
  `neutral -> generate -> MySQL -> reverse -> compare`
  vollstaendig durchlaufen
- 6.6 gilt nicht als fachlich abgeschlossen, wenn nur Teilpfade
  isoliert getestet wurden

### 4.4 Degradierte Faelle muessen explizit `W116` belegen

Verbindliche Folge:

- degradiertes Verhalten darf nicht nur implizit ueber fehlende
  Rekonstruktion getestet werden
- der erwartete `W116`-Pfad muss pro Testfall sichtbar und eindeutig
  assertiert werden

### 4.5 Compare wird auf Neutralmodell- und Operanddiagnose-Ebene belegt

Verbindliche Folge:

- Sequence-Diffs werden ueber Compare-Ergebnis und Dokumentstruktur
  getestet, nicht ueber MySQL-Hilfsobjekte
- operandseitige Notes bleiben Diagnose des Operanden und werden nicht
  kuenstlich in `validation` oder Diffarten umetikettiert

### 4.6 Synthetische Notes sind zulaessig, reale D1-D3-Evidenz bleibt Ziel

Verbindliche Folge:

- Runner- und CLI-Tests duerfen vor dem finalen T5-Gate mit
  synthetischen Operand-Notes arbeiten, wenn der fachliche
  Dokument-/Renderer-Vertrag bereits unabhaengig pruefbar ist
- der Audit muss bestaetigen, dass mindestens ein existierender oder
  neuer Test echten D1-D3-Output als Eingangsevidenz nutzt; ohne
  diesen Nachweis ist 6.6 nicht abgeschlossen

---

## 5. Zielarchitektur fuer 6.6

### 5.1 Testschichten und Verantwortung

6.6 organisiert die 0.9.4-Testabdeckung in fuenf Schichten:

1. Driver-Unit
   - `MysqlSchemaReaderTest`
   - optional `MysqlMetadataQueriesTest`
   - Verantwortung:
     - Supportzustandsklassifikation
     - Sequence-/Trigger-Materialisierung
     - `W116`-Aggregation
     - Supportobjekt-Unterdrueckung
2. Driver-Integration
   - MySQL-Testcontainer-Tests
   - Verantwortung:
     - echte Metadatenquellen
     - echte Generator-/Reverse-Wege
     - Round-Trip und Degradationsfaelle gegen eine reale MySQL-DB
3. Application-Runner
   - `SchemaCompareRunnerTest`
   - Verantwortung:
     - Compare-Dokument
     - `sourceOperand`/`targetOperand`
     - Exit-Code-Vertrag
4. Renderer-Unit
   - `CompareRendererOperandTest`
   - Verantwortung:
     - direkte Plain-/JSON-/YAML-Darstellung
     - Trennung von Operand-Notes und `validation`
     - strukturierte Operandfelder ohne CLI-Nebenpfade
5. CLI-Output
   - `CliSchemaCompareTest`
   - Verantwortung:
     - sichtbarer Nutzervertrag am Kommandoeingang
     - Plain-Sichtbarkeit
     - strukturierte JSON-/YAML-Ausgabe

### 5.2 Testmatrix nach Fachfall

6.6 verteilt die wesentlichen 0.9.4-Faelle wie folgt:

- Reader-/Aggregationsfaelle primaer in Driver-Unit
- Metadata-/Quoting-/Markerrealitaet primaer in Driver-Integration
- Compare-Diff-/Exit-Semantik primaer in Runner-Tests
- Renderer-Vertrag primaer in Renderer-Unit-Tests:
  Serialisierungsvertrag, Note-Platzierung, Feld-Trennung,
  Auslassung leerer Bloecke
- Outputformat- und Sichtbarkeitsvertrag primaer in CLI-Tests:
  Nutzervertrag am Kommandoeingang, `stderr`-Verhalten, Formatwahl,
  Datei-/Stdout-Ausgabe

Verbindliche Regel:

- ein Fall darf auf mehreren Ebenen abgesichert werden, aber jede Ebene
  bekommt einen klaren Primaerauftrag
- kein Pflichtfall bleibt allein auf manueller Sichtpruefung

### 5.3 Artefaktstrategie

6.6 erzeugt keine neue Produktionsarchitektur, aber einen klaren
Testartefakt-Schnitt:

- Driver-Tests liefern die fachliche Reverse-Evidenz
- Runner-/CLI-Tests liefern die Compare- und Output-Evidenz
- Renderer-Unit-Tests liefern den direkten Serialisierungsnachweis
- der Round-Trip-Test ist das integrative Abschlussartefakt fuer
  0.9.4

Empfohlene Reihenfolge:

1. T0 Testmatrix und Lueckenbild festziehen
2. T1 Driver-Unit-Audit und Delta fuer D1 bis D3
3. T2 MySQL-Integration auditieren und Round-Trip-Gate absichern
4. T3 Runner-Vertraege auditieren und Delta absichern
5. T4 Renderer- und CLI-Vertrag auditieren und Delta absichern
6. T5 Abschluss-Gegenlauf und Coverage-Gate

---

## 6. Konkrete Arbeitsschritte

### T0 Testmatrix und Lueckenbild festziehen

- die vorhandene Testbasis gegen den 0.9.4-Masterplan auditiert
  zusammenfassen
- die 0.9.4-Pflichtfaelle aus dem Masterplan auf konkrete Testorte
  mappen und nur echte Delta-Luecken markieren
- markieren, welche Faelle:
  - rein unit-testbar sind
  - zwingend echte MySQL-Integration brauchen
  - Runner-/CLI-Vertrag brauchen
- festlegen, dass die T0-Matrix in diesem Teilplan selbst lebt:
  Ausgangslage in Abschnitt 2, Pflichtabdeckung in Abschnitt 7
- offene Alt-Tests oder Delta-Luecken gegen D1 bis E1 sichtbar benennen

Done-Kriterien:

- jeder Pflichtfall hat einen primaeren Testort
- die vorhandene Testbasis ist als Audit-Stand im Teilplan selbst
  benannt, nicht nur implizit vorausgesetzt
- Round-Trip- und Exit-Code-Faelle sind explizit als Pflicht-Gates
  markiert

### T1 Driver-Unit-Audit und Delta fuer D1 bis D3

- `MysqlSchemaReaderTest` und `MysqlMetadataQueriesTest` gegen die
  finalen D1-/D2-/D3-Vertraege auditieren
- nur dort erweitern, wo der Audit echte Delta-Luecken zeigt:
  Supportobjekt-, Unterdrueckungs-, Grundform- oder
  Degradationsfaelle
- positive und degradierte Sequence-/Trigger-Faelle gegen dieselben
  Aggregationsvertraege absichern

Done-Kriterien:

- D1-, D2- und D3-Kernvertraege haben nach dem Audit belastbare
  Unit-Test-Evidenz
- `W116` wird in Sequence- und Trigger-Degradationsfaellen explizit
  assertiert
- Supportobjekt-Unterdrueckung und Nicht-Unterdrueckung nicht-
  kanonischer Objekte sind abgedeckt

### T2 MySQL-Integration auditieren und Round-Trip-Gate absichern

- `MysqlSequenceEmulationIntegrationTest` als bestehende Basis gegen
  den finalen 0.9.4-Vertrag auditieren
- nur fehlende Delta-Faelle gegen echte MySQL-DB ergaenzen oder
  gezielt neue Faelle anlegen
- intakten Round-Trip
  `neutral -> generate -> MySQL -> reverse -> compare`
  verbindlich absichern
- degradierte Realfaelle gegen eine echte MySQL-DB absichern:
  fehlende Routinen, fehlende Trigger, markerlose Trigger,
  Grundformabweichungen, mehrere Sequences

Done-Kriterien:

- mindestens ein echter Round-Trip ist gruen
- mindestens ein degradiertes Reverse-Szenario ist gegen echte MySQL-
  Metadaten nachgewiesen
- Compare gegen echte reverse-te Operanden bleibt frei von
  Hilfsobjekt-Rauschen

### T3 Runner-Vertraege auditieren und Delta absichern

- `SchemaCompareRunnerTest` gegen file-vs-file, file-vs-db und db-vs-db
  auditieren; nur fehlende Delta-Faelle fuer operandseitige Notes und
  Exit-Codes nachziehen
- absichern, dass `CompareValidation` keine Operand-Notes aufnimmt
- db-vs-db gilt primaer als Runner-Pfad ueber zwei `db:`-Operanden und
  `dbLoader`; eine zweite Live-DB ist fuer 6.6 nur dann Pflicht, wenn
  der fachliche Vertrag anders nicht belegbar ist
- exit-stabile Faelle fuer `W116` ohne Diff explizit pruefen

Done-Kriterien:

- operandseitiges `W116` fuehrt ohne echten Diff nicht zu Exit 1
- operandseitiges `W116` fuehrt ohne Validation nicht zu Exit 3
- `sourceOperand`/`targetOperand` und `validation` bleiben sauber
  getrennt

### T4 Renderer- und CLI-Vertrag auditieren und Delta absichern

- `CompareRendererOperandTest` und `CliSchemaCompareTest` gegen den
  finalen Outputvertrag auditieren
- nur fehlende Delta-Faelle fuer Plain-, JSON- und YAML-Pfade
  ergaenzen
- absichern, dass operandseitige Notes im strukturierten Dokument
  sichtbar bleiben
- pruefen, dass JSON/YAML keine zusaetzliche Plain-`stderr`-Doppelung
  erzwingen

Done-Kriterien:

- Renderer-Unit belegt, dass JSON/YAML operandseitige Notes in
  `sourceOperand`/`targetOperand` tragen
- Renderer-Unit belegt, dass `validation` in JSON/YAML frei von
  operandseitigen Notes bleibt
- CLI belegt die sichtbare Plain-Ausgabe und den Nutzervertrag am
  Kommandoeingang

### T5 Abschluss-Gegenlauf und Coverage-Gate

- Pflichtfaelle gegen T0-Testmatrix gegenpruefen
- Coverage-Ziel fuer die betroffenen Driver-/Application-Module
  verifizieren
- offene Luecken oder bewusst verschobene Restfaelle explizit benennen

Done-Kriterien:

- alle Pflichtfaelle aus Abschnitt 7 sind einem gruenen Test
  zugeordnet und bestanden oder explizit als blockiert markiert
- pro betroffenem Modul gilt der Projektstandard von mindestens
  90% Line-Coverage
- 6.6 ist erst voll abgeschlossen, wenn mindestens ein Test echten
  D1-D3-Output als Eingang fuer Compare- oder CLI-Verifikation nutzt

Abhaengigkeiten:

- T0 vor T1 bis T5
- T1 vor dem finalen T2-Round-Trip-Gate
- T2 vor finalem Abschluss von T5
- T3 und T4 koennen nach T0 parallel zu T1/T2 anlaufen, wenn fuer
  Zwischenstufen synthetische Operand-Notes genuegen
- der finale Abschluss von T5 haengt daran, dass T2 den
  D1-D3-Realitaetsnachweis geliefert hat; T3 und T4 selbst schliessen
  ab, wenn ihr Audit und ggf. Delta auf ihrer Schicht erledigt ist

---

## 7. Verifikation

Pflichtfaelle fuer 6.6:

Faelle 1 bis 14 uebernehmen den fachlichen Vertrag aus Abschnitt 7 des
Masterplans. Faelle 15 und 16 erweitern den Produkt-Scope nicht,
sondern machen den in den Masterplan-Faellen 8 und 9 bereits
enthaltenen Exit- und Outputvertrag fuer Testzwecke explizit.

Statusschema pro Pflichtfall:

- `offen` = Audit fuer diesen Fall noch nicht auf konkreten Test
  rueckgefuehrt
- `abgedeckt durch ...` = vorhandene Testevidenz ist ausreichend und
  konkret benannt
- `Delta in Tn` = es fehlt gezielte Nacharbeit in T1 bis T4
- `blockiert ...` = Fall ist bewusst verschoben oder haengt an einem
  externen Vorzustand

1. **Intakter Round-Trip**
   `neutral -> generate -> MySQL -> reverse -> compare` bleibt
   diff-frei und exit-stabil.
   Primaer: Integration.
   Status: `abgedeckt durch` `MysqlSequenceEmulationIntegrationTest`
   (`compare reverse snapshot against live reverse output stays diff-free`,
   `PF1: round-trip neutral sequence definition survives generate-reverse cycle`).

2. **Sequence-Metadaten-Diff**
   geaenderte `increment`-, `minValue`-, `maxValue`-, `cycle`- oder
   `cache`-Werte fuehren genau zu Sequence-Diffs.
   Primaer: Runner, ergaenzend Integration.
   Status: `abgedeckt durch` `SchemaComparatorTest`
   (`sequence changed`, `sequence changed reports increment, min/max, cycle, and cache diffs`)
   und `MysqlSequenceEmulationIntegrationTest`
   (`compare reverse snapshot reports sequence metadata drift as pure sequencesChanged`).

3. **Support-Routinen fehlen**
   Sequences bleiben reverse-bar, aber der Operand traegt
   sequence-bezogenes `W116`.
   Primaer: Unit, ergaenzend Integration.
   Status: `abgedeckt durch` `MysqlSchemaReaderTest`
   (`D2: missing routines produce W116 per materialized sequence`)
   und `MysqlSequenceEmulationIntegrationTest`
   (`PF3: missing support routines produce W116 against real MySQL`).

4. **Support-Trigger fehlt**
   Sequence bleibt sichtbar, `SequenceNextVal` wird nicht
   rekonstruiert, `W116` erscheint.
   Primaer: Unit, ergaenzend Integration.
   Status: `abgedeckt durch` `MysqlMetadataQueriesTest`
   (`listPotentialSupportTriggers returns MISSING_MARKER when marker absent`),
   `MysqlSchemaReaderTest` (`D3: MISSING_MARKER trigger produces no SequenceNextVal`)
   und `MysqlSequenceEmulationIntegrationTest`
   (`reverse treats markerless support-like trigger as degraded without SequenceNextVal`).

5. **Include-Flag-Unabhaengigkeit**
   Reverse funktioniert fuer Sequence-Erkennung auch mit
   `includeTriggers = false` und `includeFunctions = false`.
   Primaer: Unit.
   Status: `abgedeckt durch` `MysqlSchemaReaderTest`
   (`D3: includeTriggers=false does not prevent D3 recognition`,
   `D3: includeTriggers=false via full read() still materializes SequenceNextVal`)
   und `MysqlSequenceEmulationIntegrationTest`
   (`reverse with includeTriggers=false still reconstructs SequenceNextVal`).

6. **Supportobjekt-Unterdrueckung**
   intakte `dmg_sequences`, Support-Routinen und kanonische
   Sequence-Trigger erscheinen nicht als normale Nutzerobjekte.
   Primaer: Unit, ergaenzend Integration.
   Status: `abgedeckt durch` `MysqlSchemaReaderTest`
   (`BASELINE: non-sequence single-table schema produces identical result with support scan`)
   und `MysqlSequenceEmulationIntegrationTest`
   (`reverse: support trigger not visible in user triggers`,
   `PF1: round-trip neutral sequence definition survives generate-reverse cycle`).

7. **Nicht-kanonische Objekte bleiben Nutzerobjekte**
   aehnlich benannte oder markerlose Nutzerobjekte werden nicht
   versehentlich weggefiltert.
   Primaer: Unit.
   Status: `abgedeckt durch` `MysqlSchemaReaderTest`
   (`D3: USER_OBJECT trigger is not suppressed and no W116`)
   und `MysqlSequenceEmulationIntegrationTest`
   (`reverse treats markerless support-like trigger as degraded without SequenceNextVal`).

8. **Compare mit degradiertem Operand**
   operandseitiges `W116` bleibt sichtbar, erzeugt aber keinen
   kuenstlichen Diff-Eintrag.
   Primaer: Runner.
   Status: `abgedeckt durch` `SchemaCompareRunnerTest`
   (`operand W116 without diff returns exit 0`,
   `operand W116 with real diff returns exit 1`,
   `file-vs-db with W116 on target only follows real diff`).

9. **Compare JSON/YAML mit degradiertem Operand**
   operandseitiges `W116` bleibt auch in `sourceOperand`/
   `targetOperand` maschinenlesbar sichtbar.
   Primaer: Renderer-Unit, ergaenzend CLI.
   Status: `abgedeckt durch` `CompareRendererOperandTest`
   (JSON/YAML-Operanddiagnostik) und `CliSchemaCompareTest`
   (`json renderer includes source_operand and target_operand diagnostics`,
   `yaml renderer includes source_operand and target_operand diagnostics`).

10. **Markerloser, aber semantisch intakter Trigger**
    es entsteht in 0.9.4 keine Spaltenzuordnung auf Verdacht, sondern
    degradierte Diagnose mit `W116`.
    Primaer: Unit, ergaenzend Integration.
    Status: `abgedeckt durch` `MysqlSchemaReaderTest`
    (`D3: MISSING_MARKER trigger produces no SequenceNextVal`,
    `D3: MISSING_MARKER without verankerbare Spalte produces no W116`)
    und `MysqlSequenceEmulationIntegrationTest`
    (`reverse treats markerless support-like trigger as degraded without SequenceNextVal`).

11. **Grundform vs. Zusatzspalten**
    Zusatzspalten in `dmg_sequences` brechen den Reverse nicht; fehlende
    Pflichtspalten oder unvereinbare Grundform schon.
    Primaer: Unit, ergaenzend Integration.
    Status: `abgedeckt durch` `MysqlSchemaReaderTest`
    (INVALID_SHAPE-Test fuer falsche Pflichtspaltentypen,
    `PF11: scanSequenceSupport returns AVAILABLE when dmg_sequences has extra columns`,
    `D2: valid row materializes to SequenceDefinition`).

12. **Mehrere Sequences gleichzeitig**
    mindestens zwei Sequences in verschiedenen Tabellen bleiben im
    Reverse parallel stabil; ein degradierter Zustand einer Sequence
    blockiert die andere nicht.
    Primaer: Integration, ergaenzend Unit.
    Status: `abgedeckt durch` `MysqlSequenceEmulationIntegrationTest`
    (`PF12: multiple sequences in different tables reverse independently`)
    und `MysqlSchemaReaderTest`
    (`D3: degraded trigger does not block other confirmed triggers`).

13. **Eine Sequence wird mehrfach genutzt**
    dieselbe Sequence kann mehreren Spalten in verschiedenen Tabellen
    zugeordnet sein; der Reverse faltet alle betroffenen Spalten wieder
    auf dieselbe neutrale Sequence zurueck.
    Primaer: Integration, ergaenzend Unit.
    Status: `abgedeckt durch` `MysqlSequenceEmulationIntegrationTest`
    (`PF13: shared sequence across multiple tables reverses to same sequence`)
    und `MysqlSchemaReaderTest`
    (`D3: multiple columns on same sequence`).

14. **Mehrdeutiger Sequence-Key**
    kollidierende Sequence-Keys werden nicht still ueberschrieben,
    sondern degradiert mit aggregiertem `W116`.
    Primaer: Unit.
    Status: `abgedeckt durch` `MysqlSchemaReaderTest`
    (`D2: ambiguous keys produce W116 but no SequenceDefinition`).

15. **Exit-Code-Stabilitaet bei operandseitigem `W116`**
    file-vs-file, file-vs-db und db-vs-db bleiben bei rein
    operandseitigem `W116` auf Exit 0, solange kein realer Diff oder
    Validation-Fehler vorliegt.
    Primaer: Runner, ergaenzend CLI.
    Status: `abgedeckt durch` `SchemaCompareRunnerTest`
    (`file-vs-file with W116 on both sides but identical model stays exit 0`,
    `file-vs-db with W116 on target only follows real diff`,
    `db-vs-db with W116 on both sides but identical model stays exit 0`)
    und `CliSchemaCompareTest` (strukturierter Operand-Output fuer degradierte Operanden).

16. **Plain-/JSON-/YAML-Trennung**
    Plain zeigt Notes sichtbar; JSON/YAML tragen die Notes strukturiert
    und halten `validation` frei von Operand-Notes.
    Primaer: Renderer-Unit, ergaenzend CLI.
    Status: `abgedeckt durch` `CompareRendererOperandTest`
    (`plain still renders operand info as before`,
    `json does not place operand notes inside validation`,
    `yaml does not place operand notes inside validation`)
    und `SchemaCompareRunnerTest`
    (`operand notes appear on stderr only for plain format`,
    `operand notes do not appear on stderr for json format`,
    `operand notes do not appear on stderr for yaml format`).

Coverage-Ziel:

- pro betroffenem Modul gilt der Projektstandard von mindestens
  90% Line-Coverage
- Schwerpunkt auf den neuen Reverse-Pfaden in `driver-mysql`
- keine 0.9.4-Pflichtpruefung bleibt allein auf manueller Sichtkontrolle

Gesamt-Gate:

6.6 ist erst dann abgeschlossen, wenn T5 erledigt ist und alle
Pflichtfaelle aus diesem Abschnitt als `abgedeckt durch ...`,
`Delta in Tn` oder `blockiert ...` konkret dokumentiert und fuer den
finalen Stand bestanden oder begruendet verschoben sind.

---

## 8. Betroffene Codebasis

Voraussichtlich direkt betroffen:

- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReaderTest.kt`
- `adapters/driven/driver-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueriesTest.kt`
- `test/integration-mysql/src/test/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceEmulationIntegrationTest.kt`
- `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunnerTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliSchemaCompareTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/CompareRendererOperandTest.kt`

Fachlich relevante Vertragsanbieter, voraussichtlich nicht direkt zu
aendern:

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSchemaReader.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlMetadataQueries.kt`
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceReverseSupport.kt`
- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCompareRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererPlain.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererJson.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/CompareRendererYaml.kt`

Optional, falls der bestehende Testaufbau nicht ausreicht:

- dedizierter neuer MySQL-Testcontainer-Test fuer
  reverse-/compare-zentrierte Round-Trip-Faelle

---

## 9. Risiken und Abgrenzung

### 9.1 Zu viel Unit, zu wenig echte MySQL-Realitaet

Risiko:

- reine Unit- oder Snapshot-Tests koennen Marker-, Quoting- oder
  Metadatenbesonderheiten der echten MySQL-DB uebersehen

Gegenmassnahme:

- mindestens ein echter Round-Trip und mindestens ein degradierter
  Realfall sind Pflicht

### 9.2 Flaky Testcontainer-Pfade verwischen den Vertragsnachweis

Risiko:

- instabile Container- oder Timing-Pfade machen aus einem
  Produktvertragsproblem ein zufaelliges Infrastrukturproblem

Gegenmassnahme:

- Integrationsfaelle auf wenige, robuste Pflichtfaelle begrenzen
- den fachlichen Kern parallel in Unit-Tests absichern

### 9.3 Runner- und CLI-Vertrag koennen auseinanderlaufen

Risiko:

- Dokumentstruktur ist korrekt, aber sichtbare CLI-Ausgabe oder
  `stderr`-/JSON-/YAML-Verhalten driftet auseinander

Gegenmassnahme:

- Runner- und CLI-Faelle getrennt, aber auf denselben Operandvertraegen
  absichern

### 9.4 Synthetische Notes bleiben zu lange Ersatz fuer echte Evidenz

Risiko:

- Runner-/CLI-Tests koennen gruen sein, obwohl der echte D1-D3-Pfad
  noch nicht denselben Input liefert

Gegenmassnahme:

- synthetische Notes nur als Zwischenstufe zulassen
- mindestens ein finaler Test muss echten D1-D3-Output verwenden

### 9.5 Coverage-Ziel ohne fachliche Tiefe waere wertlos

Risiko:

- formale Coverage kann steigen, ohne die kritischen Sequence- und
  `W116`-Pfade wirklich abzudecken

Gegenmassnahme:

- Coverage nur zusammen mit den Pflichtfaellen aus Abschnitt 7 werten
- Schwerpunkt explizit auf Reverse-, Degradations- und Compare-
  Schnittstellen legen

### 9.6 6.6 haengt hart an unfertigen D1-D3-/E1-Eingaengen

Risiko:

- trotz breiter bestehender Testbasis kann 6.6 inkonsistent werden,
  wenn D1 bis D3 oder E1 nach dem Audit noch fachlich nachgezogen
  werden und der vorhandene Teststand dann gegen einen veralteten
  Vertrag laeuft

Gegenmassnahme:

- T0 frueh als Testmatrix-Schnitt festziehen
- synthetische Notes und vorlaeufige Runner-/CLI-Tests nutzen, wo
  noetig
- in T5 explizit bestaetigen, dass bestehende oder neue Tests echten
  D1-D3-Output als Eingang nutzen und noch dem finalen Vertrag
  entsprechen
