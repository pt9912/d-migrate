# `inline_foreign_keys` — vorhandenes Deferral-Verhalten konfigurierbar machen

> **Status:** Draft mit Scope (2026-09-13). Teil von
> [`done/ddl-config-block-unimplemented.md`](ddl-config-block-unimplemented.md).

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
- `ddl.inline_foreign_keys: never` auf MySQL/SQLite bricht mit einer
  Meldung, die den Dialekt nennt (siehe Closure: Exit 2, nicht 7 wie hier
  skizziert — Begründung dort).
- Ohne den Schlüssel: unverändertes Verhalten (`AUTO`, an `--split-mode`
  gekoppelt, wie heute).

## Berührte Stellen

- `hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateRunner.kt`
- `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/config/DdlConfigResolver.kt`
- ggf. `adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaGenerateCommand.kt`

## Closure (2026-09-13)

Wie im Scope vermutet: der Schnitt war kleiner als "neue Rendering-Faehigkeit
bauen" — `deferForeignKeys` stand bereits in `DdlGenerationOptions` und war
bereits in PostgreSQL/MSSQL/Oracle implementiert, nur nicht direkt
konfigurierbar.

**Umgesetzt:**

- `DdlConfigResolver`: `ddl.inline_foreign_keys` (`auto`/`always`/`never`),
  **nicht** dialekt-geschachtelt gelesen (anders als `mssql.*`/`mysql.*`) —
  `readIdentifier`/`readChoice` dafuer generalisiert (nahmen bisher
  `ddl.mssql.` als festen Praefix an; jetzt ein `qualifiedKey`-Parameter,
  Default unveraendert fuer die bestehenden Aufrufstellen).
- `--inline-foreign-keys` CLI-Flag (`.choice("auto","always","never")`,
  Muster wie `--mssql-hash-partitions`).
- `SchemaGenerateRunner.resolveDeferForeignKeys`: `null`/`"auto"` unveraendert
  `splitMode`-getrieben; `"always"`/`"never"` sind additiv; `"never"` ohne
  `generator.supportsDeferredForeignKeys` (MySQL, SQLite) -> **Exit 2**, nicht
  Exit 7 wie urspruenglich skizziert — die Config-Validierung (Wertebereich)
  laeuft bereits im Resolver (Exit 7 bei Tippfehler); diese Pruefung ist eine
  **Dialekt**-Kompatibilitaetspruefung, die erst nach dem Generator-Lookup in
  `execute()` moeglich ist (dort, wo `resolveMssqlHashMode`/`resolveSqliteSeqMode`
  denselben Fall — Wert nur fuer einen Dialekt sinnvoll — ebenfalls mit Exit 2
  behandeln). `hexagon/application` kann zudem keine `ConfigResolveException`
  werfen (Schicht-Grenze, die CLI-Adapter-Exception liegt in
  `adapters/driving/cli`).

**Verifiziert:** neue Tests in `DdlConfigResolverTest` (Lesen, Tippfehler,
Praezedenz), `SchemaGenerateRunnerTest` (alle drei Modi + Dialekt-Ablehnung,
sabotage-verifiziert), `SchemaGenerateWiringTest` (Config-Datei- und
CLI-Praezedenz end-to-end, ein Sabotage-Lauf zusaetzlich). Ganzer Repo-Build
einmal ohne `MODULES` (oeffentliche `SchemaGenerateRequest`-Signatur
geaendert; einzige weitere Aufrufstelle ist eine Integrationsspec, die per
Default-Parameter unberuehrt blieb).

**Nicht Teil dieses Schnitts:** `schema migrate` kennt `inline_foreign_keys`
nicht — die Spec-Zeile und der Scope waren durchgehend auf `schema generate`
bezogen.
