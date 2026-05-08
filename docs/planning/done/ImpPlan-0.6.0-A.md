# Implementierungsplan: Phase A - Spezifikationsbereinigung und Scope-Fixierung

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: A (Spezifikationsbereinigung und Scope-Fixierung)
> **Status**: Done (2026-04-13)
> **Referenz**: `docs/planning/implementation-plan-0.6.0.md` Abschnitt 1, Abschnitt 2,
> Abschnitt 3, Abschnitt 4, Abschnitt 5 Phase A, Abschnitt 6.1 bis 6.5,
> Abschnitt 7, Abschnitt 8, Abschnitt 9, Abschnitt 10;
> `spec/cli-spec.md`; `spec/neutral-model-spec.md`; `spec/architecture.md`;
> `spec/design.md`; `spec/connection-config-spec.md`

---

## 1. Ziel

Phase A zieht den 0.6.0-Vertrag aus dem Masterplan in eine belastbare
Dokumentationsbasis und bereinigt dabei aeltere Reverse-/Compare-Erzaehlungen,
die nicht mehr zum realen Scope oder zum aktuellen Code passen.

Der Teilplan liefert bewusst noch keine Code-Umsetzung. Ergebnis von Phase A
ist eine konsistente Spezifikation, auf der die spaeteren Phasen B bis H ohne
Parser-Altlasten, Port-Fiktionen oder vermischte Kommandovertraege aufbauen
koennen.

Konkret soll nach Phase A klar und widerspruchsfrei dokumentiert sein:

- dass `schema reverse` in 0.6.0 Live-DB-first ist
- dass `--source` bzw. `--target` bei den neuen DB-Pfaden DB-URLs oder
  Named-Connection-Aliase meinen, nicht SQL-Dateien oder stdin-DDL
- dass Reverse-Ausgabe und Reverse-Report zwei getrennte Artefakte sind
- dass `schema compare` weiterhin modellbasiert bleibt, aber `file/file`,
  `file/db` und `db/db` akzeptiert
- dass `data transfer` ein direkter DB-zu-DB-Datenpfad ist und kein
  Export-Import-Workaround ueber Zwischenartefakte
- welche aelteren Doku-Aussagen aus Entwurfsstaenden fuer 0.6.0 explizit nicht
  mehr gelten

---

## 2. Ausgangslage

Aktueller Stand in Dokumentation und Code:

- `spec/cli-spec.md` beschreibt `schema reverse` noch als
  `--source <url|path>`-Kommando mit optionalem `--source-dialect` und erlaubt
  damit implizit SQL-Dateien und Parser-Pfade, die nicht Teil des
  0.6.0-Mindestvertrags sind.
- dieselbe `cli-spec` beschreibt `schema compare` noch als file-only-MVP und
  enthaelt noch keine 0.6.0-Spezifikation fuer `data transfer`.
- die Kopfzeile von `spec/cli-spec.md` behauptet zudem noch, dass aktuell nur
  `schema validate` implementiert sei, obwohl im realen Code bereits
  `schema generate`, `schema compare`, `data export` und `data import`
  existieren.
- `spec/neutral-model-spec.md` enthaelt weiterhin einen parserzentrierten
  Reverse-Abschnitt mit SQL-Datei-Input, Dialekt-Erkennung und dem Beispiel
  `schema reverse --source schema.sql --source-dialect ...`.
- `spec/architecture.md` dokumentiert bereits ein `DatabaseDriver`-Interface mit
  `schemaReader()` und `schemaWriter()` sowie ein nacktes
  `SchemaReader.readSchema(connection): SchemaDefinition`, obwohl der reale
  Code diese Portform noch nicht besitzt.
- `spec/design.md` beschreibt fuer Reverse weiterhin zwei gleichwertige Pfade
  (JDBC plus DDL-Parser) und vermischt damit spaetere Parser-Ideen mit dem
  0.6.0-Live-DB-Scope.
- `spec/connection-config-spec.md` ist dagegen bereits eine belastbare Basis fuer
  URL-Syntax, Dialekt-Aliase, Named Connections und Pool-Konfiguration.

Realer Code-Iststand:

- `DatabaseDriver` exponiert heute `ddlGenerator()`, `dataReader()`,
  `tableLister()`, `dataWriter()` und `urlBuilder()`, aber noch keinen
  `schemaReader()`.
- `TableLister` existiert produktiv und wird bereits fuer Auto-Discovery im
  Export genutzt.
- `SchemaCommand` enthaelt aktuell `validate`, `generate` und `compare`, aber
  noch kein `reverse`.
- `SchemaCompareCommand` akzeptiert derzeit nur Dateipfade.
- `DataCommand` enthaelt derzeit nur `export` und `import`, aber noch kein
  `transfer`.

Konsequenz:

- Die 0.6.0-Dokumentation ist nicht nur unvollstaendig, sondern teils in zwei
  verschiedene Richtungen verzogen:
  - parser- und dateibasiertes Reverse aus aelteren Entwurfsstaenden
  - bereits vorausgeschriebene Portstrukturen, die im Code noch gar nicht
    existieren
- Ohne Phase A wuerden die Code-Phasen gegen einen Mix aus Altlast,
  Zielarchitektur und realem Ist-Code arbeiten.

---

## 3. Scope fuer Phase A

### 3.1 In Scope

- Bereinigung der 0.6.0-Dokumentation auf den realen Live-DB-Scope fuer
  `schema reverse`
- Klarstellung der Operandensemantik fuer `schema compare`
- Spezifikation des neuen CLI-Pfads `data transfer`
- Abgleich von `spec/architecture.md` mit dem realen Driver-Iststand und der
  additiven Port-Einfuehrung fuer 0.6.0
- Bereinigung parserzentrierter Reverse-Aussagen in `spec/neutral-model-spec.md`
  und `spec/design.md`
- klare Zuordnung von URL- und Alias-Semantik zur
  `spec/connection-config-spec.md`
- Dokumentation des Reverse-Output-/Report-Vertrags auf CLI-Ebene
- Fixierung der 0.6.0-Grenzen gegenueber SQL-Datei-/stdin-Parser-Pfaden

### 3.2 Bewusst nicht Teil von Phase A

- Implementierung von `SchemaReader`, `SchemaReadResult` oder
  `SchemaReadOptions` im Code
- Erweiterung von `DatabaseDriver` oder Ersatz von `TableLister`
- Reverse-, Compare- oder Transfer-Runner
- JSON-/YAML-Writer, Reverse-Report-Writer oder andere Formats-Arbeit
- konkrete Diff-Modell-Erweiterungen fuer Sequenzen, Custom Types, Views oder
  Routinen
- target-autoritatives Preflight und FK-Zykluslogik im `data transfer`
- generischer SQL-Parser, stdin-DDL oder `schema reverse --source <path>`
- neue Dialekte jenseits PostgreSQL, MySQL und SQLite

---

## 4. Leitentscheidungen fuer Phase A

### 4.1 Reale Codebasis geht vor veralteter Doku-Fiktion

Phase A dokumentiert 0.6.0 nicht gegen eine Wunscharchitektur, sondern gegen den
realen Ist-Stand.

Verbindliche Folge:

- die regulaeren Docs beschreiben, was heute existiert
- neue 0.6.0-Ports werden als additive Erweiterung dokumentiert
- bereits vorgezogene Interface-Skizzen muessen als Zielbild oder Platzhalter
  kenntlich gemacht werden, nicht als bestehende Produktrealitaet

### 4.2 `schema reverse` ist in 0.6.0 Live-DB-first

Fuer 0.6.0 gilt verbindlich:

- `schema reverse` arbeitet gegen echte Datenbankverbindungen
- `--source` ist eine DB-URL oder ein Named-Connection-Alias, der auf eine
  DB-URL aufgeloest wird
- SQL-Dateien, stdin-DDL und `--source-dialect` gehoeren nicht in den
  0.6.0-Mindestvertrag

Falls spaeter ein SQL-Parser kommt, wird das als eigener additiver
Funktionsschnitt dokumentiert und nicht rueckwirkend in 0.6.0 hineinerzaehlt.

### 4.3 Reverse-Ausgabe und Reverse-Report bleiben getrennt

Phase A fixiert fuer `schema reverse` denselben Grundsatz wie spaeter in der
Implementierung:

- `--output` enthaelt immer nur das wieder einlesbare Schema-Dokument
- Reverse-Notes und bewusst uebersprungene Objekte werden nicht in das
  Schema-Dokument eingebettet
- Hinweise erscheinen menschenlesbar auf `stderr`
- strukturierte Notes und `skipped_objects` liegen in einem separaten
  Report-Artefakt

Bevorzugtes Muster fuer 0.6.0:

- `--report <path>` optional
- Default-Sidecar analog zum bestehenden Muster, wenn `--output` gesetzt ist
- fuer das Schema-Artefakt ist `--format yaml|json` der kanonische
  Kommando-Flag; das globale `--output-format` bleibt Darstellungsflag und ist
  kein zweiter konkurrierender Weg, die Reverse-Datei zu serialisieren

### 4.4 `schema compare` erweitert den Eingabepfad, nicht das Grundprinzip

Auch mit DB-Operanden bleibt Compare modellbasiert.

Verbindliche Einordnung in Phase A:

- `file/file`, `file/db` und `db/db` sind gleichwertige Operandenkombinationen
- beide Operanden werden vor dem Diff zu `SchemaDefinition` bzw.
  gleichwertigem Reverse-Ergebnis aufgeloest
- die Operandenart wird in der CLI-Doku explizit disambiguiert, damit
  Named-Connection-Aliase nicht mit Dateipfaden kollidieren
- kanonische 0.6.0-Dokuform fuer Compare-Operanden:
  - `file:<path>`
  - `db:<url-or-alias>`
- benannte Connections werden im Compare-Pfad deshalb als `db:<alias>`
  dokumentiert, nicht als nackter String
- `schema compare` diffed keine SQL-Texte und fuehrt keinen impliziten
  Migrationspfad ein
- Exit `0` und Exit `1` bleiben fuer "identisch" bzw. "Unterschiede gefunden"
  reserviert

### 4.5 `data transfer` ist ein DB-zu-DB-Datenpfad

Phase A dokumentiert `data transfer` als neuen Datenpfad, nicht als
umbenannten Export-/Import-Umweg.

Verbindlich fuer 0.6.0:

- `data transfer` haengt unter `data`
- `--source` und `--target` nutzen dieselbe URL-/Alias-Semantik wie die
  bestehenden DB-Kommandos
- der Pfad streamt von DB zu DB ohne Zwischenformat
- vor dem ersten Write laeuft ein target-autoritatives Preflight fuer
  Tabellen-/Spaltenkompatibilitaet
- die Tabellenreihenfolge wird aus FK-Beziehungen oder einer gleichwertig
  dokumentierten Strategie abgeleitet
- FK-Zyklen scheitern bereits im Preflight, sofern kein expliziter sicherer
  Bypass dokumentiert ist
- Preflight-Fehler bleiben exit-code-seitig vom eigentlichen Streaming-Fehlerpfad
  getrennt
- Routinen, Views und Trigger werden nicht implizit mitkopiert

### 4.6 URL- und Alias-Semantik bleibt in `connection-config-spec.md` zentriert

`spec/cli-spec.md` soll fuer 0.6.0 keine zweite, leicht andere URL-Spezifikation
aufbauen.

Verbindliche Folge:

- URL-Syntax, Dialekt-Aliase und Named-Connection-Aufloesung bleiben in
  `spec/connection-config-spec.md` kanonisch
- `spec/cli-spec.md` referenziert diese Regeln und beschreibt nur die
  kommandospezifische Bedeutung von `--source` und `--target`

### 4.7 Die Reverse-Port-Doku bleibt ergebnisorientiert

Phase A darf `SchemaReader` nicht als nackten
`SchemaDefinition`-Lieferanten dokumentieren, wenn 0.6.0 gleichzeitig sichtbare
Reverse-Notes und `skipped_objects` verspricht.

Deshalb gilt fuer die Doku:

- `SchemaReader` liefert ein Ergebnisobjekt oder einen gleichwertigen Vertrag
  mit `schema`, `notes` und optional `skipped_objects`
- `TableLister` bleibt fuer bestehende Export-Pfade als Zwischenport sichtbar
- die Port-Doku darf `TableLister` nicht schon in Phase A still aus der
  Architektur streichen

### 4.8 Parser-Narrative duerfen nicht weiter als 0.6.0-Istzustand erscheinen

Wenn SQL-DDL-Parser, Dialekt-Erkennung aus Dateien oder stdin-Parsing in den
Docs weiter als normaler 0.6.0-Pfad stehen bleiben, verwischt der Scope sofort
wieder.

Darum gilt:

- parserbezogene Reverse-Texte werden entfernt oder klar als spaeteres Thema
  markiert
- Beispiele fuer 0.6.0 verwenden Live-DB-Quellen statt SQL-Dateien

---

## 5. Arbeitspakete

### A.1 `spec/cli-spec.md`

Bereinigen und erweitern von:

- Implementierungsstatus am Dokumentkopf auf den realen aktuellen Stand
- `schema reverse` auf den 0.6.0-Live-DB-Vertrag:
  - `--source <url-or-alias>`
  - kein `url|path`
  - kein stdin-DDL
  - kein `--source-dialect` im Mindestumfang
  - `--format yaml|json` als kanonischer Format-Flag fuer das
    Schema-Artefakt
  - kein zweiter konkurrierender Reverse-Dateiformat-Pfad ueber das globale
    `--output-format`
  - `--output` = reines Schema
  - `--report` = strukturierter Reverse-Report
  - Include-Flags fuer optionale Objekte
  - Exit-Code-Vertrag fuer Usage-, Connection-, Reverse- und I/O-Fehler
- `schema compare` fuer `file/file`, `file/db` und `db/db`
  - mit expliziter Operandnotation `file:` bzw. `db:`
  - mit `db:<alias>` fuer Named Connections im Compare-Pfad
- neues Kommando `data transfer`
  - inklusive target-autoritativem Preflight
  - automatischer Reihenfolge aus FK-Beziehungen
  - Zyklusfehlern im Preflight
  - getrenntem Preflight-/Streaming-Fehlerpfad
- Verweise auf `connection-config-spec.md` statt eigener konkurrierender
  URL-Grammatik

### A.2 `spec/neutral-model-spec.md`

Abgleichen von:

- parserzentrierten Reverse-Aussagen, die nicht in den 0.6.0-Mindestvertrag
  gehoeren
- Beispielen wie `schema reverse --source schema.sql --source-dialect ...`
- Erweiterungsabschnitten, die eine Driver-API mit `SchemaWriter` oder
  parserfirstigen Reverse-Pfaden implizieren
- Einordnung von Reverse als Live-DB-Read-Pfad auf Basis des neutralen Modells

Ziel:

- das Dokument beschreibt das Modell und seine Reverse-Relevanz, aber nicht
  versehentlich einen separaten 0.6.0-SQL-Parser als Ist-Scope

### A.3 `spec/architecture.md`

Ergaenzen und bereinigen von:

- aktuellem `DatabaseDriver`-Iststand
- additiver Einfuehrung von `schemaReader()` ueber die bestehende Driver-Fassade
- Einordnung von `TableLister` als bestehendem Zwischenport
- Reverse-Ergebnisvertrag mit `schema`, `notes` und optional
  `skipped_objects`
- Trennung zwischen heutigem Code und 0.6.0-Zielstruktur

### A.4 `spec/design.md`

Bereinigung von:

- Reverse als angeblich gleichwertigem JDBC- und DDL-Parser-Dualpfad
- Beispielen, die parserzentrierte oder scopefremde Reverse-Pfade fuer 0.6.0
  nahelegen
- Compare- und Transfer-Beispielen, die noch nicht sauber an den
  regulaeren 0.6.0-Vertrag angebunden sind

Ziel:

- `spec/design.md` stuetzt den 0.6.0-Masterplan, statt aeltere
  Produktvorstellungen parallel weiterzutragen

### A.5 `spec/connection-config-spec.md`

Nur dort anpassen, wo fuer 0.6.0 echte Klarstellungen noetig sind:

- explizite Wiederverwendung von Named Connections fuer `schema reverse`,
  `schema compare` und `data transfer`
- Vermeidung von Luecken zwischen CLI-Beispielen und der kanonischen
  URL-/Alias-Spezifikation

Wichtig:

- `connection-config-spec.md` ist Referenzbasis, nicht Nebenkriegsschauplatz
- Phase A soll hier keine zweite Reverse-Spezifikation aufbauen

---

## 6. Betroffene Artefakte

Direkt betroffen:

- `spec/cli-spec.md`
- `spec/neutral-model-spec.md`
- `spec/architecture.md`
- `spec/design.md`
- `spec/connection-config-spec.md`

Indirekt betroffen als Referenz- und Abnahmebasis:

- `docs/planning/implementation-plan-0.6.0.md`
- `docs/planning/roadmap.md`
- `spec/hexagonal-port.md`

Code-Referenzen fuer den Ist-Abgleich:

- `hexagon/ports/src/main/kotlin/dev/dmigrate/driver/DatabaseDriver.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt`

---

## 7. Akzeptanzkriterien

- [x] `spec/cli-spec.md` beschreibt `schema reverse` fuer 0.6.0 als
      Live-DB-Pfad mit DB-URL oder Named-Connection-Alias, nicht mehr als
      `url|path`- oder stdin-DDL-Kommando.
- [x] `spec/cli-spec.md` fuehrt fuer den 0.6.0-Mindestvertrag kein
      `--source-dialect` fuer `schema reverse` mehr als normalen Pfad.
- [x] Der Reverse-Vertrag in `spec/cli-spec.md` trennt explizit zwischen
      reinem Schema in `--output` und strukturiertem Reverse-Report.
- [x] `spec/cli-spec.md` fixiert fuer `schema reverse` genau einen kanonischen
      Format-Flag fuer das Schema-Artefakt; fuer 0.6.0 ist das `--format
      yaml|json`, nicht parallel noch ein zweiter konkurrierender Dateiformat-
      Pfad ueber das globale `--output-format`.
- [x] `spec/cli-spec.md` beschreibt `schema compare` mit den Operandenkombinationen
      `file/file`, `file/db` und `db/db`, ohne das modellbasierte Diff-Prinzip
      aufzugeben.
- [x] `spec/cli-spec.md` definiert fuer `schema compare` eine explizite
      Operand-Disambiguierung; fuer den kanonischen 0.6.0-Vertrag werden
      Compare-Operanden als `file:<path>` bzw. `db:<url-or-alias>` beschrieben.
- [x] `spec/cli-spec.md` enthaelt einen 0.6.0-konformen Abschnitt fuer
      `data transfer`.
- [x] Der `data transfer`-Abschnitt in `spec/cli-spec.md` fixiert bereits in
      Phase A target-autoritatives Preflight, FK-basierte Reihenfolge,
      Zyklusfehler im Preflight und einen vom Streaming getrennten
      Fehlerpfad.
- [x] Die Kopf- und Statusangaben in `spec/cli-spec.md` widersprechen dem
      aktuellen implementierten Kommando-Iststand nicht mehr grob.
- [x] `spec/neutral-model-spec.md` beschreibt fuer 0.6.0 keinen parserbasierten
      SQL-Datei-/stdin-Reverse-Pfad mehr als aktiven Milestone-Scope.
- [x] `spec/architecture.md` behauptet nicht mehr, dass `DatabaseDriver` den
      0.6.0-Read-Port bereits produktiv exponiert; die Port-Einfuehrung ist als
      additive Arbeit dokumentiert.
- [x] `spec/architecture.md` dokumentiert den Reverse-Vertrag nicht mehr als
      nacktes `SchemaDefinition`-Ergebnis ohne Notes-/Skip-Pfad.
- [x] `spec/design.md` stuetzt den Live-DB-first-Scope und fuehrt keinen
      gleichwertigen DDL-Parser-Pfad mehr als 0.6.0-Istzustand.
- [x] `spec/connection-config-spec.md` bleibt die kanonische Quelle fuer
      URL-Syntax, Dialekt-Aliase und Named-Connection-Aufloesung.
- [x] `spec/cli-spec.md`, `spec/neutral-model-spec.md`, `spec/architecture.md`
      und `spec/design.md` beschreiben denselben 0.6.0-Scope ohne
      Widersprueche bei Reverse, Compare und Transfer.

---

## 8. Verifikation

Phase A wird dokumentationsseitig verifiziert, nicht ueber Code- oder
Integrationstests.

Mindestumfang:

1. Querlesen von `spec/cli-spec.md`, `spec/neutral-model-spec.md`,
   `spec/architecture.md`, `spec/design.md` und
   `spec/connection-config-spec.md` auf konsistente 0.6.0-Aussagen.
2. Abgleich gegen den realen Code-Iststand von:
   - `DatabaseDriver`
   - `SchemaCommands`
   - `DataCommands`
3. Explizite Gegenpruefung auf Widersprueche bei:
   - `schema reverse` als `url|path` vs. Live-DB-only
   - SQL-Datei- und stdin-Reverse
   - `--source-dialect`
   - `--format` vs. globalem `--output-format` fuer Reverse-Dateiformate
   - reinem Schema-Output vs. separatem Reverse-Report
   - file-only-Compare vs. `file/db` und `db/db`
   - Operand-Disambiguierung ueber `file:` und `db:`
   - direktem `data transfer` vs. Export-/Import-Zwischenformat
   - target-autoritativem Transfer-Preflight, FK-Reihenfolge und Zykluspfad
   - bereits existierendem vs. erst einzufuehrendem `schemaReader()`
4. Abschluss-Review, dass parserbezogene Reverse-Texte nur noch als spaeterer
   additiver Problemraum erscheinen, nicht mehr als 0.6.0-Istvertrag.

Praktische Review-Hilfe:

- gezielte Volltextsuche nach `url|path`, `source-dialect`, `stdin`,
  `schema.sql`, `DDL-Parser`, `schemaReader()`, `schemaWriter()`,
  `only schema validate`, `data transfer`, `file:`, `db:`, `--format`,
  `--output-format`
- Vergleich der CLI-Aussagen mit `spec/connection-config-spec.md`
- Gegenlesen der Architektur-Aussagen mit
  `hexagon/ports/.../DatabaseDriver.kt`

---

## 9. Risiken und offene Punkte

### R1 - Parser-Altlasten bleiben in Randdokumenten stehen

Wenn Reverse weiter in einem Dokument als Live-DB-Pfad und im naechsten als
SQL-Datei-Parser erscheint, ist der Scope schon vor Phase B wieder unscharf.

### R2 - Architektur kann dem Code erneut vorauslaufen

Gerade bei `schemaReader()`, Ergebnisvertrag und Driver-Fassade ist die
Versuchung gross, Zielarchitektur als waere sie schon implementiert zu
dokumentieren. Phase A muss diese Trennung bewusst sauber halten.

### R3 - Compare- und Transfer-Semantik kann zu weich bleiben

Ohne klare Operandentypen und saubere DB-zu-DB-Einordnung drohen spaetere
Phasen mit mehreren halbkompatiblen Interpretationen von Compare und Transfer.

### R4 - URL-Grammatik wird an mehreren Stellen doppelt gepflegt

Wenn `cli-spec.md` und `connection-config-spec.md` dieselbe URL-Syntax jeweils
leicht anders beschreiben, entstehen vermeidbare Widersprueche bei Named
Connections, Dialekt-Aliasen und Beispielkommandos.

---

## 10. Abschlussdefinition

Phase A ist abgeschlossen, wenn die regulaeren Spezifikationsdokumente den
0.6.0-Scope fuer Reverse, Compare und Transfer konsistent und ohne Rueckgriff
auf parserzentrierte Altentwuerfe beschreiben.

Danach ist fuer die Code-Phasen klar:

- welche Eingabeformen 0.6.0 wirklich unterstuetzt
- wie Reverse-Schema und Reverse-Report getrennt transportiert werden
- wie `schema compare` seine Operanden aufloest
- was `data transfer` fachlich ist und was nicht
- und an welchen Stellen die Doku bewusst zwischen realem Ist-Code und
  additiver 0.6.0-Zielstruktur unterscheidet
