# Implementierungsplan: 0.9.7 — SQLite Trigger Reverse-Read

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.2 Folge-Slice (SQLite-Reader-Hardening fuer Trigger)
> **Status**: ✅ erledigt 2026-05-22 (Sub-Slices A–E, Commits `61587635` Parser+Unit-Tests / `0256bc5b` Schema-Reader-Routing + Substring-Pfad-Löschung / `8ba78822` Round-Trip-Tests / `bcd374b3` Live-DB-Integration / Closing)
> **Vorbedingung**: E.2 Trigger-Rendering Vollscheibe ✅ 2026-05-18
>                  (PG / MySQL / SQLite-Render alle gruen);
>                  bestehender `SqliteSchemaReader.readTriggers` (Stub).
> **Referenz**: `docs/planning/done-archive/ImpPlan-0.9.7-E.2-trigger-rendering.md` §7.3
>             (SQLite-Trigger-Reverse-Read als E.2-Carve-out);
>             `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTriggerDdlHelper.kt`
>             (in-code-Carve-out-Hinweis);
>             `adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt`
>             (heutiger Parser-Stub).

---

## 1. Auslöser

`SqliteSchemaReader.readTriggers` liest heute Trigger aus
`sqlite_master`, parst aber den `CREATE TRIGGER`-DDL-Text via
naiver String-Substring-Suche
(`upper.contains("BEFORE")` etc.). Konkret fehlen:

- **WHEN-Klausel** (`condition` auf `TriggerDefinition`): wird
  nicht extrahiert; SQLite-Trigger mit `WHEN (NEW.x > 0)`
  verlieren ihre Bedingung beim Reverse-Read.
- **FOR EACH ROW / FOR EACH STATEMENT** (`forEach`): SQLite
  unterstützt nur `FOR EACH ROW`, aber der Reader liest das
  Feld nicht; spätere Vergleiche koennten falsch ausschlagen,
  wenn das neutrale Modell `FOR EACH STATEMENT` enthält
  (z.B. nach Cross-Dialect-Transfer).
- **`INSTEAD OF` vs. `BEFORE` Reihenfolge**: Die
  aktuelle Suche nach `timing` matcht `BEFORE` vor `INSTEAD OF`,
  wodurch `INSTEAD OF` fälschlich als `BEFORE` geparst werden kann.
- **Multi-Statement-Body**: Parser-Regex
  `BEGIN\\s+(.*?)\\s+END` greift den Body als einen einzigen
  Block und behandelt `END` nicht robust bei geschachtelter
  Syntax (Kommentare/Strings/untergeordnete `BEGIN..END`-Blöcke)
  sowie semikolon-getrennten Statements mit Sonderformatierung.
  Reverse-Read → Write → Reverse ist deshalb nicht idempotent.
- **Fehlerhafte Parse-Fallbacks**: bei Mehrdeutigkeit setzt der
  Parser `BEFORE` / `INSERT` als Default und emittiert nur eine
  `WARNING` (R210 / R211). Das kann Daten still schlucken — der
  Reverse-gelesene Trigger ist semantisch falsch.
- **Schemaqualifizierte Trigger-Namen** (`main.trigger_name`):
  nicht vollständig behandelt; SQLite-`attached`-Schemata
  werden derzeit als falsch zugeordnete Trigger-Objekte gelesen.

Konsequenz: E.2-Trigger-Vollscheibe ist auf SQLite nur fuer den
**File-to-File**-Pfad voll funktionsfaehig. Live-DB-zu-Live-DB-
Diffing fuer SQLite-Schemata mit Triggern ist nicht blockiert, erzeugt
aber semantisch fehlerhafte Vergleiche, weil der Reverse-Read
nicht alle Trigger-Attribute sauber traegt.

---

## 2. Warum jetzt?

E.2 hat den File-zu-File-Pfad fertig; der Live-Read-Pfad war
ausdrücklich Carve-out (siehe in-code-Hinweis
`SqliteTriggerDdlHelper.kt:75-78`). 0.9.7-Roadmap §E Rest listet:

> SQLite-Trigger-Reverse-Read aus sqlite_master

Operatoren, die SQLite-Datenbanken mit Triggern reverse-readen
(`schema reverse` oder `schema compare` mit DB-Operand), bekommen
heute eine fehlerhafte `TriggerDefinition` zurueck. Das hat
konkrete Konsequenzen:
- `schema compare` zwischen zwei Live-DBs mit gleichen
  Triggern erzeugt false-positive Diffs (WHEN-Klausel oder
  Body-Whitespace werden inkonsistent gelesen).
- `schema migrate` (Datei-zu-DB) gegen eine SQLite-DB mit
  Triggern emittiert Drop+Create-Ops fuer Trigger, die in der
  Source-Datei identisch deklariert sind.
- Round-Trip (Reverse → Write → Reverse) ist nicht idempotent.

---

## 3. Scope

### 3.1 In-Scope

- Neuer `SqliteTriggerSqlParser` in
  `adapters/driven/driver-sqlite/.../parser/`. Eingabe: roher
  `CREATE TRIGGER`-SQL-String aus `sqlite_master.sql`. Ausgabe:
  vollstaendige `TriggerDefinition`:
  - `timing` (BEFORE / AFTER / INSTEAD_OF) — robuster Parser
    (Token-basiert statt Substring-Suche).
  - `event` (INSERT / UPDATE / DELETE) — Token-basiert.
  - `forEach` — heute fehlt in der bestehenden Reader-Route.
    SQLite unterstützt nur `ROW`; der neue Reader setzt `forEach = ROW`
    explizit (als Default), `STATEMENT` bleibt weiterhin ein
    inkompatibles Modell für den Renderer.
    (`FOR EACH` wird in diesem Slice nie auf `STATEMENT` aufgelöst.)
  - `condition` (WHEN-Klausel) — heute fehlend.
  - `body` (Multi-Statement-Block zwischen `BEGIN` und `END`) —
    pinnt Whitespace und Semikolons fuer Idempotenz.
  - `table` — bleibt wie heute aus `tbl_name`.
  - `sourceDialect = "sqlite"` — bleibt.
- `SqliteSchemaReader.readTriggers` ruft den neuen Parser.
- ~~`SqliteTypeMapping.parseTriggerSql` als deprecated markieren, aber
  als Redirect auf den neuen Parser behalten.~~ **Abweichung in der
  Umsetzung (2026-05-22, Sub-Slice B Commit `0256bc5b`)**:
  `parseTriggerSql` und `TriggerParseResult` wurden ersatzlos gelöscht
  statt als deprecated Shim behalten. Begründung: beide waren
  `internal`-only, der einzige Aufrufer (`SqliteSchemaReader.readTriggers`)
  wurde im selben Commit migriert, und ein deprecated Wrapper wäre
  damit Dead-Code. Die vier Trigger-Tests in `SqliteTypeMappingTest`
  wurden mitentfernt — jeder Case ist in
  `SqliteTriggerSqlParserTest` mit strikteren Assertions abgedeckt.
- Round-Trip-Tests: Reverse → emit YAML → Reverse → identisch.
- Live-DB-Integration: pinned mit einem echten SQLite-File
  (`test/integration-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/` oder
   `adapters/driven/driver-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/`)
  und mehreren Trigger-Varianten (mit/ohne WHEN, BEFORE/AFTER/
  INSTEAD_OF, multi-statement Body).

### 3.2 Out-of-Scope

- **SQLite-Schemata mit attached databases** (`main.x` /
  `temp.x` / `aux.x`). Schemaqualifizierte Namen bleiben
  blockierender Carve-out; der Parser reagiert mit
  `SQLITE_TRIGGER_SCHEMA_QUALIFIED_NAME_UNSUPPORTED`.
- **Trigger-Body-Sanitization**: Body bleibt opaker SQL-Text.
  Der Renderer hat den Sanitization-Carve-out
  (`SqliteTriggerDdlHelper.kt:58-65`); der Reader ueberlaesst
  Validation dem Renderer-Pfad.
- **`CREATE TEMP TRIGGER`**: heute nicht von Schema-File-Codec
  unterstuetzt; bleibt out-of-scope.
- **Trigger auf Views vs. Trigger auf Tabellen**: SQLite kennt
  `INSTEAD OF`-Trigger nur auf Views, aber das neutrale Modell
  unterscheidet das nicht — der Reader behandelt beide gleich;
  Trigger-auf-View-Validation lebt im Renderer-Pfad.

---

## 4. Vorbedingungen

| Vorbedingung | Status |
| ------------ | ------ |
| E.2 Trigger-Vollscheibe (File-zu-File) | ✅ 2026-05-18 |
| `TriggerDefinition` neutrales Modell (mit `forEach` + `condition`) | ✅ |
| `SqliteSchemaReader` infrastruktur (`JdbcMetadataSession`, `SchemaReadNote`) | ✅ |
| `TriggerNameCollisionDetector` | ✅ E.2 Sub-Slice A.1 |

---

## 5. Architektur

### 5.1 Parser-Design

SQLite's `CREATE TRIGGER`-Grammatik (vereinfacht aus
https://sqlite.org/lang_createtrigger.html):

```
CREATE [TEMP|TEMPORARY] TRIGGER [IF NOT EXISTS]
  [schema-name.] trigger-name
  [BEFORE | AFTER | INSTEAD OF]
  { DELETE | INSERT | UPDATE [OF column-name [, ...]] }
  ON [schema-name.] table-name
  [FOR EACH ROW]
  [WHEN expr]
  BEGIN
    statement-list
  END
```

Token-basierter Parser (kein vollwertiger SQL-Parser noetig):

1. Strip/normalize Whitespace + Header-Comments (`--`-Line-Comments,
   `/* */`-Block-Comments) vor dem `BEGIN`; halte das
   `BEGIN .. END`-Body-Segment in Inhalt und Formatierung
   unveraendert fuer Idempotenz.
2. Konsumiere `CREATE` `[TEMP|TEMPORARY]` `TRIGGER`
   `[IF NOT EXISTS]`.
3. Konsumiere `[schema.]trigger_name`.
   Bei `schema.`-Praefix wird `R212` als `ACTION_REQUIRED` gesetzt
   (`SQLITE_TRIGGER_SCHEMA_QUALIFIED_NAME_UNSUPPORTED`) und der Trigger wird
   verworfen, damit kein falscher Objektkey entsteht.
4. Konsumiere `[INSTEAD OF | BEFORE | AFTER]`.
   (`INSTEAD OF` zuerst, damit die SUBSTRING-Kollision mit `BEFORE`
   vermieden wird.)
   Bei fehlendem Token wird `R210` als `ACTION_REQUIRED` gesetzt und als
   Fallback `timing = BEFORE` gemeldet.
5. Konsumiere `{ DELETE | INSERT | UPDATE [OF cols] }`.
   `UPDATE OF cols` wird heute nicht im Modell unterstuetzt —
   `WARNING` + Default `UPDATE` ohne Spaltenliste.
6. Konsumiere `ON [schema.]table_name`. Bei einem Schema-Praefix wird `R212`
   gesetzt und der Trigger verworfen, damit kein Objektkey auf
   `schema.table` entsteht.
7. Konsumiere `[FOR EACH ROW]` → setzt `forEach = ROW`.
   (SQLite unterstuetzt nur ROW; Default ROW wenn nicht
   spezifiziert.)
8. Konsumiere `[WHEN expr]` — `expr` wird lazy bis `BEGIN`
   extrahiert und als `condition = <captured>` gesetzt.
9. Konsumiere `BEGIN` … `END` → `body = <captured>`.
   Falls nicht vorhanden oder mehrdeutig, `body = null` und der Trigger
   wird ohne Body als best-effort weitergeführt.
   Whitespace pinned (siehe §5.2).

### 5.2 Body-Idempotenz

Der Body soll Reverse → Write → Reverse identisch bleiben.
Pinning:

- Trim maximal ein optionales `;` direkt vor `END` (SQLite-Konvention).
- Normalize Zeilenumbrueche (CRLF -> LF) im Trigger-Body.
- Interne Einrückung und Statement-Spacing bleibt unveraendert.
- Keine semantische Kanonisierung (Statements bleiben in ihrer
  syntaktischen Form).

Falls die geschriebene YAML eine andere
Whitespace-Normalisierung anwendet, ist Round-Trip nicht
idempotent. Tests pinnen explizit.

### 5.3 Error-Handling

`TriggerParseResult` dient als Grundlage für
`timing/event/body` + `notes: List<SchemaReadNote>`.
Es ist sinnvoll, ihn für dieses Slice auf
`forEach/condition/table` zu erweitern oder intern direkt eine
`TriggerDefinition` plus Notes aufzubauen.
Erweitert:

- `R210` (timing unklar) → ACTION_REQUIRED statt WARNING, wenn die
  CREATE-TRIGGER-Grammatik nicht parsed.
- `R211` (event unklar) → ACTION_REQUIRED.
- `R212` (NEU, schema-qualifizierter Trigger-Name) → ACTION_REQUIRED.
- `R213` (NEU, `UPDATE OF cols` ohne Modell-Support) → WARNING.

### 5.4 Integration mit bestehender Pipeline

`SqliteSchemaReader.readTriggers` ruft den neuen Parser; der
Rest der Read-Pipeline (`TriggerNameCollisionDetector` aus E.2)
greift unveraendert. `OperationMapper` und `SqliteRebuildPlanner`
sehen keinen Unterschied — `TriggerDefinition` ist das einzige
Inkrement.

---

## 6. Sub-Slice-Schnitt

| Sub-Slice | Inhalt |
|---|---|
| A | Neuer `SqliteTriggerSqlParser` (token-basiert) mit Unit-Tests fuer alle Trigger-Varianten |
| B | `SqliteSchemaReader.readTriggers` ruft den neuen Parser; alter `SqliteTypeMapping.parseTriggerSql` wird gelöscht (Abweichung vom Originaltext „deprecated + Redirect" — siehe §3.1, Begründung internal-only + single caller migrated atomically) |
| C | Round-Trip-Tests: Reverse → File-Write → Reverse mit echtem SQLite-File |
| D | Live-DB-Integrationstest in `test/integration-sqlite/src/test/kotlin/dev/dmigrate/driver/sqlite/`: Trigger anlegen, Reverse-Read, Compare bestaetigt Identitaet der Trigger-Definition |
| E | `R212`-Ausnahmen (`schema.table` oder `schema.trigger`) im Reader nicht in die Trigger-Map übernehmen; bestehende Schema-/Objekt-Keys vermeiden; zudem §7.3 E.2 Carve-out-Eintrag im master plan loeschen; Roadmap §E Rest aktualisieren; Plan-Doc nach `docs/planning/done/` |

---

## 7. Akzeptanzkriterien

- [x] `SqliteTriggerSqlParser` extrahiert `timing`, `event`,
      `forEach`, `condition`, `body` aus jedem von SQLite via
      `sqlite_master.sql` zurueckgegebenen DDL-String.
- [x] WHEN-Klausel wird korrekt extrahiert.
- [x] `FOR EACH ROW` wird explizit gepinnt.
- [x] Schema-qualifizierte Trigger-Namen (`main.x`) werden mit
      `R212` `ACTION_REQUIRED` markiert und der Trigger aus dem
      Reverse-Read-Ergebnis ausgeschlossen
      (`ParsedTrigger.rejected = true`).
- [x] `UPDATE OF cols` emittiert `R213`-WARNING; Trigger wird
      mit `event = UPDATE` ohne Spalten gefuehrt.
- [x] Multi-Statement-Body wird Round-Trip-idempotent.
- [x] Live-DB-Integrationstest: `CREATE TRIGGER` anlegen,
      Reverse-Read, Compare gegen ein File-Schema-Modell mit
      identischer Trigger-Definition → No-op
      (`SqliteTriggerReverseReadIntegrationTest`).
- [x] Bestehende Trigger-Tests bleiben gruen.
- [x] `make docker-check` gruen.

---

## 8. Definition of Done (§13-Template)

- [x] **Modus**: file-to-DB + DB-to-DB (Reader-Pfad);
      file-to-file unveraendert.
- [x] **Renderbare Ops**: keine neuen; nur Reverse-Read-Hardening.
- [x] **Neue Diagnostics**: `R212` (schema-qualified-name
      `ACTION_REQUIRED`-Block), `R213` (UPDATE OF cols warning). Bestehende
      `R210` / `R211` werden von WARNING zu `ACTION_REQUIRED`, wenn das
      `CREATE TRIGGER`-DDL nicht parsebar ist.
- [x] **Up / Down**: irrelevant (Reader-Slice).
- [x] **Report-Felder**: `SchemaReadNote` traegt neue Codes.
- [x] **Dialekte**: nur SQLite.
- [x] **F.0-Erfuellung**: irrelevant.
- [x] **Positive + Blocker-Tests**: pro Parser-Case ein Test
      (`SqliteTriggerSqlParserTest`, ~30 Cases).
- [x] **Rollback-Test**: irrelevant.
- [x] **Datei-zu-Datei**: unveraendert.
- [x] **Bestehende Vertraege unveraendert**: File-Codec
      unveraendert; `TriggerDefinition`-Modell unveraendert;
      `OperationMapper`-Pfad unveraendert. Nur der Reader-Pfad
      wird strikter.

---

## 9. Out-of-Scope / Folge-Themen

- Schema-qualifizierte Trigger-Namen (`main.x` / `temp.x` /
  `aux.x`) — eigener Slice (SQLite attached databases im
  Allgemeinen).
- `CREATE TEMP TRIGGER` — File-Codec-Erweiterung noetig; nicht
  in dieser Tranche.
- Vollwertiger SQLite-SQL-Parser (statt Token-basiert) —
  separater grosser Slice mit JSqlParser oder eigenem ANTLR-
  Parser.
- Trigger-Body-semantische-Validation — bleibt
  `SqliteTriggerDdlHelper`-Carve-out (`body sanitisation is out
  of scope`).

---

## 10. Risiken

- **Trigger-DDL-Varianten**: SQLite's `sqlite_master.sql` ist
  der ORIGINAL-DDL-Text, den der User angelegt hat — beliebige
  Whitespace-/Comment-Varianten. Mitigation: Parser ist
  token-basiert + eingeschraenkter Header-Comment-Strip auf dem
  Praeheader, sodass das Body-Format fuer Idempotenz erhalten bleibt;
  Tests decken die typischen Varianten.
- **Round-Trip-Idempotenz**: wenn Reverse → YAML → Reverse
  unterschiedliche Whitespace produziert, gilt der Slice als
  fehlgeschlagen. Mitigation: explizite Round-Trip-Tests in
  Sub-Slice C.
- **R210/R211 ACTION_REQUIRED-Upgrade**: bestehende Live-DBs mit
  unparseable Triggern werden ploetzlich in der Ergebnisbewertung als
  `ACTION_REQUIRED` markiert statt rein akzeptiert. Mitigation:
  dokumentierte Breaking-Change-Kommunikation im CHANGELOG und optionaler
  Folge-Slice für ein bewusstes Override-Verhalten.

---

## 11. Erwartete Commit-Reihenfolge

| Sub-Slice | Commit-Subjekt-Skizze |
|---|---|
| A | `feat(sqlite): token-based trigger SQL parser for sqlite_master reverse-read` |
| B | `refactor(sqlite): route SchemaReader trigger reverse-read through SqliteTriggerSqlParser` |
| C | `test(sqlite): round-trip idempotency for trigger reverse-read` |
| D | `test(sqlite): live-DB integration for trigger reverse-read` |
| E | `docs(plan): SQLite trigger reverse-read closing` |
