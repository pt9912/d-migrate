---
status: accepted
date: 2026-06-26
decision-makers: pt9912
consulted: docs/planning/done/reverse-column-ordinal-order.md (Slice), spec/neutral-model-spec.md, spec/schema-reference.md
informed: adapters/driven/driver-postgresql, adapters/driven/driver-mysql, adapters/driven/driver-sqlite, adapters/driven/formats
---

# Spalten-Ordinalreihenfolge erhalten (Hybrid: geordnete Serialisierung + explizites `ordinal`)

> **Status: accepted.** Gate-ADR für den Slice
> [`../planning/done/reverse-column-ordinal-order.md`](../planning/done/reverse-column-ordinal-order.md).

## Kontext und Problemstellung

Beim TPC-4c-Spike fiel auf, dass ein roher Byte-Vergleich `data export` (Quelle) vs.
Re-Import-Ziel abwich, obwohl der Transfer zellgenau verlustfrei ist. Ursache: `schema
reverse` legte die Spalten **alphabetisch nach Namen** ab statt in der **physischen
Ordinalreihenfolge** der Quelle, und das generierte Ziel-DDL übernahm das treu.

Die Reverse-Reader liefern intern bereits physisch geordnete `LinkedHashMap`s (PG/MySQL
`ORDER BY ordinal_position`, SQLite `cid`). Geplättet wurde die Reihenfolge erst bei der
**Serialisierung** (`buildColumns` sortierte `sortedBy { it.key }`) und — im Diff-Pfad —
bei der **DDL-Generierung** (CREATE TABLE über `columns.entries.sortedBy { it.key }`).
Der Parser hingegen **erhält** die Dokumentreihenfolge bereits (`LinkedHashMap` +
`fieldNames()`).

## Entscheidung

Die Ordinalreihenfolge wird **end-to-end erhalten**. Repräsentation: **Hybrid**.

1. **Geordnete Serialisierung.** Spalten werden in physischer Reihenfolge serialisiert
   (Helper `Map<String, ColumnDefinition>.inOrdinalOrder()`), nicht mehr alphabetisch.
   Alle übrigen Map-Felder (Tabellen, Custom-Types) bleiben für deterministischen Output
   nach Name sortiert.
2. **Explizites `ordinal`-Feld.** `ColumnDefinition` trägt ein optionales `ordinal: Int?`
   (1-basiert). Reverse befüllt es; der Serializer schreibt es; der Parser liest es zurück.

`inOrdinalOrder()` sortiert stabil nach `ordinal` (nullsLast); Spalten ohne `ordinal`
(hand-authored, Overlay-Zusatz) behalten ihre Einfügereihenfolge. Single Source of Truth
für Serializer **und** alle DDL-Generate-Pfade (PG/MySQL/SQLite, inkl. SQLite-Rebuild).

## Begründung der Hybrid-Wahl

- **Geordnete Serialisierung allein** liefert lesbares YAML in natürlicher Reihenfolge und
  würde — weil der Parser die Reihenfolge erhält — bereits round-trippen. Sie ist aber
  **implizit/positional**: ein Overlay-Merge oder eine versehentliche Umsortierung im YAML
  verschiebt die physische Reihenfolge unbemerkt.
- **Explizites `ordinal` allein** wäre robust, aber das YAML bliebe alphabetisch mit
  `ordinal:`-Zahlen — verbose und unnatürlich zu lesen.
- **Hybrid** kombiniert beide Stärken: lesbares, natürlich geordnetes YAML **und** eine
  explizite, gegen Re-Parse/Overlay/Umsortierung robuste Positionsangabe.

## Konsequenzen für `compare` und Migration-Fingerprint

`ordinal` ist bewusst **invariant** gegenüber:

- **`schema compare`**: `TableComparator.compareColumn` vergleicht feldweise
  (type/required/default/unique/references/generation), nicht `==` auf der ganzen
  `ColumnDefinition`. `ordinal` erzeugt deshalb keine Diffs. Eine reine Umsortierung ist
  kein Migrationsschritt (die meisten DBs altern Spaltenreihenfolge ohnehin nicht).
- **Post-Compare-Drift-Fingerprint (v3)**: `CanonicalPayload.column()` /
  `MigrationFingerprint` listen die Felder explizit auf und sortieren Spalten nach Name.
  `ordinal` fließt nicht ein → Operation-IDs / Migration-Artefakte bleiben stabil.

## Verworfene Alternative

`sortedBy { it.key }` ersatzlos entfernen und auf reine Einfügereihenfolge vertrauen —
verworfen, weil ohne explizites Feld jede YAML-Umsortierung/Overlay-Operation die physische
Reihenfolge unbemerkt verschiebt. Das `ordinal`-Feld entkoppelt „deterministische
Serialisierung (Spalten in Ordinalreihenfolge, übrige Maps nach Name)" sauber von
„physischer Reihenfolge".

## Nicht-Ziele

- Reihenfolge als **Diff-/ALTER-Signal** (Umsortierung migrieren) — nicht im Scope;
  `compare` bleibt order-invariant.
- View-Spaltenreihenfolge — `ViewDefinition.columns` ist bereits eine geordnete Liste.
