# `inline_foreign_keys` — vorhandenes Deferral-Verhalten konfigurierbar machen

> **Status:** Draft mit Scope (2026-09-13). Teil von
> [`open/ddl-config-block-unimplemented.md`](../open/ddl-config-block-unimplemented.md).

## Ziel

`ddl.inline_foreign_keys: auto|always|never` steuert, ob `schema generate`
Fremdschlüssel inline (in derselben `CREATE TABLE`) oder zurückgestellt
(separates `ALTER TABLE … ADD CONSTRAINT` nach allen Tabellen) rendert —
Vorrang **CLI-explizit > Config > Default** (`auto`, heutiges Verhalten
unverändert).

## Der überraschende Befund: die Fähigkeit existiert schon

`DdlGenerationOptions.deferForeignKeys: Boolean` ist **kein neues Feld** —
es steht bereits im Options-Typ und ist bereits in drei der fünf Dialekte
implementiert (gemessen per Grep über alle Generatoren):

| Dialekt | `supportsDeferredForeignKeys` | Guard im Generator |
| --- | --- | --- |
| PostgreSQL | `true` | `PostgresDdlGenerator.kt:133,146` |
| MSSQL | `true` | `MssqlDdlGenerator.kt:165,178` |
| Oracle | `true` | `OracleDdlGenerator.kt:131,152` |
| MySQL | `false` (Default, nicht überschrieben) | kein Guard — FKs immer inline über `inlineForeignKeyLines` (`MysqlDdlGenerator.kt:136,242`) |
| SQLite | `false` (Default, nicht überschrieben) | kein Guard — FKs immer inline |

`deferForeignKeys` wird heute **ausschließlich intern** gesetzt, an einer
einzigen Stelle:

```kotlin
// SchemaGenerateRunner.kt:202
deferForeignKeys = request.splitMode == SplitMode.PRE_POST && generator.supportsDeferredForeignKeys,
```

Das heißt: der Nutzer erreicht das bestehende Verhalten heute nur indirekt,
über `--split-mode pre-post` (das noch andere Dinge tut — die Trennung von
DDL in einen "pre"- und "post"-Teil, nicht nur FKs). Es gibt keinen direkten
Hebel, Deferral unabhängig vom Split-Modus an- oder abzuschalten.

**MySQL und SQLite fehlt keine Implementierung, sondern die Fähigkeit
selbst ist dort nie gebaut worden** — beide rendern FKs ausnahmslos inline.
Das ist kein Bug: MySQL/SQLite kennen kein `DEFERRABLE`-Constraint-Konzept
wie PostgreSQL/Oracle/MSSQL (MySQL hat stattdessen die Session-Variable
`FOREIGN_KEY_CHECKS`, SQLite die Pragma `foreign_keys` — beide global,
keine Per-Constraint-Deferred-Semantik). Ein `inline_foreign_keys: always`
wäre dort ein No-Op (schon der Default), `never` wäre auf beiden Dialekten
nicht abbildbar, ohne ein anderes Mittel zu erfinden.

## Scope-Skizze

Deutlich kleiner als ursprünglich angenommen — kein neues Rendering, nur
eine neue, direkte Zugriffsfläche auf einen vorhandenen Schalter:

1. `DdlConfigResolver`: `ddl.inline_foreign_keys` lesen, drei Werte
   (`auto`/`always`/`never`), ungültiger Wert → Exit 7 (Muster wie
   `mssql.partition_storage`).
2. `SchemaGenerateRunner.kt:202`: die Herleitung erweitern —
   ```kotlin
   deferForeignKeys = when (resolvedInlineForeignKeys) {
       InlineForeignKeysMode.ALWAYS -> false
       InlineForeignKeysMode.NEVER -> generator.supportsDeferredForeignKeys
       InlineForeignKeysMode.AUTO -> request.splitMode == SplitMode.PRE_POST && generator.supportsDeferredForeignKeys
   }
   ```
   (Werte-Namen vorläufig — bei Umsetzung gegen die Spec-Begriffe
   `auto|always|never` abgleichen, `never` bedeutet "immer zurückstellen",
   nicht "nie" im Sinne von "nie inline"; Benennung in der Spec vor dem Bau
   klären, da die heutige Spec-Zeile die Werte nicht erläutert.)
3. Für MySQL/SQLite: `always` ist ein No-Op (Default-Verhalten), `never`
   (Deferral erzwingen) müsste explizit als "auf diesem Dialekt nicht
   unterstützt" abgelehnt werden (Exit 7, analog zu anderen
   Dialekt-Fähigkeits-Lücken in `DdlConfigResolver`) — **nicht** stillschweigend
   ignoriert.
4. CLI-Flag `--inline-foreign-keys` optional ergänzen, gleiche Präzedenz wie
   `--partition-storage`.
5. Handbuch: kurzer Abschnitt, wann Deferral sinnvoll ist (zyklische
   Fremdschlüssel, Bulk-Load-Reihenfolge).

## Offene Frage vor dem Bau

Ob `--split-mode pre-post` weiterhin implizit dieselbe Wirkung haben soll
(heutiges `AUTO`-Verhalten), oder ob die beiden Hebel entkoppelt werden
sollen (Split-Modus bestimmt nur noch Datei-Layout, nie mehr Deferral).
Tendenz: `AUTO` wie oben skizziert beibehalten — bricht kein bestehendes
Verhalten, `always`/`never` sind rein additiv.

## Akzeptanzkriterien

- `ddl.inline_foreign_keys: never` erzwingt zurückgestellte FKs auf
  PostgreSQL/MSSQL/Oracle, unabhängig vom Split-Modus.
- `ddl.inline_foreign_keys: never` auf MySQL/SQLite bricht mit Exit 7 und
  einer Meldung, die den Dialekt nennt.
- Ohne den Schlüssel: unverändertes Verhalten (`AUTO`, an `--split-mode`
  gekoppelt, wie heute).

## Berührte Stellen

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/DdlConfigResolver.kt`
- ggf. `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`
