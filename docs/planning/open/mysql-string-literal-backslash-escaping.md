# MySQL-String-Literale: Backslash-Escaping fehlt im DDL-Renderpfad (P1)

> **Status:** Vorabklärung (2026-07-17)
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befund 1 = P1
> und Befund 3 = P2). Beide teilen dieselbe Wurzel und werden gemeinsam
> geschnitten, weil ein einziger Fix sie erledigt.
> **Aktivierungsbedingung:** P1 — sollte vor 1.0.0-final priorisiert werden
> → `next/`-Plan.

## Befund

`MysqlTypeMapper.toDefaultSql` rendert `DefaultValue.StringLiteral` als
einfach-gequotetes MySQL-Literal und escapet dabei **nur** `'` → `''`:

```kotlin
is DefaultValue.StringLiteral -> "'${default.value.replace("'", "''")}'"
```

MySQL behandelt bei Default-`sql_mode` (ohne `NO_BACKSLASH_ESCAPES`) den
Backslash als Escape-Zeichen; Quote-Verdopplung allein genügt dort nicht. Ein
Wert, der auf `\` endet, escapet das schließende Quote weg — das Literal läuft
weiter und verschluckt nachfolgendes DDL, der Rest wird als SQL geparst.
d-migrate setzt `NO_BACKSLASH_ESCAPES` nirgends; es gilt der unsichere Default.

Dieselbe Lücke nochmal bei Inline-ENUM-Werten (Befund 3).

**Die Lücke ist eine Inkonsistenz, kein Wissensdefizit:** `MysqlSequenceSqlCodec`
im selben Modul macht es korrekt —

```kotlin
fun quoteStringLiteral(value: String): String =
    SqlIdentifiers.quoteStringLiteral(value.replace("\\", "\\\\"))
```

Die Backslash-Verdopplung existiert als etabliertes Muster; der DDL-Renderpfad
wendet sie nur nicht an. Der Reverse-Pfad ist verifiziert: `parseDefault`
transportiert den Backslash unverändert aus einem PG-Quellschema herein.

## Warum das im Bedrohungsmodell zählt

Angreifer ist der Betreiber der **Quell-Datenbank** — laut
[`SECURITY.md`](../../../SECURITY.md) untrusted, und der Kern-Use-Case ist genau
„fremdes Schema nach MySQL migrieren". Er legt an:

```sql
col_a TEXT DEFAULT 'a\'
col_b TEXT DEFAULT ', x INT); DROP TABLE kunden; -- '
```

Beides ist in PG gültig (`standard_conforming_strings=on`).

**Impact-Präzisierung aus der Gegenprüfung** (alle drei Prüfer unabhängig):
`allowMultiQueries` kommt im Repo nicht vor, Connector/J-Default ist `false` —
auf dem JDBC-`migrate --execute`-Pfad läuft ein angehängtes `; DROP TABLE`
daher **nicht** als Zweitstatement. Der volle Impact entsteht über zwei andere
Routen:

1. `schema generate` emittiert ein DDL-Skript, das im dokumentierten Workflow
   dem `mysql`-Client gefüttert wird — dort ist Multi-Statement normal.
2. Auch über JDBC bleibt ein Breakout *innerhalb* des `CREATE TABLE` möglich:
   injizierte Zusatzspalten, entfernte NOT-NULL-Constraints, manipulierte
   Table-Options, geschluckte fremde Defaults.

## Arbeitspakete (Skizze)

1. Backslash-Verdopplung dialekt-bewusst nach `SqlIdentifiers.quoteStringLiteral`
   ziehen (Signatur um `dialect` erweitern, analog zum bestehenden
   `quoteIdentifier(value, dialect)`). Der Duplikat-Charakter der Fundstellen ist
   das Symptom der fehlenden Single-Source — deshalb dort und nicht je Aufrufer.
2. Aufrufer umstellen: `MysqlTypeMapper`, `MysqlEnumColumnRenderer`,
   `MysqlColumnConstraintHelper`, `MysqlDiffSqlBuilders`. `MysqlSequenceSqlCodec`
   auf die neue Basis ziehen (seine lokale Verdopplung wird redundant).
3. KDoc von `SqlIdentifiers` korrigieren — „The result is always safe for
   interpolation" ist nachweislich falsch. Sein Verweis auf `docs/quality.md` <!-- d-check:ignore (die Nichtexistenz IST der Befund) -->
   zeigt zudem auf eine **nicht existierende Datei**.
4. Prüfen, ob `PartitionLiteralGuard` (Denylist `;`/`--`/`/*`) konsistent
   nachziehen muss — er kennt den Backslash ebenfalls nicht.
5. Regression: PBT-Arb für Literale mit Backslash/Quote-Kombinationen
   ([`LN-046`](../../../spec/lastenheft-d-migrate.md)-Infrastruktur vorhanden), plus Live-Test gegen MySQL mit dem
   Round-Trip PG → MySQL.

## Fundstellen

- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt:49` (Defekt, P1)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlEnumColumnRenderer.kt:35` (Defekt, P2)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlColumnConstraintHelper.kt:39` (Generate-Pfad)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt:58` (Diff-Pfad)
- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/SqlIdentifiers.kt:54` (backslash-unsichere Basis)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlSequenceSqlCodec.kt:11` (korrektes Gegenmuster)
