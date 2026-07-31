# Partition-Bound-Literale: Backslash umgeht PartitionLiteralGuard (P2, CWE-89)

> **Status:** BEHOBEN 2026-07-18
> **Trigger:** Beim Fix von [`mysql-string-literal-backslash-escaping.md`](mysql-string-literal-backslash-escaping.md)
> (P1, Commit `447a9006`) fiel der verwandte, aber getrennt zu lösende
> Partition-Bound-Pfad auf.
>
> **Umsetzung (Designfrage-Entscheid: dialekt-bewusstes Re-Escaping, kein
> Denylist-Reject):** `MysqlPartitionBoundRenderer.renderColumnBoundLiteral`
> verdoppelt abschließend Backslashes **innerhalb** des bereits gequoteten
> Literals (`escapeBackslashForMysql`). Beide MySQL-Emit-Pfade — LIST
> `VALUES IN (…)` und RANGE `VALUES LESS THAN (…)` — laufen durch diesen einen
> Choke-Point. `SqlIdentifiers.quoteStringLiteral` ist bewusst **nicht**
> wiederverwendet: es startet von einem unquotierten Wert und würde die bereits
> SQL-standard verdoppelten Quotes (`''`) erneut escapen. `PartitionLiteralGuard`
> bleibt dialekt-neutral; seine KDoc dokumentiert nun, warum `\` dort **nicht**
> auf die Denylist gehört. Tests: `MysqlPartitionBoundRendererTest`
> (Backslash-Verdopplung, `''`-Erhalt, numerisch unangetastet) +
> `PartitionLiteralGuardTest` (Backslash bewusst nicht abgelehnt). Docker
> `:driver-common:check` und `:driver-mysql:check` grün.
>
> **PG-Ziel nicht betroffen (verifiziert):** `PostgresDdlGenerator` emittiert die
> Grenze per `ensureSafe`-Passthrough; `standard_conforming_strings=on`
> (PostgreSQL-Default) behandelt `\` literal, also kein Escaping nötig und kein
> Cross-Dialect-Drift. Der Fix ist genau deshalb MySQL-seitig, nicht in der
> geteilten Denylist.

## Befund

Anders als die DEFAULT-/ENUM-Literale (P1, behoben) laufen
Partition-Bound-Literale **nicht** über `SqlIdentifiers.quoteStringLiteral`. Der
Vertrag ist: das Modell trägt das Quoting bereits (der PG-Reverse liefert
`'a'` inklusive Quotes, der Renderer fügt keine hinzu — sonst entstünde
Cross-Dialect-Drift). Geschützt wird der Wert allein durch
`PartitionLiteralGuard.ensureSafe`, eine **Denylist**:

```kotlin
private val UNSAFE = listOf(";", "--", "/*")
```

Der Backslash steht nicht darauf. `MysqlPartitionBoundRenderer` und
`MysqlIndexPartitionDdlHelper` emittieren den geprüften Wert verbatim in
`VALUES LESS THAN (…)` / `VALUES IN (…)`.

## Angriffsszenario

Angreifer kontrolliert die Quell-DB (untrusted, [`SECURITY.md`](../../../SECURITY.md)).
Er legt eine LIST-partitionierte Tabelle auf einer Text-Spalte an mit einem
Bound, dessen Wert auf `\` endet. Der PG-Reverse liefert das Modell-Literal
`'a\'`. Beim Generieren/Migrieren nach MySQL entsteht `VALUES IN ('a\', …)` —
der Backslash escapt bei Default-`sql_mode` das schließende Quote weg, folgender
DDL-Text wird als SQL geparst. Dieselbe Ausbruchsklasse wie der P1-DEFAULT-Fix,
nur über den Partitions-Pfad und schmaler (String-Bounds auf LIST/RANGE COLUMNS).

## Warum getrennt vom P1-Fix

- Kein `quoteStringLiteral`-Aufruf — der P1-Fix (dialekt-bewusste
  Escaping-Funktion) greift hier konstruktiv nicht.
- Der Schutz ist eine Denylist, kein Escaper; der Fix ist mechanisch anders.
- Es kollidiert mit dem Vertrag „das Modell trägt das Quoting" (ADR 0019/0020):
  ein nachträgliches Re-Escaping müsste die bereits vorhandenen Quotes
  respektieren, ohne Cross-Dialect-Drift zu erzeugen.

## Offene Designfrage

- **Denylist um `\` erweitern (reject):** einfachster Fix, lehnt aber legitime
  Bounds mit Backslash ab. Da Backslash in Partitions-Bounds sehr selten ist,
  vermutlich akzeptabel — aber es ist ein Reject, kein Passthrough.
- **Dialekt-bewusstes Re-Escaping beim MySQL-Rendern:** respektiert legitime
  Werte, muss aber das Modell-Quoting auspacken, den Inhalt MySQL-escapen und neu
  quoten — Interaktion mit dem „Modell trägt Quotes"-Vertrag sorgfältig prüfen.
- Analog bewerten, ob PostgreSQL-Ziel betroffen ist (dort `standard_conforming_strings`
  → Backslash literal, also vermutlich nicht — verifizieren).

## Fundstellen

- `adapters/driven/driver-common/src/main/kotlin/dev/dmigrate/driver/PartitionLiteralGuard.kt` (Denylist ohne `\`)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlPartitionBoundRenderer.kt` (emittiert verbatim)
- `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt` (`ensureSafe` → `VALUES LESS THAN`/`VALUES IN`)
