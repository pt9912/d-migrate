# Implementierungsplan: 0.9.7 — MySQL-Routine-Identity-Reverse-Read

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.1-Carve-out (MySQL-Seite des Slice-E-Themas)
> **Status**: ✅ erledigt 2026-05-22 (Commit `41c62fe8`: MetadataQueries SELECT-Erweiterung + Reader-Population + Tests)
> **Vorbedingung**: E.1 ✅ (`docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md`)
> **Referenz**: `docs/planning/done/ImpPlan-0.9.7-E.1-routine-migration.md` §2
>             (Aus-Scope-Carve-out)

---

## 1. Ziel

Slice E von E.1 schloss den Slice-A-Reverse-Read-Carve-out **nur
für PostgreSQL**: `readPostgresFunctions` /
`readPostgresProcedures` populieren `security` / `definer` /
`searchPath` jetzt aus `pg_proc`. Der MySQL-Reader
(`MysqlRoutineReader`) baut `FunctionDefinition` /
`ProcedureDefinition` weiterhin ohne diese Felder — die
Default-Werte `null` werden über den Data-Class-Default geliefert.

Dieser Plan schließt die MySQL-Lücke: Reverse-Read soll
`security` (`SQL SECURITY DEFINER/INVOKER`), `definer`
(`DEFINER = 'user'@'host'`) und `sqlMode` (Active `sql_mode`-
Snapshot zur Routine-Erzeugungszeit) für MySQL-Routinen
projizieren, sodass file-zu-DB-Diffs gegen ein MySQL-Schema mit
expliziten Identity-Attributen keine spurious-Replace mehr
emittieren.

## 2. Scope

In Scope:

- `MysqlMetadataQueries` (oder ein neuer
  `MysqlRoutineIdentityQueries`-Helper) liest
  `information_schema.routines` mit folgenden zusätzlichen
  Spalten:
  - `security_type` → `RoutineSecurity.DEFINER` / `INVOKER`
  - `definer` → roher `'user'@'host'`-String (MySQL liefert das
    in genau diesem Format)
  - `sql_mode` → roher `sql_mode`-Snapshot, comma-getrennt
- `MysqlRoutineReader.readFunctions` und `readProcedures`
  konsumieren die neuen Spalten und schreiben sie in die
  zurückgegebenen Definitions.
- `definer`-Parsing: aktuell liefert MySQL den vollqualifizierten
  String `user@host` (mit Quotes je nach `sql_mode`). Wir
  speichern unverändert und überlassen dem Renderer das Re-
  Emittieren in MySQL-Syntax.

Aus Scope:

- PG-Reverse-Read von `sqlMode` (PG kennt kein `sql_mode`; Slice
  E ließ das Feld bewusst auf `null`).
- Anpassungen am Comparator oder den Renderern — die existierenden
  C.2-Renderer konsumieren `security`/`definer`/`sqlMode` schon;
  nur die Daten fehlen heute.
- Validator-Regel "INVOKER + definer ist widersinnig" — siehe
  E.1 §2 Carve-out, separater Slice.

## 3. Acceptance Criteria

- [x] `MysqlMetadataQueries.listFunctions` / `listProcedures`
      projezieren `security_type`, `definer`, `sql_mode` aus
      `information_schema.routines`.
- [x] `MysqlRoutineReader` schreibt die Felder in
      `FunctionDefinition` / `ProcedureDefinition`.
- [x] Ein file-zu-DB-Diff einer MySQL-Function mit
      `SQL SECURITY DEFINER` + nicht-leerem `sql_mode` produziert
      keine spurious-Replace-Diagnose, sobald die File-Side die
      gleichen Werte trägt — der bereits bestehende
      `MigrationFingerprint`-Pfad (`RoutineIdentityNormalizer.normalizeMysqlSqlMode`)
      sortiert/normalisiert sql_mode, sodass Reihenfolge-Drift kein
      spurious-Replace mehr ist.
- [x] Unit-Test pinnt die drei Projection-Pfade per
      Fake-`JdbcOperations` (`MysqlMetadataQueriesTest` SELECT-
      Surface-Pins).
- [x] Reader-Test pinnt SECURITY DEFINER + sql_mode-Roundtrip
      (`MysqlRoutineReaderTest`, plus INVOKER + null-Fallback +
      Procedure-Pfad).
- [x] CHANGELOG-Eintrag.

## 4. Definition of Done

- [x] AC §3 erfüllt.
- [x] `make docker-test` + `make docker-check` grün (driver-mysql).
- [x] Plan-Datei nach `docs/planning/done/` verschoben.

## 5. Risiken

- **MySQL `definer`-Format-Varianten**: `'user'@'host'` mit
  optionaler Quote-Lockerung je nach `sql_mode`. Mitigation:
  unverändert speichern, Renderer macht das Quoting passend zum
  Ziel-Server.
- **`sql_mode`-Reihenfolge**: PG-`search_path` ist
  positional-sensitiv; MySQL-`sql_mode` ist eine Set-Liste, aber
  die Renderer-Seite speichert sie als comma-getrennten String.
  Comparator vergleicht die Strings byte-genau — wenn der
  Reverse-Read eine andere Reihenfolge liefert als das File,
  spurious-Replace. Mitigation: bei Bedarf canonical-sort beim
  Lesen.

## 6. Out-of-Scope-Verweis

- Validator-Regel "INVOKER + definer ist widersinnig" gehört
  einem späteren Validator-Slice; siehe E.1 §2 Carve-out.
