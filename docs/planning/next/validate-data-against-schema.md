# `validate data` — Datendatei gegen Schema-Definition validieren

**Status**: Entwurf (2026-06-09 — Semantik geklärt, vorhandene Bausteine kartiert,
Scope + offene Designfragen ausgearbeitet; bereit für Review).

**Trigger**: `validate data` ist in [cli-spec.md](../../../spec/cli-spec.md) (Abschnitt
`validate`) als bloßes „Geplant." spezifiziert — als einziges Kommando **ohne
jeden Anker**: kein `LF`/`LN`, keine Roadmap, kein normativer Spec-Abschnitt
(nur Verwendungsbeispiele in `cli-spec.md` und `spec/design.md`), kein Code.
Aufgefallen bei der Referenz-/Provenienz-Durchsicht der „Geplant"-Marker am
2026-06-09. Statt es als „nicht eingeplant" zu markieren oder zu entfernen,
wird der Scope hier ausgearbeitet.

**Aktivierungsbedingung** (Move nach `in-progress/`): Fachliche
Vorbedingungen sind in diesem Plan fixiert; der Move erfolgt erst mit dem
ersten Implementierungs-Commit gemäß
[`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md).
Der Lastenheft-Backfill ist mit `LF-027` erledigt, siehe Vorbedingungen.

> Status-Update 2026-06-09: `LF-027` im Lastenheft angelegt; cli-spec-Marker
> verweist als Markdown-Link mit Kennung darauf (Option C). Trigger-Absatz
> beschreibt den ursprünglichen ankerlosen Zustand.

---

## 1. Bedeutung

DB-freie **Daten-gegen-Schema-Konformitätsprüfung**: Eine Datendatei
(JSON/YAML/CSV) wird gegen eine neutrale Schema-Definition geprüft und ein
Konformitäts-Report ausgegeben. Kein Datenbank-Zugriff, kein Import.

Zielbild: pro Datensatz gegen die Spaltendefinition der Zieltabelle prüfen:
- Spalten-Präsenz (Pflichtspalten vorhanden, keine unbekannten Spalten)
- Typ-Konformität (Wert passt zum neutralen Spaltentyp)
- Nullability (NOT-NULL nicht verletzt)
- Länge/Präzision (z. B. `VARCHAR(n)`, `DECIMAL(p,s)`)
- Enum-/Custom-Type-Zugehörigkeit, soweit die neutrale Schema-Definition
  konkrete Werte oder prüfbare Metadaten enthält
- CHECK-Constraints und FK-Referenzintegrität als spätere Ausbaustufe

**v1-Untergrenze**: eine Datendatei gegen **eine** explizit gebundene Tabelle
prüfen; verpflichtend sind Spalten-Präsenz, Typ-Konformität, Nullability und
Länge/Präzision. CHECK/FK und ausdrucksbasierte Custom-Type-Prüfungen sind
nicht Teil von v1.

**v1-Präsenz-Semantik**: `validate data` prüft importfähige Quelldaten, nicht
einen nach DB-Defaults materialisierten Tabellenzustand. Eine fehlende
Schema-Spalte ist genau dann ein Fehler, wenn die Spalte `required = true` ist
und weder `default` noch `generation` besitzt. Eine vorhandene Spalte mit
`null` bleibt dagegen ein Nullability-Fehler, wenn die Spalte required ist;
Default/Generation entschuldigt nur Abwesenheit, nicht explizites `null`.
Unbekannte Eingangsspalten sind Fehler.

## 2. Abgrenzung

- **`schema validate`** (implementiert, [cli-spec.md](../../../spec/cli-spec.md))
  prüft die *Schema-Datei selbst* auf Korrektheit — keine Daten.
- **`data import`-Preflight** (`ImportPreflightValidator`,
  `schemaTargetValidator` in `hexagon/application/…/cli/commands/`) prüft
  Spalten-Matching gegen ein Schema, aber im Kontext eines Live-DB-Imports.
  `validate data` ist die eigenständige, DB-freie Trockenvariante.

## 3. Vorhandene Bausteine (wiederverwenden, nicht duplizieren)

- **Schema laden**: `SchemaDefinition` (`hexagon/core/…/model/SchemaDefinition.kt`)
  + `JsonSchemaCodec` / `YamlSchemaCodec` (`adapters/driven/formats`).
- **Daten streamen / Reader-Vorlagen**: `DataChunkReaderFactory`, `JsonChunkReader`,
  `YamlChunkReader`/`YamlEventCursor`, `CsvChunkReader` und
  `FormatReadOptions` aus `adapters/driven/formats/…/format/data`.
  Wichtig: `DataChunkReaderFactory.create(...)` braucht den Tabellennamen
  bereits beim Reader-Bau. Die Tabellen-Zuordnung ist daher Voraussetzung für
  das Einlesen, nicht erst für die Row-Engine. Die heutigen JSON-/YAML-Reader
  dürfen für v1 aber **nicht unverändert** als Validierungs-Reader dienen:
  sie binden die Spalten an die erste Row und verlieren die Information
  „Spalte fehlt" vs. „Spalte ist explizit `null`". `validate data` braucht
  daher einen schema-/presence-bewahrenden `ValidationRowReader` oder eine
  entsprechende Erweiterung der Reader.
- **Neutrale Datenform**: `DataChunk` + `ColumnDescriptor` (`hexagon/core`)
  und, wo vorhanden, `ChunkSchema`/`ChunkColumnSchema` (`ports-common`) als
  JDBC-freie Daten-/Schemaoberfläche. Für die Row-Engine reicht `DataChunk`
  allein nicht, weil Fundstellen und Präsenzinformationen fehlen; v1 sollte
  ein kleines internes Row-Modell wie `ValidationRow(table, rowOrdinal,
  sourceLocation?, presentColumns, valuesByColumn)` einführen.
- **Importpfad als fachliche Vorlage, nicht als direkte Engine**:
  `ImportTableValidator`, `ImportTypeCompatibility`, `TargetColumn` und
  `schemaTargetValidator` prüfen heute Schema-vs.-DB-Zielstruktur. Sie sind
  JDBC-/Target-gekoppelt und dürfen für `validate data` nicht unverändert zur
  DB-freien Row-Validierung werden. Wiederverwendbar sind die Testfälle,
  Fehlermuster und ggf. extrahierbare, neutrale Teile.
- **CSV-/Wert-Coercion**: `ValueDeserializer` dokumentiert die
  Import-Coercion, ist aber über `JdbcTypeHint` JDBC-gekoppelt. Für
  `validate data` braucht es eine `NeutralType`-basierte Coercion
  (oder eine sauber extrahierte neutrale Schicht), damit CSV-Regeln
  konsistent bleiben, ohne JDBC in den Validator zu ziehen.
- **CHECK-Preflight**: `CheckPreflightProbeRunner`/`CheckPreflight*` ist ein
  Live-DB-Probe (`SELECT count(*) ...`) für Migrationen. Das Umfeld ist keine
  DB-freie Ausdrucks-Engine; für v1 nur als Reporting-/Gating-Vorlage nutzen,
  CHECK-Auswertung selbst bleibt out of scope.

Es fehlt: das `validate`-Command-Group-Wiring (heute registriert `Main.kt`
nur `schema`/`data`/`export`/`mcp`), ein Tabellenbindungs-/Input-Topologie-
Resolver, ein presence-bewahrender Row-Stream und eine **DB-freie**
Row-Validierungs-Engine, die Datensätze gegen neutrale Spaltenregeln prüft.

## 4. Scope-Skizze (Sub-Slices)

1. **`validate`-Command-Group + Wiring** — `ValidateCommand` als Top-Level in
   `Main.kt` registrieren, mit `ValidateDataCommand` (und später dem schon
   geplanten `validate procedure`, `LN-034`). Drei-Schicht-Muster (Command →
   Runner → Wiring, Runner/Wiring `internal`), analog zu
   [`SchemaCommands.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaCommands.kt)
   und
   [`DataImportWiring.kt`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/DataImportWiring.kt).
2. **Tabellenbindung + Input-Topologie** — vor jedem Reader-Bau entscheiden,
   welche Schema-Tabelle geprüft wird. Festgelegter v1-Schnitt:
   `--table <name>` als Pflichtflag. Tabellenauflösung folgt dem bestehenden
   Import-Matching (`ImportDirectoryResolver.matchingSchemaTableNames`):
   exakter Treffer zuerst, sonst eindeutiger unqualifizierter Treffer;
   unbekannte oder mehrdeutige Schema-Tabelle → Exit `3`, syntaktisch
   ungültiger `--table`-Wert → Exit `2`. Keine Ableitung aus Dateiname, keine
   Top-Level-Tabellenstruktur in v1.
3. **Schema + Daten einlesen** — Schema-Codec nach Endung/Format wählen,
   vorzugsweise über `SchemaFileResolver`. Datendatei mit dem aufgelösten
   Tabellennamen streamen (JSON/YAML/CSV), Encoding über `EncodingDetector`.
   JSON/YAML v1 liest Top-Level-Sequenzen von Row-Objekten, aber über einen
   schema-aware Reader/Adapter, der spätere schema-bekannte Keys nicht wegen
   First-Row-Inferenz abweist und pro Row Präsenzinformationen erhält. CSV v1
   startet mit Header-CSV; `--csv-no-header` bleibt ein eigener Folge-Slice.
4. **Konformitäts-Engine (DB-frei)** — v1: Präsenz, Typ, Nullability,
   Länge/Präzision gegen `SchemaDefinition`/`NeutralType`, inkl.
   NeutralType-basierter CSV-Coercion. Die Engine arbeitet auf
   `ValidationRow`/äquivalentem Row-Modell, damit fehlend vs. explizit `null`
   und Datensatzordinal erhalten bleiben. Streaming, damit große Dateien ohne
   Vollmaterialisierung prüfbar sind. CHECK/FK und ausdrucksbasierte
   Custom-Type-Prüfung erst in späteren Slices.
5. **Report + Exit-Codes** — Report (Text; optional `--json` für Skripting)
   mit Fundstellen. v1 garantiert Tabelle, 1-basiges Datensatzordinal,
   Spalte, Regel und kurze Wertvorschau; Zeile/Spalte in der Quelldatei sind
   optional und nur auszugeben, wenn der konkrete Reader sie liefert. Exit
   `0` Erfolg, `3` Validierungsfehler, `2` ungültige Flags, `7`
   Parse-/IO-Fehler.
6. **Doku-/Spec-Hygiene** — „Geplant."-Marker in cli-spec entfernen bzw. auf
   die `LF`-Kennung umstellen (Markdown-Link/ID, ohne Paragraphzeichen),
   Spec um Tabellenbindung, v1-Scope und Exit-Code-Vertrag ergänzen.

## 5. Designentscheidungen und offene Folgefragen
1. **Tabellen-Zuordnung v1 (entschieden)**: `--table <name>` ist Pflicht.
   Matching ist konsistent zum Importpfad: exakter Tabellenname gewinnt,
   eindeutiger unqualifizierter Match ist erlaubt, Ambiguität oder fehlender
   Schema-Treffer ist ein Validierungs-/Preflight-Fehler. Ableitung aus dem
   Dateinamen und Top-Level-Keys als Tabellenwrapper sind nicht Teil von v1.
2. **Constraint-Tiefe nach v1**: Spalten/Typ/Nullability/Länge sind v1.
   CHECK und FK bleiben out of scope, solange keine DB-freie
   Ausdrucks-/Referenz-Engine spezifiziert ist. FK-Referenzintegrität braucht
   außerdem den vollen Datensatz und ggf. mehrere Tabellen.
3. **CSV-Typisierung**: CSV liefert nur Strings — Coercion-Regeln (wann gilt
   `"42"` als gültiges `INTEGER`?) müssen `NeutralType`-basiert definiert
   werden, konsistent zu den Import-Regeln, aber ohne `JdbcTypeHint`-Abhängigkeit.
   `--csv-no-header` braucht zusätzlich einen Spaltenordnungs-Vertrag
   (Schema-Reihenfolge vs. explizite Spaltenliste).
4. **Output-Format**: nur Text vs. zusätzlich `--json`.

## 6. Vorbedingungen
- **Lastenheft-Backfill erledigt**: `LF-027` „Datenvalidierung gegen Schema"
  ist in [`lastenheft-d-migrate.md`](../../../spec/lastenheft-d-migrate.md)
  angelegt; der cli-spec-Marker verweist als Markdown-Link mit Kennung darauf
  (gate-geprüfter Pfad, stabile Kennung). Damit ist die fehlende Provenienz,
  die diesen Plan ausgelöst hat, geschlossen.
- **Tabellen-Zuordnung v1 festgelegt** ([Designentscheidungen](#5-designentscheidungen-und-offene-folgefragen), Frage 1):
  `--table <name>` ist Pflicht, mit Import-kompatiblem Tabellenmatching.
- **Reader-/Report-Vertrag festgelegt**: v1 braucht presence-bewahrende Rows;
  der Report garantiert Datensatzordinal statt Source-Line.
- **v1-Scope festgeschrieben**: CHECK/FK entweder ausdrücklich out of scope
  oder mit eigener DB-freier Engine-Spezifikation versehen.
- **Exit-Code-Vertrag nachgezogen**: command-spezifische cli-spec nennt neben
  `0`/`3` auch `2` und `7`, wenn der Runner diese Pfade implementiert.
- Referenz-Stil beachten: keine Paragraphzeichen, nur Markdown-Links/`LF`-`LN`-Kennungen.

## 7. Empfohlener Schnitt
Klein halten: v1 auf `--table <name>` plus Spalten/Typ/Nullability/Länge
gegen **eine** Tabelle begrenzen. JSON/YAML bleiben bei Top-Level-Sequenzen
von Row-Objekten, verwenden aber nicht den unveränderten First-Row-Reader-
Vertrag; v1 braucht schema-aware, presence-bewahrendes Streaming. CSV startet
mit Header-CSV; `--csv-no-header`, Top-Level-Tabellenwrapper, CHECK und FK
folgen als eigene Slices. Das liefert schnell sichtbaren Nutzen und nutzt
vorhandene Parser-/Encoding-Bausteine, ohne JDBC-gekoppelte Importbausteine
in eine DB-freie Engine zu ziehen.
