# Implementierungsplan: 0.9.4 - Arbeitspaket 6.6 `Tests und Verifikation`

> **Milestone**: 0.9.4 - Beta: MySQL-Sequence Reverse-Engineering und Compare
> **Arbeitspaket**: 6.6 (`Phase D/E: Tests und Verifikation`)
> **Status**: Draft (2026-04-21)
> **Referenz**: `docs/implementation-plan-0.9.4.md` Abschnitt 6.6,
> Abschnitt 7 und Abschnitt 9 (im Masterplan);
> `docs/ImpPlan-0.9.4-6.1.md`;
> `docs/ImpPlan-0.9.4-6.2.md`;
> `docs/ImpPlan-0.9.4-6.3.md`;
> `docs/ImpPlan-0.9.4-6.4.md`;
> `docs/ImpPlan-0.9.4-6.5.md`;
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
Supportobjekt-Unterdrueckung auf Unit-, Integrations-, Runner- und
CLI-Ebene stabil funktionieren.

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

- Driver-Unit-Tests pruefen Reader-Verhalten isoliert
- MySQL-Integrationstests pruefen echte JDBC-/Metadata-Pfade
- `SchemaCompareRunnerTest` prueft Dokument- und Exit-Vertraege
- `CliSchemaCompareTest` prueft Nutzer- und Output-Sichtbarkeit

Was fuer 6.6 noch fehlt, ist der verbindliche Gesamtschnitt:

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
- Testnachzug in:
  - `MysqlSchemaReaderTest`
  - `MysqlMetadataQueriesTest`, falls Query- oder
    Metadatenklassifikation dort gezielt abgesichert werden muss
  - MySQL-Testcontainer-Integrationstests fuer Reverse und Compare
  - `SchemaCompareRunnerTest`
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
- Zuordnung der 0.9.4-Pflichtfaelle auf Unit-, Integration-, Runner-
  und CLI-Testtypen

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
- CLI-Tests pruefen sichtbaren Plain-/JSON-/YAML-Vertrag

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
- mindestens ein finaler Test muss echten D1-D3-Output als
  Eingangsevidenz nutzen; ohne diesen Nachweis ist 6.6 nicht
  abgeschlossen

---

## 5. Zielarchitektur fuer 6.6

### 5.1 Testschichten und Verantwortung

6.6 organisiert die 0.9.4-Testabdeckung in vier Schichten:

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
4. CLI-Output
   - `CliSchemaCompareTest`
   - Verantwortung:
     - Plain-Sichtbarkeit
     - strukturierte JSON-/YAML-Ausgabe
     - keine kuenstliche Vermischung von `validation` und Operand-Notes

### 5.2 Testmatrix nach Fachfall

6.6 verteilt die wesentlichen 0.9.4-Faelle wie folgt:

- Reader-/Aggregationsfaelle primaer in Driver-Unit
- Metadata-/Quoting-/Markerrealitaet primaer in Driver-Integration
- Compare-Diff-/Exit-Semantik primaer in Runner-Tests
- Outputformat- und Sichtbarkeitsvertrag primaer in CLI-Tests

Verbindliche Regel:

- ein Fall darf auf mehreren Ebenen abgesichert werden, aber jede Ebene
  bekommt einen klaren Primaerauftrag
- kein Pflichtfall bleibt allein auf manueller Sichtpruefung

### 5.3 Artefaktstrategie

6.6 erzeugt keine neue Produktionsarchitektur, aber einen klaren
Testartefakt-Schnitt:

- Driver-Tests liefern die fachliche Reverse-Evidenz
- Runner-/CLI-Tests liefern die Compare- und Output-Evidenz
- der Round-Trip-Test ist das integrative Abschlussartefakt fuer
  0.9.4

Empfohlene Reihenfolge:

1. T0 Testmatrix und Lueckenbild festziehen
2. T1 Driver-Unit-Faelle aus D1 bis D3 nachziehen
3. T2 echte MySQL-Integration und Round-Trip aufbauen
4. T3 Runner-Vertraege absichern
5. T4 CLI-Sichtbarkeit und strukturierte Ausgabe absichern
6. T5 Abschluss-Gegenlauf und Coverage-Gate

---

## 6. Konkrete Arbeitsschritte

### T0 Testmatrix und Lueckenbild festziehen

- die 0.9.4-Pflichtfaelle aus dem Masterplan auf konkrete Testorte
  mappen
- markieren, welche Faelle:
  - rein unit-testbar sind
  - zwingend echte MySQL-Integration brauchen
  - Runner-/CLI-Vertrag brauchen
- offene Alt-Tests oder Luecken gegen D1 bis E1 sichtbar benennen

Done-Kriterien:

- jeder Pflichtfall hat einen primaeren Testort
- Round-Trip- und Exit-Code-Faelle sind explizit als Pflicht-Gates
  markiert

### T1 Driver-Unit-Tests fuer D1 bis D3 nachziehen

- `MysqlSchemaReaderTest` um Supportobjekt-,
  Unterdrueckungs- und Degradationsfaelle erweitern
- wo sinnvoll `MysqlMetadataQueriesTest` um gezielte
  Grundform-/Metadatenfaelle erweitern
- positive und degradierte Sequence-/Trigger-Faelle gegen dieselben
  Aggregationsvertraege absichern

Done-Kriterien:

- D1-, D2- und D3-Kernvertraege haben Unit-Test-Evidenz
- `W116` wird in Sequence- und Trigger-Degradationsfaellen explizit
  assertiert
- Supportobjekt-Unterdrueckung und Nicht-Unterdrueckung nicht-
  kanonischer Objekte sind abgedeckt

### T2 MySQL-Integration und Round-Trip aufbauen

- bestehende MySQL-Testcontainer-Tests erweitern oder gezielt neue
  Faelle anlegen
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

### T3 Runner-Vertraege fuer Compare absichern

- `SchemaCompareRunnerTest` fuer db-basierte Sequence-Faelle,
  operandseitige Notes und Exit-Code-Pfade erweitern
- absichern, dass `CompareValidation` keine Operand-Notes aufnimmt
- exit-stabile Faelle fuer `W116` ohne Diff explizit pruefen

Done-Kriterien:

- operandseitiges `W116` fuehrt ohne echten Diff nicht zu Exit 1
- operandseitiges `W116` fuehrt ohne Validation nicht zu Exit 3
- `sourceOperand`/`targetOperand` und `validation` bleiben sauber
  getrennt

### T4 CLI-Output und strukturierte Renderer pruefen

- `CliSchemaCompareTest` fuer Plain-, JSON- und YAML-Pfade erweitern
- `CompareRendererOperandTest` fuer operandseitige Notes und
  strukturierte Operanddarstellung gezielt nutzen oder erweitern
- absichern, dass operandseitige Notes im strukturierten Dokument
  sichtbar bleiben
- pruefen, dass JSON/YAML keine zusaetzliche Plain-`stderr`-Doppelung
  erzwingen

Done-Kriterien:

- Plain zeigt operandseitige Notes sichtbar
- JSON/YAML enthalten operandseitige Notes in
  `sourceOperand`/`targetOperand`
- `validation` bleibt in JSON/YAML frei von operandseitigen Notes

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
- der finale Abschluss von T3 und T4 haengt an echtem D1-D3-Output und
  damit spaetestens an T2

---

## 7. Verifikation

Pflichtfaelle fuer 6.6:

1. **Intakter Round-Trip**
   `neutral -> generate -> MySQL -> reverse -> compare` bleibt
   diff-frei und exit-stabil.
   Primaer: Integration.

2. **Sequence-Metadaten-Diff**
   geaenderte `increment`-, `minValue`-, `maxValue`-, `cycle`- oder
   `cache`-Werte fuehren genau zu Sequence-Diffs.
   Primaer: Runner, ergaenzend Integration.

3. **Support-Routinen fehlen**
   Sequences bleiben reverse-bar, aber der Operand traegt
   sequence-bezogenes `W116`.
   Primaer: Unit, ergaenzend Integration.

4. **Support-Trigger fehlt**
   Sequence bleibt sichtbar, `SequenceNextVal` wird nicht
   rekonstruiert, `W116` erscheint.
   Primaer: Unit, ergaenzend Integration.

5. **Include-Flag-Unabhaengigkeit**
   Reverse funktioniert fuer Sequence-Erkennung auch mit
   `includeTriggers = false` und `includeFunctions = false`.
   Primaer: Unit.

6. **Supportobjekt-Unterdrueckung**
   intakte `dmg_sequences`, Support-Routinen und kanonische
   Sequence-Trigger erscheinen nicht als normale Nutzerobjekte.
   Primaer: Unit, ergaenzend Integration.

7. **Nicht-kanonische Objekte bleiben Nutzerobjekte**
   aehnlich benannte oder markerlose Nutzerobjekte werden nicht
   versehentlich weggefiltert.
   Primaer: Unit.

8. **Compare mit degradiertem Operand**
   operandseitiges `W116` bleibt sichtbar, erzeugt aber keinen
   kuenstlichen Diff-Eintrag.
   Primaer: Runner.

9. **Compare JSON/YAML mit degradiertem Operand**
   operandseitiges `W116` bleibt auch in `sourceOperand`/
   `targetOperand` maschinenlesbar sichtbar.
   Primaer: CLI.

10. **Markerloser, aber semantisch intakter Trigger**
    es entsteht in 0.9.4 keine Spaltenzuordnung auf Verdacht, sondern
    degradierte Diagnose mit `W116`.
    Primaer: Unit, ergaenzend Integration.

11. **Grundform vs. Zusatzspalten**
    Zusatzspalten in `dmg_sequences` brechen den Reverse nicht; fehlende
    Pflichtspalten oder unvereinbare Grundform schon.
    Primaer: Unit, ergaenzend Integration.

12. **Mehrere Sequences gleichzeitig**
    mehrere Sequences bleiben parallel stabil; ein degradierter Zustand
    einer Sequence blockiert die anderen nicht.
    Primaer: Integration, ergaenzend Unit.

13. **Eine Sequence wird mehrfach genutzt**
    dieselbe Sequence wird mehreren Spalten korrekt zugeordnet.
    Primaer: Integration, ergaenzend Unit.

14. **Mehrdeutiger Sequence-Key**
    kollidierende Sequence-Keys werden nicht still ueberschrieben,
    sondern degradiert mit aggregiertem `W116`.
    Primaer: Unit.

15. **Exit-Code-Stabilitaet bei operandseitigem `W116`**
    file-vs-file, file-vs-db und db-vs-db bleiben bei rein
    operandseitigem `W116` auf Exit 0, solange kein realer Diff oder
    Validation-Fehler vorliegt.
    Primaer: Runner, ergaenzend CLI.

16. **Plain-/JSON-/YAML-Trennung**
    Plain zeigt Notes sichtbar; JSON/YAML tragen die Notes strukturiert
    und halten `validation` frei von Operand-Notes.
    Primaer: CLI.

Coverage-Ziel:

- pro betroffenem Modul gilt der Projektstandard von mindestens
  90% Line-Coverage
- Schwerpunkt auf den neuen Reverse-Pfaden in `driver-mysql`
- keine 0.9.4-Pflichtpruefung bleibt allein auf manueller Sichtkontrolle

Gesamt-Gate:

6.6 ist erst dann abgeschlossen, wenn T5 erledigt ist und alle
Pflichtfaelle aus diesem Abschnitt bestanden oder mit begruendeter
Blockade dokumentiert sind.

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

- solange D1 bis D3 oder E1 noch fachlich in Bewegung sind, kann 6.6
  nur teilweise finalisiert werden

Gegenmassnahme:

- T0 frueh als Testmatrix-Schnitt festziehen
- synthetische Notes und vorlaeufige Runner-/CLI-Tests nutzen, wo
  noetig
- den Abschluss von T5 an echten D1-D3-Output koppeln
