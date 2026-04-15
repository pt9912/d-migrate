# Implementierungsplan: Phase E - CLI und Report-Writing

> **Milestone**: 0.7.5 - Daten-Profiling
> **Phase**: E (CLI und Report-Writing)
> **Status**: Review (2026-04-15)
> **Referenz**: `docs/implementation-plan-0.7.5.md` Abschnitt 2,
> Abschnitt 4.5, Abschnitt 5.3 bis 5.4, Abschnitt 6 Phase E, Abschnitt 7.1,
> Abschnitt 7.3, Abschnitt 8.4 bis 8.5, Abschnitt 9; `docs/ImpPlan-0.7.5-A.md`;
> `docs/ImpPlan-0.7.5-B.md`; `docs/ImpPlan-0.7.5-C.md`;
> `docs/ImpPlan-0.7.5-D.md`; `docs/roadmap.md` Milestone 0.7.5;
> `docs/profiling.md`; `docs/design.md` Abschnitt 3.6

---

## 1. Ziel

Phase E macht den in Phase D produktivierten Profiling-Use-Case ueber die
Root-CLI erreichbar und fuehrt die erste dedizierte Report-Ausgabe fuer
Profiling ein. Ergebnis der Phase ist ein lauffaehiger `d-migrate data profile`
Pfad mit klarer Flagoberflaeche, JSON als Default-Ausgabe, optionaler
Dateiausgabe und formatgleicher JSON-/YAML-Serialisierung.

Der Teilplan liefert bewusst noch keine finalen Milestone-Smokes ueber reale
Laufzeitumgebungen und keinen Abschlussabgleich der regulaeren Doku. Er schafft
die produktive Benutzerschnitt und die Writer-Schicht, auf der Phase F dann
Round-Trip-Smokes und Dokumentationsangleich aufsetzt.

Nach Phase E soll klar und testbar gelten:

- `data profile` ist ueber `d-migrate -> data -> profile` erreichbar
- `DataProfileCommand` bleibt eine duenne Clikt-Schale ueber `DataProfileRunner`
- JSON ist das Default-Format
- stdout ist der Default-Ausgabepfad
- Datei-Ausgabe laeuft ueber einen dedizierten `ProfileReportWriter`
- JSON und YAML transportieren fuer dieselben Daten denselben Informationsgehalt
- der 0.7.5-CLI-Vertrag bleibt bewusst klein:
  - `--source`
  - `--tables`
  - `--schema`
  - `--top-n`
  - `--format`
  - `--output`
- nicht zum 0.7.5-CLI-Vertrag gehoeren:
  - `--query`
  - `--analyze-normalization`
- der Default-Report bleibt diff-freundlich und enthaelt kein laufzeitvariables
  `generatedAt`

---

## 2. Ausgangslage

Aktueller Stand der Codebasis und der vorigen Phasen:

- Phase D liefert den produktiven Use-Case in `hexagon:application`:
  - `ProfileTableService`
  - `ProfileDatabaseService`
  - `DataProfileRunner`
  - Request-, Fehler- und Exit-Code-Vertrag
- `adapters:driving:cli` folgt heute einem klaren Muster:
  - Clikt-Command sammelt Flags
  - Request-DTO wird gebaut
  - delegiert wird an einen Runner
  - Exit-Codes werden ueber `ProgramResult` propagiert
  Das ist bereits sichtbar bei `DataExportCommand`.
- `DataCommand` ist heute die Root fuer:
  - `export`
  - `import`
  - `transfer`
  Phase E muss dort `profile` sauber einhaengen.
- `adapters:driven:formats` enthaelt bereits dedizierte Writer statt
  generischer Mischlogik:
  - `ReverseReportWriter`
  - `TransformationReportWriter`
- `docs/profiling.md` beschreibt ein groesseres spaeteres Zielbild mit:
  - `--query`
  - `--analyze-normalization`
  - Query-Profiling
  - spaeteren Analysefeldern
  Das ist fuer 0.7.5 bewusst zu breit.
- Der 0.7.5-Masterplan fixiert fuer Phase E:
  - `data profile` als neues Subcommand
  - JSON als Default
  - stdout als Default
  - Datei-Ausgabe ueber `ProfileReportWriter`
  - `--schema` nur fuer PostgreSQL
- Der Masterplan fixiert ausserdem den Determinismusvertrag:
  - stabile Tabellenreihenfolge
  - stabile Spaltenreihenfolge
  - stabile `topValues`
  - kein laufzeitvariables `generatedAt` im Default-Report

Konsequenz fuer Phase E:

- Das Hauptrisiko ist jetzt nicht mehr die Profiling-Fachlogik, sondern
  Vertragsdrift zwischen Command, Runner und Writer.
- Wenn Phase E zu viel aus `docs/profiling.md` mitzieht, explodiert der
  CLI-Scope sofort ueber den 0.7.5-Kern hinaus.
- Wenn JSON/YAML nicht denselben Informationsgehalt tragen, wird der Report-
  Vertrag spaeter schwer zu testen und zu dokumentieren.

---

## 3. Scope fuer Phase E

### 3.1 In Scope

- `DataCommand` um `DataProfileCommand()` erweitern
- `DataProfileCommand` als neue Clikt-Schale einfuehren
- Help-Text und Argument-Parsing fuer `data profile` implementieren
- verbindlichen 0.7.5-Flagvertrag abbilden:
  - `--source`
  - `--tables`
  - `--schema`
  - `--top-n`
  - `--format`
  - `--output`
- JSON als Default-Format umsetzen
- stdout als Default-Ausgabepfad umsetzen
- `ProfileReportWriter` in `adapters:driven:formats` einfuehren
- JSON- und YAML-Ausgabe fuer denselben Profil-Report bereitstellen
- Fehlerausgabe ueber bestehende CLI-/Formatter-Konventionen anbinden
- CLI-Help- und Round-Trip-Tests fuer den neuen Pfad aufbauen

### 3.2 Bewusst nicht Teil von Phase E

- neue Profiling-Fachlogik in Services oder Runnern
- Query-Profiling ueber `--query`
- `--analyze-normalization`
- FD-Discovery, `StructuralFinding`, `NormalizationProposal`
- spaetere semantische oder LLM-basierte Analyse
- Milestone-Abschluss-Smokes gegen PostgreSQL/MySQL
- regulaerer Doku-Abgleich in `docs/cli-spec.md`, `docs/design.md`,
  `docs/architecture.md`, `docs/guide.md`

Praezisierung:

Phase E exponiert den bereits vorhandenen Use-Case fuer Nutzer und serialisiert
den Report. Neue Analysefaehigkeiten oder Abschlussdokumentation bleiben Phase
F vorbehalten.

---

## 4. Leitentscheidungen fuer Phase E

### 4.1 `DataProfileCommand` bleibt eine duenne Clikt-Schale

Phase E soll dem bestehenden CLI-Stil folgen und nicht noch einmal
Use-Case-Logik im Driving-Adapter duplizieren.

Verbindliche Folge:

- der Command sammelt nur CLI-Argumente
- er uebernimmt den bestehenden Root-`CliContext` analog zu den uebrigen
  Commands
- er baut daraus einen `DataProfileRequest`
- er delegiert an `DataProfileRunner`
- Exit-Codes werden ueber `ProgramResult` propagiert
- Root-Kontextfelder wie `quiet` werden in den Request weitergereicht, auch
  wenn Phase E keinen eigenen Fortschrittskanal fuer Profiling einfuehrt

### 4.2 `data profile` haengt unter `data`, nicht als neues Root-Command

Der neue Use-Case gehoert in die bestehende Daten-Kommandogruppe.

Verbindliche Folge:

- `DataCommand` bekommt `DataProfileCommand()` als weiteres Subcommand
- die Hierarchie bleibt `d-migrate -> data -> profile`
- Help- und Bootstrap-Tests muessen diese Verdrahtung explizit abdecken

### 4.3 Der 0.7.5-CLI-Vertrag bleibt klein und abschliessend

Phase E zieht bewusst nur den vom Masterplan freigegebenen Flagsatz nach.

Verbindliche Folge:

- verpflichtend oder optional vorhanden sind nur:
  - `--source`
  - `--tables`
  - `--schema`
  - `--top-n`
  - `--format`
  - `--output`
- `--source` ist Pflicht
- `--format` ist optional mit Default `json`
- `--output` ist optional mit Default stdout
- `--tables` ist optional mit Default alle
- `--schema` ist optional und fachlich nur fuer PostgreSQL erlaubt
- `--top-n` ist optional mit Default `10`
- `--source` folgt dem bestehenden Datenpfadvertrag als `<url-or-name>` und
  muss deshalb sowohl direkte URLs als auch Named Connections aus
  `.d-migrate.yaml` unterstuetzen
- weder `--query` noch `--analyze-normalization` erscheinen in Help, Parsing
  oder Request-Vertrag von 0.7.5

### 4.4 JSON ist Default, YAML gleichwertige Alternative

Beide Ausgabeformate sollen denselben Profilinhalt transportieren, nicht zwei
leicht unterschiedliche Produkte sein.

Verbindliche Folge:

- JSON ist Default bei fehlendem `--format`
- YAML ist eine rein alternative Serialisierungsform
- JSON und YAML serialisieren dasselbe Report-Modell
- Formatunterschiede betreffen nur Syntax, nicht Informationsgehalt
- das kommandoeigene `--format` steuert ausschliesslich das Profil-Report-
  Payloadformat
- das Root-Flag `--output-format` ueberschreibt das Profil-Report-Format
  nicht
- Hinweis: `--output-format` wird in 0.7.5 fuer den Profiling-Fehlerpfad
  nicht separat ausgewertet; Fehlermeldungen erscheinen als Plain-Text
  auf stderr (konsistent mit den bestehenden Runnern). Eine formatierte
  Fehlerausgabe waere ein eigener spaeterer Scope.
- bei `data profile --format json|yaml` gewinnt fuer die Nutzlastausgabe immer
  das Kommando-`--format`, unabhaengig vom globalen `--output-format`

### 4.5 stdout ist Default, Datei-Ausgabe geht ueber `ProfileReportWriter`

Phase E fuehrt keinen zweiten ad-hoc Ausgabepfad ein.

Verbindliche Folge:

- ohne `--output` wird auf stdout geschrieben
- mit `--output` wird ueber `ProfileReportWriter` an den Dateipfad geschrieben
- der Writer kapselt Rendern und Dateischreiben fuer Profil-Reports
- der Command oder Runner baut keine eigene JSON-/YAML-Dateilogik zusammen

### 4.6 Der Default-Report bleibt byte-reproduzierbar

Der Masterplan hat den Determinismusvertrag bereits festgezogen. Phase E muss
ihn in der konkreten Ausgabe einhalten.

Verbindliche Folge:

- Tabellenreihenfolge bleibt stabil
- Spaltenreihenfolge bleibt stabil
- `topValues` erscheinen in ihrer deterministischen Service-/Adapter-Reihenfolge
- der Standard-Report enthaelt kein laufzeitvariables `generatedAt`
- JSON- und YAML-Writer duerfen keine impliziten Zeitstempel oder
  nichtdeterministische Metadaten hinzufuegen

### 4.7 Fehlerausgabe bleibt bei bestehenden CLI-Konventionen

Profiling soll sich aus Nutzersicht wie die uebrigen Kommandos anfuehlen.

Verbindliche Folge:

- fehlende oder ungueltige Flags liefern Exit `2`
- Runner-seitig gemappte Fehlercodes bleiben unveraendert sichtbar
- Fehlertexte gehen ueber die bestehenden stderr-/ProgramResult-Pfade
- der neue Command fuehrt keinen eigenen parallelen Fehlerkanal ein

### 4.8 `--schema` wird im CLI-Vertrag sichtbar, aber nicht still erweitert

Phase D hat die Semantik festgezogen; Phase E exponiert sie.

Verbindliche Folge:

- Help und Parsing nennen `--schema` explizit
- die Nutzerdoku des Commands macht klar: nur PostgreSQL
- ein explizites `--schema` auf MySQL oder SQLite bleibt ein Exit-2-Fall
- es gibt keine stille Ignorierung und keine halbherzige "best effort"-
  Auslegung

---

## 5. Arbeitspakete

### E.1 `DataCommand` um `DataProfileCommand()` erweitern

Mindestens noetig:

- `DataProfileCommand()` als neues Subcommand registrieren
- Root-Hierarchie und Help-Tests aktualisieren

Ziel:

- `data profile` ist ueber dieselbe Bootstrappipeline erreichbar wie die
  bestehenden Datenkommandos

### E.2 `DataProfileCommand` implementieren

Mindestens noetig:

- Clikt-Optionen definieren
- Help-Text formulieren
- Root-`CliContext` uebernehmen
- `DataProfileRequest` aus CLI-Werten bauen
- an `DataProfileRunner` delegieren
- `ProgramResult` bei Exit-Code ungleich `0` werfen

Ziel:

- eine testbare, duenne Command-Schale ohne Fachlogik

### E.3 0.7.5-Flagvertrag exakt abbilden

Verpflichtender Scope:

- `--source` Pflicht
- `--format` optional, Default `json`
- `--output` optional, Default stdout
- `--tables` optional, Komma-Liste
- `--schema` optional, nur PostgreSQL
- `--top-n` optional, Default `10`
- Root-`CliContext` wird uebernommen, insbesondere `quiet`
- Root-`--output-format` beeinflusst nicht das JSON-/YAML-Payloadformat des
  Profil-Reports
- `--source` funktioniert sowohl fuer direkte URLs als auch fuer Named
  Connections

Ziel:

- der CLI-Vertrag entspricht exakt dem 0.7.5-Masterplan und nicht dem
  spaeteren Vollbild aus `docs/profiling.md`

### E.4 `ProfileReportWriter` einfuehren

Mindestens noetig:

- dedizierter Profil-Writer in `adapters:driven:formats`
- Renderpfad fuer JSON
- Renderpfad fuer YAML
- Dateischreiben ueber den Writer

Ziel:

- Profil-Reports folgen demselben Schichtmuster wie bestehende Reverse- und
  Transformationsreports

### E.5 stdout-Default und Datei-Ausgabe sauber verdrahten

Mindestens noetig:

- bei fehlendem `--output` auf stdout rendern
- bei gesetztem `--output` ueber den Writer in die Datei schreiben
- beide Pfade muessen denselben Report-Inhalt transportieren

Ziel:

- Nutzer bekommen einen konsistenten Report-Vertrag unabhaengig vom Ausgabepfad

### E.6 Deterministischen Report-Vertrag absichern

Verpflichtender Scope:

- kein `generatedAt` im Default-Report
- stabile Tabellen- und Spaltenreihenfolge erhalten
- JSON und YAML ohne laufzeitvariable Zusatzfelder

Ziel:

- identische Eingabedaten fuehren zu diff-freundlichen, reproduzierbaren
  Reports

### E.7 CLI- und Writer-Tests aufbauen

Mindestens erforderlich:

- `CliHelpAndBootstrapTest` fuer `data profile --help`
- Round-Trip-Tests fuer JSON auf stdout
- Round-Trip-Tests fuer YAML in Datei
- Tests fuer Tabellenfilter
- Tests fuer `--schema` auf PostgreSQL
- Tests fuer Named-Connection-Aufloesung ueber `.d-migrate.yaml`
- Tests fuer Root-`CliContext`-Uebernahme, insbesondere `quiet`
- Tests fuer die Trennung von Root-`--output-format` und kommandoeigenem
  `--format`
- Tests fuer ungueltige Flags und Exit `2`
- Writer-Tests fuer JSON/YAML-Informationsgleichheit

Ziel:

- der neue CLI-Pfad ist belastbar, bevor Phase F ihn gegen reale Umgebungen
  haertet

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataProfileCommand.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/CliHelpAndBootstrapTest.kt`
- `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/...`
- `adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/report/ProfileReportWriter.kt`
- `adapters/driven/formats/src/test/kotlin/dev/dmigrate/format/report/ProfileReportWriterTest.kt`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/implementation-plan-0.7.5.md`
- `docs/ImpPlan-0.7.5-D.md`
- `docs/profiling.md`

---

## 7. Akzeptanzkriterien

- [ ] `data profile --help` ist ueber die Root-CLI erreichbar.
- [ ] Der neue Command bleibt eine duenne Clikt-Schale ueber
      `DataProfileRunner`.
- [ ] Fehlende oder ungueltige Flags liefern Exit `2`.
- [ ] JSON ist das Default-Format bei fehlendem `--format`.
- [ ] stdout ist der Default-Ausgabepfad bei fehlendem `--output`.
- [ ] JSON- und YAML-Ausgabe transportieren fuer dieselben Daten denselben
      Informationsgehalt.
- [ ] Das kommandoeigene `--format` steuert das Profil-Report-Payloadformat
      unabhaengig vom Root-`--output-format`.
- [ ] `DataProfileCommand` uebernimmt den bestehenden Root-`CliContext`
      konsistent, insbesondere `quiet`.
- [ ] `--source` funktioniert sowohl fuer direkte URLs als auch fuer Named
      Connections aus `.d-migrate.yaml`.
- [ ] Der Default-Report enthaelt kein laufzeitvariables `generatedAt`.
- [ ] `--query` und `--analyze-normalization` sind nicht Teil des 0.7.5-
      Commands.

---

## 8. Risiken

### R1 - Der Command zieht Runner- oder Service-Logik an sich

Wenn `DataProfileCommand` Validierungs-, Profilierungs- oder Writerlogik
selbst uebernimmt, bricht das bestehende CLI-Muster und verschlechtert die
Testbarkeit.

### R2 - Phase E zieht das groessere Zielbild aus `docs/profiling.md` vor

Sobald `--query`, `--analyze-normalization` oder spaetere Analyseausgaben in
die CLI einfliessen, ist der 0.7.5-Scope nicht mehr hart begrenzt.

### R3 - stdout- und Datei-Pfad driften auseinander

Wenn stdout direkt rendert, Datei-Ausgabe aber einen separaten Writerpfad
benutzt, drohen unterschiedliche Inhalte oder Feldmengen fuer denselben
Report.

### R4 - Der Writer fuehrt laufzeitvariable Metadaten ein

Sobald `ProfileReportWriter` implizite Zeitstempel oder aehnliche Provenienz-
felder hinzufuegt, ist der Determinismusvertrag des Default-Reports gebrochen.

### R5 - JSON und YAML werden semantisch ungleich

Wenn eines der beiden Formate Felder anders schneidet, anders benennt oder
Informationen verliert, werden Tests, Nutzervertrag und spaetere Doku unnoetig
fragil.

---

## 9. Abschluss-Checkliste

- [ ] `DataCommand` kennt `DataProfileCommand()` als neues Subcommand.
- [ ] `DataProfileCommand` sammelt nur CLI-Flags, baut den Request und
      delegiert an `DataProfileRunner`.
- [ ] Der 0.7.5-Flagsatz ist exakt umgesetzt.
- [ ] JSON ist Default, stdout ist Default-Ausgabe.
- [ ] Datei-Ausgabe laeuft ueber `ProfileReportWriter`.
- [ ] JSON und YAML sind inhaltlich gleichwertig.
- [ ] Root-`--output-format` und kommandoeigenes `--format` sind eindeutig
      getrennt.
- [ ] Root-`CliContext` wird konsistent uebernommen.
- [ ] Named Connections ueber `.d-migrate.yaml` sind abgesichert.
- [ ] Der Default-Report bleibt ohne `generatedAt` diff-freundlich.
- [ ] Phase E bleibt frei von `--query`, Normalisierungsanalyse und sonstigem
      spaeteren Scope.
