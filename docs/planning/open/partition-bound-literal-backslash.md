# Partition-Bound-Literale: Backslash umgeht PartitionLiteralGuard (P2, CWE-89)

> **Status:** Vorabklärung (2026-07-17)
> **Trigger:** Beim Fix von [`mysql-string-literal-backslash-escaping.md`](mysql-string-literal-backslash-escaping.md)
> (P1, Commit `447a9006`) fiel der verwandte, aber getrennt zu lösende
> Partition-Bound-Pfad auf.
> **Aktivierungsbedingung:** P2 — RC-Kandidat → `next/`-Plan.

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
