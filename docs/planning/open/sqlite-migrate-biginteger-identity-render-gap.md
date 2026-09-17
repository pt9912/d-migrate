# SQLite-`migrate` von authored `biginteger`+`identity`: Render-Lücke + Präferenz-Threading im Re-Read

> Status: **Draft (Trigger Watch)**
> Trigger: Abspaltung beim Bau des Reverse-Präferenzen-Slices
> ([`../done/reverse-preferences.md`](../done/reverse-preferences.md),
> Nicht-Scope). Der Slice adressiert den **Transfer**-Fall (SQLite reverse →
> PG/MySQL generate); dieser Befund betrifft `migrate --execute` **gegen ein
> SQLite-Ziel** mit authored `biginteger`+`generation: identity`.
> Severity: **P3** (schmal — nur wer explizit `biginteger`+`identity` gegen SQLite
> migriert; final beim Schnitt einzustufen).

## Befund — **zwei** Ursachen (Review B3)

Ein spec-valides Schema mit authored `biginteger` + `generation: identity` gegen
ein SQLite-Ziel migriert **driftet**, und zwar aus **zwei** unabhängigen Gründen.
Beide müssen behoben werden — der Render-Fix allein lässt die Drift bestehen:

1. **Render-Lücke im Diff/Rebuild-Pfad.** Der Voll-Generate-Pfad
   ([`SqliteColumnConstraintHelper`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteColumnConstraintHelper.kt),
   `generateRowidIdentityColumn`) rendert `biginteger`+`identity` korrekt als
   `INTEGER PRIMARY KEY AUTOINCREMENT`. Der von `migrate --execute` genutzte
   Diff/Rebuild-Renderpfad
   ([`SqliteDiffSqlBuilders`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSqlBuilders.kt))
   inspiziert `col.generation` **nicht** → rendert plain `INTEGER` + separates
   `PRIMARY KEY(id)` (valides DDL, **appliziert**, aber **AUTOINCREMENT still
   verloren**). `columnLine` **und** `primaryKeyClause` müssen auf Parität mit dem
   Voll-Generate-Pfad gehoben werden.

2. **Präferenz-Threading im Post-Compare-Re-Read.** Selbst nach dem Render-Fix
   driftet der Post-Compare, solange der Re-Read des SQLite-Ziels die
   Reverse-Präferenz **nicht mitführt**: der Default-Re-Read liefert `identifier`,
   das authored Soll ist `biginteger`+`identity` → Exit 5. Die aus dem
   Reverse-Präferenzen-Slice stammende `SchemaReadOptions.sqliteAutoincrement`-
   Präferenz muss bis in den Post-Compare-Re-Read (`SchemaMigrateExecutionStage`)
   gefädelt werden — oder der `migrate`-Kontext leitet sie deterministisch ab.

## Nachtrag 2026-09-17 — derselbe Re-Read-Befund für MySQL

Seit dem vierten Bauabschnitt des Compare-Slices
([`../in-progress/compare-projektion-und-normalisierung.md`](../in-progress/compare-projektion-und-normalisierung.md))
gibt es die Reverse-Präferenz `serial`/`identity` für MySQLs
`BIGINT AUTO_INCREMENT` (und SQLite unter der 64-Bit-Breite). `schema migrate`
liest den Ist-Stand weiterhin **ohne** Präferenz. Gemessen gegen MySQL 9.7.2:
ein mit `--mysql-autoincrement-syntax identity` zurückgelesenes Schema als Soll
gegen dieselbe Datenbank plant fünf `AlterColumnGeneration`
(`ALTER TABLE … MODIFY COLUMN … BIGINT NOT NULL AUTO_INCREMENT`, wirkungslos),
ohne Präferenz keine Operation. Ursache 2 oben gilt damit für beide Dialekte;
für MySQL fehlt nur das Threading, einen Render-Befund gibt es dort nicht.
Das Handbuch rät bis dahin, für diesen Weg ohne `identity` zu lesen.

## Nicht-Scope

- Der SQLite→PG/MySQL-**Transfer** (kein SQLite-Generate involviert) — der ist im
  Reverse-Präferenzen-Slice gelöst.
