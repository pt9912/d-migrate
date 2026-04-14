# Implementierungsplan: Phase G - `data transfer` ohne Zwischenformat

> **Milestone**: 0.6.0 - Reverse-Engineering und Direkttransfer
> **Phase**: G (`data transfer` ohne Zwischenformat)
> **Status**: Done (2026-04-14)
> **Referenz**: `docs/implementation-plan-0.6.0.md` Abschnitt 4.5,
> Abschnitt 5 Phase G, Abschnitt 6.5, Abschnitt 7, Abschnitt 8,
> Abschnitt 9, Abschnitt 10; `docs/ImpPlan-0.6.0-A.md`;
> `docs/ImpPlan-0.6.0-B.md`; `docs/ImpPlan-0.6.0-E.md`;
> `docs/cli-spec.md`; `docs/connection-config-spec.md`

---

## 1. Ziel

Phase G fuehrt `data transfer` als produktiven, skriptfaehigen
DB-zu-DB-Datenpfad ein:

```bash
d-migrate data transfer --source <url-or-alias> --target <url-or-alias>
```

Der Pfad streamt Daten direkt von einer Quelldatenbank in eine
Zieldatenbank, ohne Zwischenformat, ohne temporaeren Dateiexport und ohne
neuen Format-Layer.

Nach Phase G soll auf Core-, Application- und CLI-Ebene klar und testbar
gelten:

- `data transfer` ist ein eigener Datenpfad unter `data`, kein versteckter
  Export-/Import-Umweg
- `--source` und `--target` sind beide explizit erforderlich
- URL-/Alias-Aufloesung verwendet dieselben Connection-Bausteine wie
  `data export` und `data import`, aber ohne Config-Default-Fallbacks
- vor dem ersten Write laeuft ein target-autoritatives Preflight fuer
  Tabellenwahl, Tabellen-/Spaltenkompatibilitaet, Reihenfolge und FK-Zyklen
- `DataReader` der Quelle und `DataWriter` des Ziels werden direkt
  verbunden
- bestehende Datenpfad-Flags werden konsistent wiederverwendet, mindestens:
  - `--tables`
  - `--filter`
  - `--since-column`
  - `--since`
  - `--truncate`
  - `--on-conflict`
  - `--trigger-mode`
  - `--chunk-size`
- Preflight-Fehler bleiben exit-code-seitig vom eigentlichen
  Streaming-/Write-Fehlerpfad getrennt
- Fortschritt, `quiet` und `no-progress` folgen dem Stil der heutigen
  Datenkommandos
- Routinen, Views und Trigger werden nicht implizit mitkopiert

---

## 2. Ausgangslage

Aktueller Stand in `hexagon:application`, `adapters:driving:cli`,
`adapters:driven:streaming` und `hexagon:ports`:

- `DataCommand` kennt heute nur `export` und `import`; ein
  `transfer`-Subcommand existiert noch nicht
- der Help-Text von `DataCommand` beschreibt den Top-Level-Pfad noch als
  "Data export and import commands"
- `DataExportCommand` / `DataExportRunner` und
  `DataImportCommand` / `DataImportRunner` bilden bereits den
  etablierten Split:
  - Clikt-Schale sammelt Flags
  - Runner enthaelt die testbare Orchestrierungslogik
  - Exit-Codes sind explizit dokumentiert
  - Fortschritt laeuft ueber `ProgressReporter`
- `data export` besitzt bereits source-seitige Vorpruefungen fuer:
  - `--since-column` / `--since`
  - Identifier fuer `--tables`
  - den M-R5-Pfad `--filter` plus literales `?` zusammen mit `--since`
- `data import` besitzt bereits target-seitige Vorpruefungen fuer:
  - `--truncate` plus explizites `--on-conflict abort`
  - `--trigger-mode disable` auf nicht unterstuetzten Dialekten
  - schema-basiertes Preflight und FK-basierte Reihenfolge bei
    Directory-Import
- `DatabaseDriver` exponiert heute bereits:
  - `dataReader()`
  - `dataWriter()`
  - `tableLister()`
  - `schemaReader()`
- `SchemaReader` liefert mit `SchemaReadResult` bereits den 0.6.0-tauglichen
  Envelope aus:
  - `schema`
  - `notes`
  - `skippedObjects`
- `ResolvedSchemaOperand` existiert im Application-Layer bereits und ist
  ausdruecklich fuer Compare- oder Transfer-Workflows mit DB-Quellen
  kommentiert
- `StreamingExporter` und `StreamingImporter` existieren bereits, aber ein
  direkter DB-zu-DB-Orchestrator fehlt noch
- `ProgressOperation` kennt heute nur `EXPORT` und `IMPORT`
- `docs/cli-spec.md` beschreibt `data transfer` fuer 0.6.0 bereits als
  Soll-Zustand, waehrend der Code noch keinen entsprechenden Pfad besitzt

Konsequenz fuer Phase G:

- Der fehlende Baustein ist primaer kein neuer Reader-, Writer- oder
  Driver-Port.
- Die Kernarbeit liegt im Application-Layer und in der CLI-Verdrahtung:
  - gleichzeitige Koordination von Source und Target
  - target-autoritatives Preflight
  - sichere Tabellenreihenfolge
  - direkter Reader->Writer-Streamingpfad
- Die groesste Fehlergefahr liegt in:
  - blindem Wiederverwenden der unterschiedlichen Source-/Target-Defaults aus
    Export und Import
  - einer Preflight-Logik, die nur Source-Sicht oder nur Lister-Reihenfolge
    verwendet
  - stiller Rueckkehr zum Datei-Zwischenformat
  - ungescrubbten user-facing Source-/Target-Referenzen in Fehlern

---

## 3. Scope fuer Phase G

### 3.1 In Scope

- neues `data transfer`-Subcommand unter `data`
- `DataTransferRequest` und `DataTransferRunner` als eigener
  Application-Pfad
- explizite Aufloesung von `--source` und `--target` ueber:
  - Named Connections
  - `ConnectionUrlParser`
  - `HikariConnectionPoolFactory`
  - `DatabaseDriverRegistry`
- kein Config-Default fuer Source oder Target:
  - kein Export-Fallback auf `database.default_source`
  - kein Import-Fallback auf `database.default_target`
- Wiederverwendung der bestehenden Datenpfad-Flag-Semantik fuer:
  - source-seitige Filterung und inkrementellen Read
  - target-seitige Write-Optionen
  - `chunk-size`
- Lesen von Source- und Target-Schema vor dem Streaming, soweit fuer
  Tabellenwahl, Kompatibilitaet, Reihenfolge und sichere Vorpruefungen
  erforderlich
- Wiederverwendung des bestehenden `ResolvedSchemaOperand`-Gedankens fuer
  source-/target-seitige Schema-Envelopes
- target-autoritatives Preflight fuer:
  - Tabellenexistenz
  - Tabellen-/Spaltenkompatibilitaet
  - FK-basierte Schreibreihenfolge
  - FK-Zykluspfad
  - transfer-spezifische Flag- und Dialektvalidierungen
- direkter DB-zu-DB-Streamingpfad auf Basis von:
  - `DataReader`
  - `DataWriter`
  - `ConnectionPool`
  - `PipelineConfig`
  - `ImportOptions`
  - `DataFilter`
- Fortschritt und Summary analog zu Export/Import
- konsistentes Scrubbing fuer user-facing Source-/Target-Referenzen und
  Fehlertexte
- Tests fuer Runner, CLI, Preflight und Streaming-Verhalten
- Nachfuehrung von `docs/cli-spec.md` auf den finalen Phase-G-Vertrag

### 3.2 Bewusst nicht Teil von Phase G

- Export in temporaere Dateien mit nachgelagertem Import
- neue Datenformate, neue Format-Flags oder neuer Datei-I/O
- `--output`, `--format` oder `--schema` fuer `data transfer`
- automatische Schema-Migration des Ziels bei Inkompatibilitaeten
- implizites Kopieren von:
  - Views
  - Routinen
  - Triggerdefinitionen
- neue globale Root-Flags
- Checkpointing, Retry-Orchestrierung oder Paralleltransfer
- freier Dialekt-Bypass fuer FK-Zyklen ohne expliziten, dokumentierten und
  sicheren Vertrag

---

## 4. Leitentscheidungen fuer Phase G

### 4.1 `data transfer` bleibt ein direkter DB-zu-DB-Datenpfad

Phase G fuehrt keinen verdeckten Export-/Import-Zwischenweg ein.

Verbindliche Folge:

- Daten fliessen direkt von `DataReader.streamTable(...)` zu
  `DataWriter.openTable(...).write(...)`
- es gibt keinen `DataChunkReaderFactory`-/`DataChunkWriterFactory`-Pfad
- es gibt keine temporaeren Dateien, keine Formatserialisierung und kein
  implizites `json`/`yaml`/`csv` innerhalb des Transfers

Nicht akzeptabel ist:

- dass `data transfer` intern exportiert, danach wieder importiert und den
  Dateiumweg nur vor dem User versteckt
- dass `data transfer` ploetzlich Format-Flags braucht, obwohl der Pfad
  dateilos sein soll

### 4.2 Source- und Target-Aufloesung verwenden dieselben Bausteine, aber keine Defaults

Phase A und die CLI-Spezifikation verlangen dieselbe URL-/Alias-Semantik wie
die bestehenden DB-Kommandos, aber keinen impliziten Default-Pfad.

Verbindliche Folge:

- `--source` und `--target` sind beide Pflicht
- beide Seiten akzeptieren:
  - vollstaendige Connection-URLs
  - Named-Connection-Aliase aus `.d-migrate.yaml`
- die Aufloesung verwendet dieselben Primaerbausteine wie Export/Import:
  - `NamedConnectionResolver`
  - `ConnectionUrlParser`
  - `HikariConnectionPoolFactory`
- `data transfer` fuehrt keine eigene URL- oder Alias-Syntax ein

Wichtig:

- die Bausteine sind dieselben wie in Export/Import
- die Semantik ist trotzdem nicht identisch:
  - kein Export-Fallback auf `default_source`
  - kein Import-Fallback auf `default_target`

### 4.3 Transfer-Preflight ist schema-basiert und target-autoritativ

Der Transferpfad muss vor dem ersten Write wissen, welche Tabellen ueberhaupt
uebertragen werden duerfen und in welcher Reihenfolge.

Verbindliche Folge:

- Source- und Target-Schema werden vor dem Streaming gelesen, soweit fuer:
  - Tabellenwahl
  - Kompatibilitaet
  - Reihenfolge
  - Zykluspruefung
  noetig
- die Kandidatenmenge kommt aus der Source-Seite
- die Autoritaet fuer Schreibkompatibilitaet und Reihenfolge liegt auf der
  Target-Seite
- `TableLister.listTables(...)` bleibt fuer Export relevant, ist fuer
  Transfer aber nicht die primaere Wahrheit fuer Auswahl oder Reihenfolge
- dieses Preflight bleibt bis zum eigentlichen Streaming seiteneffektfrei:
  - kein `DataWriter.openTable(...)`
  - kein `TRUNCATE` / `DELETE`
  - keine FK-Check-Umschaltung
  - keine Trigger-De-/Reaktivierung

Bevorzugtes Muster fuer Phase G:

- minimaler Schema-Read fuer Transfer, z. B.:
  - `includeViews = false`
  - `includeProcedures = false`
  - `includeFunctions = false`
  - `includeTriggers = false`, sofern der konkrete Trigger-Preflight keinen
    Trigger-Read benoetigt

Ziel ist:

- keine unnoetige Reverse-Arbeit fuer Objekttypen, die der Datenpfad nicht
  kopiert
- trotzdem ein sauberes neutrales Modell fuer Tabellen, Spalten und
  FK-Abhaengigkeiten

Wenn fuer writer-nahe Zielpruefungen heute nur mutierende Logik in
`openTable(...)` existiert, muss Phase G die noetigen Checks in einen
read-only Helper oder gleichwertigen Vorpruefpfad herausziehen, statt
`openTable(...)` im Preflight aufzurufen.

### 4.4 Tabellenwahl und Tabellenreihenfolge werden bewusst getrennt

Die Auswahl, *was* uebertragen wird, und die Reihenfolge, *wann* geschrieben
wird, sind im Transfer zwei verschiedene Schritte.

Verbindliche Folge:

- wenn `--tables` gesetzt ist:
  - diese Liste wird identifier-seitig validiert
  - die Tabellen muessen source-seitig existieren
  - target-seitige Kompatibilitaet wird im Preflight geprueft
- wenn `--tables` nicht gesetzt ist:
  - die Kandidatenliste wird aus dem gelesenen Source-Schema abgeleitet
  - nicht aus einer zufaelligen Lister-Reihenfolge
- die Schreibreihenfolge wird aus dem Zielschema oder einer gleichwertig
  sicheren target-autoritiven Projektion bestimmt

Nicht akzeptabel ist:

- die Tabellen genau in der Reihenfolge zu schreiben, in der der Reader oder
  Lister sie zufaellig liefert
- Source-FK-Reihenfolge als schreibautoritativ zu behandeln, wenn das Target
  ein anderes oder strengeres FK-Bild hat

### 4.5 FK-Zyklen muessen vor dem ersten Write entschieden sein

FK-Zyklen duerfen nicht erst mitten im Streaming auffallen.

Verbindliche Folge:

- FK-Zyklen auf dem gewaehlten Transfer-Teilgraphen werden im Preflight
  erkannt
- Default-Verhalten ist harter Preflight-Fehler mit Exit `3`
- ein zulaessiger Bypass ist nur dann akzeptabel, wenn er:
  - explizit aktiviert ist
  - dialektsicher ist
  - dokumentiert ist

Phase G selbst fuehrt keinen stillen "best effort trotz Zyklus"-Pfad ein.

### 4.6 Transfer komponiert bestehende Datenpfad-Semantiken, statt neue zu erfinden

`data transfer` soll fuer Source und Target moeglichst dieselben
Nutzererwartungen bedienen wie die existierenden Datenkommandos.

Verbindliche Folge:

- source-seitige Filterlogik folgt dem Export-Vertrag:
  - `--filter`
  - `--since-column`
  - `--since`
  - Identifier- und M-R5-Regeln fuer den kombinierten Filterpfad
- target-seitige Write-Optionen folgen dem Import-Vertrag:
  - `--truncate`
  - `--on-conflict`
  - `--trigger-mode`
- `--chunk-size` bleibt in `PipelineConfig`, nicht in einem neuen
  Transfer-spezifischen Options-Sack
- transfer-spezifische Vorpruefungen kombinieren diese beiden Seiten sauber

Mindestens explizit abzusichern:

- `--since-column` und `--since` nur gemeinsam
- Identifier-Validierung fuer `--tables`
- kein literales `?` in `--filter`, wenn `--since` aktiv ist
- Widersprueche wie `--truncate` plus explizites `--on-conflict abort`
- `--on-conflict update` ist nur zulaessig, wenn die Zieltabelle einen
  Primary Key hat; fehlender PK ist ein target-seitiger Preflight-Fehler
  mit Exit `3`
- nicht unterstuetzte `--trigger-mode disable`-Dialektkombinationen
- nicht unterstuetzte `--trigger-mode strict`-Dialektkombinationen bleiben wie
  im heutigen Importpfad Validierungsfehler mit Exit `2`; nur der fachliche
  Strict-Preflight auf unterstuetzten Dialekten endet mit Exit `3`

Wichtig:

- Phase G erfindet keine von Export/Import abweichenden Flag-Bedeutungen
- wo heute bereits CLI- oder Dialektpruefungen existieren, sollen diese
  wiederverwendet oder in einen gemeinsamen Helper gezogen werden

### 4.7 User-facing Referenzen und Fehlertexte muessen fuer beide Seiten gescrubbt bleiben

Transfer arbeitet gleichzeitig mit zwei DB-Referenzen und verdoppelt damit
das Leck-Risiko in Fehlern, Preflight-Meldungen und Warnungen.

Verbindliche Folge:

- es gibt je eine user-facing Referenz fuer Source und Target
- Alias-Operanden duerfen alias-basiert erscheinen
- URL-Operanden muessen ueber denselben Scrubbing-Pfad maskiert werden wie
  in den anderen DB-Pfaden
- user-facing Fehlertexte duerfen keine unmaskierten Credentials oder andere
  sensitive Verbindungsdetails enthalten
- rohe Exception-Messages aus Resolver, Parser, Pool, Registry,
  `SchemaReader`, Reader oder Writer duerfen nicht ungefiltert nach `stderr`
  gehen, wenn sie Connection-Details enthalten

### 4.8 Erfolg, Progress, `--output-format` und Exit-Codes bleiben im Stil der heutigen Datenkommandos

Transfer soll sich fuer Skripte und fuer interaktive CLI-Nutzung wie ein
nahtloser dritter Datenpfad anfuehlen.

Verbindliche Folge:

- erfolgreiche Laeufe erzeugen kein neues dateibasiertes Artefakt
- Fortschritt laeuft ueber `ProgressReporter`
- `quiet` und `no-progress` unterdruecken Fortschrittssignale wie bei
  Export/Import
- eine menschenlesbare Abschluss-Summary wird wie heute auf `stderr`
  geschrieben, sofern nicht durch `quiet` oder `no-progress` unterdrueckt
- `data transfer` fuehrt fuer erfolgreiche Laeufe keinen neuen
  strukturierten stdout-Erfolgs-Envelope ein
- `data transfer` fuehrt in Phase G auch keinen neuen `json`/`yaml`-
  Fehlerpfad ueber das globale `--output-format` ein
- das globale `--output-format` bleibt fuer `data transfer` bewusst
  unverdrahtet und folgt damit der heutigen Export-/Import-Semantik
- Transfer-Fehler bleiben menschenlesbare `stderr`-Ausgaben statt neuer
  strukturierter Fehlerdokumente

Exit-Code-Vertrag fuer Phase G:

- Exit `0`:
  - Transfer erfolgreich
- Exit `2`:
  - ungueltige CLI-Argumente
  - ungueltige Identifier
  - unzulaessige Flag-Kombinationen
  - dialektseitig ungueltige Transfer-Optionen
- Exit `3`:
  - Preflight fehlgeschlagen
  - Source- oder Target-Schema ist als neutrales Modell invalid
  - Zielinkompatibilitaet
  - Tabellen-/Spaltenmismatch
  - strikter Triggerpfad auf unterstuetzten Dialekten
  - fehlender Primary Key bei `--on-conflict update`
  - FK-Zyklen
- Exit `4`:
  - Connection-Fehler
  - DB-Metadatenfehler beim Schema-Read
  - Reader-/Writer-Vorbereitungsfehler nach erfolgreicher URL-Aufloesung
- Exit `5`:
  - Streaming- oder Write-Fehler waehrend des Transfers
  - Post-Chunk- oder Post-Table-Finalisierung
- Exit `7`:
  - Config-Fehler
  - ungueltige URL-/Alias-Aufloesung
  - Registry-/Dialekt-Lookup-Fehler

---

## 5. Arbeitspakete

### G.1 `DataTransferCommand` und `DataTransferRequest` einfuehren

Im Driving-Adapter ist ein neues `data transfer`-Subcommand aufzubauen.

Mindestens noetig:

- neues `DataTransferCommand` unter `adapters:driving:cli`
- Erweiterung von `DataCommand` um:
  - Subcommand `transfer`
  - aktualisierten Help-Text
- neues `DataTransferRequest` im Application-Layer mit mindestens:
  - `source`
  - `target`
  - `tables`
  - `filter`
  - `sinceColumn`
  - `since`
  - `onConflict`
  - `triggerMode`
  - `truncate`
  - `chunkSize`
  - `cliConfigPath`
  - `quiet`
  - `noProgress`

Wichtig:

- `data transfer` bekommt keine Datei-Flags aus Export/Import vererbt
- `--source` und `--target` sind Clikt-seitig beide `required()`

### G.2 Source-/Target-Resolver und gemeinsame CLI-Vorpruefungen aufbauen

Der Runner braucht einen klaren, symmetrischen Endpoint-Resolver.

Mindestens noetig:

- source- und target-seitige Aufloesung ueber dieselben Primaerbausteine
- kein stiller Rueckgriff auf Config-Defaults
- Identifier-Validierung fuer `--tables`
- Export-seitige Vorpruefung fuer:
  - `--since-column`
  - `--since`
  - M-R5-Kombination mit `--filter`
- Import-seitige Vorpruefung fuer:
  - `--truncate` plus `--on-conflict`
  - `--trigger-mode`
- Aufbau von:
  - `DataFilter` fuer den Source-Read
  - `ImportOptions` fuer den Target-Write
- Bildung gescrubbter user-facing `sourceRef`- und `targetRef`-Strings
- keine Verdrahtung von `CliContext.outputFormat` in einen neuen Transfer-
  Fehler- oder Erfolgs-Envelope

Bevorzugtes Ziel:

- Export-/Import-Helfer werden nicht copy-paste dupliziert, sondern fuer den
  Transferpfad wiederverwendet oder in gemeinsame Utilitys gezogen

### G.3 Schema-Envelopes fuer Source und Target aufloesen

Transfer braucht vor dem Streaming ein neutrales Bild beider Seiten.

Mindestens noetig:

- Lesen des Source-Schemas ueber `DatabaseDriver.schemaReader()`
- Lesen des Target-Schemas ueber `DatabaseDriver.schemaReader()`
- Bildung eines transfertauglichen Endpoint-Envelopes aus:
  - user-facing Referenz
  - `ConnectionConfig`
  - `ResolvedSchemaOperand` oder gleichwertigem Schema-Envelope
- Transport von:
  - `schema`
  - `validation`
  - `notes`
  - `skippedObjects`

Wichtig:

- `ResolvedSchemaOperand` existiert bereits und soll fuer Transfer bewusst
  wiederverwendet statt neu erfunden werden
- Notes und `skippedObjects` duerfen im Transferpfad nicht still verschwinden
- invalides Source- oder Target-Schema ist kein warnender Randfall, sondern
  ein harter Preflight-Fehler mit Exit `3`

Verbindliche Folge fuer Notes:

- `SchemaReadSeverity.INFO` bleibt reine Info-/Verbose-Information und blockiert
  den Transfer nicht
- `SchemaReadSeverity.WARNING` bleibt Warnsignal und wird nur dann zum
  Preflight-Fehler, wenn Phase G fuer den konkreten Tabellen-/Spaltenpfad eine
  verbindliche Unsicherheitsregel definiert
- `SchemaReadSeverity.ACTION_REQUIRED` auf selektierten Tabellen oder
  benoetigten Spalten ist ein Preflight-Problem und darf nicht in einen
  "best effort"-Transfer kippen
- `skippedObjects`, die selektierte Tabellen oder benoetigte Spalten betreffen,
  sind Preflight-Fehler
- irrelevante Hinweise fuer nicht selektierte Objekte koennen Warn-/Verbose-
  Informationen bleiben

### G.4 Target-autoritatives Transfer-Preflight implementieren

Das Preflight ist der fachliche Kern von Phase G.

Mindestens noetig:

- Ableitung der Kandidatentabellen:
  - explizit aus `--tables`
  - sonst aus dem Source-Schema
- Source-seitige Existenzpruefung dieser Tabellen
- Target-seitige Existenzpruefung derselben Tabellen
- Validation-Gate fuer Source und Target:
  - invalides neutrales Schema -> Exit `3` vor jedem Streaming
- PK-Gate fuer `--on-conflict update`:
  - fehlender Ziel-Primary-Key -> Exit `3` vor jedem Streaming
- Spaltenpruefung mindestens fuer:
  - fehlende Zielspalten
  - unerwartete Zielspalten, falls sie die Schreibsemantik brechen
  - Nullability-Mismatch
  - Typkompatibilitaet
- Trigger-bezogene Preflights fuer `triggerMode = strict`, sofern vor dem
  ersten Write sicher pruefbar
- severity-basierte Auswertung von `notes` / `skippedObjects` fuer selektierte
  Tabellen und benoetigte Spalten
- klare Trennung zwischen:
  - schema-basiertem Vorfehler
  - spaeterem Write-Fehler

Wichtig:

- das Target ist autoritativ fuer die Frage, ob sicher geschrieben werden
  darf
- Source- und Target-Schema duerfen nicht auf einen kleinsten gemeinsamen
  Nenner weichgespuelt werden, wenn dadurch echte Zielinkompatibilitaeten
  unsichtbar werden
- nicht unterstuetzte `triggerMode`-/Dialektkombinationen bleiben
  Validierungsfehler im Stil des bestehenden Importpfads statt spaeterer
  Write- oder Metadatenfehler
- das Preflight darf nicht durch Aufruf mutierender Writer-APIs bereits
  Seiteneffekte auf dem Target ausloesen

### G.5 Sichere Reihenfolge und FK-Zykluspfad verdrahten

Transfer braucht fuer Mehrtabellenlaeufe eine deterministische und sichere
Schreibreihenfolge.

Mindestens noetig:

- Ermittlung der Abhaengigkeiten auf Basis des Zielschemas oder einer
  gleichwertig sicheren target-autoritiven Projektion
- topologische Sortierung der gewaelten Tabellen
- expliziter Zyklusnachweis fuer den selektierten Teilgraphen
- Exit `3` bei Zyklen, sofern kein spaeter explizit definierter sicherer
  Bypass aktiv ist

Bevorzugte Wiederverwendung:

- die bestehende topologische Sortierlogik aus dem Import-/DDL-Umfeld als
  Vorlage oder extrahierter Shared Helper

Nicht akzeptabel ist:

- Tabellen in Source-Reihenfolge zu schreiben und erst auf Writer-Fehler zu
  warten
- Zyklen nur implizit ueber spaetere FK-Verletzungen sichtbar zu machen

### G.6 Direkten Reader->Writer-Orchestrator einfuehren

Neben dem Preflight braucht Phase G einen eigentlichen Streamingpfad.

Mindestens noetig:

- neuer `TransferExecutor` oder gleichwertiger Orchestrator
- pro Tabelle:
  - Source-Chunkstream ueber `DataReader.streamTable(...)`
  - Target-Session ueber `DataWriter.openTable(...)`
  - Chunk-fuer-Chunk-Write
  - Commit-/Rollback-/Finalisierungsvertrag analog zum Importpfad
- Wiederverwendung von:
  - `PipelineConfig(chunkSize = ...)`
  - `ImportOptions`
  - bestehender Progress-Infrastruktur
- Ergebnisobjekt mit mindestens:
  - pro Tabelle Status
  - Rows inserted/updated/skipped/failed
  - Laufzeit

Wichtig:

- schema-basiertes Preflight und `writer.openTable(...)` muessen in dieselbe
  Richtung validieren
- `writer.openTable(...)` darf nicht als Preflight-Mechanismus missbraucht
  werden, weil bestehende Writer bereits in `openTable(...)` mutieren koennen
- `writer.openTable(...)` bleibt trotz Preflight die letzte operative
  Autoritaet fuer den konkreten Write-Pfad
- Post-Table-Finalisierung, Trigger-Reenable und Sequence-Sync duerfen nicht
  durch den neuen Orchestrator regressieren

### G.7 Fortschritt, Summary und Scrubbing in den CLI-Pfad ziehen

`data transfer` soll fuer den User wie ein nativer dritter Datenpfad wirken.

Mindestens noetig:

- Wiederverwendung oder additive Erweiterung von `ProgressOperation` /
  `ProgressEvent`
- `ProgressRenderer`-Support fuer Transfer
- menschenlesbare Transfer-Summary auf `stderr`
- Unterdrueckung von Progress/Summary unter `quiet` oder `no-progress`
- zentrales Scrubbing fuer:
  - Source-/Target-Referenzen
  - Fehlertexte
  - Preflight-Fehlermeldungen

Wichtig:

- Erfolgsfaelle bleiben wie bei Export/Import ohne neuen stdout-Envelope
- Transfer erzeugt keine "hidden report file"
- Transfer darf nicht still die bestehende Import-Nomenklatur in Summary oder
  Progress uebernehmen; Progress-/Summary-Texte muessen den Pfad als
  `transfer` bzw. gleichwertig transferneutral benennen

### G.8 Tests und Doku nachziehen

Fuer Phase G sind mindestens folgende Testklassen bzw. Testbereiche
einzuplanen:

- `DataTransferRunnerTest`
- CLI-Tests fuer `data transfer`
- Preflight-Tests fuer:
  - Tabellen-/Spaltenkompatibilitaet
  - Reihenfolge
  - FK-Zyklen
  - Flag-Semantik
  - Scrubbing
- Streaming-/Executor-Tests fuer:
  - Reader->Writer-Kopplung
  - Conflict- und Triggerpfade
  - Summary-/Progress-Verhalten

Zusatzaufgabe:

- `docs/cli-spec.md` auf den finalen Phase-G-Flagvertrag nachziehen; der
  heutige Abschnitt beschreibt nur den Minimalvertrag

---

## 6. Technische Zielstruktur

Eine moegliche Minimalform fuer den Transfervertrag ist:

```kotlin
data class DataTransferRequest(
    val source: String,
    val target: String,
    val tables: List<String>?,
    val filter: String?,
    val sinceColumn: String?,
    val since: String?,
    val onConflict: String?,
    val triggerMode: String,
    val truncate: Boolean,
    val chunkSize: Int,
    val cliConfigPath: Path? = null,
    val quiet: Boolean,
    val noProgress: Boolean,
)

data class TransferEndpoint(
    val reference: String,
    val connection: ConnectionConfig,
    val schema: ResolvedSchemaOperand,
)

data class TransferPreflightResult(
    val source: TransferEndpoint,
    val target: TransferEndpoint,
    val selectedTables: List<String>,
    val writeOrder: List<String>,
    val sourceFilter: DataFilter?,
    val importOptions: ImportOptions,
)

data class TableTransferSummary(
    val table: String,
    val rowsInserted: Long,
    val rowsUpdated: Long,
    val rowsSkipped: Long,
    val rowsFailed: Long,
    val durationMs: Long,
    val error: String? = null,
)

data class TransferResult(
    val tables: List<TableTransferSummary>,
    val totalRowsInserted: Long,
    val totalRowsUpdated: Long,
    val totalRowsSkipped: Long,
    val totalRowsFailed: Long,
    val durationMs: Long,
)

fun interface TransferExecutor {
    fun execute(
        sourcePool: ConnectionPool,
        targetPool: ConnectionPool,
        reader: DataReader,
        writer: DataWriter,
        preflight: TransferPreflightResult,
        config: PipelineConfig,
        progressReporter: ProgressReporter,
    ): TransferResult
}

class DataTransferRunner(
    private val resolveSource: (String, Path?) -> String,
    private val resolveTarget: (String, Path?) -> String,
    private val urlParser: (String) -> ConnectionConfig,
    private val poolFactory: (ConnectionConfig) -> ConnectionPool,
    private val driverLookup: (DatabaseDialect) -> DatabaseDriver,
    private val transferExecutor: TransferExecutor,
    private val progressReporter: ProgressReporter = NoOpProgressReporter,
    private val stderr: (String) -> Unit = { System.err.println(it) },
) {
    fun execute(request: DataTransferRequest): Int
}
```

Wichtiger als die exakte Kotlin-Form sind die Zielsemantiken:

- beide Seiten werden explizit und symmetrisch aufgeloest
- beide Seiten tragen ein neutrales Schema-Enveloping mit
  `ResolvedSchemaOperand`
- Tabellenwahl kommt aus der Source-Seite
- Schreibreihenfolge kommt aus einer target-autoritiven Sicht
- das Preflight bleibt read-only und loest vor dem ersten echten Write keine
  Target-Seiteneffekte aus
- Reader und Writer werden direkt verbunden
- Filter- und Write-Optionen werden aus bestehenden Vertraegen abgeleitet
- Erfolg, Progress und Exit-Codes bleiben nah an Export/Import
- invalides Source- oder Target-Schema stoppt den Lauf mit Exit `3`
- `SchemaReadSeverity` wird nicht pauschal zu einem Transfer-Blocker
  zusammengedrueckt; nur definierte ACTION_REQUIRED-/`skippedObjects`-Funde
  auf selektierten Tabellen/Spalten blockieren zwingend
- user-facing Referenzen und Fehlertexte bleiben gescrubbt
- `--output-format` bleibt fuer `data transfer` unverdrahtet wie bei
  Export/Import
- Resultat- und Progress-Vertrag sind transferneutral statt implizit
  import-zentriert

---

## 7. Betroffene Artefakte

Direkt betroffen oder neu einzufuehren:

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt`
- ggf. neue Transfer-Helfer unter
  `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/`
- neue Runner-Tests unter
  `hexagon/application/src/test/kotlin/dev/dmigrate/cli/commands/DataTransferRunnerTest.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataCommands.kt`
- neues `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferCommand.kt`
- neue bzw. erweiterte CLI-Tests unter
  `adapters/driving/cli/src/test/kotlin/dev/dmigrate/cli/commands/`
- `adapters/driven/streaming/...` fuer den direkten Transfer-Orchestrator
- `hexagon/ports/src/main/kotlin/dev/dmigrate/streaming/ProgressEvent.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/output/ProgressRenderer.kt`
- `docs/cli-spec.md`

Indirekt vorausgesetzt:

- `DatabaseDriver.schemaReader()`
- `ResolvedSchemaOperand`
- bestehende Export-/Import-Helfer fuer Filter, Identifier und Importoptionen
- Schema-Reader in PostgreSQL, MySQL und SQLite

---

## 8. Akzeptanzkriterien

- [ ] `data` exponiert nach Phase G die Subcommands `export`, `import` und
      `transfer`.
- [ ] `data transfer` akzeptiert `--source` und `--target` als Pflichtflags.
- [ ] `--source` und `--target` akzeptieren URL oder Named-Connection-Alias
      gemaess `connection-config-spec.md`.
- [ ] `data transfer` verwendet fuer Source und Target keine impliziten
      Config-Defaults.
- [ ] `data transfer` streamt ohne Zwischenformat direkt von DB zu DB.
- [ ] `data transfer` fuehrt keine Datei-Flags wie `--format`, `--output`
      oder `--schema` ein.
- [ ] `--tables`, `--filter`, `--since-column`, `--since`, `--truncate`,
      `--on-conflict`, `--trigger-mode` und `--chunk-size` sind fuer Transfer
      konsistent mit den bestehenden Datenpfaden definiert.
- [ ] `--since-column` und `--since` sind nur gemeinsam gueltig.
- [ ] Der M-R5-Pfad fuer `--filter` plus literales `?` zusammen mit
      `--since` gilt auch fuer `data transfer`.
- [ ] `--tables` wird identifier-seitig validiert.
- [ ] Bei fehlendem `--tables` wird die Kandidatenliste aus dem
      Source-Schema abgeleitet.
- [ ] Vor dem ersten Write laeuft ein target-autoritatives Preflight fuer
      Tabellen-/Spaltenkompatibilitaet.
- [ ] Dieses Preflight bleibt read-only und ruft keine mutierende
      `DataWriter.openTable(...)`-Logik vor dem eigentlichen Streaming auf.
- [ ] `--on-conflict update` ohne Ziel-Primary-Key fuehrt vor dem Streaming zu
      Exit `3`.
- [ ] Die Schreibreihenfolge wird target-autoritiv aus FK-Beziehungen oder
      einer gleichwertig sicheren Projektion bestimmt.
- [ ] FK-Zyklen im gewaehlten Transfer-Teilgraphen fuehren standardmaessig zu
      Exit `3`.
- [ ] Preflight-Fehler bleiben exit-code-seitig vom eigentlichen
      Streaming-Fehlerpfad getrennt.
- [ ] `DataReader` und `DataWriter` werden direkt verbunden; es gibt keine
      temporaeren Dateien und keinen Format-Umweg.
- [ ] Source- und Target-Schema werden ueber `schemaReader()` gelesen und als
      transfertaugliche Envelopes weitergereicht.
- [ ] Invalides Source- oder Target-Schema fuehrt vor dem Streaming zu Exit
      `3`.
- [ ] Notes und `skippedObjects` aus dem Schema-Read gehen im Transferpfad
      nicht still verloren.
- [ ] `SchemaReadSeverity.INFO` bleibt nicht-blockierende Information.
- [ ] `SchemaReadSeverity.WARNING` blockiert nicht pauschal den Lauf.
- [ ] `SchemaReadSeverity.ACTION_REQUIRED` oder relevante `skippedObjects` auf
      selektierten Tabellen bzw. benoetigten Spalten werden als
      Preflight-Problem sichtbar.
- [ ] Nicht unterstuetzte `--trigger-mode strict`-/Dialektkombinationen
      bleiben Validierungsfehler mit Exit `2`; nur der fachliche Strict-
      Preflight auf unterstuetzten Dialekten endet mit Exit `3`.
- [ ] User-facing Source-/Target-Referenzen bleiben alias-basiert oder
      maskiert.
- [ ] User-facing Fehlertexte bleiben auch dann gescrubbt, wenn Exceptions
      URLs oder andere sensitive Verbindungsdetails enthalten.
- [ ] Erfolgreiche Laeufe schreiben keine neue stdout-Erfolgsstruktur und
      keine hidden report files.
- [ ] Das globale `--output-format` bleibt fuer `data transfer` unverdrahtet
      und fuehrt weder zu strukturierten Erfolgs- noch Fehler-Envelope.
- [ ] Fortschritt, `quiet` und `no-progress` verhalten sich fuer Transfer
      konsistent zu Export/Import.
- [ ] Progress- und Summary-Texte bleiben transferneutral und uebernehmen
      nicht still die Import-Nomenklatur.
- [ ] Exit-Codes `0`, `2`, `3`, `4`, `5` und `7` sind fuer `data transfer`
      sauber getrennt und testbar.
- [ ] Routinen, Views und Triggerdefinitionen werden nicht implizit
      mitkopiert.
- [ ] `docs/cli-spec.md` beschreibt denselben Phase-G-Vertrag wie der Code.

---

## 9. Verifikation

Mindestumfang fuer die Umsetzung:

1. Gezielter Testlauf fuer Application-, Streaming-, Driver- und CLI-Pfad:

```bash
docker build --target build \
  --build-arg GRADLE_TASKS=":hexagon:application:test :adapters:driven:driver-common:test :adapters:driven:driver-postgresql:test :adapters:driven:driver-mysql:test :adapters:driven:driver-sqlite:test :adapters:driven:streaming:test :adapters:driving:cli:test" \
  -t d-migrate:phase-g .
```

2. Falls echte Dialektpfade fuer Schema-Read und Write betroffen sind,
   zusaetzlicher Integrationslauf:

```bash
./scripts/test-integration-docker.sh
```

3. Manuelle CLI-Smokes:

```bash
d-migrate data transfer \
  --source staging \
  --target local_pg

d-migrate data transfer \
  --source postgresql://user:pw@host/src \
  --target mysql://user:pw@host/dst \
  --tables customers,orders \
  --truncate \
  --on-conflict update

d-migrate data transfer \
  --source sqlite:///work/src.db \
  --target sqlite:///work/dst.db \
  --filter "updated_at >= '2026-01-01'" \
  --since-column updated_at \
  --since "2026-01-01T00:00:00"
```

Dabei explizit pruefen:

- kein Zwischenartefakt entsteht
- `--source` und `--target` beide verpflichtend bleiben
- `--tables` leer oder implizit aus dem Source-Schema sauber aufgeloest wird
- invalides Source- oder Target-Schema fuehrt vor jedem Streaming zu Exit `3`
- Zielinkompatibilitaeten vor dem ersten Write auf Exit `3` fallen
- `--on-conflict update` ohne Ziel-Primary-Key faellt vor dem Streaming auf
  Exit `3`
- das Preflight bleibt seiteneffektfrei; vor dem ersten echten Write gibt es
  kein `TRUNCATE`, `DELETE`, keine FK-Check-Umschaltung und keine
  Trigger-Deaktivierung
- FK-Zyklen vor dem Streaming sichtbar werden
- `SchemaReadSeverity.INFO`/`WARNING` blockieren nicht pauschal; relevante
  ACTION_REQUIRED-/`skippedObjects`-Funde tun es
- nicht unterstuetzte `--trigger-mode strict`-/Dialektkombinationen enden wie
  im bestehenden Importpfad mit Exit `2`
- Konflikt- und Triggerpfade dieselbe Semantik wie im Import verwenden
- Fortschritt und Summary unter `quiet` / `no-progress` konsistent sind
- `--output-format` aendert fuer `data transfer` weder Erfolgs- noch
  Fehlerausgaben
- Progress- und Summary-Texte sprechen von Transfer statt still von Import
- Fehlertexte keine unmaskierten Connection-Details enthalten

---

## 10. Risiken und offene Punkte

### R1 - Default-Semantik aus Export/Import kann versehentlich in Transfer hineinleaken

Export und Import verwenden zwar dieselben Resolver-Bausteine, aber mit
unterschiedlicher Default-Semantik. Wenn Transfer blind `resolve(...)` oder
`resolveTarget(...)` uebernimmt, kann ein impliziter Config-Default in einen
Pfad rutschen, der eigentlich beide Seiten explizit verlangen soll.

### R2 - Tabellenwahl aus Source und Reihenfolge aus Target muessen strikt getrennt bleiben

Wenn Auswahl und Reihenfolge in einem Schritt "irgendwie" aus einem Schema
abgeleitet werden, entsteht schnell eine halbautoritative Mischlogik. Das
fuehrt bei Dialekt- oder Schemaabweichungen zu spaeten und schwer lesbaren
Write-Fehlern statt zu sauberem Preflight.

### R3 - Schema-Preflight und `writer.openTable(...)` duerfen nicht auseinanderlaufen

Wenn das schema-basierte Preflight andere Kompatibilitaetsregeln hat als der
konkrete Writer, drohen zwei schlechte Varianten:

- falsche Gruensignale im Preflight
- unnoetig strenge Vorfehler, obwohl der Writer sicher schreiben koennte

Phase G sollte deshalb die Validierungslogik moeglichst aus denselben
Primitiven aufbauen oder die Unterschiede explizit dokumentieren.

### R4 - FK-Zyklen duerfen nicht erst als Laufzeitfehler auftauchen

Ohne explizite Zykluspruefung driftet Transfer schnell in einen
"probieren-und-hoffen"-Pfad ab, der erst waehrend des Schreibens mit
FK-Verletzungen scheitert. Das ist fuer Skripte, Exit-Code-Vertrag und
Fehlerdiagnose deutlich schlechter als ein frueher Preflight-Exit.

### R5 - Zwei DB-Referenzen verdoppeln das Scrubbing-Risiko

Transfer arbeitet mit Source und Target gleichzeitig. Wenn Scrubbing nur fuer
eine Seite oder nur fuer die reine Referenz, nicht aber fuer Exception-Texte
gilt, koennen Credentials in Fehlern oder Warnungen leicht wieder auftauchen.

### R6 - Progress-Infrastruktur braucht eine additive Transfer-Semantik

Die heutige Progress-Infrastruktur kennt nur `EXPORT` und `IMPORT`. Wenn
Transfer ohne klare Erweiterung einfach als eines von beiden etikettiert wird,
werden Renderer, Tests und User-Erwartung schnell uneinheitlich.
